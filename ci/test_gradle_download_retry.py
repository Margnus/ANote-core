import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from gradle_download_retry import run_gradle


class GradleDownloadRetryTest(unittest.TestCase):
    def execute(self, outcomes):
        calls = []

        def child(command, **kwargs):
            calls.append(list(command))
            code, text = outcomes[len(calls) - 1]
            kwargs['stdout'].write(text)
            if isinstance(code, Exception):
                raise code
            return SimpleNamespace(returncode=code)

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'framework.log'
            error = None
            with patch('gradle_download_retry.subprocess.run', side_effect=child):
                try:
                    run_gradle(['./gradlew', ':shared-core:linkDebugFrameworkIosArm64'], Path(tmp), path, 10)
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as failure:
                    error = failure
            logs = {p.name: p.read_text() for p in Path(tmp).glob('*.log')}
        return calls, logs, error

    def test_transient_download_retries_same_command_and_keeps_both_logs(self):
        calls, logs, error = self.execute([(1, 'Server returned HTTP response code: 504 for URL: https://vendor/file'),
                                         (0, 'BUILD SUCCESSFUL')])
        self.assertIsNone(error)
        self.assertEqual(calls[0], calls[1])
        self.assertIn('504', logs['framework.attempt-1.log'])
        self.assertEqual(logs['framework.log'], 'BUILD SUCCESSFUL')
        self.assertEqual(logs['framework.attempt-2.log'], 'BUILD SUCCESSFUL')

    def test_persistent_download_stops_after_two_attempts(self):
        calls, logs, error = self.execute([(1, 'Received status code 503 from server')]*2)
        self.assertEqual(len(calls), 2)
        self.assertIsInstance(error, subprocess.CalledProcessError)
        self.assertEqual(logs['framework.log'], logs['framework.attempt-2.log'])

    def test_compile_or_test_failure_does_not_retry_even_with_http_text(self):
        for diagnostic in ('e: file:///source.kt: invalid input', 'There were failing tests', 'AssertionError'):
            calls, _, error = self.execute([(1, 'Received status code 504\n' + diagnostic)])
            self.assertEqual(len(calls), 1)
            self.assertIsInstance(error, subprocess.CalledProcessError)

    def test_nontransient_http_and_plain_failure_are_not_retried(self):
        for text in ('Received status code 403', 'Server returned HTTP response code: 404', 'BUILD FAILED'):
            calls, _, error = self.execute([(1, text)])
            self.assertEqual(len(calls), 1)
            self.assertIsInstance(error, subprocess.CalledProcessError)

    def test_timeout_keeps_partial_log_and_does_not_retry(self):
        calls, logs, error = self.execute([(subprocess.TimeoutExpired('gradle', 10), 'partial download')])
        self.assertEqual(len(calls), 1)
        self.assertEqual(logs['framework.log'], 'partial download')
        self.assertIsInstance(error, subprocess.TimeoutExpired)


if __name__ == '__main__':
    unittest.main()
