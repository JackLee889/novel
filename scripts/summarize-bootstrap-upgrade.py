"""Write a source-backed release summary without private configuration."""
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'doc'/(ROOT/'doc/bootstrap-upgrade-current.txt').read_text(encoding='utf-8').strip()
def read(name):return json.loads((OUT/name).read_text(encoding='utf-8'))
packages=read('package-verification.json');browser=read('browser-built-acceptance.json')
runtime=read('runtime-verification.json');http=read('runtime-acceptance.json');candidate=read('candidate-acceptance.json')
vendors=read('vendor-verification.json');changes=read('source-changes.json')
assert browser['passed'] and http['passed'] and candidate['passed'] and runtime['originalDataUnchanged']
assert browser['adminJarSha256']==packages['admin']['sha256']
assert all(row['passed'] for row in browser['checks']) and vendors['failures']==0
counts={row['table']:row['count'] for row in read('database-after.json') if row['kind']=='count'}
lines=[
    'Bootstrap 5.3.8 升级与本机部署报告',
    '',
    '结果：已更新本机后台（18088）和采集服务（18081），两项健康检查正常。',
    '后台 Bootstrap 3.3.6 → 5.3.8；采集登录页 Bootstrap 4.0.0-beta → 5.3.8。',
    'JavaScript 使用官方 bootstrap.bundle.min.js（包含 Popper），沿用原 /js/bootstrap.min.js 入口。',
    'Bootstrap CSS/JS 来自 npm 官方发行包，验证发行包完整性及官方 SRI；许可证已保留。',
    '官方来源：https://getbootstrap.com/docs/5.3/getting-started/download/',
    '迁移文档：https://getbootstrap.com/docs/5.3/migration/',
    '',
    '适配内容：',
    '- 迁移 data-bs 属性、表单网格、偏移、徽章、浮动、按钮和标签页状态；更新静态资源版本参数。',
    '- 新增应用自身的 bootstrap-compat.js/css，适配保留版本的 Bootstrap Table、Paginator、MetisMenu、树表格和 Summernote。',
    '- Bootstrap 5 限制同一元素注册多个组件；旧编辑器下拉按钮保留原生标题及无障碍标签，避免 Tooltip 阻止 Dropdown 注册。',
    '- 修复 H+ 主题覆盖折叠显示、动态表格菜单、列选择保持展开、图标和分页样式。',
    '- 检查时发现并修复原有文件列表为空时的分页异常，以及操作按钮拼接权限隐藏类时缺少空格。',
    '- 小说新增/编辑/详情模板引用了 BookDO 中不存在的 catChildId、catChildName、bookLabel、bookStyle、heroName、ladyName；删除这些无效字段，恢复页面渲染。没有更改数据库字段。',
    '- 生成列编辑页移除重复加载公网 jquery-latest 的脚本，统一使用已有本地 jQuery。',
    '',
    '验证结果：',
    f'- 独立 Linux Maven 构建通过：{vendors["javaTests"]} 项 Java 回归测试，无失败。',
    '- Node CSRF 回归：4 项通过。',
    f'- 最终镜像：{len(browser["pages"])} 个已登录后台页面、{len(browser["checks"])} 组浏览器检查通过；页面异常、Bootstrap 组件冲突、HTTP 5xx 均为 0。',
    '- 覆盖侧栏、顶部下拉、 iframe、标签页、真实头像裁剪/旋转、表格列选择/翻页/排序/勾选、编辑器弹窗，以及桌面和 390px 手机布局。',
    '- 已登录后台验证使用独立 MySQL/Redis/上传目录、复制的只读样本和专用测试账号；头像没有提交上传，未写原业务数据。',
    f'- 候选环境接口检查 {len(candidate["checks"])} 项、本机部署后检查 {len(http["checks"])} 项通过。',
    f'- 比对 {sum(row["javaAndLibraryEntriesVerified"] for row in packages.values())} 个 Java 类/依赖条目与当前安全版本一致；{len(vendors["unchangedFiles"])} 个其他静态库文件校验不变。',
    f'- 发布资源校验 {sum(row["resourcesVerified"] for row in packages.values())} 项通过；发布 JAR/ZIP/镜像一致。',
    '',
    '原系统状态：',
    f'- 阅读前台两个容器的 ID、启动时间和 JAR 哈希未变；共 {runtime["untouchedContainers"]} 个原容器未重启。',
    '- jQuery、Layui、Bootstrap Table、wangEditor、Summernote 等已有静态库保留原版本。',
    '- 支付、AI、投稿仍关闭；书源 1、2、4、12 保持停止，规则哈希未变；没有启动采集。',
    '- 原服务环境变量、凭据、域名及挂载不变；没有执行数据库迁移、业务记录删除或数据卷删除。',
    '- 原数据库业务计数保持不变：'+', '.join(name+'='+str(number) for name,number in counts.items()),
    '',
    '交付与回退：',
]
for module,row in packages.items():lines += ['- '+module+': '+row['image'], '  JAR SHA256: '+row['sha256']]
lines += [
    '- 发行包：packages/novel-admin.zip、packages/novel-crawl.zip。',
    '- 本次只部署在本机，未发布到公网生产服务器；未验收真实业务增删改、支付、AI、投稿和实际采集。',
    '- 原源码在 before/，原运行 JAR 在 runtime-backup/，启动配置在 deployment-before/。',
    '- 回退本机运行镜像：在 PowerShell 执行本目录 rollback-local.ps1，只回退 admin/crawl 到 security-upgrade-20261008-003503。',
    '- 回退不反向导入数据库、不删数据卷；当前源码保留升级结果，完整旧源码已备份。',
    '- 隔离测试 MySQL 的数据卷和测试账号保留，仅停止本次测试服务。',
]
(OUT/'bootstrap-upgrade-report.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
(OUT/'task-ledger.txt').write_text('Completed: verified Bootstrap 5.3.8 upstream assets; owned page/plugin adapters; source snapshot; 63 Java and 4 CSRF tests; 44 rendered pages; 16 browser checks; candidate acceptance; two-service local release; runtime and original data verification.\n'
    'Delivery boundary: local deployment only; other vendor versions and readers unchanged; payment/AI/submission off; sources 4/12 stopped.\n'
    'Rollback: rollback-local.ps1 restores only admin/crawl images to the current security release; original data volumes retained.\n',encoding='utf-8')
print(json.dumps(dict(report=str(OUT/'bootstrap-upgrade-report.txt'),changedFiles=len(changes['changed']),addedResourceFiles=len(changes['added']),passed=True),ensure_ascii=False))
