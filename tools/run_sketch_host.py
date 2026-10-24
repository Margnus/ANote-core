#!/usr/bin/env python3
"""Run the isolated sample on the authorized dedicated AVD; never operate ordinary UI or NoteCore data."""
import hashlib,json,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]; out=root/'build/sketch-host'
adb='/Users/lixiaofan/Library/Android/sdk/platform-tools/adb'
def call(*args):return subprocess.check_output([adb,'-s','emulator-5554',*args],timeout=90)
def snapshot():
 assert call('shell','getprop','ro.boot.qemu.avd_name').decode().strip()=='NoteCorePreview'
 names=call('shell','run-as','dev.notecore.preview','ls','files/preview-notes').decode().splitlines()
 files={n:hashlib.sha256(call('exec-out','run-as','dev.notecore.preview','cat','files/preview-notes/'+n)).hexdigest() for n in names if n.endswith('.ncp')}
 package=call('shell','pm','path','dev.notecore.preview').decode().strip().removeprefix('package:')
 return {'files':files,'apk_sha256':hashlib.sha256(call('exec-out','cat',package)).hexdigest()}
before=snapshot();assert len(before['files'])==66
if (out/'notecore-before.json').exists():
 assert json.loads((out/'notecore-before.json').read_text(encoding='utf-8'))==before
(out/'notecore-before.json').write_text(json.dumps(before,indent=2)+'\n',encoding='utf-8')
for p in ('samples/sketch-host/build/outputs/apk/debug/sketch-host-debug.apk','samples/sketch-host/build/outputs/apk/androidTest/debug/sketch-host-debug-androidTest.apk'):
 print(call('install','-r',str(root/p)).decode())
result=call('shell','am','instrument','-w','dev.notecore.sample.sketch.test/dev.notecore.sample.sketch.SketchInstrumentation')
(out/'instrumentation.log').write_bytes(result);print(result.decode())
assert b'INSTRUMENTATION_RESULT: status=passed' in result and b'INSTRUMENTATION_CODE: -1' in result, 'Instrumented acceptance did not pass'
after=snapshot();(out/'notecore-after.json').write_text(json.dumps(after,indent=2)+'\n',encoding='utf-8');assert before==after
print('NoteCore original APK and all 66 current notes unchanged')
