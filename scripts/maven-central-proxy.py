"""Forward public Maven Central downloads through the host's working TLS connection."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.request import urlopen, Request
from urllib.error import HTTPError
import time
import threading
import requests

CACHE = Path(__file__).resolve().parent.parent / 'doc/security-dependency-upgrade-20261008-003503/central-download-cache'
CACHE.mkdir(exist_ok=True)
locks = {}
mutex = threading.Lock()
clients = threading.local()

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_HEAD(self):
        self.serve_file(True)

    def do_GET(self):
        self.serve_file(False)

    def serve_file(self, head_only):
        if not self.path.startswith('/maven2/') or '?' in self.path:
            self.send_error(404);return
        rel = self.path.removeprefix('/maven2/')
        dest = (CACHE / rel).resolve()
        if not dest.is_relative_to(CACHE.resolve()) or '%' in rel:
            self.send_error(400);return
        with mutex:lock = locks.setdefault(rel, threading.Lock())
        with lock:
            if not dest.is_file():
                error = None
                for retry in range(5):
                    try:
                        if not hasattr(clients, 'session'):clients.session = requests.Session()
                        with clients.session.get('https://repo.maven.apache.org/maven2/' + rel,
                                             headers={'User-Agent': 'novel-security-build/1'}, timeout=(10, 30)) as response:
                            if response.status_code == 404:self.send_error(404);return
                            response.raise_for_status()
                            data = response.content
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        dest.write_bytes(data)
                        error = None
                        break
                    except HTTPError as ex:
                        if ex.code == 404:self.send_error(404);return
                        error = ex
                    except Exception as ex:error = ex
                    time.sleep(min(retry + 1, 3))
                if error is not None:self.send_error(502, 'Central download temporarily unavailable');return
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(dest.stat().st_size))
        self.end_headers()
        if head_only:return
        try:
            with dest.open('rb') as stream:
                while chunk := stream.read(1024 * 1024):self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):pass

    def log_message(self, format, *args):pass

print('Public Maven Central forwarding available on port 19295', flush=True)
ThreadingHTTPServer(('0.0.0.0', 19295), Handler).serve_forever()
