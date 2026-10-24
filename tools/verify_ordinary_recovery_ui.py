#!/usr/bin/env python3
"""Check retained ordinary CUA observations, exact files/PIDs and scoped SIGKILL evidence."""
import argparse
import json
from pathlib import Path
from verify_ios_host import EXPECTED
from verify_ios_preview_ui import document, events, sha

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', type=Path, required=True)
    args = parser.parse_args()
    folder = args.case.resolve(); folder.relative_to(ROOT/'build')
    case = load(folder/'case.json')
    build = load(ROOT/'reports/ios-preview-build.json')
    restored = load(ROOT/'reports/ios-preview-restored.json')
    host = load(ROOT/'reports/ios-host-tests.json')
    assert build['status'] == restored['status'] == host['status'] == 'passed'
    assert set(host['checks']) == EXPECTED and host['test_count'] == len(EXPECTED)
    assert case['artifact_sha256'] == build['sha256'] == restored['artifact_sha256']
    assert case['device'] == restored['device'] == host['device']['udid']
    assert restored['no_xctest_plugin'] and sha(ROOT/build['artifact']) == build['sha256']
    bindings = {}
    for report, fields in ((build, ('source_and_build_input_sha256',)),
                           (host, ('source_and_framework_sha256', 'test_build_sha256', 'input_sdk_contract_sha256'))):
        for field in fields:
            for relative, digest in report[field].items():
                assert sha(ROOT/relative) == digest, relative
                bindings[relative] = digest
    assert load(folder/'payload-before.json') == load(folder/'payload-after.json') == restored['installed_payload_sha256']
    before, after = load(folder/'files-before.json'), load(folder/'files-after.json')
    assert len(before) == 12 and all(after.get(name) == digest for name, digest in before.items())
    assert set(after)-set(before) == {case['note_relative_path'], case['recovery_relative_path']}
    names = ['stage-01-two-strokes', 'stage-02-undo', 'stage-03-redo', 'stage-04-two-pages',
             'stage-04-unattributed-extra-input', 'stage-05-after-zoom-background', 'stage-06-reopened', 'stage-07-after-ordinary-kill']
    docs = [document(folder/(name+'.ncp')) for name in names]
    assert [(len(p), len(s), int(d[5])) for d,p,s in docs[:5]] == [(1,2,3),(1,1,4),(1,2,5),(2,5,10),(2,6,11)]
    def stable(stroke):
        row, points = stroke
        return row[:3]+row[4:], points
    assert list(map(stable, docs[0][2])) == list(map(stable, docs[2][2]))
    assert stable(docs[1][2][0]) == stable(docs[0][2][0])
    assert int(docs[2][2][1][0][3]) > int(docs[0][2][1][0][3])
    final_root, final_pages, final_strokes = docs[-1]
    assert final_root[2] == case['title'] and [p[5] for p in final_pages] == ['BLANK','GRID']
    assert [sum(s[0][2] == p[2] for s in final_strokes) for p in final_pages] == [2,4]
    assert case['known_cua_completed_strokes'] == case['unattributed_additional_strokes'] == 3
    assert len({sha(folder/(name+'.ncp')) for name in names[4:]}) == 1
    assert after[case['note_relative_path']] == sha(folder/(names[-1]+'.ncp'))
    assert [stable(s) for s in docs[3][2]] == [stable(s) for s in final_strokes[:5]]
    assert all(len(s[1]) >= 2 for s in final_strokes)
    assert len({(p[1],p[2]) for p in final_strokes[0][1]}) == 1
    assert all(len({(p[1],p[2]) for p in s[1]}) > 1 for s in final_strokes[1:])
    assert [s[0][4:8] for s in final_strokes[:3]] == [
        ['MONOLINE','1','-14339513','2.5'], ['MONOLINE','1','-14339513','2.5'], ['MONOLINE','1','-13276994','5.0']]
    # Parse length framing in UTF-16 units, matching the actual shared codec. This does not execute UI.
    envelope = (folder/'recovery-seed.ncp').read_text(encoding='utf-8')
    magic, metadata, frame, body = envelope.split('\n',3)
    assert magic == 'NCRECOVERY\t1'
    base_size, ink_size, operation = frame.split('\t'); base_size=int(base_size)*2; ink_size=int(ink_size)*2
    raw = body.encode('utf-16-le')
    assert raw[base_size+ink_size:].decode('utf-16-le') == 'RECOVERY-END\n'
    base = raw[:base_size].decode('utf-16-le'); ink = raw[base_size:base_size+ink_size].decode('utf-16-le')
    assert base.splitlines()[1] == metadata
    assert ''.join(chr(int(operation[n:n+4],16)) for n in range(0,len(operation),4)) == case['recovery_operation']
    (folder/'recovery-base.ncp').write_text(base, encoding='utf-8', newline='\n')
    (folder/'recovery-ink.ncp').write_text(ink, encoding='utf-8', newline='\n')
    base_root, base_pages, base_strokes = document(folder/'recovery-base.ncp')
    _, ink_pages, ink_strokes = document(folder/'recovery-ink.ncp')
    assert base_root[2] == case['recovery_title'] and base_root[5] == '11'
    assert base_pages == final_pages and base_strokes == final_strokes
    assert case['recovery_seed_is_synthetic'] and case['recovery_seed_not_original_worker_write']
    assert case['recovery_seed_source_sha256'] == sha(folder/'stage-06-reopened.ncp')
    assert len(ink_strokes) == 1 and ink_pages[0][:6] == base_pages[1][:6]
    assert ink_strokes[0][0][1] == case['recovery_object']
    assert sha(folder/'recovery-seed.ncp') == sha(folder/'recovery-01-opened.ncp') == sha(folder/'recovery-02-reopened-after-kill.ncp')
    committed_names = ['recovery-03-committed','recovery-04-repeat-save','recovery-05-reopened-committed']
    assert len({sha(folder/(name+'.ncp')) for name in committed_names}) == 1
    committed, pages, strokes = document(folder/(committed_names[-1]+'.ncp'))
    assert committed[1] == base_root[1] and committed[2] == case['recovery_title'] and committed[5] == '12'
    assert strokes[:-1] == base_strokes and strokes[-1] == ink_strokes[0]
    assert len(strokes)==7 and [sum(s[0][2] == p[2] for s in strokes) for p in pages] == [2,5]
    assert pages[0] == base_pages[0] and pages[1][:6] == base_pages[1][:6] and pages[1][6] == '12'
    assert after[case['recovery_relative_path']] == sha(folder/(committed_names[-1]+'.ncp'))
    stage = lambda name: load(folder/(name+'.json'))
    first = load(folder/'process-before.json')['pid']
    second = stage('stage-07-after-ordinary-kill')['pid']
    third = stage('recovery-02-reopened-after-kill')['pid']
    fourth = stage('recovery-05-reopened-committed')['pid']
    assert len({first,second,third,fourth}) == 4
    kills=[]
    for label,pid in [('kill-01-ordinary-saved',first),('kill-02-pending-active',second),('kill-03-recovered-committed',third)]:
        record=load(folder/(label+'.json'))
        assert record['signal']=='SIGKILL' and record['old_pid_gone'] and record['note_files_unchanged_during_kill']
        assert record['old_process']['pid']==pid and record['service_target'].endswith(record['old_process']['label'])
        kills.append({'pid':pid,'signal':'SIGKILL','old_pid_gone':True})
    assert load(folder/'ordinary-process-final.json')['pid'] == fourth
    observations=load(folder/'ui-observations.json')
    assert observations['method'].startswith('CUA Device Hub') and observations['not_inferred_from_hashes_or_host_tests']
    assert observations['all_additional_input_retained'] and observations['unattributed_additional_strokes']==3
    states={item['label']:' '.join(item['ax_fragments']) for item in observations['states']}
    for label, fragments in {'two_strokes':['2 笔','2笔'],'undo':['1 笔','1笔'],'redo':['2 笔','2笔'],
        'normal_reopened_page_one':['1/2','已打开 · 6 笔','2笔'],'normal_reopened_page_two':['2/2','4笔'],
        'ordinary_after_sigkill':['已打开 · 6 笔','2笔'],
        'pending_reopened_after_sigkill':['已恢复未提交笔迹，请点击保存重试提交','2/2','4笔'],
        'recovery_committed':['已保存到本机 · 7 笔','5笔'],
        'committed_after_sigkill_page_two':['2/2','5笔'],
        'final_library':['本机笔记',case['recovery_title'],case['title']]}.items():
        assert all(value in states[label] for value in fragments), label
    ordinary = [events(folder/'render.ndjson',first), events(folder/'render.ndjson',fourth)]
    paths = [p for p in folder.iterdir() if p.is_file()]
    paths += [Path(__file__), ROOT/'tools/capture_ordinary_ios_case.py',ROOT/'tools/verify_ios_preview_ui.py', ROOT/build['artifact']]
    paths += [ROOT/'reports'/name for name in ('ios-preview-build.json','ios-preview-restored.json','ios-host-tests.json')]
    bindings.update({p.relative_to(ROOT).as_posix():sha(p) for p in paths})
    report=dict(schema='ios-ordinary-recovery-ui-1',status='passed',source_and_artifact_sha256=bindings,
        artifact_sha256=build['sha256'],device=case['device'],original_ncp_files_unchanged=len(before),current_ncp_files=len(after),
        ordinary_ui_selected_flows_verified=True,normal_close_reopen_verified=True,home_background_foreground_verified=True,
        ordinary_document_strokes=6,known_cua_strokes=3,unattributed_additional_strokes_retained=3,
        ordinary_document_strokes_per_page=[2,4],ordinary_provider_release_traces=ordinary,sigkill_processes=kills,
        recovery_ui_fixture_synthetic=True,recovery_fixture_was_not_written_by_original_worker=True,
        recovery_pending_preserved_after_sigkill=True,recovery_explicit_commit_once=True,recovery_commit_revision=12,
        recovery_committed_strokes_per_page=[2,5],recovery_committed_file_unchanged_after_sigkill=True,
        screenshots_observed_inline_only=True,screenshot_pixel_oracle_verified=False,
        ordinary_process_kill_of_existing_saved_files_verified=True,kill_during_unconfirmed_write_verified=False,
        killed_process_native_release_receipts_verified=False,power_loss_durability_verified=False,
        physical_device_or_heap_verified=False,android_ordinary_ui_verified=False,c1_gate_complete=False,
        scope='Current ordinary UIKit CUA case only, with PID-specific files/provider logs and targeted SIGKILL: new named note, dot/lines, undo/redo, two pages/paper/tool, zoom, Home/foreground, normal close/reopen, saved ordinary file restart. Three extra strokes appeared outside recorded CUA instructions; source unestablished, user reported not using the simulator, all retained and never attributed to controlled input. Separately seed a unique synthetic recovery envelope copied from the actual saved UI document, open on ordinary owner, SIGKILL while pending, reopen, explicit save once, repeat save, SIGKILL and reopen committed file. The seed is not evidence that the ordinary worker wrote a pending checkpoint. Prior original-worker host tests remain separate. No unconfirmed-write kill, replacement crash window, power-loss, complete owner loss, killed-process native receipt, physical stylus/heap or full C1 claim.')
    (ROOT/'reports/ios-ordinary-recovery-ui.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'status':'passed','original_files_unchanged':len(before),'ordinary_strokes':6,'recovery_strokes':7,'distinct_ordinary_pids':4}))


if __name__ == '__main__':
    main()
