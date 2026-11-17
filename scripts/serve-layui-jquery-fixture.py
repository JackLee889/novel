"""Loopback-only component preview using the actual retained vendor assets."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit
import mimetypes

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc' / (ROOT / 'doc/layui-jquery-upgrade-current.txt').read_text(encoding='utf-8').strip()
STATIC = ROOT / 'novel-admin/src/main/resources/static'
fixture = OUT / 'component-preview.html'
fixture.write_text('''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bootstrap 5 compatibility acceptance</title>
<link href="/css/bootstrap.min.css" rel="stylesheet"><link href="/css/font-awesome.css" rel="stylesheet">
<link href="/css/plugins/bootstrap-table/bootstrap-table.min.css" rel="stylesheet"><link href="/css/plugins/summernote/summernote-0.8.8.css" rel="stylesheet">
<link href="/css/style.css" rel="stylesheet"><link href="/css/bootstrap-compat.css" rel="stylesheet"></head>
<body class="gray-bg"><main class="wrapper wrapper-content"><div class="ibox"><div class="ibox-content">
<h2>Bootstrap 5 / retained plugins</h2><div class="tooltip-demo"><button id="tip" class="btn btn-primary" title="Tooltip OK" data-bs-toggle="tooltip">提示</button></div>
<button id="modal-open" class="btn btn-primary" data-bs-toggle="modal" data-bs-target="#test-modal">打开对话框</button>
<div id="test-modal" class="modal fade" tabindex="-1" aria-labelledby="modal-title"><div class="modal-dialog"><div class="modal-content"><div class="modal-header"><h3 class="modal-title" id="modal-title">对话框</h3><button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="关闭"></button></div><div class="modal-body"><input id="modal-input" class="form-control" aria-label="测试输入"></div></div></div></div>
<div class="alert alert-warning alert-dismissible" id="test-alert">Alert OK<button class="btn-close" data-bs-dismiss="alert" aria-label="关闭提示"></button></div>
<table id="test-table"></table><h3>分页</h3><ul id="pager"></ul><h3>树表格</h3><table id="tree"></table>
<h3>保留的 Summernote 编辑器</h3><div id="editor"></div></div></div></main>
<script src="/js/vendor/jquery/jquery-4.0.0.min.js"></script><script src="/js/vendor/jquery/jquery-migrate-4.0.2.min.js"></script><script src="/js/vendor/jquery/jquery-legacy-compat.js"></script><script src="/js/vendor/layui/layui.js"></script><script src="/js/vendor/layui/layui-compat.js"></script><script src="/js/bootstrap.min.js"></script><script src="/js/bootstrap-compat.js"></script>
<script src="/js/plugins/bootstrap-table/bootstrap-table.js"></script><script src="/js/plugins/bootstrap-table/locale/bootstrap-table-zh-CN.min.js"></script>
<script src="/js/plugins/jqTreeGrid/jquery.treegrid.min.js"></script><script src="/js/plugins/jqTreeGrid/jquery.treegrid.extension.js"></script><script src="/js/plugins/jqTreeGrid/jquery.treegrid.bootstrap3.js"></script>
<script src="/js/bootstrap-paginator.min.js"></script><script src="/js/plugins/summernote/summernote.js"></script><script src="/js/content.js"></script>
<script>
$(function(){
  $('#test-table').bootstrapTable({pagination:true,pageSize:10,pageList:[10,20,50],showColumns:true,showToggle:true,showRefresh:true,search:true,
    columns:[{checkbox:true},{field:'id',title:'ID',sortable:true},{field:'name',title:'名称'},{field:'status',title:'状态'}],
    data:Array.from({length:58},function(_,i){return{id:i+1,name:'测试 '+(i+1),status:i%2?'正常':'停用'};})});
  $('#pager').bootstrapPaginator({bootstrapMajorVersion:3,currentPage:1,totalPages:8,onPageClicked:function(e,o,t,p){window.pagerPage=p;}});
  $('#tree').bootstrapTreeTable({url:'/tree.json',columns:[{field:'name',title:'名称'}],expandColumn:0});
  $('#editor').summernote({height:150});
  window.componentReady=true;
});
</script></body></html>''',encoding='utf-8')

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = unquote(urlsplit(self.path).path)
        if url == '/tree.json':
            data = '[{"menuId":1,"parentId":0,"name":"根节点"},{"menuId":2,"parentId":1,"name":"子节点"}]'.encode('utf-8')
            self.send_response(200); self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data); return
        file = fixture if url in ('/', '/component-preview.html') else (STATIC / url.lstrip('/')).resolve()
        if file != fixture and STATIC.resolve() not in file.parents:
            self.send_error(403); return
        if not file.is_file(): self.send_error(404); return
        data = file.read_bytes()
        self.send_response(200)
        self.send_header('Content-Type',mimetypes.guess_type(str(file))[0] or 'application/octet-stream')
        self.send_header('Cache-Control','no-store')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def log_message(self, *args): pass

print('Bootstrap component preview: http://127.0.0.1:19390',flush=True)
ThreadingHTTPServer(('127.0.0.1',19390),Handler).serve_forever()
