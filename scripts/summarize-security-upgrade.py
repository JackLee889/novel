"""Write a sanitized release report from the completed build and live acceptance evidence."""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc/security-dependency-upgrade-20261008-003503'
def read(name):
    return json.loads((OUT/name).read_text(encoding='utf-8'))

runtime = read('runtime-verification.json')
candidate = read('candidate-acceptance.json')
acceptance = read('runtime-acceptance.json')
pcm = read('runtime-reader-pcm.json')
checksums = read('dependency-checksums.json')
packages = read('package-verification.json')
versions = read('packaged-library-versions.json')
assert candidate['passed'] and candidate['readerPcmVerified']
assert acceptance['passed'] and acceptance['readerPcmVerified'] and pcm['passed']
assert checksums['libraries'] == checksums['verified'] and not checksums['errors']
assert runtime['businessDataPreserved'] and len(runtime['services']) == 4

tests = {}
for module in ('front','crawl','admin'):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (OUT/'candidate-source'/('novel-'+module)/'target/surefire-reports').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in totals: totals[key] += int(suite.get(key, '0'))
    assert totals['tests'] and not totals['failures'] and not totals['errors'] and not totals['skipped']
    tests[module] = totals
java_tests = sum(row['tests'] for row in tests.values())
assert java_tests == 152
for name, expected in [('node-tests.log',23),('csrf-client-tests.log',4)]:
    log = (OUT/name).read_text(encoding='utf-8')
    assert f'# pass {expected}' in log and '# fail 0' in log

before = read('source-before.json')
current = {}
for name in ('pom.xml','novel-common','novel-front','novel-crawl','novel-admin','templates','config','doc/sql','scripts'):
    base = ROOT/name
    files = [base] if base.is_file() else base.rglob('*')
    for path in files:
        if path.is_file() and not any(part in {'target','node_modules','__pycache__','.git'} for part in path.relative_to(ROOT).parts):
            with path.open('rb') as stream:
                current[path.relative_to(ROOT).as_posix()] = hashlib.file_digest(stream,'sha256').hexdigest()
source = dict(
    changed=[dict(path=path,before=old,after=current[path]) for path,old in before.items() if path in current and current[path]!=old],
    added=[dict(path=path,sha256=digest) for path,digest in current.items() if path not in before],
    removed=[path for path in before if path not in current and not (ROOT/path).is_file()])
(OUT/'source-changes.json').write_text(json.dumps(source,ensure_ascii=False,indent=2),encoding='utf-8')

def version(coordinate):
    found = {row['version'] for rows in versions.values() for row in rows if row['coordinate']==coordinate}
    assert len(found)==1, (coordinate,found)
    return next(iter(found))

selected = [
    ('Spring Boot','org.springframework.boot:spring-boot','前台/采集 3.4.0，后台 2.7.18'),
    ('Apache Shiro','org.apache.shiro:shiro-web','1.11.0'),
    ('Tomcat','org.apache.tomcat.embed:tomcat-embed-core','随旧 Boot 管理'),
    ('Logback','ch.qos.logback:logback-classic','随旧 Boot 管理'),
    ('Bouncy Castle','org.bouncycastle:bcprov-jdk18on','原间接依赖带入旧 jdk15on/jdk18on 包'),
    ('Swagger 注解','io.swagger.core.v3:swagger-annotations-jakarta','原间接依赖 2.2.38'),
    ('Jackson 3','tools.jackson.core:jackson-databind','原业务使用 Jackson 2'),
    ('Jackson 2 兼容依赖','com.fasterxml.jackson.core:jackson-databind','随旧 Boot 管理'),
    ('MySQL Connector/J','com.mysql:mysql-connector-j','8.0.29，原坐标 mysql:mysql-connector-java'),
    ('MyBatis Boot Starter','org.mybatis.spring.boot:mybatis-spring-boot-starter','公共模块 3.0.4，后台 1.1.1'),
    ('MyBatis Dynamic SQL','org.mybatis.dynamic-sql:mybatis-dynamic-sql','1.5.0'),
    ('PageHelper Starter','com.github.pagehelper:pagehelper-spring-boot-starter','1.4.6'),
    ('Fastjson 2 兼容 API','com.alibaba:fastjson','1.2.83'),
    ('JJWT','io.jsonwebtoken:jjwt-api','0.9.1，原单一 JAR'),
    ('Jedis','redis.clients:jedis','2.9.0'),
    ('Commons IO','commons-io:commons-io','2.9.0'),
    ('Commons Lang','org.apache.commons:commons-lang3','3.4'),
    ('Commons Text','org.apache.commons:commons-text','1.4'),
    ('Velocity','org.apache.velocity:velocity-engine-core','1.7，原 velocity 坐标'),
    ('SnakeYAML','org.yaml:snakeyaml','2.2'),
    ('springdoc','org.springdoc:springdoc-openapi-starter-webmvc-ui','原 Springfox 2.6.1'),
    ('Spring AI','org.springframework.ai:spring-ai-model','1.0.0，业务仍关闭'),
]
matrix = [dict(name=label,before=old,after=version(coordinate),coordinate=coordinate) for label,coordinate,old in selected]
(OUT/'dependency-upgrade-matrix.json').write_text(json.dumps(matrix,ensure_ascii=False,indent=2),encoding='utf-8')

