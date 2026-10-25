"""Numbered Russian NLU and transactional dialogue; no Android execution claims."""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timezone

import roster_store as store
from voice import EXACT, OP_OF, alice_reply, normalize, speak_duration

ORDINALS = ('', 'первая', 'вторая', 'третья', 'четвертая', 'пятая', 'шестая', 'седьмая', 'восьмая', 'девятая')
CARDINALS = (
    (), ('один', 'одна', 'одно', 'одну', 'одной', 'одного', 'одному', 'одним', 'одною', 'одном'),
    ('два', 'две', 'двух', 'двум', 'двумя'), ('три', 'трех', 'трем', 'тремя'),
    ('четыре', 'четырех', 'четырем', 'четырьмя'), ('пять', 'пяти', 'пятью'),
    ('шесть', 'шести', 'шестью'), ('семь', 'семи', 'семью'), ('восемь', 'восьми', 'восемью', 'восьмью'),
    ('девять', 'девяти', 'девятью'))
NUMBER_WORDS = {s: n for n, forms in enumerate(CARDINALS) for s in forms}
# All masculine/feminine/neuter/plural cases, including soft third declension.
ORDINAL_ROOTS = ('перв', 'втор', 'трет', 'четверт', 'пят', 'шест', 'седьм', 'восьм', 'девят')
ORDINAL_ENDINGS = ('ый', 'ая', 'ое', 'ую', 'ой', 'ого', 'ому', 'ым', 'ом', 'ые', 'ых', 'ыми', 'ою')
for n, root in enumerate(ORDINAL_ROOTS, 1):
    endings = ('ий', 'ья', 'ье', 'ью', 'ьей', 'ьего', 'ьему', 'ьим', 'ьем', 'ьи', 'ьих', 'ьими', 'ьею') if n == 3 else ORDINAL_ENDINGS
    for end in endings:
        NUMBER_WORDS[root + end] = n
# третий/третья/третью/третьей are covered above.
OUTSIDE = {'ноль', 'нуль', 'нулевая', 'нулевой', 'десять', 'десятая', 'десятый', 'десятой', 'десятую', 'одиннадцать', 'двенадцать', 'тринадцать', 'четырнадцать', 'пятнадцать', 'шестнадцать', 'семнадцать', 'восемнадцать', 'девятнадцать', 'двадцать', 'сто'}
FILLERS = {'алиса', 'скажи', 'скажите', 'пожалуйста', 'спроси', 'спросите'}
GLUE = {'для', 'у', 'с', 'со', 'про', 'о', 'об', 'выпечка', 'выпечку', 'выпечки', 'выпечкой', 'выпечке', 'номер', 'номера', 'номеру', 'номером'}
LIST = {'что печется', 'что печем', 'какие выпечки', 'что в печи', 'список выпечек', 'что готовится'}
ALIASES = dict(EXACT, **{'что с': 'what', 'что со': 'what', 'как': 'what', 'статус': 'what',
                       'что': 'what', 'сколько еще': 'remaining', 'сколько муки': 'quantities',
                       'сколько воды': 'quantities', 'отмени': 'cancel', 'отменить': 'cancel'})


@dataclass(frozen=True)
class Parsed:
    intent: str
    number: int | None = None
    keys: tuple[str, ...] = ()


def norm(text):
    t = normalize(text)
    t = re.sub(r'(?<=\d)[.,](?=\d)', 'invalid', t)
    return ' '.join(w for w in t.replace('№', ' ').replace('#', ' ').split() if w not in FILLERS)


def stem(word):
    # Conservative recipe-token inflection, not fuzzy edit-distance retargeting.
    return re.sub(r'(иями|ями|ами|ого|ему|ому|ыми|ими|ией|ий|ой|ый|ая|яя|ое|ее|ом|ем|ам|ям|ах|ях|ию|ия|ью|ей|ов|ев|а|я|ы|и|у|ю|е|о)$', '', word)


