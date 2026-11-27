"""Check that an iPad capture still matches its original IPA and raw Instruments files."""
import argparse
from datetime import datetime
import json
from pathlib import Path

from capture_apple_m1 import (
    CAPTURE_FILES, HARDWARE_UDID, capture_artifacts, preview_process, read_candidate, sha256, validate_trace,
)


def verify(directory, ipa):
    receipt = directory / 'capture.json'
    if receipt.is_symlink():
        raise ValueError('capture receipt must be a regular file')
    data = json.loads(receipt.read_bytes())
    if (data['schema'] != 2 or data['status'] != 'trace-collected'
            or data['m1_accepted'] is not False or data['installed_binary_sha256_verified'] is not False):
        raise ValueError('expected a completed capture with pending device acceptance')
    device = data['device_udid']
    if not HARDWARE_UDID.fullmatch(device):
        raise ValueError('invalid hardware UDID')
    if type(data['pid']) is not int or data['pid'] < 1:
        raise ValueError('invalid process ID')
    started = datetime.fromisoformat(data['started_utc'])
    finished = datetime.fromisoformat(data['finished_utc'])
    if started.tzinfo is None or finished.tzinfo is None or finished < started:
        raise ValueError('invalid capture timestamps')
    executable, candidate = read_candidate(ipa)
    if candidate != data['candidate']:
        raise ValueError('IPA does not match the capture receipt')
    steps = data['steps']
    if set(steps) != CAPTURE_FILES or any(
        step['status'] != 'recorded' or step['returncode'] != 0 for step in steps.values()
    ):
        raise ValueError('missing or failed recording step')
    for name, topic in (('device', 'details'), ('installed', 'apps'),
                        ('process-before', 'processes'), ('process-after', 'processes')):
        if steps[name]['command'][:7] != ['xcrun', 'devicectl', 'device', 'info', topic, '--device', device]:
            raise ValueError(f'{name} command does not match the recorded device')
    if steps['record']['command'][:7] != [
        'xcrun', 'xctrace', 'record', '--device', device, '--attach', str(data['pid']),
    ]:
        raise ValueError('Instruments command does not match the recorded device and PID')
    artifacts = capture_artifacts(directory)
    if not {f'{name}.{stream}' for name in steps for stream in ('stdout', 'stderr')}.issubset(artifacts):
        raise ValueError('missing raw recording output')
    if artifacts != data['artifact_sha256']:
        raise ValueError('capture artifact digest mismatch or unexpected file')
    before, after = (preview_process((directory / (name + '.stdout')).read_bytes(), executable, data['pid'])
                     for name in ('process-before', 'process-after'))
    if before != after:
        raise ValueError('preview executable changed during trace')
    validate_trace(directory)
    return {'status': 'trace-evidence-verified', 'capture_sha256': sha256(receipt),
            'ipa_sha256': candidate['ipa_sha256'], 'artifact_count': len(artifacts),
            'installed_binary_sha256_verified': False, 'trace_metrics_verified': False, 'm1_accepted': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--ipa', type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.capture, args.ipa), indent=2))
    except (OSError, KeyError, TypeError, ValueError) as error:
        parser.exit(1, f'{error}\n')
