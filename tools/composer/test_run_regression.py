import contextlib
import io
import importlib.util
from pathlib import Path
import unittest
from unittest import mock
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location("runner", Path(__file__).with_name("run_regression.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class InstrumentationOutcomeTest(unittest.TestCase):
    def test_first_output_reject_passes(self):
        for suffix in ("", " --reject-with icmp-port-unreachable", " --reject-with icmp6-port-unreachable"):
            with self.subTest(suffix=suffix):
                runner.require_first_output_reject(
                    "-P OUTPUT ACCEPT\n-A OUTPUT ! -o lo -j REJECT" + suffix + "\n-A OUTPUT -j ACCEPT\n")

    def test_missing_or_displaced_output_reject_fails(self):
        for rules in ("-P OUTPUT ACCEPT\n", "-A OUTPUT -j ACCEPT\n-A OUTPUT ! -o lo -j REJECT\n",
                      "-A OUTPUT ! -o lo -j ACCEPT\n"):
            with self.subTest(rules=rules), self.assertRaises(RuntimeError):
                runner.require_first_output_reject(rules)

    def test_cleanup_timeout_preserves_primary_and_attempts_remaining_actions(self):
        primary = RuntimeError("synthetic instrumentation failure")
        actions = []
        def fail():
            actions.append("first")
            raise subprocess.TimeoutExpired("SYNTHETIC_PRIVATE_COMMAND", 10)
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured), self.assertRaises(RuntimeError) as caught:
            try:
                raise primary
            finally:
                runner.finish_cleanup((fail, lambda: actions.append("second")), sys.exc_info()[1])
        self.assertIs(caught.exception, primary)
        self.assertEqual(actions, ["first", "second"])
        self.assertIn("TimeoutExpired", captured.getvalue())
        self.assertNotIn("SYNTHETIC_PRIVATE_COMMAND", captured.getvalue())

    def test_cleanup_failure_after_success_is_not_silenced(self):
        def fail():
            raise subprocess.TimeoutExpired("SYNTHETIC_PRIVATE_COMMAND", 10)
        with self.assertRaisesRegex(RuntimeError, "Composer cleanup failed: TimeoutExpired"):
            runner.finish_cleanup((fail,), None)
        runner.finish_cleanup((lambda: None,), None)

    def test_complete_run_passes(self):
        runner.require_success("Time: 1\nOK (9 tests)\n")

    def test_short_empty_crashed_or_failed_runs_reject(self):
        for output in ("", "OK (1 test)", "OK (8 tests)", "OK (9 tests)\nFAILURES!!!",
                       "OK (9 tests)\nINSTRUMENTATION_FAILED", "OK (9 tests)\nProcess crashed",
                       "OK (9 tests)\nINSTRUMENTATION_ABORTED",
                       "OK (9 tests)\nINSTRUMENTATION_STATUS_CODE: -3",
                       "OK (9 tests)\nINSTRUMENTATION_STATUS_CODE: -4"):
            with self.subTest(output=output), self.assertRaises(RuntimeError): runner.require_success(output)

    def test_failure_summary_names_test_without_raw_assertion_or_path(self):
        output = self.failure_output()
        with self.assertRaises(RuntimeError) as caught:
            runner.require_success(output)
        message = str(caught.exception)
        self.assertIn("ComposerPanicInstrumentedTest.confirmedPanicClearsDraftWhileCancelAndFreshTypingPreserveText", message)
        self.assertIn("java.lang.AssertionError", message)
        self.assertIn("ComposerPanicInstrumentedTest.kt:54", message)
        self.assertNotIn("SYNTHETIC_PRIVATE_VALUE", message)
        self.assertNotIn("/synthetic/private", message)

    @staticmethod
    def failure_output():
        return ("INSTRUMENTATION_STATUS: class=com.bitchat.android.ui.ComposerPanicInstrumentedTest\n"
                "INSTRUMENTATION_STATUS: test=confirmedPanicClearsDraftWhileCancelAndFreshTypingPreserveText\n"
                "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: SYNTHETIC_PRIVATE_VALUE /synthetic/private\n"
                " at synthetic.test(ComposerPanicInstrumentedTest.kt:54)\n"
                "INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\n")

    def test_timeout_and_nonzero_exit_keep_concise_details_and_raw_local_log(self):
        for error in (subprocess.TimeoutExpired("synthetic-command", 600),
                      subprocess.CalledProcessError(1, "synthetic-command")):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as scratch:
                log = Path(scratch) / "instrumentation.log"
                def fail(*args, **kwargs):
                    kwargs["stdout"].write(self.failure_output())
                    kwargs["stdout"].flush()
                    raise error
                with mock.patch.object(runner.subprocess, "run", side_effect=fail), self.assertRaises(RuntimeError) as caught:
                    runner.run_instrumentation(["synthetic-command"], log)
                self.assertIn("ComposerPanicInstrumentedTest", str(caught.exception))
                self.assertNotIn("SYNTHETIC_PRIVATE_VALUE", str(caught.exception))
                self.assertIn("SYNTHETIC_PRIVATE_VALUE", log.read_text())

    def test_interrupted_run_identifies_last_started_test(self):
        output = ("INSTRUMENTATION_STATUS: class=com.bitchat.android.ui.ComposerPanicInstrumentedTest\n"
                  "INSTRUMENTATION_STATUS: test=confirmedPanicClearsDraftWhileCancelAndFreshTypingPreserveText\n")
        self.assertIn("last test: ComposerPanicInstrumentedTest.", runner.failure_summary(output))

    def test_real_subprocess_failure_reports_test_without_raw_values(self):
        with tempfile.TemporaryDirectory() as scratch:
            command = [sys.executable, "-c", "import sys; print(" + repr(self.failure_output()) + "); sys.exit(1)"]
            with self.assertRaises(RuntimeError) as caught:
                runner.run_instrumentation(command, Path(scratch) / "instrumentation.log")
            self.assertIn("exited 1", str(caught.exception))
            self.assertIn("ComposerPanicInstrumentedTest", str(caught.exception))
            self.assertNotIn("SYNTHETIC_PRIVATE_VALUE", str(caught.exception))

    def test_junit_comparison_failure_type_without_expected_or_actual_values(self):
        output = self.failure_output().replace("java.lang.AssertionError: SYNTHETIC_PRIVATE_VALUE /synthetic/private",
            "org.junit.ComparisonFailure: expected:<SYNTHETIC_EXPECTED> but was:<SYNTHETIC_ACTUAL>")
        summary = runner.failure_summary(output)
        self.assertIn("org.junit.ComparisonFailure", summary)
        self.assertNotIn("SYNTHETIC_EXPECTED", summary)
        self.assertNotIn("SYNTHETIC_ACTUAL", summary)


if __name__ == "__main__": unittest.main()
