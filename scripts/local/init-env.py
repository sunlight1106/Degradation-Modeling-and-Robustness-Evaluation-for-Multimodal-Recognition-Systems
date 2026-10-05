#!/usr/bin/env python3
"""Create development-only secrets without overwriting existing credentials."""
import os,secrets,shlex
from pathlib import Path
root=Path(__file__).resolve().parents[2]
runtime=Path(os.environ.get('RUNTIME_DIR',root/'.runtime'))
folder=runtime/'secrets';folder.mkdir(parents=True,exist_ok=True);folder.chmod(0o700)
configs={
 'stack.env': {'JWT_SECRET':secrets.token_hex(32),'CREDENTIAL_MASTER_KEY':secrets.token_hex(32),'DB_PASSWORD':secrets.token_hex(24),'MYSQL_ROOT_PASSWORD':secrets.token_hex(24),'BOOTSTRAP_ADMIN_PASSWORD':secrets.token_hex(16),'DB_USERNAME':'robust_local','BOOTSTRAP_ADMIN_USERNAME':'admin','BOOTSTRAP_TEST_PASSWORD':'','RESET_TEST_PASSWORD':'false','MODEL_MODE':'demo'},
 'components.env': {'MINIO_ROOT_USER':'robust-local','MINIO_ROOT_PASSWORD':secrets.token_hex(24),'S3_ENDPOINT':'http://127.0.0.1:9000','S3_BUCKET':'personal-platform'}}
for name,values in configs.items():
 p=folder/name
 try:
  fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
 except FileExistsError:continue
 with os.fdopen(fd,'w') as f:f.write(''.join(k+'='+shlex.quote(v)+'\n' for k,v in values.items()))
 print('Created '+str(p)+' (values not printed)')
