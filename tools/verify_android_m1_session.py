"""Verify the package, process and raw files of paired Android M-1 snapshots."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re

from capture_android_m1 import process_identity, verify_metric_identity


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def read_snapshot(directory, apk_digest):
    receipt = directory / 'snapshot.json'
    data = json.loads(receipt.read_bytes())
    if (data['schema'] != 2 or data['status'] != 'snapshot-collected' or data['requested_samples'] != 1
            or data['emulator_detected'] is not False or data['origin'] != 'device-candidate'):
        raise ValueError(f'{directory}: expected one complete device snapshot')
    if not data['candidate_apk_matches_installed'] or any(
        data[key] != apk_digest for key in ('candidate_apk_sha256', 'installed_apk_sha256')
    ):
        raise ValueError(f'{directory}: snapshot APK does not match the supplied candidate')
    samples = data['samples']
    if len(samples) != 1 or samples[0]['status'] != 'snapshot-collected' or samples[0]['artifact_prefix'] != '':
        raise ValueError(f'{directory}: invalid sample record')
    required = {'fingerprint', 'apk-path', 'apk-path-after', 'installed-apk-sha256', 'installed-apk-sha256-after',
                'pid-before', 'pid-after', 'stat-before', 'stat-after', 'frames', 'memory'}
    artifacts = data['artifacts']
    if not required.issubset(artifacts):
        raise ValueError(f'{directory}: missing raw snapshot files')
    for label, entry in artifacts.items():
        if not re.fullmatch(r'[a-z0-9-]+', label) or entry['returncode'] != 0:
            raise ValueError(f'{directory}: invalid snapshot command {label}')
        for suffix, key in (('.txt', 'sha256'), ('.stderr.txt', 'stderr_sha256')):
            path = directory / (label + suffix)
            if sha256(path) != entry[key]:
                raise ValueError(f'{path}: digest mismatch')

    def raw(label):
        return (directory / (label + '.txt')).read_text(encoding='utf-8').strip()

    pid = raw('pid-before')
    identity = samples[0]['process_identity']
    if (not re.fullmatch(r'[1-9][0-9]{0,9}', pid) or raw('pid-after') != pid
            or any(process_identity(raw(label), pid) != identity for label in ('stat-before', 'stat-after'))):
        raise ValueError(f'{directory}: process identity does not match the raw snapshot')
    for label in ('frames', 'memory'):
        verify_metric_identity(raw(label), pid, label)
    package_path = raw('apk-path')
    if not package_path.startswith('package:/data/app/') or raw('apk-path-after') != package_path:
        raise ValueError(f'{directory}: installed APK path changed')
    if any(raw(label).split() != [apk_digest, package_path.removeprefix('package:')]
           for label in ('installed-apk-sha256', 'installed-apk-sha256-after')):
        raise ValueError(f'{directory}: raw installed APK digest differs')
    started = datetime.fromisoformat(data['started_utc'])
    finished = datetime.fromisoformat(data['finished_utc'])
    if started.tzinfo is None or finished.tzinfo is None or finished < started:
        raise ValueError(f'{directory}: invalid snapshot timestamps')
    fingerprint = raw('fingerprint')
    if not fingerprint:
        raise ValueError(f'{directory}: missing device fingerprint')
    return (data['serial'], fingerprint, package_path, identity), started, finished, sha256(receipt)


def verify(pre, post, apk, trace):
    apk_digest = sha256(apk)
    before, _, pre_finished, pre_digest = read_snapshot(pre, apk_digest)
    after, post_started, _, post_digest = read_snapshot(post, apk_digest)
    if before != after:
        raise ValueError('device, APK path or process changed between snapshots')
    if pre.resolve() == post.resolve() or post_started < pre_finished:
        raise ValueError('expected distinct snapshots in pre/post order')
    if trace.stat().st_size == 0:
        raise ValueError('trace file is empty')
    return {'status': 'snapshot-pair-verified', 'serial': before[0], 'process_identity': before[3],
            'fingerprint': before[1], 'apk_sha256': apk_digest, 'pre_sha256': pre_digest, 'post_sha256': post_digest,
            'trace_sha256': sha256(trace), 'trace_metrics_verified': False, 'm1_accepted': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('pre', 'post', 'apk', 'trace'):
        parser.add_argument('--' + flag, type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.pre, args.post, args.apk, args.trace), indent=2))
    except (OSError, KeyError, TypeError, ValueError) as error:
        parser.exit(1, f'{error}\n')
