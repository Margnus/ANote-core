"""Host-result retention protocol only; no Xcode or native execution claim."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock

from verify_apple_candidate import run_host_tests


class HostResultRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        self.result = self.output / 'host.xcresult'
        self.result.mkdir()
        self.report = {}
        self.command = ['xcodebuild', 'test']

    def test_success_returns_both_authoritative_payloads(self):
        capture = Mock(side_effect=[json.dumps({'result': 'Passed'}), json.dumps({'testNodes': []})])
        run = Mock()
        summary, tree = run_host_tests(run, capture, self.command, self.result, self.output, self.report)
        self.assertEqual(summary, {'result': 'Passed'})
        self.assertEqual(tree, {'testNodes': []})
        run.assert_called_once_with('app-host', self.command, 900)
        self.assertEqual(capture.call_count, 2)

    def test_failed_xctest_keeps_assertions_and_original_error(self):
        failure = subprocess.CalledProcessError(65, self.command)
        detail = {'testNodes': [{'name': 'testRead', 'result': 'Failed', 'message': 'fixture refusal'}]}
        capture = Mock(side_effect=[json.dumps({'result': 'Failed'}), json.dumps(detail)])
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            run_host_tests(Mock(side_effect=failure), capture, self.command, self.result, self.output, self.report)
        self.assertIs(caught.exception, failure)
        self.assertEqual(json.loads((self.output / 'xctest-tests.json').read_text(encoding='utf-8')), detail)

    def test_timeout_keeps_available_results(self):
        failure = subprocess.TimeoutExpired(self.command, 900)
        capture = Mock(return_value='{}')
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            run_host_tests(Mock(side_effect=failure), capture, self.command, self.result, self.output, self.report)
        self.assertIs(caught.exception, failure)
        self.assertTrue((self.output / 'xctest-summary.json').is_file())
        self.assertTrue((self.output / 'xctest-tests.json').is_file())

    def test_missing_bundle_does_not_invent_results(self):
        failure = subprocess.CalledProcessError(65, self.command)
        self.result.rmdir()
        capture = Mock()
        with self.assertRaises(subprocess.CalledProcessError):
            run_host_tests(Mock(side_effect=failure), capture, self.command, self.result, self.output, self.report)
        capture.assert_not_called()
        self.assertFalse((self.output / 'xctest-summary.json').exists())

    def test_result_extraction_failure_preserves_execution_error(self):
        failure = subprocess.CalledProcessError(65, self.command)
        capture = Mock(side_effect=['{}', ValueError('incomplete result JSON')])
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            run_host_tests(Mock(side_effect=failure), capture, self.command, self.result, self.output, self.report)
        self.assertIs(caught.exception, failure)
        self.assertIn('incomplete result JSON', self.report['host_result_capture_error'])
        self.assertTrue((self.output / 'xctest-summary.json').is_file())


if __name__ == '__main__':
    unittest.main()
