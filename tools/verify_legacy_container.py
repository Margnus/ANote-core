#!/usr/bin/env python3
"""Fixed Java-generated framing oracle and refusal cases; no original archive or payload decoder."""
import hashlib
import json
import tempfile
import zipfile
from pathlib import Path
from inspect_legacy_container import inspect_bytes,inspect_archive,FramingError,KNOWN
ROOT=Path(__file__).resolve().parents[1]
FIXTURE=ROOT/'fixtures/legacy/container-framing.json'
def checks():
    fixture=json.loads(FIXTURE.read_text(encoding='utf-8'));assert fixture['origin']=='synthetic-java-dataoutputstream'
    assert sorted(KNOWN)==fixture['known_types']
    data=bytes.fromhex(fixture['hex']);result=inspect_bytes(data)
    assert result['version']==2 and result['unknown_record_indices']==[1] and not result['migration_supported']
    assert [r['bytes'] for r in result['records']]==[2,3]
    assert result['records'][0]['payload_sha256']==hashlib.sha256(b'{}').hexdigest()
    assert result['records'][0]['name_sha256']==hashlib.sha256('中文😀\0'.encode('utf-16-be')).hexdigest()
    assert result['records'][1]['payload_sha256']==hashlib.sha256(bytes([0,1,2])).hexdigest()
    note_marker=b'\x00\x00\x00\x01\x00\x04NOTE';start=data.index(note_marker)
    size_marker=b'\x00\x00\x00\x00\x00\x00\x00\x02';length=data.index(size_marker,start+8)
    mutations={'bad_link':data[:start]+b'\x00\x00\x00\x03'+data[start+4:],
        'premature_end':data[:start]+b'\x00\x00\x00\x00'+data[start+4:],
        'negative_length':data[:length]+b'\xff'*8+data[length+8:],
        'truncated_payload':data[:length+8], 'trailing_data':data+b'x',
        'wrong_tail_length':data[:-1]+b'\x12',
        'unknown_version':data[:5]+b'\x00\x00\x00\x03'+data[9:],
        'not_modified_utf':data.replace(bytes.fromhex('c080'),b'\x00\x00',1)}
    refused=[]
    for name,value in mutations.items():
        try:inspect_bytes(value)
        except FramingError:refused.append(name)
        else:raise AssertionError(name)
    build=(ROOT/'build').resolve();assert build.is_relative_to(ROOT);build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='legacy-framing-',dir=build) as temp:
        path=Path(temp)/'synthetic.Jnotes'
        with zipfile.ZipFile(path,'w') as archive:archive.writestr('zip.Jzip',data)
        before=path.read_bytes();assert inspect_archive(path)==result and path.read_bytes()==before
        bad=Path(temp)/'unsafe-name.Jnotes'
        with zipfile.ZipFile(bad,'w') as archive:archive.writestr('../zip.Jzip',data)
        try:inspect_archive(bad)
        except FramingError:pass
        else:raise AssertionError('Archive paths must not be guessed or extracted')
        assert sorted(p.name for p in Path(temp).iterdir())==['synthetic.Jnotes','unsafe-name.Jnotes']
    return {'status':'passed','valid_java_oracles':1,'framing_refusals':refused,'unsupported_archive_layout_refused':True,
        'unknown_records_retained':True,'payload_migration_implemented':False,'real_sample_archive_verified':False,
        'scope':'Synthetic Java DataOutputStream framing and read-only ZIP layout checks; not real JNotes migration or platform execution.'}
def main():
    report=checks();paths=[FIXTURE,ROOT/'fixtures/legacy/LegacyContainerFixture.java',ROOT/'tools/inspect_legacy_container.py',Path(__file__)]
    report['source_and_artifact_sha256']={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    (ROOT/'reports/legacy-container-framing.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(report))
if __name__=='__main__':main()
