"""Encrypted, consistent cold snapshots of this Compose installation. See COMPOSE_BACKUP.md.

Never restores over existing volumes. No Docker socket is exposed to the web app.
"""
import argparse
from datetime import datetime, timezone
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

MAGIC = b'PKB-BACKUP-1\n'
VOLUMES = {'mysql': '/var/lib/mysql', 'redis': '/data', 'minio': '/data', 'training': '/data'}
ENV_NAMES = {'mysql': 'mysql_data', 'redis': 'redis_data', 'minio': 'object_data', 'training': 'training_data'}


def run(args, **kwargs):
    result = subprocess.run(args, stderr=subprocess.PIPE, **kwargs)
    if result.returncode:
        raise RuntimeError('Command failed: ' + ' '.join(args[:3]) + ' (private diagnostics suppressed)')
    return result


def docker_json(*args):
    return json.loads(run(['docker', *args], stdout=subprocess.PIPE).stdout)


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()


def password(confirm=False):
    value = os.environ.get('PKB_BACKUP_PASSWORD') or getpass.getpass('Backup password (at least 12 characters): ')
    if len(value) < 12: raise ValueError('Use at least 12 characters')
    if confirm and not os.environ.get('PKB_BACKUP_PASSWORD') and getpass.getpass('Repeat password: ') != value:
        raise ValueError('Passwords do not match')
    return value.encode()


def crypt(source, target, secret, decrypt=False):
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.primitives.kdf.scrypt import Scrypt
    with open(source, 'rb') as src, open(target, 'xb') as dst:
        if decrypt:
            if src.read(len(MAGIC)) != MAGIC: raise ValueError('Invalid archive format')
            salt, nonce = src.read(16), src.read(12)
            remaining = Path(source).stat().st_size - len(MAGIC) - 16 - 12 - 16
            if remaining < 0: raise ValueError('Incomplete archive')
            src.seek(-16, 2); tag = src.read(16); src.seek(len(MAGIC)+28)
        else:
            salt, nonce, tag = os.urandom(16), os.urandom(12), None
            dst.write(MAGIC+salt+nonce); remaining = Path(source).stat().st_size
        key = Scrypt(salt=salt, length=32, n=2**15, r=8, p=1).derive(secret)
        cipher = Cipher(algorithms.AES(key), modes.GCM(nonce, tag))
        transform = cipher.decryptor() if decrypt else cipher.encryptor()
        transform.authenticate_additional_data(MAGIC+salt+nonce)
        while remaining:
            chunk = src.read(min(1024*1024, remaining))
            if not chunk: raise ValueError('Incomplete archive')
            remaining -= len(chunk); dst.write(transform.update(chunk))
        dst.write(transform.finalize())
        if not decrypt: dst.write(transform.tag)
    os.chmod(target, 0o600)


def snapshot(root, destination, secret):
    destination = destination.resolve()
    if destination.exists(): raise ValueError('Destination already exists')
    destination.parent.mkdir(parents=True, exist_ok=True)
    config = json.loads(run(['docker','compose','config','--format','json'], cwd=root, stdout=subprocess.PIPE).stdout)
    ids = run(['docker','compose','ps','-aq'], cwd=root, stdout=subprocess.PIPE).stdout.decode().split()
    if not ids: raise ValueError('Create the installation before taking a backup')
    containers = docker_json('inspect', *ids)
    by_service = {c['Config']['Labels'].get('com.docker.compose.service'): c for c in containers}
    if not set(VOLUMES).issubset(by_service): raise ValueError('Database, object storage, Redis and training containers are required')
    running = [c['Id'] for c in containers if c['State']['Running']]
    helper = by_service['backend']['Image']
    # Verify tooling before any downtime.
    run(['docker','run','--rm','--network','none','--entrypoint','tar',helper,'--version'],stdout=subprocess.DEVNULL)
    manifest = {'format':1,'createdAt':datetime.now(timezone.utc).isoformat(),'files':{},'images':{k:v['Config']['Image'] for k,v in by_service.items()},'project':config['name']}
    with tempfile.TemporaryDirectory(prefix='pkb-backup-',dir=destination.parent) as temp:
        stage=Path(temp);os.chmod(stage,0o700)
        try:
            if running: run(['docker','stop','-t','60',*running],stdout=subprocess.DEVNULL)
            for service,mount in VOLUMES.items():
                volume=next((m['Name'] for m in by_service[service]['Mounts'] if m['Destination']==mount and m['Type']=='volume'),None)
                if not volume: raise ValueError('Expected named volume missing: '+service)
                path=stage/(service+'.tar.gz')
                with path.open('xb') as out:
                    run(['docker','run','--rm','--network','none','--read-only','--user','0','--mount',f'type=volume,source={volume},target=/snapshot,readonly','--entrypoint','tar',helper,'-C','/snapshot','-czf','-','.'],stdout=out)
                manifest['files'][path.name]={'sha256':digest(path),'bytes':path.stat().st_size}
            env=root/'.env'
            if not env.is_file(): raise ValueError('Original .env is required for credential recovery')
            data=env.read_bytes();(stage/'installation.env').write_bytes(data)
            manifest['files']['installation.env']={'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)}
        finally:
            if running:
                # Preserve the prior running/stopped selection. Dependency startup is retried by Compose health checks.
                run(['docker','start',*running],stdout=subprocess.DEVNULL)
        archive=stage/'payload.zip'
        with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_STORED,allowZip64=True) as z:
            z.writestr('manifest.json',json.dumps(manifest))
            for name in manifest['files']: z.write(stage/name,name)
        partial=destination.with_name(destination.name+'.partial')
        try:
            crypt(archive,partial,secret);partial.rename(destination)
        finally:
            if partial.exists():partial.unlink()
        status=root/'.runtime'/'backups'/'status.json';status.parent.mkdir(parents=True,exist_ok=True)
        status.write_text(json.dumps({'lastSuccess':manifest['createdAt'],'bytes':destination.stat().st_size,'sha256':digest(destination),'components':['MySQL','MinIO','Redis','训练数据','加密配置']}),encoding='utf-8')
    print('Encrypted backup completed; previously running containers restarted.')


