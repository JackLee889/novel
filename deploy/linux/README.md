# Linux 部署

这是独立发布包的操作说明。宿主机需要 Docker Engine 与 Docker Compose 2.24.4 或以上；Java 21 在镜像内。模型在浏览器运行，服务端不需要安装 Sherpa 或 GPU。

## 发布包

```text
compose.yaml                  # 本机数据库 + 前台/后台
compose.external-db.yaml      # 使用现有外部数据库
compose.sharding.yaml         # 保留 book_content0..9 分表
compose.small-server.yaml     # 2 GiB 服务器的初始资源限额
Dockerfile / entrypoint.sh     # 统一 Java 21 非 root 运行入口
.env.example / config/        # 公共配置，真实配置自行保留
apps/{front,admin,crawl}.jar
templates/green/               # 构建时由前台源资源生成
reader-resources/              # 完整模型、WASM、清单和许可证
sql/                          # 新装 SQL、历史增量；启动不自动执行
LICENSE / SHA256SUMS
```

发布包不含业务数据库备份、77 MB 的示例小说数据包、历史验收源码、截图、Windows 工具或密钥。先在解压目录执行 `sha256sum -c SHA256SUMS`，再创建私有配置。

## 配置

```sh
cp .env.example .env
chmod 600 .env
# 编辑 .env 后再执行下面的命令
docker compose config --quiet
```

填写 MYSQL_PASSWORD、MYSQL_ROOT_PASSWORD（仅新建本机数据库）、REDIS_PASSWORD、JWT_SECRET、CACHE_MANAGER_PASSWORD、CRAWL_ADMIN_PASSWORD、WEBSITE_DOMAIN。首次安装还需 ADMIN_INITIAL_PASSWORD，后台用户名为 admin；该密码仅初始化空库时使用，不能重置已有账户。新装使用自己的密码；迁移保留现有 JWT、域名、网站配置和业务账号。`.env` 支持引号，含 `$` 的既有值用单引号保留字面内容。

COMPOSE_PROJECT_NAME 和 *_VOLUME_NAME 确定数据位置。升级始终保持这些值不变。新的服务器默认创建独立命名卷；已有卷必须填写准确名称，确认选中的数据库和文件布局。已有 MySQL 卷的密码不会被 MYSQL_* 初始化变量改写。

图片和 TXT 分别挂载 `/data/pic`、`/data/books`，CONTENT_SAVE_STORAGE 按原来的 db/file 设置。PIC_SAVE_TYPE 默认 1，避免安装即启动封面抓取；迁移时按原值设置。init-data 默认只调整卷根目录权限，保留所有文件；迁移文件的所有者须匹配 APP_UID/APP_GID，必要时审查文件卷后显式启用 INIT_EXISTING_DATA_OWNERSHIP。

AI、支付、投稿默认关闭。需要启用时把自己的私有配置放在 config，通过自定义 Compose 覆盖追加 `SPRING_CONFIG_ADDITIONAL_LOCATION`，不要把密钥加入公共镜像。日志输出到容器 stdout，默认每个服务最多 3 个 10 MB 日志文件。

## 首次安装空数据库

只对本发布新建的空库使用本节。MySQL 不挂自动初始化 SQL 目录。

```sh
docker compose up -d mysql redis
# 查看服务健康状态
docker compose ps
# 审阅 sql/repair-linux-bootstrap.sql，然后把以下三项填入 .env：
# DB_INIT_SQL=repair-linux-bootstrap.sql
# DB_INIT_ACK=initialize-empty:novel_plus  （与 MYSQL_DATABASE 一致）
# DB_INIT_SQL_SHA256=<下方命令输出的 SHA256>
sha256sum sql/repair-linux-bootstrap.sql
docker compose --profile db-init run --rm db-init
docker compose build
docker compose up -d front admin
```

db-init 只连接本项目 mysql 服务，核对文件 SHA256、数据库名和显式 ACK。在同一数据库连接中取得锁并检查库为空；库中已有任意表、视图、例程或事件就拒绝导入。导入中途失败须查看日志后处理，不自动删除或重建数据。原始 novel_plus.sql 带数据库切换及 DROP TABLE，只作历史参考，不能用于已有数据库升级。

初始化 SQL 来自原项目最终 schema，合并重复建表及现有增量字段，只保留系统/权限种子；后台 admin 的密码采用你配置的 ADMIN_INITIAL_PASSWORD 和当前 Shiro 散列算法。用户、书籍、采集任务、历史及反馈不从测试报告复制。

## 迁移已有数据库

先备份并核对数据库 schema、正文布局、图片/TXT 文件、角色权限及配置。使用业务账号连接已有库，不运行 db-init。

```sh
# .env 设置容器可到达的 MYSQL_HOST、MYSQL_PORT、用户名、密码。
# 使用 TLS 等原有连接要求时，填写完整 MYSQL_JDBC_URL。
docker compose -f compose.yaml -f compose.external-db.yaml config --quiet
docker compose -f compose.yaml -f compose.external-db.yaml build
docker compose -f compose.yaml -f compose.external-db.yaml up -d front admin
```

现有库如果使用 book_content0..9，保留其原分表配置与数据源：

```sh
cp config/shardingsphere-jdbc.example.yaml config/shardingsphere-jdbc.yml
# 用原有拓扑和凭据编辑，数据库地址须能从容器访问。
# 文件须可被 APP_UID 读取；例如 root 部署时 chown 10001:10001。
chmod 600 config/shardingsphere-jdbc.yml
docker compose -f compose.yaml -f compose.external-db.yaml -f compose.sharding.yaml config --quiet
docker compose -f compose.yaml -f compose.external-db.yaml -f compose.sharding.yaml up -d --build front admin
```

