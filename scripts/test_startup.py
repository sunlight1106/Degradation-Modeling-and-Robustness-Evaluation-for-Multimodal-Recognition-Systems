import importlib.util, threading, tempfile, unittest, urllib.request, urllib.error
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('startup',Path(__file__).with_name('startup.py'))
startup=importlib.util.module_from_spec(spec);spec.loader.exec_module(startup)
class LauncherSecurityTest(unittest.TestCase):
 def setUp(self):
  self.server=startup.ThreadingHTTPServer(('127.0.0.1',0),startup.Handler);self.url=f'http://127.0.0.1:{self.server.server_port}'
  self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
 def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join(2)
 def code(self,path,headers=None,data=None):
  request=urllib.request.Request(self.url+path,headers=headers or {},data=data)
  try:
   with urllib.request.urlopen(request,timeout=3) as response:return response.status
  except urllib.error.HTTPError as error:
   code=error.code;error.close();return code
 def test_host_and_origin_protect_startup_controls(self):
  with patch.object(startup,'launch',return_value=True) as launch:
   self.assertEqual(self.code('/',{'Host':'attacker.example'}),403)
   self.assertEqual(self.code('/retry',{'Origin':'https://attacker.example','X-Launcher-Token':startup.TOKEN},b''),403)
   self.assertEqual(self.code('/retry',{'Origin':self.url,'X-Launcher-Token':'wrong'},b''),403)
   self.assertEqual(self.code('/retry'),404);launch.assert_not_called()
   self.assertEqual(self.code('/retry',{'Origin':self.url,'X-Launcher-Token':startup.TOKEN},b''),202);launch.assert_called_once()
 def test_status_does_not_expose_control_token(self):
  with tempfile.TemporaryDirectory() as folder,patch.object(startup,'RUNTIME',Path(folder)):
   startup.write_status('Synthetic startup')
   with urllib.request.urlopen(self.url+'/status',timeout=3) as response:self.assertNotIn(startup.TOKEN,response.read().decode())
if __name__=='__main__':unittest.main()
