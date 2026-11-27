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
from verify_apple_m1_session import verify


class AppleCaptureTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.ipa = Path(directory.name) / 'preview.ipa'
        self.output = Path(directory.name) / 'evidence'
        self.args = Namespace(ipa=self.ipa, output=self.output, pid=17, device='00008112-1234567890ABCDEF',
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

    def runner(self, replacement=None, trace_content=b'fixture trace', toc=None):
        if toc is None:
            toc = b'<trace-toc><run number="1"><data><table schema="time-profile"/></data></run></trace-toc>'

        def run(command, *, stdout, stderr, timeout):
            if 'processes' in command:
                stdout.write(json.dumps({'result': {'runningProcesses': [{
                    'processIdentifier': self.args.pid, 'executable': '/device/NoteCorePreview.app/NoteCorePreview',
                }]}}).encode())
            elif 'devicectl' in command:
                stdout.write(b'{"result": {}}')
            elif 'record' in command:
                trace = Path(command[command.index('--output') + 1])
                trace.mkdir()
                if trace_content is not None:
                    (trace / 'trace-store').write_bytes(trace_content)
                if replacement is not None:
                    self.write_ipa(replacement)
            elif 'export' in command:
                stdout.write(toc)
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
        checked = verify(self.output, self.ipa)
        self.assertEqual(checked['status'], 'trace-evidence-verified')
        self.assertFalse(checked['trace_metrics_verified'])
        self.assertFalse(checked['m1_accepted'])

    def test_package_replaced_during_capture_is_incomplete(self):
        self.write_ipa(b'original')
        original = hashlib.sha256(self.ipa.read_bytes()).hexdigest()
        with self.runner(replacement=b'replacement'):
            with self.assertRaisesRegex(ValueError, 'IPA changed during trace'):
                capture(self.args)
        report = self.report()
        self.assertEqual(report['status'], 'incomplete')
        self.assertEqual(report['candidate']['ipa_sha256'], original)

    def test_successful_recorder_without_trace_data_is_incomplete(self):
        self.write_ipa(b'fixture executable')
        with self.runner(trace_content=b''):
            with self.assertRaisesRegex(ValueError, 'empty Instruments trace'):
                capture(self.args)
        self.assertEqual(self.report()['status'], 'incomplete')

    def test_successful_export_without_tables_is_incomplete(self):
        self.write_ipa(b'fixture executable')
        with self.runner(toc=b'<trace-toc><run number="1"><data /></run></trace-toc>'):
            with self.assertRaisesRegex(ValueError, 'no recorded tables'):
                capture(self.args)
        self.assertEqual(self.report()['status'], 'incomplete')

    def test_modified_trace_store_fails_offline_verification(self):
        self.write_ipa(b'fixture executable')
        with self.runner():
            capture(self.args)
        (self.output / 'recording.trace' / 'trace-store').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            verify(self.output, self.ipa)

    def test_offline_verification_rejects_another_ipa_with_same_bundle_metadata(self):
        self.write_ipa(b'original')
        with self.runner():
            capture(self.args)
        self.write_ipa(b'replacement')
        with self.assertRaisesRegex(ValueError, 'IPA does not match'):
            verify(self.output, self.ipa)

    def test_offline_verification_rejects_inconsistent_record_device(self):
        self.write_ipa(b'fixture executable')
        with self.runner():
            capture(self.args)
        receipt = self.report()
        receipt['steps']['record']['command'][4] = '00008112-0000000000000000'
        (self.output / 'capture.json').write_text(json.dumps(receipt), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Instruments command does not match'):
            verify(self.output, self.ipa)


if __name__ == '__main__':
    unittest.main()
