from pathlib import Path
import json,subprocess
root=Path.cwd();case=root/'build/apple-pdf-geometry-20261029';device='7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0';commands=[]
def run(args,label):
 result=subprocess.run(args,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
 (case/(label+'.json')).write_bytes(result.stdout);(case/(label+'.stderr.log')).write_bytes(result.stderr)
 commands.append({'argv':args,'exit_code':result.returncode,'stdout':label+'.json','stderr':label+'.stderr.log'})
 (case/'execution.json').write_text(json.dumps(commands,indent=2)+'\n',encoding='utf-8')
 result.check_returncode()
run(['xcrun','simctl','spawn',device,str(case/'SharedPdfGeometryProbe.kexe'),*json.loads((case/'geometry-inputs.json').read_text(encoding='utf-8'))],'shared-geometry')
(case/'images').mkdir(exist_ok=False)
run(['xcrun','simctl','spawn',device,str(case/'PdfGeometryProbe'),str(case/'shared-geometry.json'),str(case/'images'),*[str(root/p) for p in json.loads((case/'source-pdfs-before.json').read_text(encoding='utf-8'))]],'render')
print('both actual iOS probes exited0;24 PNGs retained')
