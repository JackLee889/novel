专用小说与爬虫恢复工具。迁移根目录布局为 `application/`（完整 Linux 发布包）、`data/`、`migration/`（本目录的脚本、校验器及 Compose 覆盖）。`data/` 包含已审核的 `schema.sql`、`manifest.json`、18 个 `tables/*.sql.gz`、`SHA256SUMS`，以及可选封面清单与 `pic/`。历史用户、管理员口令、书架、浏览记录、支付和反馈不导出；管理员由新的 `ADMIN_INITIAL_PASSWORD` 初始化。

在迁移根执行，先为 `application/.env` 配置全新的 `COMPOSE_PROJECT_NAME` 和 MySQL/Redis/pic/books 卷名、数据库口令、管理员初始口令及网站域名。必须核对目标不是原部署卷，不运行通用 `db-init`：此导入器需要完全空 schema。

```bash
export DATA_MIGRATION_ACK=initialize-empty:novel_plus
export MIGRATION_DATA_SHA256="$(sha256sum data/SHA256SUMS | cut -d' ' -f1)"
docker compose --env-file application/.env -f application/compose.yaml -f migration/compose.migration.yaml --profile migration up -d mysql redis
docker compose --env-file application/.env -f application/compose.yaml -f migration/compose.migration.yaml --profile migration run --rm migration-import
```

`MIGRATION_DATA_SHA256` 应使用发布方单独提供并审核的摘要；上面的本地计算用于核对。当前数据库名为 `novel_plus`；修改名称时同步修改确认值。导入器拒绝外部数据库地址，先核验所有摘要、gzip 与固定完整列名的单行 INSERT；仅允许恰好 18 种已知 mysqldump SET 指令并在输出时去除。正文按原 ID 导入，10 个正文分表同时保留。脚本不复制多 GB SQL 到 `/tmp`，只保留 16 字节的临时第一轮口令摘要。

公开工具默认严格校验完整 SQL 字面量文法。针对由可信 MySQL 导出、逐表数量和隐私已审核的私有数据包，发布方可在 `migration/reviewed-data.sha256` 放入唯一一行 `data/SHA256SUMS` 的 64 位小写摘要；仅当它与 `MIGRATION_DATA_SHA256` 相等时启用快速格式检查。此时仍完整校验文件摘要、gzip、确切表名与完整列名前缀、单行 INSERT 的 `);` 尾部和 18 种固定 SET；不再重复解析正文字符串。公开源码不附该审核标记，格式不符或摘要不同的标记不会启用快速模式。不要把未知或自行修改的数据标为已审核。

锁、表/视图/存储过程/事件的空 schema 检查及导入共用一个 MySQL 连接。数据 INSERT 在事务中执行；gzip、校验或管道失败不会发出 COMMIT。DDL 初始化不能回滚，失败目标必须保留供诊断，不会删除或覆盖业务记录。已有 schema 一律拒绝再次导入。封面由 `covers.sh` 与 `compose.covers.yaml` 单独恢复到新 pic 卷。

恢复验收必须比对清单的逐表数量、书籍/目录/正文原 ID、目录和正文关联、来源关联、封面哈希及引用，确认用户/历史等排除表为空且只有新的初始管理员。当前源库正文位于 `book_content`，10 个分表为空，应使用默认 JDBC；不要启用分表覆盖。验证真实页面与阅读正文、管理员新口令登录、二次导入拒绝后数据不变。爬虫任务状态按原值保留，发布配置 `CRAWL_JOBS_ENABLED=false`、`CRAWL_UPDATE_THREAD=0`；验收期间不启用抓取。

离线回归：`python deploy/linux/migration/tests/test_import.py`。此测试只使用临时夹具和假的 mysql 客户端，不连接数据库。
