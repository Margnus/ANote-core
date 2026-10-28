#!/usr/bin/env python3
"""Verify retained, explicitly authorized ADB ordinary-App observations. Never sends input or infers Windows/physical acceptance."""
import hashlib,json,collections,xml.etree.ElementTree as ET
from pathlib import Path
from verify_ios_preview_ui import document
ROOT=Path(__file__).resolve().parents[1]
CASE=ROOT/'reports/android-authorized-adb-ui'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def load(name):return json.loads((CASE/name).read_text(encoding='utf-8'))
def snapshot(label):
 paths=list(CASE.glob(label+'-*.ncp'));assert len(paths)==1,label
 return paths[0],document(paths[0])
def ui(label):return list(ET.fromstring((CASE/(label+'.xml')).read_bytes()).iter('node'))
def texts(label):return [n.get('text','') for n in ui(label)]
import struct,zlib
from pathlib import Path
def read(path):
 b=Path(path).read_bytes();pos=8;data=b''
 while pos<len(b):
  n=struct.unpack('>I',b[pos:pos+4])[0];kind=b[pos+4:pos+8];chunk=b[pos+8:pos+8+n];pos+=n+12
  if kind==b'IHDR':w,h,depth,color,*_=struct.unpack('>IIBBBBB',chunk);assert depth==8 and color in (2,6)
  if kind==b'IDAT':data+=chunk
 raw=zlib.decompress(data);channels=4 if color==6 else 3;stride=w*channels;last=[0]*stride;rows=[];pos=0
 for y in range(h):
  f=raw[pos];pos+=1;row=list(raw[pos:pos+stride]);pos+=stride
  for x in range(stride):
   a=row[x-channels] if x>=channels else 0;up=last[x];c=last[x-channels] if x>=channels else 0
   if f==1:v=a
   elif f==2:v=up
   elif f==3:v=(a+up)//2
   elif f==4:
    q=a+up-c;v=min((a,up,c),key=lambda z:abs(q-z))
   else:assert f==0;v=0
   row[x]=(row[x]+v)&255
  rows.append(row);last=row
 return w,h,channels,rows
def pixel(image,x,y):
 w,h,c,rows=image;return tuple(rows[y][x*c:x*c+3])

