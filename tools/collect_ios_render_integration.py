#!/usr/bin/env python3
"""Bind observed ordinary UIKit events and manual CUA evidence, separately from host tests."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(relative):
    return json.loads((ROOT / relative).read_text(encoding='utf-8'))


def main():
    runtime = read('reports/ios-runtime-smoke.json')
    build = read('reports/ios-preview-build.json')
    restored = read('reports/ios-preview-restored.json')
    host = read('reports/ios-host-tests.json')
    notes = read('build/dev036d-test-document.json')
    assert all(r['status'] == 'passed' for r in (runtime, build, restored, host))
    assert runtime['artifact_sha256'] == restored['artifact_sha256'] == build['sha256']
    assert digest(ROOT / build['artifact']) == build['sha256']
    for relative, sha in build['source_and_build_input_sha256'].items():
        assert digest(ROOT / relative) == sha, ('Stale ordinary build', relative)
    for field in ('source_and_framework_sha256', 'test_build_sha256'):
        for relative, sha in host[field].items():
            assert digest(ROOT / relative) == sha, ('Stale host evidence', relative)
    assert host['test_count'] == 25 and host['preview_model_view_lifecycle_test_count'] == 5
    assert host['provider_source_release_owner_verified'] and host['provider_release_receipt_test_count'] == 3
    assert restored['no_xctest_plugin'] and not restored['data_deleted']
    assert notes['all_original_files_unchanged'] and notes['original_file_count'] == 6
    assert (notes['pages'], notes['total_strokes'], notes['page_strokes']) == (2, 10, [9, 1])
    events = []
    for line in (ROOT / 'build/dev036d-render-stream.log').read_text(encoding='utf-8').splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        message = event.get('eventMessage', '')
        if event.get('processID') == runtime['pid'] and message.startswith('NOTECORE_RENDER '):
            events.append({'timestamp': event['timestamp'], 'pid': runtime['pid'], 'message': message})
    presented = [e for e in events if 'frame-presented' in e['message'] and 'bitmap=0 source=provider' in e['message']]
    freed = [e for e in events if e['message'] == 'NOTECORE_RENDER provider-bytes-freed']
    settled = [e for e in events if 'provider-owner-settled' in e['message']]
    drained = [e for e in events if e['message'] == 'NOTECORE_RENDER owner-drained charged=0']
    assert presented and freed and settled and drained
    unconfirmed = 0
    for event in events:
        if event['message'] == 'NOTECORE_RENDER provider-bytes-freed':
            unconfirmed += 1
        elif 'provider-owner-settled' in event['message']:
            assert unconfirmed > 0, 'Owner settlement without a preceding provider release receipt'
            unconfirmed -= 1
    assert unconfirmed == 0, 'Provider release receipt not settled on owner'
    assert events[-1] == drained[-1], 'Final ordinary owner must confirm zero charge after exit'
    assert freed[-1]['timestamp'] <= settled[-1]['timestamp'] <= drained[-1]['timestamp']
    sources = (
        'reports/ios-preview-build.json', 'reports/ios-preview-restored.json',
        'reports/ios-host-tests.json', 'reports/ios-runtime-smoke.json',
        'build/dev036d-ui-baseline.json', 'build/dev036d-test-document.json',
        'build/dev036d-render-stream.log', 'build/dev036d-final-two-strokes.png',
        'build/dev036d-final-page2.png', 'tools/collect_ios_render_integration.py')
    report = {
        'schema': 'ios-render-integration-2', 'status': 'verified_bounded_uikit_integration',
        'artifact_sha256': build['sha256'], 'ordinary_pid': runtime['pid'],
        'device': runtime['device'], 'runtime_events': events,
        'runtime_event_scope': 'Final ordinary-package PID only; excludes XCTest and the earlier DEV-036D candidate. frame-presented means CGContext draw accepted the complete provider image; CUA separately observes visible strokes. No GPU type, display fence or photon claim.',
        'host_test_count': 25, 'model_view_lifecycle_tests': 5, 'provider_release_receipt_tests': 3,
        'model_shared_executor_view_verified': True,
        'ordinary_screen_new_backend_consumption_verified': True,
        'cpu_holder_bitmap_gate_preserved': True,
        'ordinary_provider_source_release_and_owner_zero_verified': True,
        'cpu_gate_rejection_historical_artifact_sha256': '1389b318cc3d1f0eed623ef1855df5e3a070b6acf541978b29df47334c4f19d6',
        'ui_observation_method': 'Actual manual CUA actions through Device Hub com.apple.dt.Devices, compact window for dragging. Collector binds these recorded observations; it does not infer UI actions from event counts.',
        'ui_observations': [
            'Final package created and named DEV036D-Final-20261017; actual drags produced two crossing strokes of different colors/widths on page 1; complete visible strokes remained after release.',
            'Added empty page 2 then dragged one stroke; page 2 displayed one stroke without page 1 contents; total saved 3.',
            'Returned to page 1 (2 strokes), zoomed in/out and fit page; Home visibly showed system app icons; reopened app at page 1/2 with 2 strokes and total saved 3.',
            'Concurrent external UI activity was reported by CUA; next observed page 1 had 9 strokes and total saved 10. These 7 additional strokes are retained and are not attributed to agent drags or claimed exclusively synthetic.',
            'Closed to library, reopened final file: total 10, page 1/2 had 9; page 2/2 had 1. Closed again; final ordinary PID recorded provider source release, owner settlement and owner-drained charged=0.',
        ],
        'dynamic_static_acceptance_scope': 'Three agent drag commits visibly persisted in the final ordinary package with non-bitmap provider presentation events. Host independently compares complete fallback before ACK to new static pixels with overlapping semi-transparent objects (no double alpha). No in-progress pen-to-photon, high-frequency frame or exclusive backend consumption claim for the later 9-stroke page: bounded work may use full Path fallback.',
        'test_document': notes,
        'source_contract': {
            'api': 'CGDataProviderCreateWithData / CGDataProviderReleaseDataCallback',
            'apple_documentation': 'https://developer.apple.com/documentation/coregraphics/cgdataprovider/init(datainfo:data:size:releasedata:)',
            'sdk_header': '/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator27.1.sdk/System/Library/Frameworks/CoreGraphics.framework/Headers/CGDataProvider.h',
            'sdk_header_sha256': digest(Path('/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator27.1.sdk/System/Library/Frameworks/CoreGraphics.framework/Headers/CGDataProvider.h')),
            'responsibility': 'Immutable independent RGBA allocation; accepted provider alone frees bytes through releaseData. Receipt synchronized across threads; Kotlin/native permit settled only on main owner after actual receipt. A retained provider blocks release and new model pool; UNKNOWN additionally requires explicit read-only reconciliation. Core Graphics owns destination-specific copies; no raw GPU resource is borrowed from the Kotlin pool.',
            'budget_scope': 'Original conservative two-copy native allowance and single permit retained. Source bytes and logical charge are bounded; hidden Core Graphics allocations/actual heap/GPU residency are unmeasured.',
        },
        'source_evidence_sha256': {relative: digest(ROOT / relative) for relative in sources},
        'scope': 'Bounded MONOLINE ordinary iOS UIKit complete static frame integration and source-lifetime protocol, with full canonical Path fallback on work/budget/failure. Android ordinary backend, physical stylus/frame/heap, GPU fence, arbitrary transforms, complete C1, SQLite, forced-stop and file-picker acceptance remain unverified.',
        'dev036_cross_platform_complete': False, 'c1_gate_complete': False,
        'physical_device_or_heap_or_gpu_measured': False,
    }
    (ROOT / 'reports/ios-render-integration.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': report['status'], 'ordinary_pid': runtime['pid'],
                      'presented_events': len(presented), 'provider_freed_events': len(freed),
                      'owner_zero_events': len(drained), 'original_files_unchanged': notes['original_file_count'],
                      'page_strokes': notes['page_strokes']}))


if __name__ == '__main__':
    main()
