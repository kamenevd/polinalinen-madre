"""Real local HTTP/SQLite and races; only external identity HTTPS is a fixture.

Synthetic phone acknowledgments are NOT Android execution/e2e acceptance.
"""
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

import test_yandex_identity as yi
from app import App, Handler
from db import transaction
import roster_store as store
import roster_voice as voice

V2 = yi.PHONE + '/v2/'
BASE_SHA = 'd4999178a53571304642486c13388460cfa1ec86'


def bake(number=2, **over):
    b = dict(bake_key=yi.legacy.uid(), bake_number=number, revision=7, recipe_revision='synthetic-recipe-rev',
             recipe_name='Чиабатта', state='running', step_index=1, step_count=3, step_title='Замес',
             remaining_seconds=120, start_at=None, step_text='Смешайте', next_step_title='Расстойка',
             next_step_text='Оставьте', quantities='100 г муки', portions=1, control_ready=True)
    b.update(over)
    return b


class RosterHTTP(unittest.TestCase):
    for name in ('request', 'json_call', 'form_call', 'install_header', 'bearer_header', 'enroll', 'select',
                 'snapshot', 'poll', 'webhook', 'alice', 'header_map', 'cookies', 'cookie_header',
                 'authorize_query', 'csrf_from', 'display_code_from', 'start_challenge', 'sql_one', '_link', 'unbind', 'bind'):
        locals()[name] = getattr(yi.YandexHTTP, name)

    def setUp(self):
        yi.YandexHTTP.setUp(self)
        self.settings = replace(self.settings, roster_v2=True, ack_wait_s=0.05)
        self.app.settings = self.settings

    tearDown = yi.YandexHTTP.tearDown

    def call(self, route, payload, credential=None):
        return self.json_call('POST', V2 + route, payload,
                              self.install_header(credential or self.credential))

    def attached(self):
        self.credential, self.installation = self.enroll()
        self.access, self.legacy_bake, self.generation = self._link(self.credential)
        result = self.call('attach', dict(protocol_version=2, consent_scope=store.SCOPE, consent=True))
        self.assertEqual(result[0], 200, result)
        self.connection = result[2]['connection_id']
        self.roster_revision = 0
        return self.connection

    def roster(self, bakes, revision=None, connection=None):
        if revision is None:
            self.roster_revision += 1
            revision = self.roster_revision
        return self.call('poll', dict(protocol_version=2, connection_id=connection or self.connection,
                                     roster_revision=revision, bakes=bakes))

    def speech(self, command, **kwargs):
        result = self.webhook(command, self.access, **kwargs)
        self.assertEqual(result[0], 200, result)
        return result[2]['response']['text']

    def count(self):
        return self.sql_one('SELECT COUNT(*) FROM roster_v2_commands')[0]

    def issue(self, command='пауза два', confirm='да'):
        self.assertIn('Скажите', self.speech(command))
        self.speech(confirm)
        return self.sql_one('SELECT id FROM roster_v2_commands ORDER BY rowid DESC LIMIT 1')[0]

    def command(self, b):
        self.issue()
        result = self.roster([b])
        self.assertEqual(result[0], 200, result)
        self.assertEqual(len(result[2]['commands']), 1, result)
        return result[2]['commands'][0]

    def ack_body(self, cmd, **over):
        body = dict(connection_id=cmd['connection_id'], command_id=cmd['id'], lease_id=cmd['lease_id'],
                    outcome='applied', resulting_revision=cmd['expected_revision'] + 1)
        body.update(over)
        return body

    def test_flag_default_off_and_no_mutation(self):
        self.credential, _ = self.enroll()
        self.app.settings = replace(self.settings, roster_v2=False)
        result = self.call('attach', dict(protocol_version=2, consent_scope=store.SCOPE, consent=True))
        self.assertEqual(result[0:3:2], (426, {'error': 'unsupported_protocol'}))
        self.assertFalse(yi.Settings(db_path=self.db_path).roster_v2)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM roster_v2_connections')[0], 0)

    def test_explicit_new_scope_and_pair_required_no_legacy_consent_widening(self):
        self.credential, _ = self.enroll()
        good = dict(protocol_version=2, consent_scope=store.SCOPE, consent=True)
        self.assertEqual(self.call('attach', good)[0], 409)
        self.access, _, _ = self._link(self.credential)
        for over, status in (({'consent': False}, 403), ({'consent': 1}, 403),
                             ({'consent_scope': yi.EXACT_SCOPE}, 403), ({'protocol_version': True}, 426),
                             ({'protocol_version': '2'}, 426), ({'protocol_version': 3}, 426)):
            self.assertEqual(self.call('attach', dict(good, **over))[0], status)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM roster_v2_connections')[0], 0)
        self.assertEqual(self.call('attach', good)[0], 200)
        first = self.sql_one('SELECT id,created_at,expires_at FROM roster_v2_connections')
        self.assertEqual(self.call('attach', good)[2]['connection_id'], first[0])
        self.assertEqual(self.sql_one('SELECT id,created_at,expires_at FROM roster_v2_connections'), first)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM yandex_voice_consents')[0], 0)

    def test_strict_auth_json_duplicates_unknown_paths_and_canonical_ids(self):
        self.attached()
        good = dict(protocol_version=2, connection_id=self.connection, roster_revision=1, bakes=[])
        for headers in ({}, self.bearer_header(self.access), self.install_header('x' * 43)):
            self.assertEqual(self.json_call('POST', V2 + 'poll', good, headers)[0], 401)
        for body in (dict(good, roster_revision=True), dict(good, connection_id=self.connection.upper()),
                     dict(good, roster_revision='1'), dict(good, bakes=None), dict(good, unexpected=1)):
            self.assertEqual(self.call('poll', body)[0], 400)
        for route in ('unknown', 'nested/attach'):
            self.assertEqual(self.call(route, {})[0], 404)
        raw = b'{"protocol_version":2,"protocol_version":2,"connection_id":"' + self.connection.encode() + b'","roster_revision":1,"bakes":[]}'
        headers = dict(self.install_header(self.credential), **{'Content-Type': 'application/json'})
        self.assertEqual(self.request('POST', V2 + 'poll', raw, headers)[0], 400)
        self.assertEqual(self.call('poll', dict(good, bakes=[{'remaining_seconds': float('nan')}]))[0], 400)
        self.assertEqual(self.sql_one('SELECT roster_revision FROM roster_v2_connections')[0], -1)

    def test_atomic_full_roster_revision_digest_empty_and_validation(self):
        self.attached(); b = bake()
        result = self.roster([b], 4)
        self.assertEqual(result[0], 200)
        self.assertEqual(set(result[2]), {'protocol_version', 'connection_id', 'roster_revision', 'server_time', 'commands'})
        self.assertEqual(self.roster([b], 4)[0], 200)
        self.assertEqual(self.roster([], 4)[0:3:2], (409, {'error': 'conflict'}))
        self.assertEqual(self.roster([], 3)[0:3:2], (409, {'error': 'stale_roster'}))
        stored = self.sql_one('SELECT bakes_json FROM roster_v2_connections')[0]
        bad = [dict(b, bake_number=True), dict(b, revision=True), dict(b, remaining_seconds=-1),
               dict(b, state='closed'), dict(b, control_ready='true'), dict(b, portions=6),
               dict(b, state='scheduled', start_at=1234), dict(b, photo='not allowed'),
               dict(b, start_at=1234), dict(b, step_index=3), dict(b, portions=None)]
        for invalid in bad:
            self.assertEqual(self.roster([invalid], 5)[0], 400, invalid)
        for invalid in ([b, b], [b, dict(b, bake_key=yi.legacy.uid())], [bake(n) for n in range(1, 11)]):
            self.assertEqual(self.roster(invalid, 5)[0], 400)
        self.assertEqual(self.sql_one('SELECT bakes_json FROM roster_v2_connections')[0], stored)
        self.assertEqual(self.roster([], 5)[0], 200)
        self.assertEqual(json.loads(self.sql_one('SELECT bakes_json FROM roster_v2_connections')[0]), [])

    def test_per_bake_revision_and_number_fences_survive_roster_removal(self):
        self.attached(); b = bake()
        self.assertEqual(self.roster([b], 1)[0], 200)
        for invalid in (dict(b, revision=6), dict(b, step_title='Другой'), dict(b, bake_number=3)):
            self.assertEqual(self.roster([invalid], 2)[0], 409)
        self.assertEqual(self.roster([], 2)[0], 200)
        self.assertEqual(self.roster([dict(b, revision=6)], 3)[0], 409)
        self.assertEqual(self.roster([dict(b, remaining_seconds=110)], 3)[0], 200)

    def test_dispatch_lease_ack_replay_cross_install_and_no_reexecution(self):
        self.attached(); b = bake(); self.roster([b])
        cmd = self.command(b)
        self.assertEqual(set(cmd), set('id connection_id bake_key bake_number expected_revision recipe_revision operation issued_at expires_at lease_id expected_step expected_paused'.split()))
        self.assertEqual(cmd['expires_at'] - cmd['issued_at'], 4000)
        self.assertEqual(cmd['bake_key'], b['bake_key']); self.assertEqual(cmd['bake_number'], 2)
        self.assertEqual(self.roster([b])[2]['commands'], [])
        ack = self.ack_body(cmd)
        other, _ = self.enroll()
        self.assertEqual(self.call('ack', ack, other)[0], 409)
        self.assertEqual(self.call('ack', dict(ack, lease_id=yi.legacy.uid()))[0], 409)
        self.assertEqual(self.call('ack', dict(ack, resulting_revision=True))[0], 400)
        self.assertEqual(self.call('ack', dict(ack, resulting_revision=7))[0], 400)
        self.assertEqual(self.call('ack', ack)[2], {'acknowledged': True})
        self.assertEqual(self.call('ack', ack)[2], {'acknowledged': True})
        self.assertEqual(self.call('ack', dict(ack, outcome='stale', resulting_revision=None))[0:3:2], (409, {'error': 'conflict'}))
        self.assertEqual(self.roster([b])[2]['commands'], [])
        self.assertIn('применил', self.speech('что получилось'))
        # Server receipt never mutates or pretends to execute the synthetic bake.
        self.assertEqual(json.loads(self.sql_one('SELECT bakes_json FROM roster_v2_connections')[0])[0]['revision'], 7)

    def test_ack_before_dispatch_and_expired_lease_and_readonly(self):
        self.attached(); b = bake(); self.roster([b])
        self.issue()
        cmd = json.loads(self.sql_one('SELECT payload_json FROM roster_v2_commands')[0])
        self.assertEqual(self.call('ack', self.ack_body(cmd))[0], 409)
        self.app.clock.advance_ms(4001)
        self.assertEqual(self.roster([b])[2]['commands'], [])
        self.assertEqual(self.call('ack', self.ack_body(cmd))[0], 409)  # never dispatched

    def test_confirm_pinned_to_key_number_recipe_revision_state_and_session(self):
        self.attached(); b = bake(); self.roster([b])
        for changed in (dict(b, bake_key=yi.legacy.uid()), dict(b, revision=8),
                        dict(b, recipe_revision='new', revision=8), dict(b, state='paused', revision=8)):
            # New independent connection for each pin fixture.
            if self.roster([b])[0] != 200:
                b = dict(b, revision=10)
                self.assertEqual(self.roster([b])[0], 200)
            self.assertIn('Скажите', self.speech('пауза два'))
            changed = dict(changed, revision=max(changed['revision'], b['revision'] + 1))
            self.assertEqual(self.roster([changed])[0], 200)
            before = self.count()
            self.assertIn('Ничего не меняю', self.speech('да'))
            self.assertEqual(self.count(), before)
            b = dict(b, revision=changed['revision'] + 1)
        self.roster([b]); self.speech('пауза два')
        self.assertIn('Подтверждать нечего', self.speech('да', session_id='other-session'))
        self.speech('нет'); self.assertIn('Подтверждать нечего', self.speech('да'))

    def test_cancel_separate_exit_pending_and_confirmation_expiry(self):
        self.attached(); b = bake(); self.roster([b])
        self.speech('отмени вторую'); self.assertIn('подтверждаю отмену', self.speech('да'))
        self.assertEqual(self.count(), 0)
        self.speech('подтверждаю отмену'); self.assertEqual(self.count(), 1)
        self.assertEqual(json.loads(self.sql_one('SELECT payload_json FROM roster_v2_commands')[0])['operation'], 'CANCEL')
        self.app.clock.advance_ms(5000); self.roster([b]); self.speech('пауза два')
        self.speech('выход'); self.assertIn('Подтверждать нечего', self.speech('да'))
        self.speech('пауза два'); self.app.clock.advance_ms(60000); self.roster([b])
        self.assertIn('Подтверждать нечего', self.speech('да'))
        self.assertEqual(self.count(), 1)

    def test_zero_one_many_pending60_recipe_ambiguity_and_unknown_number(self):
        self.attached(); self.roster([])
        self.assertIn('ничего не печётся', self.speech('что печется'))
        a, b = bake(1, recipe_name='Хлеб'), bake(2, recipe_name='Хлеб')
        self.roster([a]); self.assertIn('Первая, Хлеб', self.speech('сколько осталось'))
        self.roster([a, b]); self.assertIn('Назовите номер', self.speech('сколько осталось'))
        self.assertIn('Вторая, Хлеб', self.speech('два'))
        self.assertIn('Назовите номер', self.speech('пауза хлеба'))
        self.assertIn('Скажите', self.speech('вторая'))
        self.assertEqual(self.count(), 0)
        self.assertIn('Такой выпечки нет', self.speech('что с девятой'))
        self.assertIn('Назовите номер', self.speech('пауза'))
        self.app.clock.advance_ms(60000); self.roster([a, b])
        self.assertIn('Скажите, что', self.speech('вторая'))
        self.assertEqual(self.count(), 0)

    def test_scheduled_finale_untrusted_snapshot_no_invented_text_or_quantities(self):
        self.attached()
        planned = bake(3, state='scheduled', start_at=1792926000000, control_ready=False)
        final = bake(4, state='finale', control_ready=False)
        untrusted = bake(2, portions=None, control_ready=False, step_text=None, next_step_text=None, quantities=None)
        self.assertEqual(self.roster([planned, final, untrusted])[0], 200)
        self.assertIn('ещё не идёт', self.speech('пауза три'))
        self.assertIn('Впишите', self.speech('отмени четвертую'))
        self.assertIn('неизвестны', self.speech('сколько муки у второй'))
        self.assertIn('не подтверждены', self.speech('повтори для второй'))
        self.assertIn('не подтвердил', self.speech('пауза два'))
        self.assertEqual(self.count(), 0)

    def test_reconnect_explicit_attach_revokes_old_pending_commands_and_roster(self):
        self.attached(); b = bake(); self.roster([b]); self.issue()
        old = self.connection
        # Process restart fences all old connections; cannot reconnect with poll.
        self.httpd.app = self.app = App(self.settings)
        self.assertEqual(self.roster([b], connection=old)[0], 409)
        self.assertEqual(self.sql_one('SELECT state FROM roster_v2_commands')[0], 'revoked')
        result = self.call('attach', dict(protocol_version=2, consent_scope=store.SCOPE, consent=True))
        self.assertEqual(result[0], 200); self.connection = result[2]['connection_id']
        self.assertNotEqual(self.connection, old)
        self.assertEqual(self.roster([b])[0], 200)
        self.assertEqual(self.roster([], 999, old)[0], 409)
        self.assertIn('Подтверждать нечего', self.speech('да'))

    def test_legacy_unbind_disconnect_select_and_grant_replay_revoke_v2(self):
        for action in ('unbind', 'disconnect', 'select', 'revoke'):
            with self.subTest(action=action):
                self.attached(); b = bake(); self.roster([b]); self.issue()
                if action == 'unbind':
                    self.assertEqual(self.unbind(self.credential)[0], 204)
                elif action == 'disconnect':
                    self.assertEqual(self.json_call('POST', yi.PHONE + '/disconnect', {}, self.install_header(self.credential))[0], 200)
                elif action == 'select':
                    self.select(self.credential)
                else:
                    from store import _revoke_grant
                    with transaction(self.db_path) as c:
                        source = c.execute('SELECT source_grant_id FROM roster_v2_connections WHERE id=?', (self.connection,)).fetchone()[0]
                        _revoke_grant(c, source, self.app.now())
                self.assertEqual(self.roster([b])[0], 409)
                self.assertEqual(self.sql_one('SELECT bakes_json FROM roster_v2_connections WHERE id=?', (self.connection,))[0], '[]')
                self.assertEqual(self.webhook('да', self.access)[0], 401)

    def test_v2_disconnect_idempotent_cross_install_revoke_and_retention(self):
        self.attached(); b = bake(); self.roster([b]); cmd = self.command(b)
        other, _ = self.enroll()
        self.assertEqual(self.call('disconnect', {'connection_id': self.connection}, other)[0], 409)
        self.assertEqual(self.call('disconnect', {'connection_id': self.connection})[0], 204)
        self.assertEqual(self.call('disconnect', {'connection_id': self.connection})[0], 204)
        self.assertEqual(self.call('ack', self.ack_body(cmd))[0], 409)
        self.assertEqual(self.webhook('да', self.access)[0], 401)
        self.assertIsNotNone(self.sql_one('SELECT revoked_at FROM roster_v2_connections')[0])
        self.assertEqual(self.sql_one('SELECT bakes_json FROM roster_v2_connections')[0], '[]')

    def test_lost_connection_ttl_janitor_no_request_and_latest_revocation_retained(self):
        self.attached(); b = bake(recipe_name='SYNTHETIC-TTL-PRIVATE'); self.roster([b])
        self.app.clock.advance_ms(store.SNAPSHOT_TTL)
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and self.sql_one('SELECT bakes_json FROM roster_v2_connections')[0] != '[]':
            time.sleep(0.02)
        self.assertEqual(self.sql_one('SELECT bakes_json FROM roster_v2_connections')[0], '[]')
        self.assertLess(store.SNAPSHOT_TTL + 3000, 600000)
        self.assertIn('не на связи', self.speech('что печется'))

    def test_real_sql_rollback_no_partial_roster_or_command(self):
        self.attached(); b = bake(); self.roster([b])
        with transaction(self.db_path) as c:
            c.execute("CREATE TRIGGER fixture_failure BEFORE UPDATE OF bakes_json ON roster_v2_connections BEGIN SELECT RAISE(ABORT,'fixture'); END")
        before = self.sql_one('SELECT roster_revision,bakes_json FROM roster_v2_connections')
        self.assertEqual(self.roster([], 999)[0], 500)
        self.assertEqual(self.sql_one('SELECT roster_revision,bakes_json FROM roster_v2_connections'), before)
        with transaction(self.db_path) as c:
            c.execute('DROP TRIGGER fixture_failure')
            c.execute("CREATE TRIGGER fixture_command_failure BEFORE INSERT ON roster_v2_commands BEGIN SELECT RAISE(ABORT,'fixture'); END")
        self.speech('пауза два')
        # Handler returns a generic voice error, SQL confirmation remains atomic.
        self.speech('да'); self.assertEqual(self.count(), 0)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM roster_v2_pending')[0], 1)

    def test_concurrent_poll_same_revision_conflict_one_winner(self):
        self.attached(); a, b = bake(), bake(3)
        results = []; barrier = threading.Barrier(2)
        def run(bake):
            barrier.wait(2); results.append(self.roster([bake], 1)[0])
        ts = [threading.Thread(target=run, args=(x,)) for x in (a, b)]
        for t in ts: t.start()
        for t in ts: t.join(3); self.assertFalse(t.is_alive())
        self.assertEqual(sorted(results), [200, 409])
        self.assertEqual(self.sql_one('PRAGMA integrity_check')[0], 'ok')

    def test_concurrent_yes_mints_once_and_dispatches_once(self):
        self.attached(); b = bake(); self.roster([b]); self.speech('пауза два')
        results = []; barrier = threading.Barrier(2)
        def confirm():
            barrier.wait(2); results.append(self.webhook('да', self.access)[0])
        ts = [threading.Thread(target=confirm) for _ in range(2)]
        for t in ts: t.start()
        for t in ts: t.join(3); self.assertFalse(t.is_alive())
        self.assertEqual(results, [200, 200]); self.assertEqual(self.count(), 1)
        results = []; barrier = threading.Barrier(2)
        def poll():
            barrier.wait(2); results.append(len(self.roster([b], 2)[2]['commands']))
        ts = [threading.Thread(target=poll) for _ in range(2)]
        for t in ts: t.start()
        for t in ts: t.join(3); self.assertFalse(t.is_alive())
        self.assertEqual(sorted(results), [0, 1])

    def test_attach_auth_snapshot_race_unbind_cannot_revive_scope(self):
        self.attached()
        ready, proceed = threading.Event(), threading.Event(); result = []
        original = Handler._installation
        def held(handler, app):
            inst = original(handler, app)
            if handler.path == V2 + 'attach':
                ready.set(); self.assertTrue(proceed.wait(3))
            return inst
        with patch.object(Handler, '_installation', held):
            t = threading.Thread(target=lambda: result.append(self.call('attach', dict(protocol_version=2, consent_scope=store.SCOPE, consent=True))[0]))
            t.start(); self.assertTrue(ready.wait(2))
            self.assertEqual(self.unbind(self.credential)[0], 204)
            proceed.set(); t.join(3); self.assertFalse(t.is_alive())
        self.assertEqual(result, [409])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM roster_v2_connections WHERE revoked_at IS NULL')[0], 0)

    def test_live_loopback_confirm_poll_ack_returns_only_actual_fixture_receipt(self):
        self.attached(); b = bake(); self.roster([b]); self.speech('пауза два')
        self.app.settings = replace(self.settings, ack_wait_s=1)
        result = []
        t = threading.Thread(target=lambda: result.append(self.webhook('да', self.access)))
        t.start()
        deadline = time.monotonic() + 2
        while self.count() == 0 and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertEqual(self.count(), 1)
        polled = self.roster([b])
        self.assertEqual(len(polled[2]['commands']), 1)
        cmd = polled[2]['commands'][0]
        self.assertEqual(self.call('ack', self.ack_body(cmd))[2], {'acknowledged': True})
        t.join(3); self.assertFalse(t.is_alive())
        self.assertEqual(result[0][2]['response']['text'], 'Телефон сообщил, что применил команду.')
        self.assertEqual(self.count(), 1)
        self.speech('да'); self.assertEqual(self.count(), 1)

    def test_stale_disconnect_does_not_revoke_new_scoped_connection(self):
        self.attached(); old = self.connection
        self.httpd.app = self.app = App(self.settings)
        result = self.call('attach', dict(protocol_version=2, consent_scope=store.SCOPE, consent=True))
        self.connection = result[2]['connection_id']
        self.assertNotEqual(old, self.connection)
        self.assertEqual(self.call('disconnect', {'connection_id': old})[0], 204)
        self.assertEqual(self.roster([bake()])[0], 200)

    def test_revoke_between_webhook_classification_and_transaction_blocks_mint(self):
        for operation in ('revoke_grant', 'revoke_access'):
            self.attached(); b = bake(); self.roster([b]); self.speech('пауза два')
            ready, proceed = threading.Event(), threading.Event(); result = []
            original = Handler._classify
            def held(handler, app, body):
                classified = original(handler, app, body)
                ready.set(); self.assertTrue(proceed.wait(3))
                return classified
            before = self.count()
            with patch.object(Handler, '_classify', held):
                t = threading.Thread(target=lambda: result.append(self.webhook('да', self.access)))
                t.start(); self.assertTrue(ready.wait(2))
                with transaction(self.db_path) as c:
                    if operation == 'revoke_grant':
                        from store import _revoke_grant
                        grant = c.execute('SELECT source_grant_id FROM roster_v2_connections WHERE id=?', (self.connection,)).fetchone()[0]
                        _revoke_grant(c, grant, self.app.now())
                    else:
                        from security import sha256_text
                        c.execute('UPDATE access_tokens SET revoked_at=? WHERE token_hash=?', (self.app.now(), sha256_text(self.access)))
                proceed.set(); t.join(3); self.assertFalse(t.is_alive())
            self.assertEqual(self.count(), before)
            self.assertNotIn('применил', result[0][2]['response']['text'])

    def test_roster_change_or_revoke_between_mint_and_dispatch_fails_closed(self):
        for action in ('replacement', 'revision', 'revoke'):
            self.attached(); b = bake(); self.roster([b]); self.issue()
            if action == 'replacement':
                changed = bake()
            elif action == 'revision':
                changed = dict(b, revision=b['revision'] + 1)
            else:
                self.unbind(self.credential)
                self.assertEqual(self.roster([b])[0], 409)
                continue
            self.assertEqual(self.roster([changed])[2]['commands'], [])
            self.assertEqual(self.sql_one("SELECT state FROM roster_v2_commands ORDER BY rowid DESC LIMIT 1")[0], 'stale')

    def test_nine_bakes_request_response_bounds_and_no_private_tokens(self):
        self.attached(); bakes = [bake(i) for i in range(1, 10)]
        result = self.roster(bakes)
        self.assertEqual(result[0], 200)
        self.assertLessEqual(len(result[3]), 65536)
        self.assertEqual(self.header_map(result[1])['cache-control'], ['no-store'])
        self.assertNotIn('set-cookie', self.header_map(result[1]))
        self.assertIn('Девятая', self.speech('что печется'))
        raw = b' ' * 65537
        self.assertEqual(self.request('POST', V2 + 'poll', raw, dict(self.install_header(self.credential), **{'Content-Type': 'application/json'}))[0], 413)
        self.assertEqual(self.call('poll', {'protocol_version': 1})[0], 426)
        self.assertEqual(self.call('poll', {'protocol_version': 2, 'connection_id': self.connection, 'roster_revision': 2, 'bakes': bakes})[0], 200)
        self.assertEqual(self.json_call('POST', V2 + 'poll?access_token=synthetic', {}, self.install_header(self.credential))[0], 400)
        with sqlite3.connect(self.db_path) as c:
            dump = '\n'.join(c.iterdump())
        self.assertNotIn(self.credential, dump)
        self.assertNotIn(self.access, dump)
        self.assertNotIn(self.credential, self.logs.getvalue())
        self.assertNotIn(self.access, self.logs.getvalue())

    def test_every_operation_and_every_ack_outcome_actual_http(self):
        cases = [('пауза два', 'да', 'PAUSE', 'running', 'applied'),
                 ('продолжи вторую', 'да', 'RESUME', 'paused', 'stale'),
                 ('дальше два', 'да', 'ADVANCE', 'running', 'expired'),
                 ('назад для второй', 'да', 'BACK', 'running', 'rejected'),
                 ('отмени вторую', 'подтверждаю отмену', 'CANCEL', 'running', 'phone_required')]
        for phrase, confirmation, operation, state, outcome in cases:
            with self.subTest(operation=operation):
                self.attached(); b = bake(state=state); self.roster([b])
                self.issue(phrase, confirmation)
                result = self.roster([b])
                self.assertEqual(len(result[2]['commands']), 1)
                cmd = result[2]['commands'][0]
                self.assertEqual(cmd['operation'], operation)
                self.assertEqual(cmd['expected_paused'], state == 'paused')
                body = self.ack_body(cmd, outcome=outcome, resulting_revision=8 if outcome == 'applied' else None)
                self.assertEqual(self.call('ack', body)[2], {'acknowledged': True})
                self.assertEqual(self.call('ack', body)[2], {'acknowledged': True})

    def test_disambiguation_pins_original_candidates_and_number_reuse_cannot_retarget(self):
        self.attached(); a, b, other = bake(1), bake(2), bake(3, recipe_name='Пицца')
        self.roster([a, b, other]); self.speech('пауза чиабатты')
        self.assertIn('Повторите команду', self.speech('третья'))
        self.assertEqual(self.count(), 0)
        self.speech('пауза')
        self.roster([a, dict(b, bake_key=yi.legacy.uid()), other])
        self.assertIn('Повторите команду', self.speech('вторая'))
        self.assertEqual(self.count(), 0)

    def test_old_d499_real_reader_http_latest_database_and_old_unbind(self):
        self.attached(); b = bake(); self.roster([b]); self.issue()
        fixture = Path(__file__).parent / 'fixtures/d499-reader.tar.gz'
        manifest = json.loads((fixture.parent / 'd499-reader.json').read_text())
        self.assertEqual(manifest['source_sha'], BASE_SHA)
        self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), manifest['archive_sha256'])
        with tempfile.TemporaryDirectory(prefix='oldreader-d499-') as tmp:
            with tarfile.open(fixture) as archive:
                # Also supports the host's older Python tarfile API. Only
                # hashed regular relative files from the pinned archive.
                for member in archive.getmembers():
                    self.assertTrue(member.isfile())
                    self.assertIn(member.name, manifest['files'])
                    self.assertNotIn('..', Path(member.name).parts)
                    self.assertFalse(Path(member.name).is_absolute())
                    target = Path(tmp) / member.name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.extractfile(member).read())
            for name, sha in manifest['files'].items():
                self.assertEqual(hashlib.sha256((Path(tmp) / name).read_bytes()).hexdigest(), sha)
            env = dict(os.environ, PYTHONPATH=tmp)
            code = '''
import json,sys,threading
from app import App,make_server
from config import Settings
from http.client import HTTPConnection
app=App(Settings(db_path=sys.argv[1],poll_wait_s=.01))
s=make_server('127.0.0.1',0,app)
t=threading.Thread(target=s.serve_forever,kwargs={'poll_interval':.01});t.start()
def call(path,body,auth):
 c=HTTPConnection('127.0.0.1',s.server_address[1]);c.request('POST',path,json.dumps(body),{'Authorization':auth,'Content-Type':'application/json'});r=c.getresponse();v=(r.status,r.read().decode());c.close();return v
try:
 snap=json.loads(sys.argv[5])
 print(json.dumps([call('/alice/madre/voice/phone/poll',{'connection_id':sys.argv[3],'selection_generation':sys.argv[4],'snapshot':snap},'Installation '+sys.argv[2]),call('/alice/madre/voice/phone/yandex-unbind',{},'Installation '+sys.argv[2])]))
finally:s.shutdown();s.server_close();t.join()
'''
            p = subprocess.run([sys.executable, '-c', code, self.db_path, self.credential, yi.legacy.uid(), self.generation,
                                json.dumps(self.snapshot(self.legacy_bake))], cwd=tmp, env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(p.returncode, 0, p.stderr)
            result = json.loads(p.stdout)
            self.assertEqual([r[0] for r in result], [200, 204])
        self.assertEqual(self.sql_one('PRAGMA user_version')[0], 3)
        self.assertEqual(self.sql_one('PRAGMA integrity_check')[0], 'ok')
        self.assertEqual(self.roster([b])[0], 409)
        self.assertEqual(self.sql_one('SELECT state FROM roster_v2_commands')[0], 'revoked')
        self.assertEqual(self.sql_one('SELECT bakes_json FROM roster_v2_connections')[0], '[]')
        # Actual new initializer keeps old-reader revocation, not historical DB.
        yi.init_db(self.db_path)
        self.assertIsNotNone(self.sql_one('SELECT revoked_at FROM roster_v2_connections')[0])


# Independent valid Russian forms, not generated using the production parser.
FORMS = (
 'один одна одно одну одной одного одному одним одном первый первая первое первую первой первого первому первым первом первые первых первыми',
 'два две двух двум двумя второй вторая второе вторую второго второму вторым втором вторые вторых вторыми',
 'три трех трем тремя третий третья третье третью третьей третьего третьему третьим третьем третьи третьих третьими',
 'четыре четырех четырем четырьмя четвертый четвертая четвертое четвертую четвертой четвертого четвертому четвертым четвертом четвертые четвертых четвертыми',
 'пять пяти пятью пятый пятая пятое пятую пятой пятого пятому пятым пятом пятые пятых пятыми',
 'шесть шести шестью шестой шестая шестое шестую шестого шестому шестым шестом шестые шестых шестыми',
 'семь семи семью седьмой седьмая седьмое седьмую седьмого седьмому седьмым седьмом седьмые седьмых седьмыми',
 'восемь восьми восемью восьмой восьмая восьмое восьмую восьмого восьмому восьмым восьмом восьмые восьмых восьмыми',
 'девять девяти девятью девятый девятая девятое девятую девятой девятого девятому девятым девятом девятые девятых девятыми')
INTENTS = {'что с': 'what', 'сколько осталось': 'remaining', 'повтори': 'repeat', 'что дальше': 'peek',
           'сколько муки': 'quantities', 'пауза': 'pause', 'продолжи': 'resume', 'дальше': 'advance', 'назад': 'back', 'отмени': 'cancel'}


class NumberedNLU(unittest.TestCase):
    def test_all_1_to_9_forms_every_intent_both_positions_and_wrappers(self):
        count = 0
        for n, forms in enumerate(FORMS, 1):
            for form in forms.split() + [str(n), '№' + str(n), 'номер ' + str(n), 'выпечка номер ' + str(n)]:
                for command, intent in INTENTS.items():
                    for phrase in (f'{command} {form}', f'{form} {command}', f'Алиса, пожалуйста, скажи {command} для {form}'):
                        with self.subTest(phrase=phrase):
                            self.assertEqual(voice.parse(phrase), voice.Parsed(intent, n))
                        count += 1
        self.assertGreater(count, 5000)

    def test_canonical_examples_filler_yo_exclusions_recipe_ambiguity(self):
        a, b = bake(1, recipe_name='Чиабатта'), bake(2, recipe_name='Чиабатта')
        for phrase in ('что со второй', 'как вторая', 'что с выпечкой два'):
            self.assertEqual(voice.parse(phrase).intent, 'what')
            self.assertEqual(voice.parse(phrase).number, 2)
        self.assertEqual(voice.parse('сколько ещё у номера один'), voice.Parsed('remaining', 1))
        self.assertEqual(voice.parse('пауза чиабатты', [a, b]).keys, tuple(sorted([a['bake_key'], b['bake_key']])))
        self.assertEqual(voice.parse('пауза чиабатта', [a]).keys, (a['bake_key'],))
        for phrase in ('пауза одну минуту', 'пауза один раз', 'пауза 2 раза', 'дальше двадцать минут', 'дальше и назад'):
            self.assertEqual(voice.parse(phrase).intent, 'unknown', phrase)
        for phrase in ('пауза 0', 'пауза 10', 'пауза десять', 'пауза девятнадцать', 'пауза -1'):
            self.assertEqual(voice.parse(phrase).intent, 'missing')
        self.assertEqual(voice.pair('пауза два', 'пауза три', []).intent, 'ambiguous')
        self.assertEqual(voice.pair('пауза два', 'дальше два', []).intent, 'ambiguous')


if __name__ == '__main__':
    unittest.main()