def parse(text, bakes=()):
    t = norm(text)
    if not t:
        return Parsed('unknown')
    if t in LIST:
        return Parsed('list')
    if t in ('да', 'подтверждаю отмену', 'стоп', 'выход', 'хватит', 'отмена', 'нет', 'помощь', 'команды', 'что получилось', 'ping'):
        return Parsed({'подтверждаю отмену': 'confirm_cancel', 'стоп': 'exit', 'выход': 'exit', 'хватит': 'exit', 'отмена': 'exit', 'нет': 'no', 'да': 'yes', 'помощь': 'help', 'команды': 'help', 'что получилось': 'receipt', 'ping': 'ping'}[t])
    # Time/repetition operands are never bake references (and never an action).
    if re.search(r'\b(раз|раза|минут\w*|секунд\w*|час\w*)\b', t):
        return Parsed('unknown')
    tokens, numbers = t.split(), []
    rest = []
    for w in tokens:
        if w in NUMBER_WORDS:
            numbers.append(NUMBER_WORDS[w])
        elif re.fullmatch(r'-?\d+', w):
            numbers.append(int(w))
        elif w in OUTSIDE or re.fullmatch(r'(нулев|десят|одиннадцат|двенадцат|тринадцат|четырнадцат|пятнадцат|шестнадцат|семнадцат|восемнадцат|девятнадцат|двадцат|тридцат|сороков|сот)\w+', w):
            numbers.append(0)
        else:
            rest.append(w)
    if any(n < 1 or n > 9 for n in numbers):
        return Parsed('missing', 0)
    if len(numbers) > 1:
        return Parsed('ambiguous')
    number = numbers[0] if numbers else None
    if number is not None and not 1 <= number <= 9:
        return Parsed('missing', number)
    # Identify the intent first, preserving longest literal phrase.
    raw = ' '.join(rest)
    found = None
    for phrase in sorted(ALIASES, key=len, reverse=True):
        if re.search(r'(?<!\w)' + re.escape(phrase) + r'(?!\w)', raw):
            found = (ALIASES[phrase], re.sub(r'(?<!\w)' + re.escape(phrase) + r'(?!\w)', '', raw, count=1))
            break
    intent, tail = found if found else ('reference', raw)
    residual = [w for w in tail.split() if w not in GLUE]
    keys = ()
    if residual:
        stems = [stem(w) for w in residual]
        if any(len(s) < 3 for s in stems):
            return Parsed('unknown')
        matches = []
        for b in bakes:
            recipe_words = [stem(w) for w in norm(b['recipe_name']).split()]
            if all(any(s == r for r in recipe_words) for s in stems):
                matches.append(b['bake_key'])
        if not matches:
            return Parsed('unknown')
        keys = tuple(sorted(matches))
    if number is not None and keys:
        return Parsed('ambiguous')
    if not found and number is None and not keys:
        return Parsed('unknown')
    return Parsed(intent, number, keys)


def pair(command, original, bakes):
    a, b = parse(command, bakes), parse(original, bakes)
    if command and original and a != b:
        return Parsed('ambiguous')
    return a if command else b


def label(b):
    return f"{ORDINALS[b['bake_number']].capitalize()}, {b['recipe_name']}"


def listing(bakes):
    return '; '.join(f"{label(b)} — {dict(running='идёт', paused='на паузе', scheduled='запланирована', finale='испечена')[b['state']]}" for b in bakes)


