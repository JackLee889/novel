# novel · 服务器版本源码

本仓库以 2026-11-17 服务器实际部署的小说程序为基准，保存可构建源码、公开配置模板与版本核验记录。保留上游 novel-plus 的 Apache-2.0 许可证。

## 版本基准

- 前台运行包 SHA256：`38edbf9999d44e43feb4189e44e0cc117ce1d709341ac2242b29adba191be209`。
- 爬虫运行包 SHA256：`7c70596a3eb241a83f8ac85a93c94ec13305152388dc3032b5a60ac23fe20d30`。
- 实际前台的 701 个页面及静态文件与当前源码一致；Java 业务类按有效加载顺序核验。
- 服务器前台、爬虫和后台的 `SeoRepository` 分别保留各自实现。公共模块保留前台版本；`novel-crawl`、`novel-admin` 在自己的源码目录中提供经过核验的服务专用覆盖类。
- 爬虫源码恢复没有使用反编译器。来源、方法校验及历史发布兼容步骤见 `scripts/server-release/SERVER-SEO-SOURCE-PROVENANCE.json`。

## 目录

| 目录 | 内容 |
| --- | --- |
| `novel-common` | 前台使用的公共实体、数据库访问、SEO 等实现 |
| `novel-front` | 小说门户、阅读器、SEO、访客统计及前台资源 |
| `novel-crawl` | 小说采集器及服务器专用 SEO 覆盖源码 |
| `novel-admin` | Java 管理后台模块 |
| `deploy/linux` | Linux Docker Compose、启动脚本及配置示例 |
| `scripts/server-release` | 复现服务器爬虫发布形态的受限兼容工具 |
| `server-baseline` | 实际运行包哈希和代码核验清单 |
| `doc/sql` | 建表、版本迁移和程序初始化 SQL 源码 |
| `reader-resources` | 第三方听书引擎的公开 JS、许可证与资源校验清单 |
| `source-assets` | 图片、字体、IP 规则等原始二进制资源的分卷归档和哈希清单 |

## 构建服务器版本

需要 JDK 21、Maven 3.9 和 Python 3。先恢复仓库内的二进制资源，再构建并整理服务器版本：

```sh
python scripts/restore-binary-resources.py
mvn -B -ntp -Dmaven.test.skip=true package
python scripts/server-release/align-crawler-jar.py --java-home "$JAVA_HOME"
python scripts/server-release/align-admin-jar.py
```

资源恢复不需要联网：`source-assets` 保存了 799 个原始资源文件的去重归档，恢复工具会逐一核对 SHA256，保留已有的正确文件，并拒绝覆盖内容不同的文件。归档不包含线上数据库或小说正文。

后两步仅处理本地构建产物：从服务嵌套公共库中去掉前台专用的同名类族，分别保留爬虫、后台自己的实现；后台包还排除线上没有部署的后台 I/O 类。爬虫兼容工具会验证输入类的完整规范化指纹；后续修改业务源码时，应明确升级版本基准，而不是跳过校验。

构建输出位于各模块的 `target/`。绿色主题取自 `novel-front/src/main/resources`；源码中不发布已经停用且可能含私有值的 `src/main/build` 旧配置模板。

## 听书资源与 Linux 发布

较大的第三方 WASM 和模型 DATA 不进入 Git 历史。运行下面的命令可从清单所指向的官方固定版本恢复资源，并逐文件核对 SHA256：

```sh
python scripts/fetch-reader-assets.py
python scripts/package-linux.py --version 5.3.3-server-20261117
```

官方包版本、下载地址、归档哈希及每个文件的哈希位于 `reader-resources/1.13.8-matcha-icefall-zh-en/asset-manifest.json`。发布时保留许可证。Linux 配置说明见 `deploy/linux/README.md`。

## 数据与配置

仓库保存程序源码和公开配置示例，不保存线上数据库导出、小说正文数据、环境文件、密钥、真实数据库连接配置或历史备份。SQL 仅保留建表和迁移逻辑；字面量账号、菜单、演示业务等种子数据已经移除，因此它不是完整的空库初始化数据集，也不能替代数据库备份。

MyBatis 代码生成器的真实 JDBC 配置不发布；需要代码生成时，复制 `generatorConfig.example.xml` 为同目录的 `generatorConfig.xml` 并填写自己的连接信息。该私有文件被 Git 忽略。

部署时按 `.env.example` 配置自己的域名、数据库、Redis 和管理凭据。此仓库及构建脚本不会自动连接服务器、恢复数据库或执行 SQL。

## 核验

`server-baseline/production-manifest.json` 记录本次服务器基准。完整 JAR 的 ZIP 布局、编译调试信息或生成时间可以不同；有效业务类的指令、方法接口和注解，以及实际页面资源应与服务器基准一致。

源码和构建结果的本次核验结论记录在 `server-baseline/validation.json`。
