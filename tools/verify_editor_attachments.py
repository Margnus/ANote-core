#!/usr/bin/env python3
"""Verify original Android editor/window lifetime and pending recovery evidence."""
import hashlib
import json
from pathlib import Path
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    bindings = {}; reports = {}
    for name, fields in {
        'verification': ('source_sha256', 'verification_input_sha256'),
        'preview-build': ('source_and_build_input_sha256',),
        'android-input-host-tests': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
    }.items():
        path = ROOT / 'reports' / (name + '.json'); report = read(path)
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        bindings[path.relative_to(ROOT).as_posix()] = sha(path); reports[name] = report
    android = reports['android-input-host-tests']
    required = {'shared_editor_detach_capacity_and_reopen', 'pending_editor_failure_retry_original_recovery',
                'actual_activity_shared_editor_departure'}
    assert required <= set(android['owner_retirement_checks']['checks'])
    assert android['owner_retirement_check_count'] == 9
    assert android['all_original_files_unchanged'] and android['ordinary_process_restored']
    originals = read(ROOT / 'build/android-input-host/original-files.json')
    previous = read(ROOT / 'build/catalog-budget/android-after.json')
    assert len(originals) == len(previous) == 66
    assert {Path(name).name: digest for name, digest in originals.items()} == {Path(name).name: digest for name, digest in previous.items()}
    for name, digest in originals.items():
        assert sha(ROOT / 'build/pending-input-budget/android-originals' / Path(name).name) == digest
    paths = [Path(__file__), ROOT / 'build/android-input-host/original-files.json',
             ROOT / 'build/catalog-budget/android-after.json',
             ROOT / 'android-app/src/main/java/dev/notecore/app/NoteStore.kt',
             ROOT / 'android-app/src/main/java/dev/notecore/app/MainActivity.kt',
             ROOT / 'android-app/src/androidTest/java/dev/notecore/app/EditorAttachmentHostChecks.kt',
             ROOT / 'android-app/src/androidTest/java/dev/notecore/app/OwnerRetirementHostChecks.kt',
             ROOT / 'docs/owner交接与失败责任矩阵.md']
    for path in paths:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    result = {
        'schema': 'editor-attachments-1', 'status': 'passed',
        'scope': 'Original Android Store/worker and MainActivity actual launch/back-button callback/recreate with isolated cache Store injected only by instrumentation; not ordinary OS touch or full multi-document/window acceptance',
        'decision': 'Original Store holds at most eight bounded presentation identities. Leaving one editor preserves shared Notebook. Last explicit departure requires original save admission and confirmed pending recovery before original retirement. Destruction only forgets its own presentation identity.',
        'checks': sorted(required), 'input_checks': android['check_count'],
        'storage_checks': android['storage_retention_check_count'], 'owner_checks': android['owner_retirement_check_count'],
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'original_android_files_preserved': len(originals),
        'limits': ['Single active document remains a preview limit; another document is refused while editors remain, without replacing authority or crashing',
                   'Actual Activity recreation proves identical Notebook/root; toolbar state and complete simultaneous OS window delivery not accepted',
                   'Save UI fan-out for all windows, Apple per-window navigation and arbitrary duplicate directory services remain unfinished',
                   'No new shared Native/Apple run, M-1/heap/pen, process takeover, physical peak quota or power-loss evidence'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/editor-attachments.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