def describe(b, intent, now, received):
    title = label(b)
    if b['state'] == 'scheduled':
        # Wire timestamp is UTC; do not invent the user's local timezone.
        try:
            start = datetime.fromtimestamp(b['start_at'] / 1000, timezone.utc).strftime('%H:%M UTC')
        except (ValueError, OverflowError, OSError):
            start = 'время указано на телефоне'
        return f'{title} — запланирована, начало в {start}. Она ещё не идёт. Начать или отменить её можно на телефоне.'
    if b['state'] == 'finale':
        return f'{title} испечена. Впишите её в книгу на телефоне.'
    if intent == 'remaining':
        remaining = b['remaining_seconds']
        if b['state'] == 'running':
            remaining = max(0, remaining - max(0, now - received) // 1000)
        return f"{title}: осталось {speak_duration(remaining)}." + (' На паузе.' if b['state'] == 'paused' else '')
    if intent == 'quantities':
        return f"{title}: {b['quantities']}" if b['control_ready'] and b['quantities'] else f'{title}: точные количества неизвестны. Откройте телефон.'
    if intent == 'peek':
        if not b['control_ready']:
            return f'{title}: текст и порция не подтверждены. Откройте телефон.'
        return f"{title}: следующий шаг, не двигаю: {b['next_step_title'] or 'последний шаг'}. {b['next_step_text'] or ''}"
    text = f"{title}: шаг {b['step_index'] + 1} из {b['step_count']} — {b['step_title']}."
    if b['state'] == 'paused':
        text += ' На паузе.'
    if intent == 'repeat':
        text += ' ' + (b['step_text'] if b['control_ready'] and b['step_text'] else 'Текст и порция не подтверждены. Откройте телефон.')
    return text


def turn(c, row, command, original, session, now, freshness):
    """Returns (speech, optional command). Caller waits for ack outside transaction."""
    bakes = store.list_bakes(row, now)
    p = pair(command, original, bakes)
    if p.intent == 'ping':
        return 'pong', None
    if p.intent == 'help':
        return 'Это Голос закваски. Скажите «что печётся» или назовите номер: «пауза два». Каждое действие подтвердите словом «да», отмену — «подтверждаю отмену».', None
    if not session:
        return 'Нет сессии. Ничего не отправляю.', None
    if p.intent in ('exit', 'no'):
        store.clear_pending(c, row['id'], session)
        return 'Выхожу. Выпечку не отменяю.' if p.intent == 'exit' else 'Ничего не отправляю.', None
    if p.intent in ('yes', 'confirm_cancel'):
        cmd, error = store.mint(c, row, session, p.intent, now, freshness)
        return error, cmd
    if p.intent == 'receipt':
        cmd = c.execute("SELECT ack_json FROM roster_v2_commands WHERE connection_id=? AND ack_json IS NOT NULL ORDER BY ack_at DESC LIMIT 1", (row['id'],)).fetchone()
        if not cmd:
            return 'Сохранённого ответа телефона нет. Повторно не отправляю.', None
        import json
        outcome = json.loads(cmd['ack_json'])['outcome']
        return receipt_text(outcome) + ' Повторно не отправляю.', None
    if p.intent in ('unknown', 'ambiguous'):
        store.clear_pending(c, row['id'], session)
        return 'Не разобрала. Назовите один номер выпечки. Ничего не отправляю.', None
    if row['received_at'] is None or not 0 <= now - row['received_at'] <= freshness:
        store.clear_pending(c, row['id'], session)
        return 'Телефон сейчас не на связи. Состояние не подтверждаю и команду не отправляю.', None
    if p.intent == 'list':
        store.clear_pending(c, row['id'], session)
        return listing(bakes) if bakes else 'Сейчас ничего не печётся. Начните выпечку на телефоне.', None
    old = store.pending(c, row['id'], session, now)
    intent = p.intent
    allowed = None
    if intent == 'reference':
        import json
        target = json.loads(old['target_json']) if old is not None and old['target_json'] else {}
        if old is None or 'candidates' not in target:
            return 'Скажите, что узнать или сделать с этой выпечкой.', None
        intent = old['intent']
        allowed = target['candidates']
    store.clear_pending(c, row['id'], session)
    if not bakes:
        return 'Сейчас ничего не печётся. Начните выпечку на телефоне.', None
    candidates = bakes
    if p.number is not None:
        candidates = [b for b in bakes if b['bake_number'] == p.number]
    elif p.keys:
        candidates = [b for b in bakes if b['bake_key'] in p.keys]
    if not candidates or p.intent == 'missing':
        return 'Такой выпечки нет. Сейчас: ' + listing(bakes), None
    if allowed is not None and any(allowed.get(b['bake_key']) != store.fingerprint(b) for b in candidates):
        return 'Выбор или состояние выпечек изменились. Повторите команду с номером. Ничего не отправляю.', None
    if len(candidates) > 1:
        store.save_pending(c, row['id'], session, intent, {'candidates': {b['bake_key']: store.fingerprint(b) for b in candidates}}, now)
        return listing(candidates) + '. Назовите номер выпечки.', None
    b = candidates[0]
    operation = OP_OF.get(intent)
    if operation and b['state'] in ('scheduled', 'finale'):
        return describe(b, intent, now, row['received_at']), None
    if operation:
        if not store.eligible_action(b, operation):
            return f'{label(b)}: телефон не подтвердил это действие. Откройте телефон. Ничего не отправляю.', None
        store.save_pending(c, row['id'], session, operation, {'bake_key': b['bake_key'], 'fingerprint': store.fingerprint(b)}, now)
        actions = {'PAUSE': 'Пауза', 'RESUME': 'Продолжение', 'ADVANCE': 'Следующий шаг', 'BACK': 'Предыдущий шаг', 'CANCEL': 'Отмена'}
        confirmation = 'подтверждаю отмену' if operation == 'CANCEL' else 'да'
        return f"{actions[operation]} для выпечки {label(b)}? Скажите «{confirmation}».", None
    return describe(b, intent, now, row['received_at']), None


def receipt_text(outcome):
    return {'applied': 'Телефон сообщил, что применил команду.', 'stale': 'Телефон не применил команду: состояние изменилось.',
            'expired': 'Время доставки вышло. Телефон не применил команду.', 'rejected': 'Телефон отклонил команду.',
            'phone_required': 'Закончите это на экране телефона. Голосом выпечку не завершала.'}.get(outcome, 'Результат неизвестен. Проверьте телефон.')
