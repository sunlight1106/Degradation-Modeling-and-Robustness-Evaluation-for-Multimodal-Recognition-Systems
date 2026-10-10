"""Loopback-only launcher. The public application cannot control Docker."""
import json, os, secrets, subprocess, threading, time, webbrowser
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.runtime'
TOKEN = secrets.token_urlsafe(32)
lock = threading.Lock()
process = None

def write_status(phase, state='running'):
    data = {'phase': phase, 'state': state, 'updatedAt': time.time()}
    tmp = RUNTIME / 'startup-status.tmp'
    tmp.write_text(json.dumps(data), encoding='utf-8'); tmp.replace(RUNTIME / 'startup-status.json')

def launch():
    global process
    with lock:
        if process and process.poll() is None: return False
        write_status('准备启动')
        log = (RUNTIME / 'startup-ui.log').open('wb')
        process = subprocess.Popen(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'deploy.ps1')],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,creationflags=0x08000000)
        def complete(p):
            code=p.wait(); log.close()
            if code: write_status('启动失败。可重试；详细原因保存在 .runtime/startup-ui.log。','failed')
        threading.Thread(target=complete,args=(process,),daemon=True).start()
        return True

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def send(self, code, payload, content_type='application/json'):
        self.send_response(code); self.send_header('Content-Type',content_type+'; charset=utf-8'); self.send_header('Cache-Control','no-store'); self.send_header('X-Content-Type-Options','nosniff'); self.send_header('Referrer-Policy','no-referrer'); self.end_headers(); self.wfile.write(payload.encode())
    def trusted(self): return self.headers.get('Host') == f'127.0.0.1:{self.server.server_port}'
    def do_GET(self):
        if not self.trusted(): self.send(403,'{}'); return
        if self.path=='/': self.send(200,(ROOT/'scripts/startup.html').read_text(encoding='utf-8').replace('__TOKEN__',TOKEN),'text/html'); return
        if self.path=='/status':
            try: data=json.loads((RUNTIME/'startup-status.json').read_text(encoding='utf-8-sig'))
            except (ValueError,OSError): data={'state':'running','phase':'准备启动'}
            self.send(200,json.dumps(data)); return
        self.send(404,'{}')
    def do_POST(self):
        expected=f'http://127.0.0.1:{self.server.server_port}'
        if not self.trusted() or self.headers.get('Origin')!=expected or self.headers.get('X-Launcher-Token')!=TOKEN: self.send(403,'{}'); return
        if self.path!='/retry' or self.headers.get('Content-Length','0')!='0': self.send(400,'{}'); return
        self.send(202 if launch() else 409,'{}')

def main():
    RUNTIME.mkdir(exist_ok=True)
    # One launcher per installation. Do not terminate another launcher's work.
    import msvcrt
    with (RUNTIME/'startup.lock').open('a+b') as guard:
        guard.seek(0); guard.write(b'0'); guard.flush(); guard.seek(0)
        try: msvcrt.locking(guard.fileno(),msvcrt.LK_NBLCK,1)
        except OSError:
            try:
                import re, urllib.request
                port=int((RUNTIME/'startup-ui-port').read_text()); address=f'http://127.0.0.1:{port}'
                with urllib.request.urlopen(address+'/',timeout=3) as response: page=response.read(40000).decode()
                token=re.search(r"const token='([A-Za-z0-9_-]{43})'",page).group(1)
                request=urllib.request.Request(address+'/retry',data=b'',headers={'Origin':address,'X-Launcher-Token':token},method='POST')
                try: urllib.request.urlopen(request,timeout=3).close()
                except urllib.error.HTTPError as error:
                    if error.code!=409: raise
                webbrowser.open(address+'/')
            except (OSError,ValueError,AttributeError): pass
            return
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler); (RUNTIME/'startup-ui-port').write_text(str(server.server_port))
        launch(); webbrowser.open(f'http://127.0.0.1:{server.server_port}/')
        server.serve_forever()

if __name__=='__main__': main()
