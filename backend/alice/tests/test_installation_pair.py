"""Local real HTTP + latest SQLite; ONLY external Yandex HTTPS is mocked.

These are protocol fixtures, not a live login, Android execution or QA e2e.
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
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit, urlencode

import test_yandex_identity as yi
import test_roster_v2 as rv
from app import App, Handler
from db import init_db, transaction
from security import sha256_text
import installation_pair as ip


class InstallationPairHTTP(unittest.TestCase):
    for name in ('request', 'json_call', 'form_call', 'install_header', 'bearer_header', 'enroll', 'select',
                 'snapshot', 'poll', 'webhook', 'alice', 'header_map', 'cookies', 'cookie_header',
                 'authorize_query', 'csrf_from', 'display_code_from', 'start_challenge', 'sql_one',
                 '_link', 'unbind', 'bind', 'start_auto', 'callback', 'exchange', 'refresh'):
        locals()[name] = getattr(yi.YandexHTTP, name)
    for name in ('call', 'roster', 'speech', 'count', 'issue', 'command', 'ack_body'):
        locals()[name] = getattr(rv.RosterHTTP, name)

    def setUp(self):
        yi.YandexHTTP.setUp(self)
        self.settings = replace(self.settings, roster_v2=True, yandex_match=True, ack_wait_s=0.05)
        self.app.settings = self.settings
        self.credential, self.installation = self.enroll()
        self.assertEqual(self.bind(self.credential)[0], 200)
        self.roster_revision = 0

    tearDown = yi.YandexHTTP.tearDown

    def consent_body(self, request_id=None, **over):
        return dict(dict(protocol_version=2, request_id=request_id or yi.legacy.uid(),
                         consent_scope=ip.SCOPE, consent=True), **over)

    def consent(self, body=None, credential=None):
        return self.call('pair-consent', body or self.consent_body(), credential)

    def code(self, consent=True, outer='outer Ω &=+ state'):
        if consent:
            result = self.consent()
            self.assertEqual(result[0], 200, result)
        state, jar = self.start_auto(state=outer)
        status, headers, _ = self.callback(state, jar)
        self.assertEqual(status, 303)
        query = parse_qs(urlsplit(self.header_map(headers)['location'][0]).query)
        self.assertEqual(query['state'], [outer])
        self.assertEqual(query['scope'], [yi.EXACT_SCOPE])
        self.assertEqual(query['client_id'], [yi.legacy.CLIENT_ID])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants WHERE revoked_at IS NULL')[0], 0)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)
        return query['code'][0]

    def paired(self):
        self.auth_code = self.code()
        status, self.pair = self.exchange(self.auth_code)
        self.assertEqual(status, 200)
        self.access = self.pair['access_token']
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants WHERE revoked_at IS NULL')[0], 1)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)
        self.assertEqual(self.sql_one('SELECT selected_bake_key,selected_generation FROM installations WHERE id=?',
                                     (self.installation,)), (None, None))

    def attach(self):
        result = self.call('attach', dict(protocol_version=2, consent_scope=ip.SCOPE, consent=True))
        self.assertEqual(result[0], 200, result)
        self.connection = result[2]['connection_id']
        return result

    def attached(self):
        self.paired()
        self.attach()

    def test_zero_bake_full_real_code_client_token_attach_empty_poll_refresh_disconnect(self):
        self.assertEqual(self.call('attach', dict(protocol_version=2, consent_scope=ip.SCOPE, consent=True))[0], 409)
        self.attached()
        self.assertEqual(self.roster([])[0], 200)
        self.assertIn('ничего не печётся', self.speech('что печется'))
        self.assertEqual(self.attach()[2]['connection_id'], self.connection)
        status, rotated = self.refresh(self.pair['refresh_token'])
        self.assertEqual(status, 200)
        self.assertEqual(self.webhook('что печется', self.access)[0], 401)
        self.access = rotated['access_token']
        self.assertIn('ничего не печётся', self.speech('что печется'))
        self.assertEqual(self.call('disconnect', {'connection_id': self.connection})[0], 204)
        self.assertEqual(self.call('disconnect', {'connection_id': self.connection})[0], 204)
        self.assertEqual(self.webhook('что печется', self.access)[0], 401)
        self.assertEqual(self.refresh(rotated['refresh_token'])[0], 400)
        self.assertEqual(self.call('attach', dict(protocol_version=2, consent_scope=ip.SCOPE, consent=True))[0], 409)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)

    def test_planned_only_pair_then_running_real_confirmation_lease_ack_and_disconnect(self):
        self.attached()
        planned = rv.bake(3, state='scheduled', start_at=self.app.now() + 100000, control_ready=False)
        self.assertEqual(self.roster([planned])[0], 200)
        self.assertIn('ещё не идёт', self.speech('пауза три'))
        self.speech('да'); self.assertEqual(self.count(), 0)
        self.assertIn('запланирована', self.speech('что печется').lower())
        running = dict(planned, state='running', start_at=None, control_ready=True, revision=8)
        self.assertEqual(self.roster([running])[0], 200)
        self.assertIn('Скажите', self.speech('пауза три'))
        self.assertEqual(self.count(), 0)
        self.speech('да'); self.assertEqual(self.count(), 1)
        cmd = self.roster([running])[2]['commands'][0]
        self.assertEqual(cmd['bake_key'], planned['bake_key'])
        self.assertEqual(cmd['operation'], 'PAUSE')
        self.assertEqual(cmd['expires_at'] - cmd['issued_at'], 4000)
        ack = self.ack_body(cmd)
        self.assertEqual(self.call('ack', ack)[0], 200)
        self.assertEqual(self.call('ack', ack)[0], 200)
        self.assertEqual(self.roster([running])[2]['commands'], [])
        self.assertEqual(self.call('disconnect', {'connection_id': self.connection})[0], 204)
        self.assertEqual(self.call('ack', ack)[0], 409)  # duplicate still rechecks authority
        self.assertEqual(self.roster([])[0], 409)

    def test_sdk_bind_enroll_old_consent_never_create_installation_authority(self):
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants')[0], 0)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 0)
        bake, gen = self.select(self.credential)
        self.assertEqual(self.json_call('POST', yi.PHONE + '/yandex-voice-consent',
            {'bake_id': bake, 'generation': gen}, self.install_header(self.credential))[0], 200)
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 303)  # still legacy, never installation scope
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 0)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants')[0], 0)

    def test_pending_challenge_not_stable_grant_idempotency_expiry_and_consumption(self):
        body = self.consent_body()
        first = self.consent(body)
        self.assertEqual(first[0], 200)
        self.app.clock.advance_ms(1000)
        self.assertEqual(self.consent(body)[2], first[2])
        self.assertEqual(self.call('attach', dict(protocol_version=2, consent_scope=ip.SCOPE, consent=True))[0], 409)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants')[0], 0)
        code = self.code(consent=False)
        self.assertEqual(self.consent(body)[0], 409)
        self.assertEqual(self.exchange(code)[0], 200)
        expiry = self.sql_one('SELECT expires_at FROM installation_pair_grants')[0]
        self.app.clock.advance_ms(300001)
        self.attach(); self.assertEqual(self.roster([])[0], 200)
        self.assertEqual(self.sql_one('SELECT expires_at FROM installation_pair_grants')[0], expiry)
        self.assertGreater(expiry, self.app.now())

    def test_strict_consent_auth_parser_scope_unknown_duplicate_and_default_off(self):
        good = self.consent_body()
        for over, status in (({'protocol_version': True}, 426), ({'consent': 1}, 403),
                             ({'consent': False}, 403), ({'consent_scope': yi.EXACT_SCOPE}, 403),
                             ({'request_id': good['request_id'].upper()}, 400), ({'request_id': 'x'}, 400),
                             ({'extra': 'x'}, 400)):
            self.assertEqual(self.consent(dict(good, **over))[0], status)
        for headers in ({}, self.bearer_header('synthetic'), self.install_header('x' * 43)):
            self.assertEqual(self.json_call('POST', rv.V2 + 'pair-consent', good, headers)[0], 401)
        raw = json.dumps(good).replace('"consent": true', '"consent": true,"consent":true').encode()
        self.assertEqual(self.request('POST', rv.V2 + 'pair-consent', raw,
            dict(self.install_header(self.credential), **{'Content-Type': 'application/json'}))[0], 400)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_requests')[0], 0)
        self.app.settings = replace(self.settings, roster_v2=False)
        self.assertEqual(self.consent(good)[0], 426)
        self.app.settings = replace(self.settings, yandex_match=False)
        self.assertEqual(self.consent(good)[0], 503)

    def test_broker_client_redirect_secret_duplicates_and_extra_fields_rejected_without_consumption(self):
        code = self.code()
        good = dict(grant_type='authorization_code', code=code, client_id=yi.legacy.CLIENT_ID,
                    client_secret=yi.legacy.CLIENT_SECRET, redirect_uri=yi.EXACT_REDIRECT)
        for over, status in (({'client_id': 'wrong'}, 401), ({'client_secret': 'wrong'}, 401),
                             ({'redirect_uri': 'https://evil.invalid'}, 400), ({'extra': 'x'}, 400)):
            self.assertEqual(self.form_call(yi.OAUTH + '/token', dict(good, **over))[0], status)
        raw = (urlencode(good) + '&client_id=' + yi.legacy.CLIENT_ID).encode()
        self.assertEqual(self.request('POST', yi.OAUTH + '/token', raw,
                                     {'Content-Type': 'application/x-www-form-urlencoded'})[0], 401)
        self.assertIsNone(self.sql_one('SELECT used_at FROM installation_pair_codes')[0])
        self.assertEqual(self.exchange(code)[0], 200)

    def test_code_replay_and_refresh_replay_revoke_all_namespace_authority(self):
        self.attached(); self.roster([])
        self.assertEqual(self.exchange(self.auth_code)[0], 400)
        self.assertEqual(self.webhook('что печется', self.access)[0], 401)
        self.assertEqual(self.roster([])[0], 409)
        self.assertEqual(self.refresh(self.pair['refresh_token'])[0], 400)
        # A fresh explicit flow after disconnect is required, not a legacy fallback.
        self.assertEqual(self.json_call('POST', yi.PHONE + '/disconnect', {}, self.install_header(self.credential))[0], 200)
        self.paired(); self.attach()
        status, rotated = self.refresh(self.pair['refresh_token']); self.assertEqual(status, 200)
        self.assertEqual(self.refresh(self.pair['refresh_token'])[0], 400)
        self.assertEqual(self.webhook('что печется', rotated['access_token'])[0], 401)
        self.assertEqual(self.refresh(rotated['refresh_token'])[0], 400)

    def test_expired_challenge_code_access_and_grant_are_denied(self):
        body = self.consent_body(); self.assertEqual(self.consent(body)[0], 200)
        state, jar = self.start_auto()
        self.app.clock.advance_ms(300001)
        self.assertEqual(self.callback(state, jar)[0], 400)
        self.assertEqual(self.consent(body)[0], 409)
        code = self.code()
        self.app.clock.advance_ms(self.settings.auth_code_ttl_ms + 1)
        self.assertEqual(self.exchange(code)[0], 400)
        self.paired(); self.attach()
        self.app.clock.advance_ms(self.settings.access_ttl_s * 1000 + 1)
        self.assertEqual(self.webhook('что печется', self.access)[0], 401)
        status, fresh = self.refresh(self.pair['refresh_token']); self.assertEqual(status, 200)
        self.app.clock.advance_ms(self.settings.grant_ttl_ms)
        self.assertEqual(self.webhook('что печется', fresh['access_token'])[0], 401)
        self.assertEqual(self.refresh(fresh['refresh_token'])[0], 400)
        self.assertEqual(self.roster([])[0], 409)

    def test_wrong_uid_foreign_provider_pkce_cookie_state_and_upstream_replay(self):
        self.assertEqual(self.consent()[0], 200)
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, {})[0], 400)
        self.assertEqual(self.callback('wrong', jar)[0], 400)
        self.peer.info['id'] = '1234567'
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.peer.info['id'] = yi.UID
        state, jar = self.start_auto(); self.peer.challenge = 'wrong'
        self.assertEqual(self.callback(state, jar)[0], 502)
        state, jar = self.start_auto(); self.peer.info['client_id'] = 'wrong'
        self.assertEqual(self.callback(state, jar)[0], 403)
        self.peer.info['client_id'] = self.settings.yandex_client_id
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar, code='once-upstream')[0], 303)
        self.assertEqual(self.callback(state, jar, code='once-upstream')[0], 400)
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar, code='once-upstream')[0], 400)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 1)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants')[0], 0)

    def test_multi_install_uid_ambiguity_callback_and_redemption_fail_closed(self):
        self.assertEqual(self.consent()[0], 200)
        other, _ = self.enroll(); self.assertEqual(self.bind(other)[0], 200)
        # Even a single pending opt-in cannot silently select one of two UID peers.
        state, jar = self.start_auto(); self.assertEqual(self.callback(state, jar)[0], 200)
        self.assertEqual(self.consent(credential=other)[0], 200)
        state, jar = self.start_auto(); self.assertEqual(self.callback(state, jar)[0], 200)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 0)
        self.unbind(other)
        code = self.code(consent=False)
        self.assertEqual(self.bind(other)[0], 200)
        self.assertEqual(self.exchange(code)[0], 400)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants')[0], 0)

    def test_uid_ambiguity_blocks_existing_bearer_refresh_poll_and_ack(self):
        self.attached(); b = rv.bake(); self.roster([b]); cmd = self.command(b)
        other, _ = self.enroll(); self.assertEqual(self.bind(other)[0], 200)
        self.assertEqual(self.webhook('да', self.access)[0], 401)
        self.assertEqual(self.roster([b])[0], 409)
        self.assertEqual(self.call('ack', self.ack_body(cmd))[0], 409)
        self.assertEqual(self.refresh(self.pair['refresh_token'])[0], 400)

    def test_unbind_disconnect_account_change_revoke_every_stage_and_new_pair_after_disconnect(self):
        for stage in ('pending', 'code', 'paired'):
            for action in ('unbind', 'disconnect', 'account_change'):
                with self.subTest(stage=stage, action=action):
                    credential, _ = self.enroll()
                    # Distinct UID keeps this test intentionally unambiguous.
                    self.peer.info['id'] = str(int(yi.UID) + len(stage) * 100 + len(action))
                    self.assertEqual(self.bind(credential)[0], 200)
                    old_credential = self.credential; self.credential = credential
                    body = self.consent_body(); self.assertEqual(self.consent(body)[0], 200)
                    code = self.code(consent=False) if stage != 'pending' else None
                    pair = self.exchange(code)[1] if stage == 'paired' else None
                    if action == 'unbind': self.assertEqual(self.unbind(credential)[0], 204)
                    elif action == 'disconnect':
                        self.assertEqual(self.json_call('POST', yi.PHONE + '/disconnect', {}, self.install_header(credential))[0], 200)
                    else:
                        self.peer.info['id'] = str(int(self.peer.info['id']) + 10000)
                        self.assertEqual(self.bind(credential)[0], 200)
                    self.assertEqual(self.consent(body)[0], 409)
                    if code: self.assertEqual(self.exchange(code)[0], 400)
                    if pair:
                        self.assertEqual(self.webhook('да', pair['access_token'])[0], 401)
                        self.assertEqual(self.refresh(pair['refresh_token'])[0], 400)
                    self.credential = old_credential

    def test_consent_snapshot_race_unbind_aba_cannot_restore_pending(self):
        original = Handler._installation
        ready, proceed = threading.Event(), threading.Event(); result = []
        def held(handler, app):
            snapshot = original(handler, app)
            if handler.path == rv.V2 + 'pair-consent':
                ready.set(); self.assertTrue(proceed.wait(3))
            return snapshot
        with patch.object(Handler, '_installation', held):
            t = threading.Thread(target=lambda: result.append(self.consent()[0])); t.start()
            self.assertTrue(ready.wait(2)); self.unbind(self.credential); self.bind(self.credential)
            proceed.set(); t.join(3); self.assertFalse(t.is_alive())
        self.assertEqual(result, [409])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_requests')[0], 0)

    def test_callback_provider_io_race_unbind_rebind_and_replaced_request(self):
        for action in ('unbind', 'replace'):
            body = self.consent_body(); self.assertEqual(self.consent(body)[0], 200)
            state, jar = self.start_auto()
            ready, proceed = threading.Event(), threading.Event(); results = []
            self.peer.block_info = (ready, proceed)
            t = threading.Thread(target=lambda: results.append(self.callback(state, jar)[0])); t.start()
            self.assertTrue(ready.wait(2))
            if action == 'unbind': self.unbind(self.credential)
            else: self.assertEqual(self.consent()[0], 200)
            proceed.set(); t.join(3); self.assertFalse(t.is_alive())
            self.peer.block_info = None
            self.assertEqual(results, [200])
            if action == 'unbind': self.bind(self.credential)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 0)

    def test_new_consent_cannot_join_browser_authorization_started_before_it(self):
        state, jar = self.start_auto()
        self.assertEqual(self.consent()[0], 200)
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 0)
        self.assertTrue(self.code(consent=False))

    def parallel(self, functions):
        barrier = threading.Barrier(len(functions)); results = []
        def run(fn):
            barrier.wait(3); results.append(fn())
        threads = [threading.Thread(target=run, args=(fn,)) for fn in functions]
        for t in threads: t.start()
        for t in threads: t.join(4); self.assertFalse(t.is_alive())
        return sorted(results)

    def test_parallel_callbacks_confirmation_consumes_challenge_once(self):
        self.assertEqual(self.consent()[0], 200)
        state, jar = self.start_auto()
        self.assertEqual(self.parallel([lambda: self.callback(state, jar)[0]] * 2), [303, 400])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 1)
        # A second browser with independent state cannot consume the same request.
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 1)

    def test_parallel_redemptions_and_refresh_fail_closed_revoke_winner(self):
        code = self.code()
        self.assertEqual(self.parallel([lambda: self.exchange(code)[0]] * 2), [200, 400])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants WHERE revoked_at IS NULL')[0], 0)
        self.paired()
        self.assertEqual(self.parallel([lambda: self.refresh(self.pair['refresh_token'])[0]] * 2), [200, 400])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants WHERE revoked_at IS NULL')[0], 0)

    def test_revocation_between_classify_and_confirmation_cannot_mint(self):
        self.attached(); b = rv.bake(); self.roster([b]); self.speech('пауза два')
        ready, proceed = threading.Event(), threading.Event(); results = []
        original = Handler._classify
        def held(handler, app, body):
            classified = original(handler, app, body); ready.set(); self.assertTrue(proceed.wait(3)); return classified
        with patch.object(Handler, '_classify', held):
            t = threading.Thread(target=lambda: results.append(self.webhook('да', self.access))); t.start()
            self.assertTrue(ready.wait(2)); self.unbind(self.credential)
            proceed.set(); t.join(3); self.assertFalse(t.is_alive())
        self.assertEqual(self.count(), 0)
        self.assertNotIn('применил', results[0][2]['response']['text'])

    def test_restart_keeps_stable_permission_but_rejects_pending_browser_and_old_connection(self):
        self.attached(); self.roster([]); original_grant = self.sql_one('SELECT id,expires_at FROM installation_pair_grants')
        old = self.connection
        self.httpd.app = self.app = App(self.settings)
        self.assertEqual(self.roster([])[0], 409)
        self.attach(); self.assertNotEqual(old, self.connection)
        self.assertEqual(self.roster([])[0], 200)
        self.assertEqual(self.sql_one('SELECT id,expires_at FROM installation_pair_grants'), original_grant)
        self.assertIn('ничего не печётся', self.speech('что печется'))
        self.assertEqual(self.call('disconnect', {'connection_id': old})[0], 204)
        self.assertEqual(self.roster([])[0], 200)

    def test_namespace_flag_off_does_not_fall_back_into_legacy(self):
        self.attached(); self.roster([])
        self.app.settings = replace(self.settings, roster_v2=False)
        self.assertEqual(self.webhook('да', self.access)[0], 401)
        self.assertEqual(self.refresh(self.pair['refresh_token'])[0], 400)
        self.assertEqual(self.exchange(self.auth_code)[0], 400)
        self.assertEqual(self.roster([])[0], 426)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)

    def test_epoch_scope_client_and_credential_fences_every_bearer_poll_refresh_ack(self):
        for change in ('epoch', 'scope', 'client'):
            self.paired(); self.attach(); b = rv.bake(); self.roster([b]); cmd = self.command(b)
            with transaction(self.db_path) as c:
                if change == 'epoch':
                    c.execute('UPDATE yandex_epochs SET epoch=epoch+1 WHERE installation_id=?', (self.installation,))
                elif change == 'scope': c.execute("UPDATE installation_pair_grants SET scope='legacy' WHERE revoked_at IS NULL")
                else: c.execute("UPDATE installation_pair_grants SET client_id='wrong-client' WHERE revoked_at IS NULL")
            self.assertEqual(self.webhook('да', self.access)[0], 401)
            self.assertEqual(self.roster([b])[0], 409)
            self.assertEqual(self.call('ack', self.ack_body(cmd))[0], 409)
            self.assertEqual(self.refresh(self.pair['refresh_token'])[0], 400)
            self.assertEqual(self.json_call('POST', yi.PHONE + '/disconnect', {}, self.install_header(self.credential))[0], 200)

    def test_disconnect_requires_fresh_full_pair_and_stale_disconnect_cannot_revoke_it(self):
        self.attached(); old_connection = self.connection; old_access = self.access
        self.assertEqual(self.call('disconnect', {'connection_id': old_connection})[0], 204)
        self.assertEqual(self.webhook('да', old_access)[0], 401)
        self.paired(); self.attach()
        self.assertNotEqual(self.connection, old_connection)
        self.assertEqual(self.call('disconnect', {'connection_id': old_connection})[0], 204)
        self.assertEqual(self.roster([])[0], 200)
        self.assertIn('ничего не печётся', self.speech('что печется'))

    def test_two_independent_browser_approvals_cannot_confirm_same_request_twice(self):
        self.assertEqual(self.consent()[0], 200)
        state1, jar1 = self.start_auto(); challenge1 = self.peer.challenge
        state2, jar2 = self.start_auto(); challenge2 = self.peer.challenge
        self.peer.challenge = challenge1
        self.assertEqual(self.callback(state1, jar1)[0], 303)
        self.peer.challenge = challenge2
        self.assertEqual(self.callback(state2, jar2)[0], 200)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 1)

    def test_foreign_installation_cannot_attach_poll_ack_or_disconnect_namespace(self):
        self.attached(); b = rv.bake(); self.roster([b]); cmd = self.command(b)
        other, _ = self.enroll()
        self.assertEqual(self.call('attach', dict(protocol_version=2, consent_scope=ip.SCOPE, consent=True), other)[0], 409)
        body = dict(protocol_version=2, connection_id=self.connection, roster_revision=999, bakes=[])
        self.assertEqual(self.call('poll', body, other)[0], 409)
        self.assertEqual(self.call('ack', self.ack_body(cmd), other)[0], 409)
        self.assertEqual(self.call('disconnect', {'connection_id': self.connection}, other)[0], 409)
        self.assertEqual(self.call('ack', self.ack_body(cmd))[0], 200)

    def test_concurrent_phone_ack_auth_snapshot_cannot_survive_account_epoch_change(self):
        self.attached(); b = rv.bake(); self.roster([b]); cmd = self.command(b)
        ready, proceed = threading.Event(), threading.Event(); results = []
        original = Handler._installation
        def held(handler, app):
            snapshot = original(handler, app)
            if handler.path == rv.V2 + 'ack':
                ready.set(); self.assertTrue(proceed.wait(3))
            return snapshot
        with patch.object(Handler, '_installation', held):
            t = threading.Thread(target=lambda: results.append(self.call('ack', self.ack_body(cmd))[0])); t.start()
            self.assertTrue(ready.wait(2)); self.unbind(self.credential); self.bind(self.credential)
            proceed.set(); t.join(3); self.assertFalse(t.is_alive())
        self.assertEqual(results, [409])
        self.assertIsNone(self.sql_one('SELECT ack_json FROM roster_v2_commands')[0])

    def test_restart_rejects_unfinished_browser_but_allows_new_explicit_pending_flow(self):
        self.assertEqual(self.consent()[0], 200)
        state, jar = self.start_auto()
        self.httpd.app = self.app = App(self.settings)
        self.assertEqual(self.callback(state, jar)[0], 400)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_codes')[0], 0)
        self.assertEqual(self.exchange(self.code(consent=False))[0], 200)

    def test_atomic_code_redemption_failure_rolls_back_used_and_grant(self):
        code = self.code()
        with transaction(self.db_path) as c:
            c.execute("CREATE TRIGGER fixture_pair_failure BEFORE INSERT ON installation_pair_access BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        self.assertEqual(self.exchange(code)[0], 500)
        self.assertIsNone(self.sql_one('SELECT used_at FROM installation_pair_codes')[0])
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM installation_pair_grants')[0], 0)
        with transaction(self.db_path) as c: c.execute('DROP TRIGGER fixture_pair_failure')
        self.assertEqual(self.exchange(code)[0], 200)

    def test_private_data_raw_tokens_and_provider_state_never_persist_or_log(self):
        self.attached(); self.roster([])
        with sqlite3.connect(self.db_path) as c: blob = '\n'.join(c.iterdump())
        for marker in (self.access, self.pair['refresh_token'], self.auth_code, self.credential, 'outer Ω &=+ state'):
            self.assertNotIn(marker, blob); self.assertNotIn(marker, self.logs.getvalue())
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM access_tokens')[0], 0)
        self.assertEqual(self.sql_one('SELECT COUNT(*) FROM yandex_voice_consents')[0], 0)
        self.assertEqual(self.sql_one('PRAGMA integrity_check')[0], 'ok')

    def test_old_readers_latest_db_rollback_reject_namespace_and_preserve_unbind(self):
        for reader in ('d499', 'f138'):
            with self.subTest(reader=reader):
                self.paired(); self.attach(); b = rv.bake(); self.roster([b]); cmd = self.command(b)
                fixture = Path(__file__).parent / ('fixtures/' + reader + '-reader.tar.gz')
                manifest = json.loads(fixture.with_suffix('').with_suffix('.json').read_text())
                self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), manifest['archive_sha256'])
                with tempfile.TemporaryDirectory(prefix='installation-oldreader-') as tmp:
                    with tarfile.open(fixture) as archive:
                        for member in archive.getmembers():
                            self.assertTrue(member.isfile()); self.assertIn(member.name, manifest['files'])
                            self.assertFalse(Path(member.name).is_absolute()); self.assertNotIn('..', Path(member.name).parts)
                            target = Path(tmp) / member.name; target.parent.mkdir(parents=True, exist_ok=True)
                            target.write_bytes(archive.extractfile(member).read())
                    for name, digest in manifest['files'].items():
                        self.assertEqual(hashlib.sha256((Path(tmp) / name).read_bytes()).hexdigest(), digest)
                    script = '''
import json,sys,threading
from app import App,make_server
from config import Settings
from http.client import HTTPConnection
s=make_server('127.0.0.1',0,App(Settings(db_path=sys.argv[1],poll_wait_s=.01)))
t=threading.Thread(target=s.serve_forever,kwargs={'poll_interval':.01});t.start()
def call(path,body,auth):
 c=HTTPConnection('127.0.0.1',s.server_address[1]);c.request('POST',path,json.dumps(body),{'Authorization':auth,'Content-Type':'application/json'});r=c.getresponse();v=r.status;r.read();c.close();return v
try:
 root='/alice/madre/voice/phone'
 print(json.dumps([call('/alice/madre',{'request':{'command':'пауза два'}},'Bearer '+sys.argv[3]),call(root+'/poll',{'connection_id':sys.argv[4],'selection_generation':sys.argv[5],'snapshot':json.loads(sys.argv[6])},'Installation '+sys.argv[2]),call(root+'/yandex-unbind',{},'Installation '+sys.argv[2])]))
finally:s.shutdown();s.server_close();t.join()
'''
                    p = subprocess.run([sys.executable, '-c', script, self.db_path, self.credential, self.access,
                                        yi.legacy.uid(), yi.legacy.uid(), json.dumps(self.snapshot(yi.legacy.uid()))],
                                       cwd=tmp, env=dict(os.environ, PYTHONPATH=tmp), capture_output=True, text=True, timeout=15)
                    self.assertEqual(p.returncode, 0, p.stderr)
                    self.assertEqual(json.loads(p.stdout), [401, 409, 204])
                init_db(self.db_path)
                self.assertEqual(self.sql_one('PRAGMA user_version')[0], 3)
                self.assertEqual(self.sql_one('PRAGMA integrity_check')[0], 'ok')
                self.assertEqual(self.webhook('да', self.access)[0], 401)
                self.assertEqual(self.roster([b])[0], 409)
                self.assertEqual(self.call('ack', self.ack_body(cmd))[0], 409)
                self.assertEqual(self.refresh(self.pair['refresh_token'])[0], 400)
                self.assertEqual(self.sql_one('SELECT COUNT(*) FROM grants')[0], 0)
                self.bind(self.credential)


if __name__ == '__main__':
    unittest.main()