db = read('database-after.json')
manifest = dict(
    generated=datetime.now().astimezone().isoformat(), localDeployment=True, java=21,
    tests=tests, nodeTests=27, candidateHttpChecks=len(candidate['checks']), runtimeHttpChecks=len(acceptance['checks']),
    readerPcmVerified=True, dependencyChecksumsVerified=checksums['verified'], packages=packages,
    sourceChanges={key:len(rows) for key,rows in source.items()}, runtime=runtime,
    features=dict(payment=False,ai=False,submission=False),
    sources=[row for row in db if row['kind']=='sources'],
    databaseCounts=[row for row in db if row['kind']=='count'],
    browserVendorUpgrade=False, databaseEngineUpgrade=False, publicProductionAcceptance=False)
(OUT/'release-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')

lines = [
    'novel-plus 安全修复与 Java 依赖升级报告',
    '生成时间：'+manifest['generated'],
    '',
    '结果：源码修复、构建、本机四个应用服务更新和本机验收完成。',
    '应用：novel-common、novel-front、novel-crawl、novel-admin；运行环境 Java 21。',
    '本次升级范围：Java 直接依赖、Spring Boot 管理的依赖和关键安全组件。',
    '版本选择以本次读取的官方 Maven 元数据为准，排除 RC、M、beta 等预发布版本；',
    '受 Boot 管理的依赖保持兼容组合，并对 Tomcat、Logback、Jackson 等组件作显式升级。',
    '浏览器静态组件 jQuery、Bootstrap、Layui、富文本编辑器与 Sherpa-ONNX WASM 维持原版本。',
    '',
    '已修复的安全问题',
    '1. 后台排序：27 处 MyBatis 排序改为列名白名单与固定 ASC/DESC 分支；',
    '   Query 校验排序标识符、方向和分页，绕过 Query 直接调用 Mapper 也不能拼接任意 SQL。',
    '2. 文件访问：统一按配置的可信目录解析；拒绝目录穿越、编码路径、绝对路径、',
    '   反斜线和符号链接；下载、删除、上传和代码生成恢复明确权限校验。',
    '3. CSRF：后台绑定 Shiro 会话的随机令牌，采集启用 Spring Security CSRF；',
    '   页面、表单、XHR、fetch 自动携带令牌，同源判断防止向外部地址传出令牌。',
    '   修改类接口使用 POST，退出登录不再通过 GET 执行。',
    '4. Shiro：1.11.0 升至 3.0.1，禁用 RememberMe 和 URL 会话标识重写。',
    '   生产默认会话 Cookie 为 Secure+HttpOnly；本机 HTTP 使用 Secure=false。',
    '5. 错误处理：不再把 Shiro 非法路径等错误响应改为 HTTP 200；保留 400/403/404 等状态。',
    '6. Redis 缓存清理仅作用于本缓存前缀；不再用 FLUSHDB 清空共享 Redis。',
    '7. 采集更新任务先筛选启用的书源，停止书源不会被任务先行占用或更新。',
    '',
    '升级和兼容改造',
    '统一 Boot 4/Jakarta/Java 21，迁移 Jackson 3、JWT、MyBatis 2、Jedis 8、Velocity 2、',
    'ip2region 3 和 Spring 7 的 API；移除 Springfox、旧 Fastjson 1、过时 EHCache 等依赖。',
    'JJWT 保留原配置值与旧令牌验签兼容，新签发令牌使用符合新版接口要求的密钥处理。',
    '修复后台旧别名扫描冲突，并补上 Boot 4 拆分后的缓存模块。',
    '',
    '主要依赖版本（完整的实际 JAR 清单见 packaged-library-versions.json）',
]
for row in matrix: lines.append(f"{row['name']}：{row['before']} -> {row['after']}")
lines += [
    '',
    '验证结果',
    f"Java 测试：{java_tests} 通过（前台 {tests['front']['tests']}、采集 {tests['crawl']['tests']}、后台 {tests['admin']['tests']}），0 失败/错误/跳过。",
    'Node 测试：27 通过，0 失败。',
    f"HTTP 验收：候选 {len(candidate['checks'])} 项、本机更新后 {len(acceptance['checks'])} 项全部通过。",
    f"依赖完整性：{checksums['verified']}/{checksums['libraries']} 个库的实际 JAR 与上游 HTTPS 校验信息一致。",
    '模板、静态文件、Mapper 与源码字节一致：前台 682、后台 1149、采集 144 项。',
    '实际浏览器听书：Sherpa-ONNX 生成 16 kHz 非静音 PCM，真实 AudioContext 播放与暂停通过。',
    f"本机 PCM：{pcm['playback']['pcm'][0]['samples']} 个样本，RMS={pcm['playback']['pcm'][0]['rms']:.6f}，",
    f"播放时钟推进 {pcm['clockAdvanced']:.3f} 秒；使用 Chrome {pcm['browser']}。",
    '验证时拦截访问计数与阅读历史写入；采集测试使用无效 URL，不触发外部采集。',
    '后台有权限/无权限账号、真实 Shiro 过滤链与权限注解，使用隔离测试账号和临时目录验证。',
    '本机后台 CAPTCHA 图片、登录页和令牌已验证；现有后台账号人工登录及所有页面逐项操作仍待人工验收。',
    '',
    '本机更新与数据保留',
    '四个应用容器全部 healthy，启动配置已固定到本次新镜像；',
    '原凭据、域名配置、挂载方式与数据卷保留，MySQL 和 Redis 容器未重建。',
    f"其他 {runtime['unrelatedContainersPreserved']} 个原有容器的 ID、启动时间未改变。",
    '未进行数据库结构迁移或数据库/Redis 引擎升级。',
    '核对范围：9 张核心表记录数、书源规则哈希、状态及创建/更新时间与更新前一致。',
]
for row in db:
    if row['kind']=='count': lines.append(f"  {row['table']}：{row['count']}")
    elif row['kind']=='sources': lines.append(f"  书源 {row['id']} {row['name']}：停止（status=0）")
