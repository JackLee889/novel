"""Record source changes and verify the frontend release against this task's backup."""
from pathlib import Path
import hashlib
import json
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc/frontend-evidence'
BASE = ROOT / (OUT / 'baseline-path.txt').read_text(encoding='utf-8-sig').strip()


def digest_stream(stream):
    h = hashlib.sha256()
    while chunk := stream.read(1024 * 1024):
        h.update(chunk)
    return h.hexdigest()


def digest(path):
    with path.open('rb') as stream:
        return digest_stream(stream)


def load(name):
    return json.loads((OUT / name).read_text(encoding='utf-8-sig'))


baseline = json.loads((BASE / 'baseline-sha256.json').read_text(encoding='utf-8-sig'))
before = {row['path'].replace('\\', '/'): row for row in baseline}
changes = []
for name, row in before.items():
    path = ROOT / name
    after = digest(path) if path.exists() else None
    if after != row['sha256']:
        changes.append({'path': name, 'status': 'modified' if after else 'missing',
                        'before': row['sha256'], 'after': after})
for folder in ['novel-front/src', 'templates/green']:
    for path in (ROOT / folder).rglob('*'):
        if path.is_file() and path.relative_to(ROOT).as_posix() not in before:
            changes.append({'path': path.relative_to(ROOT).as_posix(), 'status': 'added',
                            'after': digest(path), 'bytes': path.stat().st_size})
assert not any(row['status'] == 'missing' for row in changes), 'An original file is missing.'
assert not any(row['path'].startswith('novel-front/src/main/java/') for row in changes), 'Production Java changed.'
assert not any('/config/' in row['path'] or row['path'].endswith('.yml') for row in changes), 'Production configuration changed.'
for name in ['engine.js', 'scheduler.js', 'audio-player.js', 'sherpa.worker.js', 'controller.js', 'segmenter.js', 'chapter-repository.js']:
    relative = 'novel-front/src/main/resources/static/javascript/reader/' + name
    assert digest(ROOT / relative) == before[relative]['sha256'], 'Sherpa runtime changed: ' + name
pom_path = ROOT / 'novel-front/pom.xml'
pom_before = (BASE / 'front-pom.xml').read_text(encoding='utf-8-sig')
pom_after = pom_path.read_text(encoding='utf-8-sig')
assert pom_before == pom_after.replace('                                        <include name="**/LICENSE"/>\n', ''), 'Unexpected POM change.'
changes.append({'path': 'novel-front/pom.xml', 'status': 'modified',
                'before': digest(BASE / 'front-pom.xml'), 'after': digest(pom_path),
                'purpose': 'Include original MIT licenses when copying static and template assets.'})

resources = ROOT / 'novel-front/src/main/resources'
selected = list((resources / 'templates').rglob('*.html'))
selected += list((resources / 'static/javascript/site').glob('*.js'))
selected += list((resources / 'static/reader/icons').glob('*'))
selected += [resources / 'static/css/site.css', resources / 'static/css/reader.css',
             resources / 'static/javascript/reader/storage.js']
selected = sorted({p for p in selected if p.is_file()})
jar_path = ROOT / 'novel-front/target/novel-front-5.3.3.jar'
zip_path = ROOT / 'novel-front/target/build/novel-front.zip'
jar_hash = digest(jar_path)
with zipfile.ZipFile(jar_path) as jar:
    names = jar.namelist()
    excluded = [n for n in names if 'FixtureApplication' in n or '/frontend-fixture/' in n
                or n.endswith('.onnx') or '/tests/frontend/' in n]
    assert not excluded, excluded
    for path in selected:
        entry = 'BOOT-INF/classes/' + path.relative_to(resources).as_posix()
        with jar.open(entry) as stream:
            assert digest_stream(stream) == digest(path), entry
with zipfile.ZipFile(zip_path) as release:
    with release.open('novel-front.jar') as stream:
        assert digest_stream(stream) == jar_hash, 'ZIP JAR differs from the built JAR.'
    for path in selected:
        relative = path.relative_to(resources).as_posix()
        entry = 'templates/green/' + ('html/' + relative[10:] if relative.startswith('templates/') else relative)
        with release.open(entry) as stream:
            assert digest_stream(stream) == digest(path), entry

