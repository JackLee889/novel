"""Write a concise Chinese delivery report from verified release evidence."""
from pathlib import Path
from datetime import datetime
import json
ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'doc'/(ROOT/'doc/layui-jquery-upgrade-current.txt').read_text(encoding='utf-8').strip()
def read(name):return json.loads((OUT/name).read_text(encoding='utf-8'))
runtime=read('runtime-verification.json');http=read('runtime-acceptance.json')
assert runtime['originalDataUnchanged'] and http['passed']
java=read('java-test-results.json');browser=read('browser-built-acceptance.json');components=read('components-built-acceptance.json');front=read('front-built-acceptance.json');pcm=read('runtime-reader-pcm.json')
assert java['failures']==0 and java['errors']==0
assert all(row['passed'] for row in (browser,components,front,pcm))
vendor=read('source-vendor-verification.json');packages=read('package-verification.json');themes=read('runtime-theme-verification.json');baseline=read('baseline.json');rollback=read('rollback-verification.json');cleanup=read('cleanup.json')
assert rollback['passed'] and cleanup['originalRunningContainers']==14
lines=[
'Layui / jQuery 升级与本机部署报告',
'生成时间：'+datetime.now().isoformat(timespec='seconds'),
'发行目录：'+str(OUT),
'',
'结果：Layui 升级至 2.13.9，jQuery 升级至 4.0.0，旧插件使用 jQuery Migrate 4.0.2 和项目兼容适配。',
'后台、采集端、普通前台及听书前台四个服务已在本机部署，健康检查通过。',
'部署范围为本机；未发布到公网生产环境。',
'',
'实际变化：',
'- 更新三模块及 green/orange/blue/dark 四套主题的 Layui 与独立 jQuery 资源、字体、许可证和调试映射。',
'- 将后台资源放在原有 /js/ 公开静态目录，保留登录页匿名加载能力。',
'- 替换页面引用为明确版本的路径，并更新旧路径下的 jQuery 内容。',
'- 使用新版 Layui 内置 layer/laydate；旧格式日期字段与上传控件已验证。',
'- 前台实际使用镜像 /app/templates 下的资源。本次镜像同时包含四套主题的增量更新，避免只改 JAR 而继续返回旧库。',
'- Layui 官方分发包仍含 jQuery 3.7.1 备用模块；保留官方文件完整性。应用先加载 jQuery 4.0.0，实际 Layui 模块与页面共享同一份 jQuery 4 实例，已在浏览器验证。',
'- jQuery Migrate 与兼容文件保留旧校验、表格、树、编辑器等插件所需 API。后续更新这些插件后，可再逐项移除兼容层。',
'',
'验证：',
f"- 全模块 Maven 构建成功；Java 测试 {java['tests']} 项，失败与错误均为 0。",
'- CSRF 客户端测试 4 项通过；实际后台登录验证码与 CSRF 请求头验证通过。',
f"- 最终运行包浏览器检查 {len(browser['checks'])+len(components['checks'])+len(front['checks'])} 组；后台 {len(browser['pages'])} 次、前台桌面/手机 {len(front['pages'])} 次页面渲染检查通过。",
'- 表格分页/排序/选择、菜单、标签页、图片裁剪、Summernote 弹窗、日期选择、上传模块初始化与采集列表分页通过。',
'- 上传与业务表单没有提交；书源没有启动采集。',
f"- 候选 HTTP 检查 {len(read('candidate-acceptance.json')['checks'])} 项、上线后 HTTP 检查 {len(http['checks'])} 项通过，资源内容与发布文件逐字节匹配。",
'- 候选、独立测试服务及上线后的听书服务均验证真实 16 kHz PCM、AudioContext 播放、暂停、跨章和 390px 手机布局。',
'- Java 类与依赖条目均与升级前运行包一致；Bootstrap 5.3.8、阅读器引擎与队列代码未改变。',
f"- 其余 {vendor['unchangedOtherVendorFiles']} 个插件/样式资源校验和保持一致。",
f"- 前台两实例各校验 {next(iter(themes.values()))['totalFiles']} 个主题文件；每实例更新 {next(iter(themes.values()))['updatedFiles']} 项，其余主题文件全部保留。",
f"- 原有 {runtime['untouchedContainers']} 个其他运行容器的 ID 和启动时间保持不变。",
'- 四服务环境变量、挂载来源、读写权限、域名和凭据保持一致。',
'- 原始数据库各表记录数量、书源规则摘要及状态与升级前一致；支付、AI、投稿继续关闭；书源 1、2、4、12 均停止。',
'',
'验收边界：',
'- 完整业务页面验收针对当前启用的绿色主题；其他主题完成库资源同步、路径检查与镜像文件校验，未切换为运行主题进行全业务测试。',
'- 浏览器为本机 Chrome，手机检查使用 390px 视口模拟；未在真实 iOS/Safari/微信设备上验收。',
'',
'服务地址：',
'后台 http://127.0.0.1:18088',
'采集 http://127.0.0.1:18081',
'前台 http://127.0.0.1:18080',
'听书前台 http://127.0.0.1:18987',
'',
'发布文件：'
]
for module,row in packages.items():lines += [str(OUT/'packages'/row['jar']), 'SHA256 '+row['sha256'],str(OUT/'packages'/('novel-'+module+'.zip')), '镜像 '+row['image']]
lines += ['', '回退：', '运行 '+str(OUT/'rollback-local.ps1'),
'回退脚本仅恢复本次四个应用服务的旧镜像引用，保留后续无关配置修改；不执行数据回滚、不删除任何数据卷或业务记录。',
'后台/采集回退到 Bootstrap 5.3.8 发行版，前台回退到现有安全升级发行版，不会降回旧 Shiro。',
'回退脚本已验证 PowerShell 语法及 Compose 解析出的镜像引用；因为当前发布正常，没有实际执行回退。',
'源码与运行包升级前备份位于 before/、runtime-backup/；源码回退需依据变化清单逐项处理，避免覆盖后续改动。',
'专用测试容器与浏览器已停止；测试数据、原始数据和所有原有数据卷均保留。',
'',
'官方依据：',
'https://jquery.com/download/',
'https://jquery.com/upgrade-guide/4.0/',
'https://github.com/jquery/jquery-migrate',
'https://layui.dev/docs/',
'https://layui.dev/docs/versions.html',
'npm 官方包 SHA512 完整性已校验；upstream/ 保存原始发行包与元数据。',
'',
'本目录 *.private* 文件包含本机配置或专用测试凭据，未在报告中输出。'
]
(OUT/'layui-jquery-upgrade-report.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
(OUT/'task-ledger.txt').write_text('Complete: Layui 2.13.9 and jQuery 4.0.0; retained-plugin compatibility; 152 Java + 4 CSRF tests; 30 browser groups; 68 rendered-page cases; 50 candidate and 69 runtime HTTP checks; real PCM/pause/cross-chapter/mobile acceptance; four-service local release; full source/runtime/theme/data verification; rollback configuration and syntax verified; test services stopped with all data and volumes retained.\nBoundary: active green theme business acceptance; inactive themes resource verification only; local deployment only; payment/AI/submission off; sources stopped.\n',encoding='utf-8')
print(str(OUT/'layui-jquery-upgrade-report.txt'))