lines += [
    '支付、AI、投稿均保持关闭；两套前台与网关均保留相应拦截。',
    '支付金额/商户核对与并发扣款、AI 权限/额度/服务接入、投稿只读图片目录问题仍在关闭范围内，',
    '本次未开放这些业务，不能据此认定完整业务已经通过生产验收。',
    '',
    '本机访问',
    '主前台：http://127.0.0.1:18987',
    '另一套前台：http://127.0.0.1:18080',
    '后台：http://127.0.0.1:18088',
    '采集：http://127.0.0.1:18081',
    '',
    '备份与回滚',
    '原源码、应用 JAR 和启动配置均已保留；本次部署未删除原数据或原数据卷。',
    '更新脚本：'+str(OUT/'update-local.ps1'),
    '回滚脚本：'+str(OUT/'rollback-local.ps1'),
    '回滚只恢复旧应用镜像和启动配置，保留数据库、Redis 及功能关闭状态。',
    '回滚会恢复旧版本依赖；升级后新签发的登录令牌在回滚后需要重新登录。',
    '本次没有执行回滚，回滚脚本与所需原镜像/配置已保留。',
    '',
    '部署边界',
    '本次完成的是已有本机 Docker 环境的更新；未连接或更新公网生产服务器。',
    '公网部署还需真实 HTTPS/反向代理与账号业务验收，HTTPS 环境应使用 Secure=true。',
    '听书本次验证桌面浏览器的真实播放与暂停；移动端、跨章节和长队列需在目标环境验收。',
    '',
    '证据文件',
    'release-manifest.json、source-changes.json、dependency-upgrade-matrix.json、',
    'packaged-library-versions.json、dependency-checksums.json、package-verification.json、',
    'candidate-acceptance.json、runtime-acceptance.json、runtime-reader-pcm.json、',
    'runtime-reader.png、runtime-verification.json、database-before.json、database-after.json、',
    'maven-package-front-crawl.log、maven-package.log、node-tests.log、csrf-client-tests.log。',
    '',
    '官方参考',
    'Shiro 安全公告：https://shiro.apache.org/security-reports.html',
    'Spring Boot：https://spring.io/projects/spring-boot',
    'Tomcat 更新：https://tomcat.apache.org/tomcat-11.0-doc/changelog.html',
    'Logback 更新：https://logback.qos.ch/news.html',
    'Bouncy Castle：https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/',
    '其余依赖的官方 Maven 元数据 URL 记录在 maven-metadata.json 与 managed-latest-metadata.json。',
]
(OUT/'security-upgrade-report.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps(dict(report=str(OUT/'security-upgrade-report.txt'),javaTests=java_tests,nodeTests=27,runtimeChecks=len(acceptance['checks']),sourceChanges=manifest['sourceChanges']),ensure_ascii=False))