本机数据库使用分表时省略 external-db 覆盖。直接 JDBC 模式读取 book_content；不能用它替代现有分表配置。历史增量 SQL 要按当前库版本逐项核对，近期历史/反馈和权限增量见 sql/20261008.sql 的注释。现有角色授权由管理员确认，部署不会给所有角色自动扩大权限。

## 访问、爬虫与验收

前台默认绑定 127.0.0.1:8083，后台 127.0.0.1:8088，爬虫 127.0.0.1:8081；MySQL/Redis 不暴露宿主端口。保留现有域名，通过自己的 HTTPS 反向代理访问。后台默认 Secure cookie，直接 HTTP 本机验收才设置 ADMIN_SECURITY_SECURE_COOKIE=false。

```sh
docker compose ps
docker compose logs --tail 100 front admin
curl -fsS http://127.0.0.1:8083/user/login.html > /dev/null
curl -fsS http://127.0.0.1:8083/reader/tts-manifest.json
# 可选爬虫控制台；自动采集和更新线程仍关闭。
docker compose --profile crawl up -d --build crawl
```

需要采集时另行配置并明确启用，默认部署不开始抓取。前台健康检查验证登录页面；上线还要检查书籍正文、登录/刷新/历史、后台权限、模型四资源的状态/大小与浏览器真实播放。模型版本目录内文件保持不变，升级模型用新版本目录。浏览器需有足够内存，手机及 Safari、目标域名 HTTPS 下的播放需现场验收。

## 升级与回滚

前台访问统计升级：先备份现有业务库，再仅执行 `sql/add-visitor-stats.sql`，新增三个统计表及初始计数行。此增量可重复应用，不重建原表、不导入历史数据。设置 `VISITOR_STATS_ENABLED=true`，并把 `VISITOR_STATS_SITE_URL` 配置为浏览器实际访问的 HTTPS 域名（留空时使用 `SEO_SITE_URL`），之后更新并重启前台。ShardingSphere 的 `SINGLE: *.*` 会在启动时发现新表，因此建表要先于重启。累计访问从功能启用后的第一次访问开始；当前在线按最近 90 秒的浏览器活动估算，隐藏页面停止心跳。手机端单排统计跟在“反馈留言”后面，桌面端使用页脚中间双排。接口检查 `GET /site-stats` 的 `available`、`onlineAvailable`，再用浏览器验证翻页与心跳；GET 和心跳均不增加累计访问。需要暂停时设置 `VISITOR_STATS_ENABLED=false` 后重启前台。回滚程序或停用统计保留新增表及已有累计值。

访客统计的访问上报和在线心跳均在服务端按 User-Agent 排除常见搜索/AI 爬虫、预览机器人、无头浏览器及脚本 HTTP 客户端；缺失、空白或超过 4096 字符的 User-Agent 同样不计入。被排除的同源请求仍返回只读统计快照，并带 `X-Visitor-Stats-Excluded: automated-client`，不写入 UV/PV 或延长在线时间。普通桌面、手机和微信内置浏览器正常计数，GET 快照继续只读。此规则作用于更新后的新请求，不反推或扣减历史累计值；完全伪装成普通浏览器的机器人仅凭 User-Agent 无法可靠识别。无需数据库迁移，也不信任客户端自行声明的代理/机器人身份请求头。

SEO 迁移依次为 `sql/repair-seo-20261008.sql`、`sql/repair-seo-menu-20261008.sql`、`sql/repair-seo-worker-observability-20261008.sql`、`sql/repair-seo-http-observability-20261008.sql`。先备份所选数据库，再在该库内执行尚未应用的增量；不要重跑新装 SQL。后两项补充任务耗时、每日状态对账与匿名 HTTP 聚合统计，已有 SEO 站点也必须先应用再更新程序。迁移保留原书籍发布状态、账号和角色授权。现有站点由管理员为需要的角色授予新增 SEO 权限；新站初始管理员亦需精确授权。配置 `SEO_ENABLED=true`、`SEO_SITE_URL=https://<正式域名>`，通过后台核定整站、章节和章节地图索引范围。`/data/seo` 使用独立 `SEO_VOLUME_NAME` 持久卷，`init-data` 初始化其权限。地图任务单线程，`SEO_WORKER_DELAY_MS` 默认 1000；较低值仅在受控重建期间按 CPU/内存和数据库负载测试后使用。模型、CSS 和 JS 的抓取规则不被听书设置遮挡。

2 GiB 服务器可叠加 `-f compose.small-server.yaml`，先测服务 RSS、swap 和业务请求延时。该覆盖给 MySQL、Redis 和 JVM 设置初始限额，爬虫 profile 保持停止；限额不是并发承载保证。任何升级或停止操作均保留命名卷。

把新包解压到新的 releases/<version> 目录；复制原 .env 和私有 config，保持域名、JWT、账号、卷名、正文存储方式与所用覆盖文件一致。APP_IMAGE_TAG 改成新版本。先检查 SHA256 与 Compose 配置，再 `build`、`up -d front admin`，完成页面与业务验收。

回滚时在上一个发布目录使用同一组配置和持久卷重新 `up -d front admin`。SQL 升级需保留独立备份，并检查旧程序兼容性；应用回滚不自动回滚 schema 或删除数据。停止服务使用 `docker compose stop`，数据卷持续保留。
