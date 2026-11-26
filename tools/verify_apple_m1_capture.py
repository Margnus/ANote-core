"""Verify IPA evidence binding without a connected iPad."""

from argparse import Namespace
import hashlib
import json
from pathlib import Path
import plistlib
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import zipfile

from capture_apple_m1 import capture


class AppleCaptureTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.ipa = Path(directory.name) / 'preview.ipa'
        self.output = Path(directory.name) / 'evidence'
        self.args = Namespace(ipa=self.ipa, output=self.output, pid=17, device='test-device',
                              template='Time Profiler', seconds=1)

    def write_ipa(self, executable):
        bundle = 'Payload/NoteCorePreview.app/'
        info = {'CFBundleExecutable': 'NoteCorePreview', 'CFBundleSupportedPlatforms': ['iPhoneOS'],
                'CFBundleIdentifier': 'dev.notecore.preview', 'CFBundleVersion': '42',
                'CFBundleShortVersionString': '1.0'}
        with zipfile.ZipFile(self.ipa, 'w') as archive:
            archive.writestr(bundle + 'Info.plist', plistlib.dumps(info))
            if executable is not None:
                archive.writestr(bundle + 'NoteCorePreview', executable)

    def runner(self, replacement=None):
        def run(command, *, stdout, stderr, timeout):
            if 'processes' in command:
                stdout.write(json.dumps({'result': {'runningProcesses': [{
                    'processIdentifier': self.args.pid, 'executable': '/device/NoteCorePreview.app/NoteCorePreview',
                }]}}).encode())
            elif 'devicectl' in command:
                stdout.write(b'{"result": {}}')
            elif 'record' in command and replacement is not None:
                self.write_ipa(replacement)
            return subprocess.CompletedProcess(command, 0)

        return patch('capture_apple_m1.subprocess.run', side_effect=run)

    def report(self):
        return json.loads((self.output / 'capture.json').read_text(encoding='utf-8'))

    def test_missing_executable_refuses_before_contacting_device(self):
        self.write_ipa(None)
        with patch('capture_apple_m1.subprocess.run') as run:
            with self.assertRaisesRegex(ValueError, 'nonempty preview executable'):
                capture(self.args)
            run.assert_not_called()
        self.assertEqual(self.report()['status'], 'incomplete')

    def test_trace_binds_both_digests_to_the_supplied_candidate(self):
        binary = b'fixture executable'
        self.write_ipa(binary)
        with self.runner():
            capture(self.args)
        report = self.report()
        self.assertEqual(report['status'], 'trace-collected')
        self.assertEqual(report['candidate']['ipa_sha256'], hashlib.sha256(self.ipa.read_bytes()).hexdigest())
        self.assertEqual(report['candidate']['ipa_executable_sha256'], hashlib.sha256(binary).hexdigest())
        self.assertFalse(report['installed_binary_sha256_verified'])

    def test_package_replaced_during_capture_is_incomplete(self):
        self.write_ipa(b'original')
        original = hashlib.sha256(self.ipa.read_bytes()).hexdigest()
        with self.runner(replacement=b'replacement'):
            with self.assertRaisesRegex(ValueError, 'IPA changed during trace'):
                capture(self.args)
        report = self.report()
        self.assertEqual(report['status'], 'incomplete')
        self.assertEqual(report['candidate']['ipa_sha256'], original)


if __name__ == '__main__':
    unittest.main()
