"""Real loopback HTTP/SQLite regressions for review B1/B2 and explicit A1.

Only ExternalYandex's HTTPS peer is mocked; barriers pause AFTER the real
initial installation-auth SQL snapshot, including the review's pre-epoch window.
"""
import sqlite3
import threading
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

import test_yandex_identity as yi
from app import Handler
from db import transaction
from security import new_installation_credential


class ConsentHTTP(unittest.TestCase):
    setUp = yi.YandexHTTP.setUp
    tearDown = yi.YandexHTTP.tearDown
    for name in ('request', 'json_call', 'form_call', 'install_header', 'bearer_header',
                 'enroll', 'select', 'snapshot', 'poll', 'webhook', 'alice', 'header_map',
                 'cookies', 'cookie_header', 'authorize_query', 'csrf_from', 'display_code_from',
                 'start_challenge', 'sql_one', '_link', 'bind', 'unbind', 'start_auto', 'callback',
                 'exchange', 'refresh', 'fallback_finish'):
        locals()[name] = getattr(yi.YandexHTTP, name)

    def consent(self, credential, bake, generation):
        return self.json_call('POST', yi.PHONE + '/yandex-voice-consent',
                              {'bake_id': bake, 'generation': generation}, self.install_header(credential))

    def prepared(self):
        credential, installation = self.enroll()
        bake, generation = self.select(credential)
        self.assertEqual(self.bind(credential)[0], 200)
        return credential, installation, bake, generation

    def first_consent(self):
        credential, installation, bake, generation = self.prepared()
        self.assertEqual(self.consent(credential, bake, generation)[0], 200)
        return credential, installation, bake, generation

    def auto_code(self):
        state, jar = self.start_auto()
        status, headers, _ = self.callback(state, jar)
        self.assertEqual(status, 303)
        query = parse_qs(urlsplit(self.header_map(headers)['location'][0]).query)
        self.assertEqual(query['scope'], [yi.EXACT_SCOPE])
        return query['code'][0]

    def assert_empty_authority(self):
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM auth_codes')[0], 0)

    def paused_auth(self, route, operation, concurrent):
        ready, proceed = threading.Event(), threading.Event()
        original = Handler._installation
        results = []
        def held(handler, app):
            inst = original(handler, app)  # not a mock auth/epoch/selection result
            if handler.path.endswith('/' + route):
                ready.set()
                if not proceed.wait(3):
                    raise RuntimeError('fixture barrier expired')
            return inst
        with patch.object(Handler, '_installation', held):
            thread = threading.Thread(target=lambda: results.append(operation()))
            thread.start()
            try:
                self.assertTrue(ready.wait(1))
                concurrent()
            finally:
                proceed.set()
                thread.join(3)
            self.assertFalse(thread.is_alive())
        return results

    def test_B2_bind_paused_before_late_epoch_cannot_survive_logout(self):
        credential, installation = self.enroll()
        access, _, _ = self._link(credential)
        self.assertEqual(self.bind(credential)[0], 200)
        results = self.paused_auth('yandex-bind', lambda: self.bind(credential)[0],
                                   lambda: self.assertEqual(self.unbind(credential)[0], 204))
        self.assertEqual(results, [409])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM yandex_bindings WHERE installation_id=?', (installation,))[0], 0)
        self.assertEqual(self.webhook('что печём', access)[0], 401)

    def approve_race(self, logout):
        credential, installation = self.enroll()
        access, bake, generation = self._link(credential)
        self.bind(credential)
        display, jar = self.start_challenge()
        _, _, preview, _ = self.json_call('POST', yi.PHONE + '/pair-preview', {'code': display}, self.install_header(credential))
        def approve():
            return self.json_call('POST', yi.PHONE + '/pair-approve', {
                'challenge_id': preview['challenge_id'], 'code': display,
                'bake_key': bake, 'selection_generation': generation}, self.install_header(credential))[0]
        def revoke():
            if logout:
                self.assertEqual(self.unbind(credential)[0], 204)
            else:
                self.select(credential)  # A -> B -> A defeats selection-only checks
            self.select(credential, bake, generation)
        self.assertEqual(self.paused_auth('pair-approve', approve, revoke), [400])
        self.assertEqual(self.webhook('что печём', access)[0], 401)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM challenges WHERE installation_id=? AND approved_at IS NOT NULL AND consumed_at IS NULL', (installation,))[0], 0)
        self.assertEqual(self.form_call(yi.OAUTH + '/confirm', {'csrf': jar['madre_csrf']}, {'Cookie': self.cookie_header(jar)})[0], 400)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM auth_codes WHERE used_at IS NULL')[0], 0)

    def test_B1_delayed_approve_after_logout_and_same_reselection_is_dead(self):
        self.approve_race(logout=True)

    def test_B1_delayed_approve_after_selection_ABA_is_dead(self):
        self.approve_race(logout=False)

    def test_first_link_requires_explicit_phone_consent_then_real_token_webhook_poll(self):
        credential, installation, bake, generation = self.prepared()
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assert_empty_authority()
        now = self.app.now()
        status, headers, payload, _ = self.consent(credential, bake, generation)
        self.assertEqual(status, 200)
        self.assertEqual(set(payload), {'expires_at'})
        self.assertLessEqual(now + 300000, payload['expires_at'])
        self.assertLessEqual(payload['expires_at'], self.app.now() + 300000)
        self.assertEqual(self.header_map(headers)['cache-control'], ['no-store'])
        self.assertNotIn('set-cookie', self.header_map(headers))
        self.assert_empty_authority()  # phone POST is evidence, NOT a grant/token
        code = self.auto_code()
        self.assertIsNotNone(self.sql_one('SELECT consumed_at FROM yandex_voice_consents WHERE installation_id=?', (installation,))[0])
        status, pair = self.exchange(code)
        self.assertEqual(status, 200)
        self.assertEqual(self.webhook('что печём', pair['access_token'])[0], 200)
        status, _, poll, _ = self.poll(credential, yi.legacy.uid(), generation, bake)
        self.assertEqual(status, 200)
        self.assertTrue(poll['linked'])
        self.assertNotIn('alice_linked', poll)  # no fabricated Station surface
        status, _, response, _ = self.webhook('что печём', pair['access_token'])
        self.assertEqual(status, 200)
        self.assertIn('Хлебушек', response['response']['text'])
        self.assertIn('match=uid uid_hash=', self.logs.getvalue())
        self.assertEqual(self.exchange(code)[0], 400)
        self.assertEqual(self.webhook('что печём', pair['access_token'])[0], 401)

    def test_endpoint_strict_fields_auth_current_selection_and_bound_uid(self):
        credential, _ = self.enroll()
        bake, generation = self.select(credential)
        self.assertEqual(self.consent(credential, bake, generation)[0], 409)
        self.bind(credential)
        for headers in ({}, self.bearer_header(credential), self.install_header(new_installation_credential()),
                        self.install_header(credential + 'x')):
            self.assertEqual(self.json_call('POST', yi.PHONE + '/yandex-voice-consent',
                {'bake_id': bake, 'generation': generation}, headers)[0], 401)
        for payload in ({}, {'bake_id': bake}, {'bake_id': bake, 'generation': generation, 'scope': yi.EXACT_SCOPE},
                        {'bake_id': None, 'generation': generation}, {'bake_id': bake, 'generation': 1}):
            self.assertEqual(self.json_call('POST', yi.PHONE + '/yandex-voice-consent', payload, self.install_header(credential))[0], 400)
        self.assertEqual(self.consent(credential, yi.legacy.uid(), generation)[0], 409)
        self.assertEqual(self.consent(credential, bake, yi.legacy.uid())[0], 409)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM yandex_voice_consents')[0], 0)
        self.assert_empty_authority()

    def test_expired_consent_falls_back_without_consumption(self):
        credential, installation, bake, generation = self.first_consent()
        expires = self.sql_one('SELECT expires_at FROM yandex_voice_consents')[0]
        self.app.clock.advance_ms(expires - self.app.now())
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assert_empty_authority()
        self.assertIsNone(self.sql_one('SELECT consumed_at FROM yandex_voice_consents')[0])

    def test_consumed_consent_cannot_issue_second_code_before_redeem(self):
        self.first_consent()
        code = self.auto_code()
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM auth_codes')[0], 1)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)
        self.assertEqual(self.exchange(code)[0], 200)
        # A current valid grant is independently sufficient for reauthorization.
        self.assertEqual(self.exchange(self.auto_code())[0], 200)

    def test_wrong_uid_and_ambiguous_fallback_do_not_consume_evidence(self):
        self.first_consent()
        self.peer.info['id'] = '22222222'
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assert_empty_authority()
        self.peer.info['id'] = yi.UID
        self.first_consent()
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assert_empty_authority()
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM yandex_voice_consents WHERE consumed_at IS NOT NULL')[0], 0)

    def test_exactly_one_eligible_installation_ignores_unconsented_phone(self):
        self.first_consent()
        self.prepared()  # same UID, selected, but no explicit authority
        self.assertEqual(self.exchange(self.auto_code())[0], 200)

    def test_reselection_uidchange_disconnect_delete_invalidate_evidence(self):
        for action in ('reselect', 'uidchange', 'disconnect', 'delete', 'logout'):
            with self.subTest(action=action):
                credential, installation, bake, generation = self.first_consent()
                if action == 'reselect':
                    self.select(credential)
                    self.select(credential, bake, generation)
                elif action == 'uidchange':
                    self.peer.info['id'] = '1122334455'
                    self.bind(credential)
                    self.peer.info['id'] = yi.UID
                    self.bind(credential)
                    self.select(credential, bake, generation)
                elif action == 'delete':
                    with transaction(self.db_path) as conn:
                        # Existing schema1/2 binding/epoch foreign keys are
                        # RESTRICT, not CASCADE; respect their deletion order.
                        conn.execute('DELETE FROM yandex_bindings WHERE installation_id=?', (installation,))
                        conn.execute('DELETE FROM yandex_epochs WHERE installation_id=?', (installation,))
                        conn.execute('DELETE FROM installations WHERE id=?', (installation,))
                elif action == 'disconnect':
                    self.json_call('POST', yi.PHONE + '/disconnect', {}, self.install_header(credential))
                    self.select(credential, bake, generation)
                else:
                    self.unbind(credential)
                    self.bind(credential)
                    self.select(credential, bake, generation)
                state, jar = self.start_auto()
                self.assertEqual(self.callback(state, jar)[0], 200)
                self.assert_empty_authority()
                self.assertEqual(self.sql_one('SELECT COUNT(*) FROM yandex_voice_consents WHERE installation_id=?', (installation,))[0], 0)
                if action != 'delete':
                    self.unbind(credential)

    def test_consent_auth_snapshot_cannot_survive_logout_or_ABA(self):
        for action in ('logout', 'reselect', 'uidchange'):
            with self.subTest(action=action):
                credential, _, bake, generation = self.prepared()
                def concurrent():
                    if action == 'logout':
                        self.unbind(credential)
                        self.bind(credential)
                    elif action == 'uidchange':
                        self.peer.info['id'] = '1122334455'
                        self.bind(credential)
                        self.peer.info['id'] = yi.UID
                        self.bind(credential)
                    else:
                        self.select(credential)
                    self.select(credential, bake, generation)
                self.assertEqual(self.paused_auth('yandex-voice-consent',
                    lambda: self.consent(credential, bake, generation)[0], concurrent), [409])
                self.assertEqual(self.sql_one('SELECT COUNT(*) FROM yandex_voice_consents')[0], 0)
                self.unbind(credential)

    def test_callback_inflight_logout_or_reselection_cannot_mint(self):
        for action in ('logout', 'reselect'):
            with self.subTest(action=action):
                credential, _, bake, generation = self.first_consent()
                state, jar = self.start_auto()
                ready, proceed = threading.Event(), threading.Event()
                self.peer.block_info = (ready, proceed)
                result = []
                thread = threading.Thread(target=lambda: result.append(self.callback(state, jar)[0]))
                thread.start()
                try:
                    self.assertTrue(ready.wait(1))
                    if action == 'logout':
                        self.unbind(credential)
                    else:
                        self.select(credential)
                    self.select(credential, bake, generation)
                finally:
                    proceed.set()
                    thread.join(3)
                    self.peer.block_info = None
                self.assertEqual(result, [200])
                self.assert_empty_authority()
                self.unbind(credential)

    def test_redeem_rechecks_consent_expiry_binding_selection_and_revocation(self):
        for action in ('expire', 'logout', 'reselect', 'uidchange', 'disconnect'):
            with self.subTest(action=action):
                credential, _, bake, generation = self.first_consent()
                if action == 'expire':
                    # Issue near consent deadline; auth code itself remains live.
                    self.app.clock.advance_ms(299000)
                code = self.auto_code()
                if action == 'expire':
                    self.app.clock.advance_ms(1001)
                elif action == 'logout':
                    self.unbind(credential)
                    self.bind(credential)
                    self.select(credential, bake, generation)
                elif action == 'reselect':
                    self.select(credential)
                    self.select(credential, bake, generation)
                elif action == 'uidchange':
                    self.peer.info['id'] = '1122334455'
                    self.bind(credential)
                    self.peer.info['id'] = yi.UID
                else:
                    self.json_call('POST', yi.PHONE + '/disconnect', {}, self.install_header(credential))
                    self.select(credential, bake, generation)
                self.assertEqual(self.exchange(code)[0], 400)
                self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)
                self.unbind(credential)

    def test_two_parallel_independent_callbacks_consume_one_consent(self):
        self.first_consent()
        state_a, jar_a = self.start_auto()
        state_b, jar_b = self.start_auto()
        # Each authorize has its own verifier; fake external peer validates its
        # corresponding PKCE challenge. This barrier is after real /info only.
        original = self.app.yandex.info
        barrier = threading.Barrier(2)
        def info(token):
            result = original(token)
            barrier.wait(2)
            return result
        # Keep same fake PKCE challenge for both flows by using one state's
        # stored verifier in the EXTERNAL peer only, never alter local state.
        self.peer.token_error = False
        results = []
        # ExternalYandex supports one expected challenge; derive on each request
        # with a per-thread external exchange adapter below.
        from yandex_identity import YandexClient
        original_exchange = YandexClient.exchange
        lock = threading.Lock()
        def exchange(client, code, verifier):
            import base64, hashlib
            with lock:
                self.peer.challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
                return original_exchange(client, code, verifier)
        with patch.object(YandexClient, 'exchange', exchange), patch.object(self.app.yandex, 'info', info):
            threads = [threading.Thread(target=lambda s=s, j=j: results.append(self.callback(s, j)[0]))
                       for s, j in ((state_a, jar_a), (state_b, jar_b))]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(3)
        self.assertEqual(sorted(results), [200, 303])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM auth_codes')[0], 1)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)

    def test_unbind_database_lock_never_acknowledges_false_success(self):
        credential, _ = self.enroll()
        access, _, _ = self._link(credential)
        self.bind(credential)
        with transaction(self.db_path):
            self.assertEqual(self.unbind(credential)[0], 503)
        self.assertEqual(self.webhook('что печём', access)[0], 200)
        self.assertEqual(self.unbind(credential)[0], 204)
        self.assertEqual(self.webhook('что печём', access)[0], 401)


    def test_retry_bind_select_screen_opening_and_phone_post_never_rearm_or_extend(self):
        credential, _, bake, generation = self.first_consent()
        expires = self.sql_one('SELECT expires_at FROM yandex_voice_consents')[0]
        self.app.clock.advance_ms(1000)
        self.bind(credential)
        self.select(credential, bake, generation)
        self.start_auto()  # merely opening authorization is not a phone action
        self.assertEqual(self.consent(credential, bake, generation)[2], {'expires_at': expires})
        self.assertEqual(self.sql_one('SELECT expires_at FROM yandex_voice_consents')[0], expires)
        self.assert_empty_authority()
        code = self.auto_code()
        self.assertEqual(self.consent(credential, bake, generation)[2], {'expires_at': expires})
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM auth_codes')[0], 1)
        self.assertEqual(self.exchange(code)[0], 200)

    def test_failed_sql_code_issuance_does_not_consume_or_leave_partial_authority(self):
        self.first_consent()
        with transaction(self.db_path) as conn:
            conn.execute("CREATE TRIGGER fail_code BEFORE INSERT ON auth_codes BEGIN SELECT RAISE(ABORT,'fixture'); END")
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 500)
        self.assertIsNone(self.sql_one('SELECT consumed_at FROM yandex_voice_consents')[0])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM challenges')[0], 0)
        self.assert_empty_authority()
        with transaction(self.db_path) as conn:
            conn.execute('DROP TRIGGER fail_code')
        self.assertEqual(self.exchange(self.auto_code())[0], 200)

    def test_reauth_code_redeem_rechecks_source_grant_expiry_and_replay_revocation(self):
        credential, _ = self.enroll()
        access, bake, generation = self._link(credential)
        self.bind(credential)
        # Keep its grant almost expired, without mocking the repository result.
        with transaction(self.db_path) as conn:
            conn.execute('UPDATE grants SET expires_at=?', (self.app.now() + 1000,))
        code = self.auto_code()
        self.app.clock.advance_ms(1001)
        self.assertEqual(self.exchange(code)[0], 400)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 1)
        self.unbind(credential)
        self.select(credential, bake, generation)
        self.bind(credential)
        self.consent(credential, bake, generation)
        code = self.auto_code()
        self.assertEqual(self.exchange(code)[0], 200)
        pending = self.auto_code()  # authorized by the newly redeemed live grant
        self.assertEqual(self.exchange(code)[0], 400)  # revokes source grant
        self.assertEqual(self.exchange(pending)[0], 400)

    def test_grant_revocation_invalidates_fresh_phone_evidence(self):
        credential, _, bake, generation = self.first_consent()
        code = self.auto_code()
        self.assertEqual(self.exchange(code)[0], 200)
        # A fresh explicit action after expiration creates fresh evidence.
        self.app.clock.advance_ms(300001)
        self.assertEqual(self.consent(credential, bake, generation)[0], 200)
        self.assertEqual(self.exchange(code)[0], 400)  # real code replay revocation
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM yandex_voice_consents')[0], 0)
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)

    def test_schema_downgrade_reupgrade_never_revives_consent_or_pending_uid_code(self):
        credential, installation, bake, generation = self.first_consent()
        code = self.auto_code()  # still unexpired when the downgrade fence runs
        self.first_consent()  # another phone has fresh, unconsumed evidence
        old_epoch = self.sql_one('SELECT epoch FROM yandex_epochs WHERE installation_id=?', (installation,))[0]
        with transaction(self.db_path) as conn:
            # Same marker the actual baseline initializer writes on rollback.
            conn.execute('PRAGMA user_version=1')
        yi.init_db(self.db_path)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM yandex_voice_consents')[0], 0)
        self.assertGreater(self.sql_one('SELECT epoch FROM yandex_epochs')[0], old_epoch)
        self.assertIsNotNone(self.sql_one('SELECT used_at FROM auth_codes')[0])
        self.assertEqual(self.exchange(code)[0], 400)
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)

    def test_schema2_upgrade_is_additive_and_idempotent(self):
        credential, installation, bake, generation = self.prepared()
        with transaction(self.db_path) as conn:
            conn.execute('DROP TABLE yandex_code_authority')
            conn.execute('DROP TABLE yandex_voice_consents')
            conn.execute('PRAGMA user_version=2')
        yi.init_db(self.db_path)
        yi.init_db(self.db_path)
        self.assertEqual(self.sql_one('PRAGMA user_version')[0], 3)
        self.assertEqual(self.sql_one('PRAGMA integrity_check')[0], 'ok')
        self.assertEqual(self.sql_one('SELECT uid FROM yandex_bindings WHERE installation_id=?', (installation,))[0], yi.UID)
        self.assertEqual(self.consent(credential, bake, generation)[0], 200)
        self.assertEqual(self.exchange(self.auto_code())[0], 200)


if __name__ == '__main__':
    unittest.main(verbosity=2)