def inspect_archive(archive, secret, root, restore_name=None):
    with tempfile.TemporaryDirectory(prefix='pkb-restore-',dir=root/'.runtime') as temp:
        stage=Path(temp);os.chmod(stage,0o700);payload=stage/'payload.zip'
        crypt(archive,payload,secret,decrypt=True)  # Authenticate before reading any archive content.
        with zipfile.ZipFile(payload) as z:
            expected={'manifest.json','installation.env',*(s+'.tar.gz' for s in VOLUMES)}
            if set(z.namelist())!=expected or len(z.namelist())!=len(expected):raise ValueError('Archive members do not match format')
            manifest=json.loads(z.read('manifest.json'))
            if manifest.get('format')!=1:raise ValueError('Unsupported backup version')
            for name in expected-{'manifest.json'}:
                path=stage/name
                with z.open(name) as src,path.open('xb') as dst:
                    import shutil
                    shutil.copyfileobj(src,dst)
                item=manifest['files'][name]
                if path.stat().st_size!=item['bytes'] or digest(path)!=item['sha256']:raise ValueError('Archive integrity check failed')
            if not restore_name:
                print('Password, authentication and all component checksums verified.');return
            if not re.fullmatch(r'pkb-restore-[a-z0-9-]{6,40}',restore_name):raise ValueError('Use a new name: pkb-restore- followed by 6-40 lowercase letters/digits/hyphens')
            destination=root/'.runtime'/restore_name
            if destination.exists():raise ValueError('Restore directory already exists')
            names={s:restore_name+'_'+ENV_NAMES[s] for s in VOLUMES}
            existing=run(['docker','volume','ls','--format','{{.Name}}'],stdout=subprocess.PIPE).stdout.decode().split()
            if any(n in existing for n in names.values()):raise ValueError('Target volumes already exist; no overwrite allowed')
            helper=manifest['images']['backend']
            run(['docker','image','inspect',helper],stdout=subprocess.DEVNULL)
            for s,n in names.items():
                run(['docker','volume','create','--label','pkb.restore='+restore_name,n],stdout=subprocess.DEVNULL)
                with (stage/(s+'.tar.gz')).open('rb') as data:
                    run(['docker','run','--rm','-i','--network','none','--read-only','--user','0','--mount',f'type=volume,source={n},target=/snapshot','--entrypoint','tar',helper,'-C','/snapshot','-xzf','-'],stdin=data,stdout=subprocess.DEVNULL)
            destination.mkdir(mode=0o700)
            (destination/'.env').write_bytes((stage/'installation.env').read_bytes());os.chmod(destination/'.env',0o600)
            # Absolute volume names isolate recovery from the live installation. Matching images preserve cold-volume compatibility.
            override={'services':{s:{'image':im} for s,im in manifest['images'].items()},'volumes':{ENV_NAMES[s]:{'name':n,'external':True} for s,n in names.items()}}
            (destination/'override.json').write_text(json.dumps(override,indent=2),encoding='utf-8')
            print('Restored into NEW volumes. Configuration directory: '+str(destination))
            print('Set a different WEB_PORT in the restored .env before starting the isolated Compose project. See docs/COMPOSE_BACKUP.md.')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['backup','verify','restore']);parser.add_argument('archive',type=Path)
    parser.add_argument('--restore-name');parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2])
    args=parser.parse_args();root=args.root.resolve();(root/'.runtime').mkdir(exist_ok=True)
    import cryptography  # Fail before downtime if the optional backup dependency is not installed.
    secret=password(args.action=='backup')
    if args.action=='backup':snapshot(root,args.archive,secret)
    else:
        if args.action=='restore' and not args.restore_name:parser.error('--restore-name is required')
        inspect_archive(args.archive.resolve(),secret,root,args.restore_name if args.action=='restore' else None)


if __name__=='__main__':
    try:main()
    except KeyboardInterrupt:print('Interrupted; check container health before continuing.');raise SystemExit(130)
    except Exception as error:print('Backup operation failed: '+type(error).__name__+'. No existing volumes were overwritten.');raise SystemExit(1)
