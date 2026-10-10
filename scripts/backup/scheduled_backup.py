"""Run a verified encrypted backup; retain only this scheduler's own archives."""
import argparse, json, os, re
from datetime import datetime, timezone
from pathlib import Path
import compose_backup as backup

PATTERN = re.compile(r'^scheduled-\d{8}T\d{6}Z\.pkb$')

def acquire_deployment_guard(root):
    guard=(root/'.runtime'/'deploy.lock').open('a+b')
    try:
        if os.name=='nt':
            import msvcrt
            guard.seek(0,2)
            if not guard.tell():guard.write(b'0');guard.flush()
            guard.seek(0);msvcrt.locking(guard.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(guard.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
    except Exception:guard.close();raise
    return guard

def retain(directory, keep, verified):
    """A verified manifest owns the files. Never traverse links or arbitrary paths."""
    if not 2 <= keep <= 365: raise ValueError('Retention must be between 2 and 365')
    entries = sorted((p for p in directory.iterdir() if PATTERN.fullmatch(p.name) and not p.is_symlink() and p.is_file() and p.name in verified),key=lambda p:p.name,reverse=True)
    for p in entries[keep:]:
        if p.resolve().parent != directory.resolve(): raise ValueError('Unexpected backup path')
        p.unlink(); verified.pop(p.name,None)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);parser.add_argument('--keep',type=int,default=7)
    args=parser.parse_args();root=args.root.resolve();directory=root/'.runtime'/'backups'/'scheduled';directory.mkdir(parents=True,exist_ok=True)
    if directory.is_symlink(): raise ValueError('Backup directory cannot be a link')
    status=root/'.runtime'/'backups'/'status.json';manifest=directory/'verified.json';secret=backup.password()
    try:
        guard=acquire_deployment_guard(root)
        verified=json.loads(manifest.read_text()) if manifest.exists() else {}
        archive=directory/('scheduled-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'.pkb')
        backup.snapshot(root,archive,secret)
        backup.inspect_archive(archive,secret,root)
        verified[archive.name]=backup.digest(archive)
        # Recheck retained archive hashes before automatic deletion.
        previous_count=len(verified)
        verified={name:sha for name,sha in verified.items() if PATTERN.fullmatch(name) and (directory/name).is_file() and not (directory/name).is_symlink() and backup.digest(directory/name)==sha}
        integrity_warning=len(verified)<previous_count
        retain(directory,args.keep,verified)
        temp=manifest.with_suffix('.tmp');temp.write_text(json.dumps(verified));temp.replace(manifest)
        data=json.loads(status.read_text());data.update({'verified':True,'lastAttempt':datetime.now(timezone.utc).isoformat(),'lastError':None,'retained':len(verified),'scheduled':True,'warning':'旧归档校验未通过或已被移走，请检查备份目录；这些文件没有被自动删除。' if integrity_warning else None});status.write_text(json.dumps(data))
    except Exception:
        data=json.loads(status.read_text()) if status.exists() else {}
        data.update({'lastAttempt':datetime.now(timezone.utc).isoformat(),'lastError':'自动备份失败，请检查本机任务日志和剩余空间。','scheduled':True});status.write_text(json.dumps(data));raise
    finally:
        if 'guard' in locals():guard.close()

if __name__=='__main__': main()
