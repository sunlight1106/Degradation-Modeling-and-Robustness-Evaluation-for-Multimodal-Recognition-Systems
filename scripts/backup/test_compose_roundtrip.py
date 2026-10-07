"""Opt-in Docker rehearsal. Creates and removes only its own uniquely named fixture volumes."""
import json
from pathlib import Path
import tempfile
import uuid
from compose_backup import snapshot, inspect_archive, run, docker_json


def main():
    repo=Path(__file__).resolve().parents[2]
    suffix=uuid.uuid4().hex[:10];project='pkb-backup-test-'+suffix;restored='pkb-restore-'+suffix
    image='robust-vision-backend:local'
    with tempfile.TemporaryDirectory(prefix=project,dir=repo/'.runtime') as temp:
        root=Path(temp).resolve()
        assert root.is_relative_to((repo/'.runtime').resolve())
        (root/'.runtime').mkdir();(root/'.env').write_text('SYNTHETIC_CONFIG=fixture-only\n')
        services={s:{'image':image,'entrypoint':['sleep','infinity'],'user':'0','volumes':[f'{s}_fixture:{mount}']} for s,mount in {'mysql':'/var/lib/mysql','redis':'/data','minio':'/data','training':'/data'}.items()}
        services['backend']={'image':image,'entrypoint':['sleep','infinity']}
        (root/'compose.yaml').write_text(json.dumps({'name':project,'services':services,'volumes':{s+'_fixture':{} for s in ('mysql','redis','minio','training')}}))
        try:
            run(['docker','compose','up','-d','--no-build'],cwd=root,stdout=-1)
            for s,mount in {'mysql':'/var/lib/mysql','redis':'/data','minio':'/data','training':'/data'}.items():
                run(['docker','compose','exec','-T',s,'sh','-c',f'printf synthetic-{s} > {mount}/fixture.txt'],cwd=root,stdout=-1)
            snapshot(root,root/'snapshot.pkb',b'synthetic-archive-password')
            inspect_archive(root/'snapshot.pkb',b'synthetic-archive-password',root)
            inspect_archive(root/'snapshot.pkb',b'synthetic-archive-password',root,restored)
            override=json.loads((root/'.runtime'/restored/'override.json').read_text())
            for key,value in override['volumes'].items():
                data=run(['docker','run','--rm','--network','none','--mount',f'type=volume,source={value["name"]},target=/snapshot,readonly','--entrypoint','cat',image,'/snapshot/fixture.txt'],stdout=-1).stdout
                assert data.startswith(b'synthetic-'),key
            assert (root/'.runtime'/restored/'.env').read_bytes()==(root/'.env').read_bytes()
            print('PASS: all four volumes and configuration recovered; original running containers restarted.')
        finally:
            run(['docker','compose','down','--volumes'],cwd=root,stdout=-1)
            volumes=run(['docker','volume','ls','--filter','label=pkb.restore='+restored,'--format','{{.Name}}'],stdout=-1).stdout.decode().split()
            for volume in volumes:
                assert volume.startswith(restored+'_')
                run(['docker','volume','rm',volume],stdout=-1)

if __name__=='__main__':main()
