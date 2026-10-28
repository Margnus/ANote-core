#!/usr/bin/env python3
"""Run isolated instrumentation and relaunch its own ordinary sample; never operate NoteCore UI or data."""
import argparse,hashlib,json,subprocess,re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--case-directory', default='build/sketch-host')
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', choices=('emulator-5554', 'emulator-5580'), default='emulator-5554')
parser.add_argument('--avd', choices=('NoteCorePreview', 'NoteCoreWindows'), default='NoteCorePreview')
args=parser.parse_args()
out=(root/args.case_directory).resolve()
assert out.is_relative_to((root/'build').resolve()), 'Evidence must remain under repository build/'
out.mkdir(parents=True,exist_ok=True)
adb=args.adb
def call(*values):return subprocess.check_output([adb,'-s',args.serial,*values],timeout=90)
def snapshot():
 assert call('shell','getprop','ro.boot.qemu.avd_name').decode().strip()==args.avd
 names=call('shell','run-as','dev.notecore.preview','ls','files/preview-notes').decode().splitlines()
 files={n:hashlib.sha256(call('exec-out','run-as','dev.notecore.preview','cat','files/preview-notes/'+n)).hexdigest() for n in names if n.endswith('.ncp')}
 package=call('shell','pm','path','dev.notecore.preview').decode().strip().removeprefix('package:')
 return {'files':files,'apk_sha256':hashlib.sha256(call('exec-out','cat',package)).hexdigest()}
stamp=root/'build/sketch-host/build-inputs.sha256'
inputs={name:digest for digest,name in (line.split('  ',1) for line in stamp.read_text(encoding='utf-8').splitlines())}
for name,digest in inputs.items(): assert hashlib.sha256((root/name).read_bytes()).hexdigest()==digest, ('Stale sample build',name)
runner_sha=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
(out/'build-inputs.sha256').write_bytes(stamp.read_bytes())
before=snapshot()
if (out/'notecore-before.json').exists():
 assert json.loads((out/'notecore-before.json').read_text(encoding='utf-8'))==before
(out/'notecore-before.json').write_text(json.dumps(before,indent=2)+'\n',encoding='utf-8')
for p in ('samples/sketch-host/build/outputs/apk/debug/sketch-host-debug.apk','samples/sketch-host/build/outputs/apk/androidTest/debug/sketch-host-debug-androidTest.apk'):
 print(call('install','-r',str(root/p)).decode())
try:
 result=call('shell','am','instrument','-w','dev.notecore.sample.sketch.test/dev.notecore.sample.sketch.SketchInstrumentation')
 (out/'instrumentation.log').write_bytes(result);print(result.decode())
 assert b'INSTRUMENTATION_RESULT: status=passed' in result and b'INSTRUMENTATION_CODE: -1' in result, 'Instrumented acceptance did not pass'
finally:
 after=snapshot();(out/'notecore-after.json').write_text(json.dumps(after,indent=2)+'\n',encoding='utf-8');assert before==after
for name,digest in inputs.items(): assert hashlib.sha256((root/name).read_bytes()).hexdigest()==digest, ('Changed during sample run',name)
assert hashlib.sha256(Path(__file__).read_bytes()).hexdigest()==runner_sha
remote=call('shell','pm','path','dev.notecore.sample.sketch').decode().strip().removeprefix('package:')
assert re.fullmatch(r'/data/app/[a-zA-Z0-9_./=+~:-]+/base.apk',remote)
installed=hashlib.sha256(call('exec-out','cat',remote)).hexdigest()
assert installed==hashlib.sha256((root/'samples/sketch-host/build/outputs/apk/debug/sketch-host-debug.apk').read_bytes()).hexdigest()
call('shell','am','force-stop','dev.notecore.sample.sketch')
launched=call('shell','am','start','-W','-n','dev.notecore.sample.sketch/dev.notecore.sample.sketch.SketchActivity')
(out/'ordinary-launch.log').write_bytes(launched); assert b'Status: ok' in launched
ordinary_pid=call('shell','pidof','dev.notecore.sample.sketch').decode().strip();assert re.fullmatch(r'\d+',ordinary_pid)
assert snapshot()==before
device={'ordinary_launch_pid':int(ordinary_pid),'ordinary_sample_relaunched':True,'installed_sample_apk_sha256':installed,'runner_sha256':runner_sha,'api':int(call('shell','getprop','ro.build.version.sdk').decode().strip()), 'avd':args.avd, 'serial':args.serial}
(out/'device.json').write_text(json.dumps(device,indent=2)+'\n',encoding='utf-8')
print(f"NoteCore original APK and all {len(before['files'])} current notes unchanged")
