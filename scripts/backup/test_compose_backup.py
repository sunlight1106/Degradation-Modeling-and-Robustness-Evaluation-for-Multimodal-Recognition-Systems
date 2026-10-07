import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec=importlib.util.spec_from_file_location('backup',Path(__file__).with_name('compose_backup.py'))
backup=importlib.util.module_from_spec(spec);spec.loader.exec_module(backup)

class EncryptedBackupTest(unittest.TestCase):
    def test_roundtrip_password_tamper_and_component_verification(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'.runtime').mkdir()
            plain=root/'input.zip';encrypted=root/'backup.pkb';decoded=root/'decoded.zip'
            files={name:b'private fixture data' for name in ['installation.env','mysql.tar.gz','redis.tar.gz','minio.tar.gz','training.tar.gz']}
            manifest={'format':1,'files':{name:{'bytes':len(value),'sha256':backup.hashlib.sha256(value).hexdigest()} for name,value in files.items()}}
            with zipfile.ZipFile(plain,'w') as z:
                z.writestr('manifest.json',json.dumps(manifest))
                for name,value in files.items():z.writestr(name,value)
            backup.crypt(plain,encrypted,b'synthetic-long-test-password')
            self.assertNotIn(b'private fixture data',encrypted.read_bytes())
            backup.crypt(encrypted,decoded,b'synthetic-long-test-password',True)
            self.assertEqual(decoded.read_bytes(),plain.read_bytes())
            backup.inspect_archive(encrypted,b'synthetic-long-test-password',root)
            with self.assertRaises(Exception):backup.crypt(encrypted,root/'wrong.zip',b'wrong-password',True)
            damaged=bytearray(encrypted.read_bytes());damaged[-25]^=1;(root/'bad.pkb').write_bytes(damaged)
            with self.assertRaises(Exception):backup.inspect_archive(root/'bad.pkb',b'synthetic-long-test-password',root)

if __name__=='__main__':unittest.main()
