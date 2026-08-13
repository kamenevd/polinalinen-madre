"""The release gate waits for unfinished checks and fails closed on everything else.

No live API: every case drives wait_for_required_checks with a scripted fetch,
a fake clock and a sleep that only records how long it was asked to wait.
"""

import unittest

from scripts import verify_github_release_context as gate


def run(name, status="completed", conclusion="success", completed_at="2026-08-13T10:00:00Z", slug="github-actions"):
    return {
        "name": name,
        "status": status,
        "conclusion": conclusion,
        "completed_at": completed_at,
        "app": {"slug": slug},
    }


def all_green():
    return [run(name) for name in gate.REQUIRED_CHECKS]


class FakeClock:
    """Monotonic time that only advances when the code under test sleeps."""

    def __init__(self):
        self.now = 0.0
        self.slept = []

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.now += seconds


class Harness:
    def __init__(self, snapshots):
        # Last snapshot repeats, so a test only lists the states it cares about.
        self.snapshots = list(snapshots)
        self.calls = 0
        self.clock = FakeClock()
        self.log = []

    def fetch(self):
        index = min(self.calls, len(self.snapshots) - 1)
        self.calls += 1
        return self.snapshots[index]

    def wait(self, **kwargs):
        kwargs.setdefault("poll_seconds", gate.DEFAULT_POLL_SECONDS)
        kwargs.setdefault("timeout_seconds", gate.DEFAULT_TIMEOUT_SECONDS)
        return gate.wait_for_required_checks(
            self.fetch,
            sleep=self.clock.sleep,
            clock=self.clock,
            log=self.log.append,
            **kwargs,
        )


class ReleaseGateTests(unittest.TestCase):
    def test_all_green_passes_on_the_first_poll_without_sleeping(self):
        harness = Harness([all_green()])
        harness.wait()
        self.assertEqual(1, harness.calls)
        self.assertEqual([], harness.clock.slept)

    def test_pending_then_green_passes_after_waiting(self):
        pending = [
            run("Workflow integrity"),
            run("Android quality"),
            run("Instrumented smoke", status="in_progress", conclusion=None, completed_at=None),
        ]
        harness = Harness([pending, pending, all_green()])
        harness.wait()
        self.assertEqual(3, harness.calls)
        self.assertEqual([gate.DEFAULT_POLL_SECONDS] * 2, harness.clock.slept)
        self.assertIn("Instrumented smoke: in_progress", harness.log[0])

    def test_missing_check_is_waited_for_not_failed(self):
        # The race this gate exists for: the quality run has not reported yet.
        harness = Harness([[run("Workflow integrity")], all_green()])
        harness.wait()
        self.assertEqual(2, harness.calls)
        self.assertIn("Instrumented smoke: not reported yet", harness.log[0])

    def test_completed_failure_fails_immediately_without_waiting(self):
        snapshot = [
            run("Workflow integrity"),
            run("Android quality", conclusion="failure"),
            run("Instrumented smoke", status="in_progress", conclusion=None, completed_at=None),
        ]
        harness = Harness([snapshot])
        with self.assertRaises(gate.ReleaseCheckError) as raised:
            harness.wait()
        self.assertIn("Android quality", str(raised.exception))
        self.assertEqual(1, harness.calls)
        self.assertEqual([], harness.clock.slept)

    def test_deadline_is_a_failure_and_bounds_the_total_wait(self):
        pending = [
            run("Workflow integrity"),
            run("Android quality"),
            run("Instrumented smoke", status="queued", conclusion=None, completed_at=None),
        ]
        harness = Harness([pending])
        with self.assertRaises(gate.ReleaseCheckError) as raised:
            harness.wait(poll_seconds=30, timeout_seconds=300)
        message = str(raised.exception)
        self.assertIn("timed out", message)
        self.assertIn("Instrumented smoke: queued", message)
        self.assertEqual(300, sum(harness.clock.slept))

    def test_in_progress_rerun_beats_an_older_green_run(self):
        # A rerun in flight must not be released against the green it replaces.
        snapshot = [
            run("Workflow integrity"),
            run("Android quality"),
            run("Instrumented smoke", completed_at="2026-08-13T09:00:00Z"),
            run("Instrumented smoke", status="in_progress", conclusion=None, completed_at=None),
        ]
        verdict = gate.evaluate_check_runs(snapshot)
        self.assertEqual(["Instrumented smoke: in_progress"], verdict.pending)
        self.assertEqual([], verdict.failures)
        self.assertFalse(verdict.green)

    def test_null_completed_at_never_crashes_the_comparison(self):
        snapshot = [
            run("Android quality", conclusion="failure", completed_at=None),
            run("Android quality", completed_at="2026-08-13T10:00:00Z"),
        ]
        verdict = gate.evaluate_check_runs(snapshot, ("Android quality",))
        self.assertTrue(verdict.green)

    def test_only_github_actions_check_runs_count(self):
        # A third-party app may publish a check with a required name.
        snapshot = [run(name, slug="some-other-app") for name in gate.REQUIRED_CHECKS]
        verdict = gate.evaluate_check_runs(snapshot)
        self.assertEqual([], verdict.failures)
        self.assertEqual(len(gate.REQUIRED_CHECKS), len(verdict.pending))

    def test_check_run_without_an_app_is_not_trusted(self):
        verdict = gate.evaluate_check_runs([{"name": "Android quality", "status": "completed", "conclusion": "success"}], ("Android quality",))
        self.assertEqual(["Android quality: not reported yet"], verdict.pending)

    def test_required_set_is_exactly_the_three_protected_checks(self):
        self.assertEqual(
            ("Workflow integrity", "Android quality", "Instrumented smoke"),
            gate.REQUIRED_CHECKS,
        )


class PollSettingsTests(unittest.TestCase):
    def test_defaults_cover_the_instrumented_smoke_job(self):
        poll, timeout = gate.poll_settings({})
        self.assertEqual(30.0, poll)
        # "Instrumented smoke" is capped at 35 minutes by its own job timeout.
        self.assertGreaterEqual(timeout, 35 * 60)

    def test_environment_overrides_are_honoured(self):
        poll, timeout = gate.poll_settings(
            {"MADRE_RELEASE_CHECK_POLL_SECONDS": "15", "MADRE_RELEASE_CHECK_TIMEOUT_SECONDS": "600"}
        )
        self.assertEqual((15.0, 600.0), (poll, timeout))

    def test_timeout_below_the_floor_is_rejected(self):
        with self.assertRaisesRegex(gate.ReleaseCheckError, "at least 300"):
            gate.poll_settings({"MADRE_RELEASE_CHECK_TIMEOUT_SECONDS": "299"})

    def test_nonsense_and_non_positive_values_are_rejected(self):
        for value in ("soon", "0", "-30"):
            with self.subTest(value=value), self.assertRaises(gate.ReleaseCheckError):
                gate.poll_settings({"MADRE_RELEASE_CHECK_POLL_SECONDS": value})


if __name__ == "__main__":
    unittest.main()
