#!/usr/bin/env python3
"""Opt-in local development writes: scanned upload, note, Redis job; then persistence checks.
Use only the isolated development stack. No real model calls are authorized by this test.
"""
import argparse,base64,hashlib,http.client,json,os,time,uuid
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--stage',choices=['submit','finish','persisted'],required=True)
p.add_argument('--state',default='.runtime/evidence/stack-state.json')
p.add_argument('--port',type=int,default=8080)
a=p.parse_args()
password=os.environ.get('STACK_PASSWORD')
if not password: p.error('Set STACK_PASSWORD in environment (not logged)')
conn=http.client.HTTPConnection('127.0.0.1',a.port,timeout=40)
token=''
def req(method,path,body=None,ctype='application/json',auth=True,expect=200):
 headers={'Content-Type':ctype}
 if auth and token: headers['Authorization']='Bearer '+token
 if body is not None and not isinstance(body,bytes): body=json.dumps(body,ensure_ascii=False).encode()
 conn.request(method,path,body,headers);r=conn.getresponse();b=r.read()
 if r.status!=expect: raise AssertionError(f'{method} {path}: HTTP{r.status}, expected{expect}: '+b[:300].decode(errors='replace'))
 if ctype=='application/octet-stream':return b
 return json.loads(b) if b else {}
req('GET','/api/v1/dashboard/summary',auth=False,expect=401)
token=req('POST','/api/v1/auth/login',{'username':os.environ.get('STACK_USERNAME','admin'),'password':password},auth=False)['data']['token']
statefile=Path(a.state); statefile.parent.mkdir(parents=True,exist_ok=True)
if a.stage=='submit':
 models=req('GET','/api/v1/models')['data']
 model=next(m for m in models if m['taskType']=='LICENSE_PLATE' and m['status']=='ACTIVE')
 # This request only targets a stack explicitly launched with MODEL_MODE=demo.
 if os.environ.get('MODEL_MODE')!='demo':raise AssertionError('MODEL_MODE must be explicitly demo for this local smoke test')
 png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')
 boundary='test-'+uuid.uuid4().hex
 body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="stack-check.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+png+f'\r\n--{boundary}--\r\n'.encode())
 file=req('POST','/api/v1/files',body,'multipart/form-data; boundary='+boundary)['data']
 assert file['scanStatus']=='CLEAN',file['scanStatus']
 note=req('POST','/api/v1/notes',{'title':'本地持久化验证 '+uuid.uuid4().hex[:8],'body':'数据库完整性：中文与emoji 🧪；重启后应保留。','tags':'local-smoke,persistence'})['data']
 task=req('POST','/api/v1/inference/tasks',{'fileId':file['id'],'modelId':model['id'],'taskType':'LICENSE_PLATE','enhancementEnabled':True})['data']
 assert task['status']=='PENDING',task['status']
 state={'file_id':file['id'],'sha256':hashlib.sha256(png).hexdigest(),'note_id':note['id'],'note_body':note['body'],'task_id':task['id'],'scan_status':file['scanStatus'],'storage_backend':file.get('storageBackend'),'initial_task_status':task['status'],'mode':'DEMO','stages':['submitted_with_worker_stopped']}
else:
 state=json.loads(statefile.read_text())
 for n in range(40 if a.stage=='finish' else 1):
  task=req('GET','/api/v1/inference/tasks/'+state['task_id'])['data']
  if task['status'] in ['COMPLETED','FAILED']: break
  time.sleep(.5)
 assert task['status']=='COMPLETED',task
 assert task['baselineResult']['adapter']=='DEMO'
 assert req('GET','/api/v1/notes/'+state['note_id'])['data']['body']==state['note_body']
 data=req('GET','/api/v1/files/'+state['file_id']+'/content',ctype='application/octet-stream')
 assert hashlib.sha256(data).hexdigest()==state['sha256']
 state['final_task_status']=task['status'];state['stages'].append(a.stage)
statefile.write_text(json.dumps(state,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'stage':a.stage,'status':'PASS','file_id':state['file_id'],'task_id':state['task_id'],'virus_scan':'CLEAN','model':'DEMO','storage_backend':state['storage_backend']},ensure_ascii=False))
conn.close()