visual, journeys, reader, boundaries = [load(n + '.json') for n in ['visual-audit', 'journeys', 'reader-regression', 'boundaries']]
assert all(r['passed'] for r in visual['results'])
assert all(r['passed'] for r in journeys['results'])
assert reader.get('success') is True
assert all(r['passed'] for r in boundaries['results'])
package_log = (OUT / 'maven-package.log').read_text(encoding='utf-8', errors='replace')
assert 'BUILD SUCCESS' in package_log and 'Tests run: 11, Failures: 0, Errors: 0, Skipped: 0' in package_log
core_log = (OUT / 'reader-core.log').read_text(encoding='utf-8', errors='replace')
assert '# pass 8' in core_log and '# fail 0' in core_log
lighthouse = {}
for name in ['home', 'login', 'author']:
    lh = load('lighthouse-' + name + '.report.json')
    lighthouse[name] = {'url': lh['finalDisplayedUrl'], 'fetchTime': lh['fetchTime'],
                        'scores': {k: round(v['score'] * 100) for k, v in lh['categories'].items()}}
assert '/author/content_add.html' in lighthouse['author']['url']
assert all(v >= 90 for item in lighthouse.values() for v in item['scores'].values())

report = {'time': datetime.now(timezone.utc).isoformat(), 'baseline': str(BASE),
          'changes': sorted(changes, key=lambda r: r['path']),
          'changedSourceFiles': sum(r['path'].startswith('novel-front/src/') for r in changes),
          'productionJavaAndConfigurationUnchanged': True, 'sherpaRuntimeUnchanged': True,
          'verifiedResourceFiles': len(selected),
          'packageChecks': {'testFixtureExcluded': True, 'externalModelsExcluded': True,
                            'jarMatchesSource': True, 'zipMatchesSourceAndJar': True},
          'packages': [{'path': str(p), 'bytes': p.stat().st_size, 'sha256': digest(p)} for p in [jar_path, zip_path]],
          'validation': {'javaTests': 11, 'readerCoreTests': 8, 'visualLayouts': len(visual['results']),
                         'journeys': len(journeys['results']), 'readerChecks': len(reader['checks']),
                         'boundaryJourneys': len(boundaries['results'])},
          'lighthouse': lighthouse}
