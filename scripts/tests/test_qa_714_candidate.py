"""QA candidate wiring and one opt-in CI frame; no backend/native readiness claim."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
from types import SimpleNamespace
import unittest

from scripts.tests.test_workflow_selector import sections, scalar as optional_scalar, step_blocks


def scalar(text, key, indent):
    value = optional_scalar(text, key, indent)
    assert value is not None, f"missing {key}"
    return value

ROOT = Path(__file__).resolve().parents[2]
METHOD = "com.polinalinen.madre.ui.razvorot.BakeNumberRenderTest.bakingPreviousStepFooter"


class Qa714CandidateTests(unittest.TestCase):
    def test_final_qa_alice_and_family_have_independent_callers_and_release_stays_off(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        flag = 'buildConfigField("boolean", "FAMILY_ROSTER_V2", '
        self.assertEqual(1, gradle.count(flag + '"false")'))
        self.assertEqual(1, gradle.count(flag + '"true")'))
        self.assertIn(flag + '"false")', gradle.split('defaultConfig {', 1)[1].split('signingConfigs {', 1)[0])
        qa = gradle.split('buildTypes.create("qa") {', 1)[1].split('\n    }', 1)[0]
        self.assertIn(flag + '"true")', qa)
        for variant in ('debug', 'release'):
            match = re.search(r'\b' + variant + r'\s*\{(.*?)\n        \}', gradle, re.S)
            assert match is not None
            self.assertNotIn('FAMILY_ROSTER_V2', match.group(1))
        flags = (ROOT / 'app/src/main/java/com/polinalinen/madre/config/FeatureFlags.kt').read_text()
        self.assertIn('const val FAMILY_ROSTER_V2 = com.polinalinen.madre.BuildConfig.FAMILY_ROSTER_V2', flags)
        family_flag = 'buildConfigField("boolean", "FAMILY_LINK_UI", '
        self.assertEqual(1, gradle.count(family_flag + '"false")'))
        self.assertEqual(2, gradle.count(family_flag))  # Only final QA override.
        self.assertIn(family_flag + '"true")', qa)
        for variant in ('debug', 'release'):
            match = re.search(r'\b' + variant + r'\s*\{(.*?)\n        \}', gradle, re.S)
            self.assertNotIn('FAMILY_LINK_UI', match.group(1))
        self.assertIn(family_flag + '"false")', gradle.split('defaultConfig {', 1)[1].split('signingConfigs {', 1)[0])
        self.assertIn('const val V2_ACCOUNT = FAMILY_LINK_UI || FAMILY_ROSTER_V2', flags)
        source = ROOT / 'app/src/main/java/com/polinalinen/madre'
        for caller in ('ui/screens/AliceScreen.kt', 'ui/razvorot/AliceSheet.kt',
                       'integration/v2/RosterV2.kt', 'account/yandex/YandexAccountViewModel.kt',
                       'voice/VoiceCoordinator.kt', 'MadreApplication.kt'):
            self.assertIn('FeatureFlags.FAMILY_ROSTER_V2', (source / caller).read_text())
        for caller in ('ui/screens/ShelfScreen.kt', 'ui/screens/SettingsShelfScreen.kt',
                       'ui/screens/FamilyLinkScreens.kt', 'viewmodel/FamilyLinkViewModel.kt',
                       'integration/v2/FamilyLinkApi.kt', 'navigation/MadreNavHost.kt'):
            self.assertIn('FeatureFlags.FAMILY_LINK_UI', (source / caller).read_text())
        activity = (source / 'MainActivity.kt').read_text()
        self.assertIn('Queue { FeatureFlags.FAMILY_LINK_UI }', activity)
        self.assertIn('provides if (FeatureFlags.FAMILY_LINK_UI) familyModel else null', activity)
        self.assertIn('if (FeatureFlags.FAMILY_LINK_UI) outState.putString(KEY_FAMILY_INVITE', activity)
        self.assertIn('if (FeatureFlags.FAMILY_ROSTER_V2) rosterJob', activity)
        self.assertIn('if (!com.polinalinen.madre.config.FeatureFlags.V2_ACCOUNT) null',
                      (source / 'MadreApplication.kt').read_text())

    def test_invite_success_opens_existing_family_list_and_qa_cannot_use_legacy_requests(self):
        source = ROOT / 'app/src/main/java/com/polinalinen/madre'
        screens = (source / 'ui/screens/FamilyLinkScreens.kt').read_text()
        self.assertIn('Phase.MEMBER -> PaperButton("К семье", openFamily,', screens)
        self.assertIn('openFamily = { model.closeInvitation(); onOpenFamily() }', screens)
        nav = (source / 'navigation/MadreNavHost.kt').read_text()
        self.assertIn('FamilyInvitationRoute {\n                    navController.navigate(MadreDestinations.SETTINGS_SHELF)', nav)
        legacy = (source / 'viewmodel/FamilyBookViewModel.kt').read_text()
        self.assertEqual(2, legacy.count('if (com.polinalinen.madre.config.FeatureFlags.V2_ACCOUNT) return'))

    def test_optional_frame_uses_exact_trusted_head_and_existing_automatic_pr_run(self):
        text = (ROOT / '.github/workflows/quality-gates.yml').read_text()
        jobs = sections(text.split('\njobs:\n', 1)[1], r'^  ([a-z0-9-]+):\n')
        frame = jobs['bake-back-frame']
        self.assertEqual('workflow-contract', scalar(frame, 'needs', 4))
        self.assertIsNone(optional_scalar(jobs['android-quality'], 'needs', 4))
        self.assertNotIn('continue-on-error', frame)
        expression = scalar(frame, 'if', 4).replace('&&', ' and ')
        for event in ('push', 'pull_request', 'workflow_dispatch', 'schedule'):
            for trusted in (False, True):
                github = SimpleNamespace(event_name=event, repository='owner/repo',
                    event=SimpleNamespace(pull_request=SimpleNamespace(head=SimpleNamespace(
                        repo=SimpleNamespace(full_name='owner/repo' if trusted else 'fork/repo')))))
                self.assertEqual(event == 'pull_request' and trusted,
                    eval(expression, {'__builtins__': {}}, {'github': github}))
        self.assertIn('ref: ${{ github.event.pull_request.head.sha || github.sha }}', frame)
        # No second automatic feature push trigger or render added to the short gate.
        self.assertIn('  push:\n    branches: [main]\n', text)
        self.assertNotIn('RenderTest', jobs['android-quality'])
        steps = step_blocks(frame)
        command = scalar(steps['Capture only the requested bake-back method'], 'run', 8)
        self.assertEqual([METHOD], re.findall(r"--tests '([^']+)'", command))
        for forbidden in ('assemble', 'verifyRoborazzi', 'extendedVerification', 'shortApkGate'):
            self.assertNotIn(forbidden, command)
        self.assertIn('--rerun-tasks', command)
        for name, step in steps.items():
            if name in ('Select only a marked exact head',) or name.startswith('actions/checkout@'):
                continue
            self.assertIn("steps.select.outputs.capture == 'true'", scalar(step, 'if', 8))
        for name, artifact, path in (
            ('Upload bake-back PNG', 'bake-back-frame-png', 'app/build/outputs/roborazzi/bake-back/*.png'),
            ('Upload raw bake-back JUnit', 'bake-back-frame-junit', 'app/build/test-results/testDebugUnitTest/')):
            step = steps[name]
            self.assertTrue(scalar(step, 'if', 8).startswith('always() &&'))
            self.assertEqual(artifact, scalar(step, 'name', 10))
            self.assertEqual(path, scalar(step, 'path', 10))
            self.assertEqual('error', scalar(step, 'if-no-files-found', 10))
        # Reuse existing action pins and setup-gradle default trusted/default-branch write policy.
        for action in re.findall(r'uses: ([^\s#]+)', frame):
            self.assertIn('uses: ' + action, jobs['android-quality'])

    def test_actual_marker_selector_reads_only_the_head_message(self):
        text = (ROOT / '.github/workflows/quality-gates.yml').read_text()
        frame = sections(text.split('\njobs:\n', 1)[1], r'^  ([a-z0-9-]+):\n')['bake-back-frame']
        selector = step_blocks(frame)['Select only a marked exact head']
        shell = textwrap.dedent(selector.split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(['git', 'init', '-q', tmp], check=True)
            for message, expected in (('qa [bake-back-frame]', 'true'), ('next ordinary microstep', 'false')):
                subprocess.run(['git', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                                'commit', '-q', '--allow-empty', '-m', message], cwd=tmp, check=True)
                output = Path(tmp) / 'output'
                output.unlink(missing_ok=True)
                result = subprocess.run(['bash', '-eo', 'pipefail', '-c', shell], cwd=tmp,
                    env=os.environ | {'GITHUB_OUTPUT': str(output)}, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('capture=' + expected + '\n', output.read_text())