def main():
 case=load('case.json');runtime=load('runtime.json')
 import re
 assert re.fullmatch(r'build/android-authorized-adb-ui-[0-9a-f-]{36}',case['folder'])
 raw_case=ROOT/case['folder']
 assert case['serial']==runtime['serial']=='emulator-5584'
 assert case['avd']==runtime['avd']=='NoteCoreGui35' and runtime['api']=='35'
 assert not case['before'] and runtime['pid']==1800 and not runtime['app_instrumentation_registered']
 expected='85fc37ae0269763c51c4d84dc13d5a26cba32e0d46f6280592b1a8d70f55e54e'
 build=json.loads((ROOT/'reports/preview-build.json').read_text(encoding='utf-8'))
 assert build['status']=='passed' and build['android']['sha256']==expected
 apk=ROOT/build['android']['artifact'];assert sha(apk)==expected==runtime['installed_apk_sha256']==case['apk_sha256']
 bindings=dict(build['source_and_build_input_sha256'])
 for name,value in bindings.items():assert sha(ROOT/name)==value,('build source changed',name)
 actions=[json.loads(line) for line in (raw_case/'actions.jsonl').read_text(encoding='utf-8').splitlines()]
 commands=[a['command'] for a in actions if a['kind']=='adb-system-input']
 assert len([c for c in commands if c[:2]==['input','swipe']])==2
 assert all(c[:2] in (['input','tap'],['input','swipe'],['input','text'],['input','keyevent'],['input','keycombination'],['am','start']) for c in commands)
 assert any(c[:3]==['input','keyevent','3'] for c in commands)
 assert any(c[:2]==['am','start'] for c in commands)
 assert '还没有笔记，写下第一个想法。' in texts('00-library')
 line=snapshot('09-line-one')[1];dot=snapshot('10-dot')[1];undo=snapshot('12-undo-dot')[1];redo=snapshot('13-redo-dot')[1]
 assert [len(x[2]) for x in (line,dot,undo,redo)]==[1,2,1,2]
 assert line[2][0]==undo[2][0]
 # Undo deletion/redo restoration advances the object's existing epoch; samples/identity/brush stay exact.
 for old,new in zip(dot[2],redo[2]):
  assert old[0][:3]+old[0][4:]==new[0][:3]+new[0][4:] and old[1]==new[1]
 assert dot[2][1][0][3]=='1' and redo[2][1][0][3]=='3'
 final_path,final=snapshot('26-reopened-page-two');root,pages,strokes=final
 assert root[2]=='ADB-UI-20261028' and int(root[5])==7 and len(pages)==2 and len(strokes)==3
 assert [len(points) for stroke,points in strokes]==[20,2,49]
 assert strokes[:2]==redo[2]
 assert strokes[0][0][2]==strokes[1][0][2]==pages[0][2] and strokes[2][0][2]==pages[1][2]
 assert len(set(s[0][1] for s in strokes))==3
 unchanged=('16-page-two-blue','17-page-one-return','18-zoom-in','19-zoom-out','20-fit-page','21-home','22-foreground','23-explicit-save','24-library-close','25-reopened-page-one','26-reopened-page-two','27-final-library')
 for label in unchanged:assert snapshot(label)[0].read_bytes()==final_path.read_bytes(),label
 assert runtime['files_after']=={root[1]+'.ncp':sha(final_path)}
 assert any(n.get('package')=='com.android.launcher3' for n in ui('21-home'))
 assert '已打开 · 3 笔' in texts('25-reopened-page-one')
 assert '1/2' in texts('25-reopened-page-one') and '2/2' in texts('26-reopened-page-two')
 assert any(n.get('text','').startswith('ADB-UI-20261028') for n in ui('27-final-library'))
 black=tuple((int(strokes[0][0][6])>>shift)&255 for shift in (16,8,0))
 blue=tuple((int(strokes[2][0][6])>>shift)&255 for shift in (16,8,0));white=(255,255,255)
 pixel_checks={}
 def check_pixel(label,x,y,color):
  actual=pixel(read(CASE/(label+'.png')),x,y);assert actual==color,(label,x,y,actual,color)
  pixel_checks[f'{label}@{x},{y}']=actual
 for label in ('10-dot','13-redo-dot','17-page-one-return','25-reopened-page-one'):
  check_pixel(label,600,550,black);check_pixel(label,625,455,black)
 check_pixel('12-undo-dot',600,550,white);check_pixel('12-undo-dot',625,455,black)
 check_pixel('14-page-two-empty',600,550,white)
 for label in ('16-page-two-blue','26-reopened-page-two'):
  check_pixel(label,625,585,blue);check_pixel(label,600,550,white);check_pixel(label,625,455,white)
 check_pixel('18-zoom-in',580,562,black);check_pixel('18-zoom-in',618,420,black)
 for label in ('19-zoom-out','20-fit-page','22-foreground'):check_pixel(label,600,550,black)
 log=(raw_case/'ordinary-render.log').read_text(encoding='utf-8')
 rows=[s for s in log.splitlines() if 'NoteCoreRender:' in s]
 assert rows and all(int(s.split()[2])==runtime['pid'] for s in rows)
 assert any('frame-scheduled ' in s and 'backend=surface-control' in s for s in rows)
 assert any('release-receipt ' in s for s in rows) and 'owner-drained charged=0' in rows[-1]
 counts={event:sum(event in s for s in rows) for event in ('frame-scheduled','release-receipt','source-released','owner-drained')}
 for p in CASE.iterdir():
  if p.is_file():bindings[p.relative_to(ROOT).as_posix()]=sha(p)
 for p in (Path(__file__),ROOT/'tools/verify_ios_preview_ui.py',ROOT/'reports/preview-build.json',apk,raw_case/'actions.jsonl',raw_case/'ordinary-render.log',raw_case/'package-dumpsys.txt',raw_case/'installed-instrumentation.txt',ROOT/'build/adb-ui-authorized-helper.py'):bindings[p.relative_to(ROOT).as_posix()]=sha(p)
 report={'schema':'authorized-android-adb-ui-1','status':'passed','date':'2026-10-28','base_core':'1a5a809','artifact_sha256':expected,
  'device':runtime,'retained_case':CASE.relative_to(ROOT).as_posix(),'input_method':'Explicitly user-authorized ADB system input; read-only uiautomator dump and device screencap',
  'ordinary_app_process_ui_verified':True,'ordinary_pid':runtime['pid'],'windows_gui_verified':False,'physical_device_verified':False,'real_stylus_verified':False,
  'two_page_normal_close_reopen_verified':True,'home_foreground_verified':True,'single_page_zoom_controls_verified':True,'undo_redo_preserves_samples_verified':True,
  'final_document_sha256':sha(final_path),'final_document_revision':int(root[5]),'stroke_count':len(strokes),'real_sample_counts':[len(points) for stroke,points in strokes],
  'original_file_count':len(case['before']),'original_files_preserved':True,'new_test_note_retained':True,'no_app_instrumentation_used':True,
  'pixel_witnesses':pixel_checks,'ordinary_render_events':counts,'ordinary_owner_drained_log_observed':True,'direct_valid_fence_observation':False,
  'process_kill_tested':False,'power_loss_verified':False,'multi_touch_verified':False,'rotation_verified':False,'heap_or_latency_measured':False,'c1_gate_complete':False,
  'observation_timing':'Stage01 PNG was captured before UI-tree idle and shows the preceding library, while its XML shows the editor; retained but excluded from pixel witnesses. Stage02 onward collects UI-tree idle before screencap. Normal reopen uses the same ordinary PID, not a process-kill scenario.',
  'scope':'Current ordinary APK on isolated API35 AVD: create named note, finger line/dot, undo/redo, second-page blue stroke, page isolation, zoom buttons/fit, Home/foreground, explicit save and normal library close/reopen. Native file bytes and ordinary-PID SurfaceControl schedule/release/drain logs corroborate the displayed frames. ADB system input is distinct from Windows CUA input, physical stylus, multi-touch, power loss and full C1.',
  'source_and_artifact_sha256':bindings}
 (ROOT/'reports/android-authorized-adb-ui.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps({k:report[k] for k in ('status','ordinary_pid','final_document_sha256','stroke_count','real_sample_counts','ordinary_render_events','windows_gui_verified','c1_gate_complete')},ensure_ascii=False))
if __name__=='__main__':main()