(OUT / 'release-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
package_rows = '\n'.join('| ' + Path(row['path']).name + ' | ' + format(row['bytes'], ',') + ' | `' + row['sha256'] + '` |' for row in report['packages'])
document = f'''# 前端重新设计与验收记录

日期：2026-10-06。工作目录：`{ROOT}`。

已完成小说前台的整体重设计，沿用原有 Thymeleaf、接口和路由。新版使用纸色背景、深绿主色、中文衬线标题与清晰的阅读层级；支持桌面、手机和夜间模式。源码已打包，正式服务尚未切换。

## 页面与交互

覆盖首页、分类书库与搜索、排行榜、书籍详情、目录、书评与回复、登录注册、个人中心、书架、历史、资料设置、密码、留言反馈、充值、作者入驻、作品管理、创建作品、章节管理、章节编辑、收益明细、公告、404/500 错误页和手机页面入口。

- 搜索、筛选、排序和分页保存在 URL 中，支持返回导航；空结果、网络错误和重试均有明确状态。
- 个人中心接入原有资料、上传、书架、阅读历史和评论接口；删除书架作品前确认，继续阅读使用实际章节 ID。
- 作者工作台保留原表单字段与接口。编辑器保留段落和空格，草稿按作品/章节保存；写作辅助先预览，由用户决定是否替换或插入。笔名校验按服务端“true 表示已占用”的语义处理。
- 充值保留原额度与支付宝 POST 提交，选择金额不会自动提交支付；原本不可用的微信支付仍明确标注。
- 全站主题可保存，并作为阅读器未单独选择主题时的初始值。新版保留原 Sherpa-ONNX 单 Worker、PCM、暂停继续和目录分页行为。关闭新版阅读开关后，传统阅读入口也采用统一设计。
- 沿用站点配置、原 logo、版权文本与既有统计脚本。书籍介绍和小说正文转换为纯文本，API 返回的标题、书评与回复均转义显示。长整型 ID 全程使用字符串。

## 源码与备份

主要入口为 `novel-front/src/main/resources/templates/common/site.html`、`static/css/site.css` 与 `static/javascript/site/`。页面源码位于 `templates/`；Maven 将它们同步到 `templates/green/`。

`scripts/redesign-frontend.py` 是本次页面生成工具，后续可以直接维护实际模板。它不属于构建自动执行步骤；手工改过模板后不应重新生成覆盖。

开始修改前已保存原始前台 src、POM、green 模板和逐文件 SHA-256 清单：`{BASE}`。上一版 JAR/ZIP 位于该目录的 `packages/`，可以用于回退。最终变更及校验清单位于 `doc/frontend-evidence/release-verification.json`。

与备份逐文件比较，生产 Java、运行配置及七个 Sherpa 核心运行模块均保持原字节。前端 POM 仅补充两处 `**/LICENSE` 包含规则，使原 MIT 图标许可随静态模板进入 ZIP；未新增运行依赖。共享图标使用原始 Tabler 3.35.0 SVG 与 MIT 许可。

## 验证结果

| 验证 | 最终结果 |
| --- | --- |
| Java 构建测试 | 11/11，通过 Maven package |
| 阅读器 Node 核心测试 | 8/8 |
| 32 个页面 × 4 种布局 | 128/128：1440px、390px、320px、390px 夜间；无页面横向溢出或脚本异常 |
| 业务操作链路 | 16/16：搜索/筛选/分页、书评回复、书架、资料、上传、创作、草稿、写作辅助、收益、登录注册、支付提交参数、手机导航和权限 |
| Sherpa 浏览器回归 | 12 项：真实单 Worker 16kHz PCM 播放、暂停继续、100+25 目录分页、主题继承、四种宽度下日夜布局和控制尺寸 |
| 兼容性与边界链路 | 5/5：真实 404/500、跨作品章节 ID、笔名占用和原生中文表单提交、传统阅读排版、付费正文权限及购买取消 |
| 发布资源字节校验 | {len(selected)} 份模板/样式/脚本/图标与 JAR、ZIP 均一致；ZIP 内 JAR 与最终 JAR 相同 |

Lighthouse 12.8.2 使用本机 Chrome、独立本地测试服务：

| 页面 | 性能 | 无障碍 | 最佳实践 | SEO |
| --- | --- | --- | --- | --- |
| 首页 | {lighthouse['home']['scores']['performance']} | {lighthouse['home']['scores']['accessibility']} | {lighthouse['home']['scores']['best-practices']} | {lighthouse['home']['scores']['seo']} |
| 登录表单 | — | {lighthouse['login']['scores']['accessibility']} | — | — |
| 作者编辑器（已登录） | — | {lighthouse['author']['scores']['accessibility']} | — | — |

报告及截图均位于 `doc/frontend-evidence/`。Lighthouse 分数反映该本地样本环境；正式数据规模、服务网络与真实手机表现需要正式环境验收。

## 本地预览与复现

地址：[http://127.0.0.1:18984/](http://127.0.0.1:18984/)。这是独立测试服务，仅监听回环地址，使用原创测试书籍、测试账户及 Mockito 底层服务。实际 PageController、ReaderController、Thymeleaf、前端代码和 Sherpa/Web Audio 为真实实现。无生产组件扫描，不连接 MySQL、Redis、支付、采集或外部 AI。测试程序与测试封面没有进入发布包。

测试登录页可使用演示手机号与至少六位密码；登录返回测试令牌。验证码图片固定为 1234。写作辅助返回明确标注的本地模拟文本。支付订单和购买接口在测试服务器内被禁止创建；业务链路测试只拦截页面提交验证参数。上传、个人设置和作者写作验证请求结构，测试服务不持久化这些业务更改。

Java/Maven 位于 `.reader-tools/`，不替换系统 Java。复现构建及主要测试：

```powershell
Set-Location -LiteralPath '{ROOT}'
.\\scripts\\build-frontend-validation.ps1
node --test tests/reader/core.test.mjs
# 在一个终端运行预览
.\\scripts\\start-frontend-validation.ps1 -Port 18984
# 在另一个终端执行浏览器检查
node tests/frontend/visual-audit.cjs
node tests/frontend/journeys.cjs
node tests/frontend/reader-regression.cjs
```

传统阅读兼容性检查需要另外运行 `start-frontend-validation.ps1 -Port 18985 -LegacyReader`，再执行 `node tests/frontend/boundaries.cjs`。验收结束后该临时 18985 服务已停止；18984 预览保留运行。

首页与登录 Lighthouse 报告由 `npx --yes lighthouse@12.8.2` 生成；`tests/frontend/lighthouse-author.cjs` 为实际已登录编辑器设置测试 Cookie 后检查无障碍，并关闭自己启动的浏览器。所有测试屏蔽原站统计请求。`tests/frontend/capture-preview.cjs` 保存最终首屏截图；`scripts/collect-frontend-redesign.py` 校验备份差异、发布包字节和最终报告。

## 发布包与验收边界

| 文件 | 字节 | SHA-256 |
| --- | --- | --- |
{package_rows}

交付文件位于 `novel-front/target/novel-front-5.3.3.jar` 与 `novel-front/target/build/novel-front.zip`。外部 TTS 模型继续按原有 `reader-resources` 配置使用，未塞入 JAR；详细模型部署约定见 `doc/reader-sherpa-validation.md`。

本次完成源码、打包和本地前端验收。正式容器没有更新，数据库与爬虫没有操作。真实账户持久化、支付/购买、外部写作服务及生产数据联调尚未做正式环境验收。
'''
(ROOT / 'doc/frontend-redesign-validation.md').write_text(document, encoding='utf-8')
print(json.dumps({k: v for k, v in report.items() if k not in ['changes', 'packages']}, ensure_ascii=True, indent=2))
