const http = require('node:http'), fs = require('node:fs'), path = require('node:path');
const root = path.resolve(__dirname, '../..');
const server = http.createServer((req, res) => {
  const route = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  let base, relative;
  if (route.startsWith('/reader/tts/')) { base = path.join(root, 'reader-resources'); relative = route.slice(12); }
  else if (route === '/' || route === '/probe.html') { base = __dirname; relative = 'probe.html'; }
  else { base = path.join(root, 'novel-front/src/main/resources/static'); relative = route; }
  const file = path.resolve(base, '.' + (relative.startsWith('/') ? relative : '/' + relative));
  if (!file.startsWith(base + path.sep)) { res.writeHead(403).end(); return; }
  const types = {'.html':'text/html; charset=utf-8', '.js':'text/javascript; charset=utf-8', '.json':'application/json',
    '.wasm':'application/wasm', '.data':'application/octet-stream'};
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404).end(); return; }
  res.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream');
  res.setHeader('Content-Length', fs.statSync(file).size);
  fs.createReadStream(file).pipe(res);
});
server.listen(18982, '127.0.0.1', () => console.log('Sherpa probe http://127.0.0.1:18982'));
