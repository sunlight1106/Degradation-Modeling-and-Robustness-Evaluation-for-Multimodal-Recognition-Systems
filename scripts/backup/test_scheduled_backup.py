import tempfile, unittest
from pathlib import Path
from scheduled_backup import retain
class RetentionTest(unittest.TestCase):
 def test_only_verified_owned_names_are_removed(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);verified={}
   for i in range(1,5):
    name=f'scheduled-2026100{i}T033000Z.pkb';(root/name).write_bytes(b'test');verified[name]='synthetic'
   (root/'manual.pkb').write_bytes(b'private');(root/'scheduled-20261005T033000Z.pkb').write_bytes(b'unverified')
   retain(root,2,verified)
   self.assertEqual(len(verified),2);self.assertTrue((root/'manual.pkb').exists());self.assertTrue((root/'scheduled-20261005T033000Z.pkb').exists());self.assertFalse((root/'scheduled-20261001T033000Z.pkb').exists())
 def test_invalid_retention_never_deletes(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);file=root/'scheduled-20261001T033000Z.pkb';file.write_bytes(b'test')
   with self.assertRaises(ValueError):retain(root,0,{file.name:'synthetic'})
   self.assertTrue(file.exists())
if __name__=='__main__':unittest.main()
