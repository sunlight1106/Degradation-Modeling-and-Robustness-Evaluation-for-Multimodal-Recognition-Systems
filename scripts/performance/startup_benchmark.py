#!/usr/bin/env python3
"""Serial, alternating warm API-start benchmark on a migrated isolated rv_perf_* database.
Dependencies must already run. Authentication, migrations and schema validation settings
are explicit and matched; timing ends when HTTP health and an actual login succeed.
"""
import argparse,json,os,subprocess,time,http.client,statistics,urllib.parse
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--before',required=True);p.add_argument('--after',required=True)
p.add_argument('--output',required=True);p.add_argument('--runs',type=int,default=3)
p.add_argument('--port',type=int,default=8082);a=p.parse_args()
url=os.environ.get('DB_URL','')
if '/rv_perf_' not in url:p.error('Use an isolated rv_perf_* DB_URL only')
if not os.environ.get('BOOTSTRAP_ADMIN_PASSWORD'):p.error('Provide BOOTSTRAP_ADMIN_PASSWORD through environment')
env=dict(os.environ);env.update(SERVER_PORT=str(a.port),SERVER_ADDRESS='127.0.0.1',APP_BOOTSTRAP_ENABLED='false',WORKER_ENABLED='false',STORAGE_MODE='filesystem',ANTIVIRUS_ENABLED='false',SPRING_FLYWAY_ENABLED='false',SPRING_JPA_HIBERNATE_DDL_AUTO='validate')
# Original repository mappings cannot validate their own CHAR schema; record the
# required original override explicitly, never silently claim parity on that fix.
results=[];out=Path(a.output);out.parent.mkdir(parents=True,exist_ok=True)
for run in range(a.runs):
 for label,jar in [('before',a.before),('after',a.after)]:
  runenv=dict(env)
  if label=='before':runenv['SPRING_JPA_HIBERNATE_DDL_AUTO']='none'
  log=out.parent/f'startup-{label}-{run}.log'
  with log.open('wb') as f:
   start=time.perf_counter();proc=subprocess.Popen(['java','-jar',str(Path(jar).resolve())],env=runenv,stdout=f,stderr=subprocess.STDOUT)
   try:
    while time.perf_counter()-start<90:
     if proc.poll() is not None:raise RuntimeError(f'{label} exited; inspect{log}')
     try:
      conn=http.client.HTTPConnection('127.0.0.1',a.port,timeout=2)
      conn.request('GET','/actuator/health');r=conn.getresponse();body=r.read()
      if r.status==200:
       payload=json.dumps({'username':'admin','password':env['BOOTSTRAP_ADMIN_PASSWORD']})
       conn.request('POST','/api/v1/auth/login',payload,{'Content-Type':'application/json'})
       r=conn.getresponse();raw=r.read()
       if r.status==200 and json.loads(raw).get('data',{}).get('token'):
        elapsed=time.perf_counter()-start;break
      conn.close()
     except (OSError,http.client.HTTPException,ValueError):pass
     time.sleep(.1)
    else:raise RuntimeError(f'{label} did not become usable within90s')
    results.append({'label':label,'run':run+1,'ready_seconds':round(elapsed,3),'log':str(log)})
    print(label,run+1,round(elapsed,3),flush=True)
   finally:
    proc.terminate()
    try:proc.wait(timeout=20)
    except subprocess.TimeoutExpired:proc.kill();proc.wait()
  time.sleep(1)
summary={label:{'median_seconds':round(statistics.median(r['ready_seconds'] for r in results if r['label']==label),3),'min_seconds':min(r['ready_seconds'] for r in results if r['label']==label),'max_seconds':max(r['ready_seconds'] for r in results if r['label']==label)} for label in ['before','after']}
out.write_text(json.dumps({'method':'alternating warm API process restarts; same live MySQL/Redis; health+real BCrypt login readiness; no downloads/bootstrap/migrations; original needs ddl-auto=none due confirmed mapping bug, optimized validates','samples':results,'summary':summary},indent=2)+'\n')
print(json.dumps(summary))
