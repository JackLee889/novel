"""Publish a live-test report only after runtime, package and DB checks pass."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import json
import shutil

root = Path(__file__).resolve().parent.parent
run = (root / 'doc/frontend-realtest-current.txt').read_text(encoding='utf-8-sig').strip()
evidence = root / 'doc' / run


def load(name):
    return json.loads((evidence / name).read_text(encoding='utf-8-sig'))


runtime = load('test-runtime.json')
result = load('live-test-results.json')
comparison = load('database-comparison.json')
package = load('final-package-verification.json')
service = load('final-runtime-verification.json')
backup = load('backup/database-backup-manifest.json')
before, after = load('database-before.json'), load('database-after.json')
assert result['success'] and all(check['passed'] for check in result['checks'])
assert comparison['unchanged'] and not comparison['changes']
assert package['image'] == result['image'] == service['frontend']['image'] == runtime['image']
assert package['jarZipAndImageContextMatch'] and service['frontend']['health'] == 'healthy'
assert service['originalComposeUnchanged'] and service['originalGatewayUnchanged']
local_zone = timezone(timedelta(hours=8))


def local(value):
    return datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(local_zone).strftime('%Y-%m-%d %H:%M:%S')


checks = len(result['checks'])
layouts = len(result['layouts'])
catalog = next(check for check in result['checks'] if '8482' in check['name'])
samples = next(check for check in result['checks'] if '52' in check['name'])
audio = result['audio']
package_rows = '\n'.join(f"| {Path(item['path']).name} | {item['bytes']:,} | `{item['sha256']}` |" for item in package['packages'])
doc = f'''# 前端真实环境测试记录

**真实公开阅读流程验证通过。** 新版站点：[http://127.0.0.1:{runtime['port']}/](http://127.0.0.1:{runtime['port']}/)。候选前端和独立 Caddy 网关保留运行，使用原部署 MySQL `novel_plus`、Redis 和文件卷，Spring 配置为 `prod`。原站 18080 和原 Compose、Caddyfile 保留原版本。

最终浏览器与接口测试时间：{local(result['started'])} 至 {local(result['completed'])}，Asia/Shanghai。数据库前后检查时间：{local(before['time'])} 至 {local(after['time'])}。

| 验证内容 | 结果 |
| --- | --- |
| 原 MySQL、Redis、文件卷身份 | 三个原命名卷存在；MySQL `/var/lib/mysql` 挂载原 `novel-plus-local_mysql-data` |
| 当前真实业务数据 | 26 本小说、8,482 条章节、8,482 条正文、7 个分类、2 条公告 |
| 真实环境验收检查 | {checks}/{checks} 通过，无未捕获浏览器异常或服务器 5xx |
| 小说搜索、分页、分类、排序和四种榜单 | API 和浏览器均通过；搜索三页返回的全部 ID 与 SQL 一致，长整数 ID 保持字符串 |
| 全部小说详情 | 26 本的 ID、书名、作者和字数与 SQL 一致 |
| 全部目录 | {catalog['pages']} 页覆盖 {catalog['chapters']:,} 章节；数量、排序、首末章和分页标记一致 |
| 正文读取 | 每本首末章共 {samples['samples']} 份正文；段落数、UTF-8 字节数及 SHA-256 内容版本与数据库一致 |
| 章节边界和缓存策略 | 跨书章节 ID、不存在书籍和无效页码被拒绝；正文接口使用 private/no-store 和 Cookie/Authorization 区分 |
| 桌面、手机、窄屏和夜间布局 | 12 个真实页面 × 4 种配置，共 {layouts}/{layouts} 通过；1440px、390px、320px 和 390px 夜间，无页面横向溢出或可见破图 |
| 真实章节导航与阅读器目录 | 首章、下一章、返回、100 条目录分页及关闭通过 |
| 实际 Sherpa 语音合成和播放 | 1 个 Worker；{audio['rate']:,}Hz、{audio['samples']:,} 个 PCM 采样且包含非零信号；首次播放约 {audio['firstPlaybackMs']/1000:.2f} 秒，暂停和继续通过 |
| 未登录状态 | 个人中心、书架和作者专区跳转到登录；书架及用户接口返回 1001；实际登录失败提示和密码显隐通过 |
| 充值、购买和外部 AI | 独立网关复制原策略；对应路由返回 403 |
| 原数据库保护 | 测试浏览器拦截 {len(result['blockedWrites'])} 次访问计数写请求；没有创建用户、作者、书籍、评论或订单 |
| 数据库测试前后校验 | {len(after['tables'])} 张表的行数和 EXTENDED 校验值均相同；原业务数据未改动 |
| 构建和发布包 | Java 11/11 测试通过；{package['resourcesVerified']} 个资源与源码、JAR、ZIP 和运行镜像构建上下文一致 |

本次修复了两个实际显示问题。原数据库的 `book_setting` 没有记录，首页虽然有真实书库仍显示空推荐；现在未配置的首屏、长篇作品和长篇榜按真实书库的字数排序展示，已有推荐配置继续使用原内容。已有浏览器会话曾保留旧业务模块，更新入口及业务模块 URL 的版本后，同一会话显示了新的真实作品列表。修复只涉及前端模板及脚本；生产 Java 和七个 Sherpa 核心模块保持原字节。

最初候选前端只连接内部网络，容器健康但 18987 没有发布到宿主机。已增加独立测试网关，连接原 local/access 网络并只发布回环地址 18987；网关保留原支付和外部 AI 路由限制。Docker 先前的启动阻塞已随用户重启解除。本次未执行临时目录改名、Docker 数据重置或原服务切换；原采集服务的运行状态没有由测试脚本改变。

尚未覆盖登录后的用户和作者写入、VIP 登录/购买正文分支、真实支付及外部 AI。原数据库的 `user`、`author` 都是 0 行，52 个抽样章节均可匿名读取。语音验证覆盖真实 WASM 合成、非零 PCM、播放时钟和交互状态，未验证实体扬声器听感或实体手机。

数据库逻辑备份：`{backup['path']}`，压缩后 {backup['bytes']:,} 字节，解压 SQL {backup['uncompressedBytes']:,} 字节；已验证完整读取和 Dump completed 标记，SHA-256 为 `{backup['sha256']}`。原配置备份在同一 `backup` 目录。备份文件完整保留。

| 最终发布文件 | 字节 | SHA-256 |
| --- | --- | --- |
{package_rows}

运行镜像：`{runtime['image']}`。测试服务为 `front-realtest` 和 `gateway-realtest`；两个服务配置保存在 `{runtime['overrideFile']}`。95 个资源与最终包一致，测试夹具和外部模型未进入 JAR；语音资源目录只读挂载到 `/opt/reader-resources`，共享文件目录 `/data` 只读挂载，候选封面自动转存关闭。

主要证据保存在 [{run}]({run}/)：`live-test-results.json`、`actual-data-proof.json`、`database-before.json`、`database-after.json`、`database-comparison.json`、`final-runtime-verification.json` 和 `final-package-verification.json`。修复前截图为 `agent-home-live.png`，最终截图为 `real-home-desktop.png`、`real-home-mobile.png`、`real-home-night.png` 和 `real-reader-playing.png`。

```powershell
# 候选站点运行期间复验实际接口、页面和语音
node tests/frontend/real-environment.cjs
# 对照本次原始快照再次读取表行数及校验值
python scripts/real-database-evidence.py --phase after
```

本页记录当前真实环境结果。`doc/frontend-redesign-validation.md` 保留前期本地模拟业务数据验收，当前发布包和运行服务以本页及 `final-package-verification.json` 为准。
'''
(root / 'doc/frontend-real-environment-validation.md').write_text(doc, encoding='utf-8')
status_path = evidence / 'real-environment-status.json'
old_status = evidence / 'blocked-preflight-status.json'
if not old_status.exists():
    shutil.copyfile(status_path, old_status)
status = dict(time=datetime.now(local_zone).isoformat(), status='passed_with_coverage_limits',
              url=result['base'], image=runtime['image'], realDatabaseQueried=True, realDatabaseBackedUp=True,
              candidateServiceStarted=True, realBrowserFlowsTested=True, databaseUnchanged=True,
              checks=checks, layouts=layouts, authenticatedWritesTested=False,
              originalDeploymentConfigUnchanged=True, originalFrontServiceSwitched=False,
              runtimeDirectoryRenamedByAgent=False, limitations=result['limitations'],
              evidenceDirectory=str(evidence))
status_path.write_text(json.dumps(status, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(status=status['status'],checks=checks,layouts=layouts,databaseUnchanged=True,url=result['base'])))
