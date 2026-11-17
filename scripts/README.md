# 项目工具

- package-linux.py：跨平台发布包构建与 SHA256 校验，当前 Linux 部署入口。
- migrate-admin-permissions.py：权限差异检查，默认只生成计划；写入仅限保护规则允许的独立测试库。
- audit-frontend.py：静态页面引用检查。
- clean-chapter-ads.py：章节与简介清理 CLI；与 novel-repair 共用一份纯清理规则，默认预览，支持来源规则、混淆广告、script/style 与孤立 HTML 标签清理、简介 JSON 转义还原、误采接口 JSON 正文离线提取，完整原文备份及条件回滚。仅 Python 标准库，不依赖旧 ops.py。
- 数据检查/导入工具：需单独选择目标和备份，不属于部署启动流程。
- prepare-reader-tts.ps1、prepare-reader-icons.ps1：Windows 开发资源准备，Linux 发布包直接携带已校验资源。

绑定具体升级、验收或 sandbox 的旧脚本只用于历史追溯，不作为 Linux 部署入口。原文及 SHA256 在 backups/linux-layout-20261008/obsolete-project-files.zip 和对应清单。

当前回归测试见根 README，服务器安装见 deploy/linux/README.md。

## 章节广告清理

先离线检查正文，输出文件不会覆盖原文件或已有文件：

```sh
python scripts/clean-chapter-ads.py file --input chapter.html
python scripts/clean-chapter-ads.py file --input chapter.html --output chapter.cleaned.html --report chapter.diff.jsonl
```

数据库调用需明确选择 Docker Compose 部署目录。CLI 参数保持兼容，调用目录不限；分发时必须将脚本和共用模块作为目录包一起复制，不能仅复制脚本：

```text
任意项目根目录/
  scripts/clean-chapter-ads.py
  novel-repair/repair/__init__.py
  novel-repair/repair/cleaning.py
```

模块路径按脚本自身的位置查找，CLI 不需要安装 Django。下例在 Linux 部署目录内执行：

```sh
python3 /path/to/clean-chapter-ads.py scan --compose-dir . --source 香书小说 --limit 100 --report ads-preview.jsonl
python3 /path/to/clean-chapter-ads.py scan --compose-dir . --book 2107645064384430080 --index 2107645767463026688 --apply --backup ads-before.jsonl
python3 /path/to/clean-chapter-ads.py restore --compose-dir . --backup ads-before.jsonl
python3 /path/to/clean-chapter-ads.py restore --compose-dir . --backup ads-before.jsonl --apply
```

- 默认扫描全局最多 100 章，`--limit 0` 才不设上限；多个来源取并集，来源/书籍/章节/ID 范围之间取交集。`--min-id` 不包含下界，`--max-id` 包含上界。
- `--apply` 必须指定来源、书籍、章节、ID 范围或显式 `--all`，且必须给独立 `--backup` 文件；已有备份/报告不覆盖。`--anchor` 使用字面量匹配，百分号和下划线不会扩大匹配范围。
- `.env` 默认取部署目录下的文件，可用 `--env-file` 指定私有配置；支持 `MYSQL_PASSWORD`/旧版 `DB_PASSWORD`，也可设置 `NOVEL_CLEAN_MYSQL_PASSWORD`。口令通过 stdin 传递，不进入命令行；不读取 root 密码。
- 可重复指定 `--compose-file` 叠加部署覆盖文件。在 Windows 调用 WSL Docker 时需显式 `--wsl-distro novel-docker`，不会自动重投或启动服务。
- 自动探测 `book_content` 与 `book_content0..9`。单表和分表同时有数据时要求 `--tables unsharded`、`sharded` 或 `all` 明确选择。写入只支持 InnoDB，存在正文表触发器时拒绝直接写入。
- 任意来源的 `filterContent` 默认不开启。`scan --use-filters` 才读取并执行来源规则；也可给 `--use-filters --filters-json source-rule.json`。使用 Python 正则，Java 专用语法会明确报错；不能把 Java/MySQL/Python 规则视为等价。
- 金额、全角字母和普通符号不作为广告证据；无法确认的混合广告、畸形 script/style 保留并报告。清空正文、删除超过默认 35% 或其他警告均跳过数据库写入；`--max-remove-ratio` 可调整删除比例阈值。
- 每批完整原文、新文和 SHA256 先落盘并 fsync，再事务更新；使用正文行 ID、章节 ID、字节级旧正文比较，遇到并发变化跳过。回滚校验 MySQL server_uuid 和数据库名称，当前正文只有仍等于清理后新文时才恢复。
- 本工具处理数据库正文；不会重抓源站、修任务/指针/封面、清空 Redis 或删除 binlog。`storage_type=txt` 的文件正文不由数据库模式修改。清理后章节/书籍字数与已有业务缓存未同步更新，应按当前部署机制单独核验。
- JSONL 备份/报告包含整章正文，应保存在受控目录。离线 `file` 即使有警告仍可生成独立结果供人工检查，不会覆盖输入。

离线回归：

```sh
python -m unittest discover -s tests/tools -p test_clean_chapter_ads.py -v
```

2026-10-08 的真实样本在 `doc/chapter-ads-debug-20261008/`：后台 Reader API 返回 48 段，清理只移除 3 处混淆广告、共 65 字符，其余段落保持不变。此样本是 API 规范化段落重建 HTML，并非数据库原始 HTML；没有写回服务器，不能代替原库 MySQL 写入/回滚验收。

2026-10-09 增加香书独立广告段规则：识别完整的 `【香书小说】提醒书友谨记...` 网址提示和 `【香书小说】倾心打造、全文无错；分享本站网址...` 分享求书广告。仅在独立 p/div/br 段落或完整纯文本片段、且不位于引号内时清理，行内、属性、剧情和不完整提示保留。

真实《诡秘之主》第一章样本位于 `doc/chapter-ads-debug-20261009-guimi/`。公开阅读页共 71 段，预览去掉首尾 2 段广告、共 85 字符，其余 69 段逐段完全一致，无警告。输入为公开页面规范化段落重建的 HTML，未连接或写入生产数据库；完整工具回归 95/95 通过。

## 误采接口 JSON 的章节

正文混入 `code/data/bookinfo/riskinfo/fo/msg` 整包接口响应时，用独立提取模式生成正文候选。必须提供已核对的书名和章节名；工具检查包结构及正文边界，不按接口字数截断，也不取 `authorwords` 中的文本。

```sh
python scripts/clean-chapter-ads.py extract-response --input chapter.html --expected-book 大奉打更人 --expected-chapter 新书已肥 --output chapter.extracted.html --report chapter.extraction.jsonl
```

- 此模式只处理本地文件，没有数据库写入选项。损坏的引号或键名只在结构与上下文可确认时生成带警告的候选；正文边界不唯一、书名/章节名不符或包外还有剧情时保留原文。
- 普通 `file/scan` 遇到接口响应污染会保留并警告，不会把元数据当成广告批量删除。提取结果的正文也不会再运行去广告规则，作者通知等内容应由人核对。
- 标准 JSON 的正文按 JSON 字符串解码；损坏包保留提取范围中的引号和反斜杠，不猜测修复正文转义。报告保存完整前后正文和警告，输出及报告均不覆盖已有文件。

第二个真实样本在 `doc/chapter-ads-debug-20261008-second/`：后台 API 返回的两段是损坏接口包，正文为“新书《灵境行者》，20万字了，书荒的可以看看。勿养~”，共 26 字符。提取保留完整作者通知，接口中的 25 字数字段不作为裁剪依据。此样本同样由 API 规范化段落重建，未写回服务器。

## 简介中的转义和尾部签名

简介模式处理 `book.book_desc` 中的同类广告，也支持明确开启后将字面 `\u002F` 还原为斜杠、`\n` 还原为换行。只解码一层，未知转义保留；普通 Windows 路径不要开启解码。原站已经截断的简介保持现有内容，不猜测补写。

离线文件默认预览，需要完整书名：

```sh
python scripts/clean-chapter-ads.py file --kind description --book-name '穿书百合文，我只想安稳苟到结局' --decode-escapes --input description.txt
python scripts/clean-chapter-ads.py file --kind description --book-name '穿书百合文，我只想安稳苟到结局' --decode-escapes --strip-bare-footer --input description.txt --output description.cleaned.txt --report description.diff.jsonl
```

独立末行的 `--完整书名` 可以识别；裸 `--` 默认保留。只有已核对属于源站残留时才使用 `--strip-bare-footer`，此参数只用于离线简介。行内破折号、引号内文本、末行后仍有剧情的文本均保留。

数据库简介使用独立子命令；来源和书籍条件取交集，默认最多预览 100 本：

```sh
python3 /path/to/clean-chapter-ads.py scan-descriptions --compose-dir . --book 2108338713759309824 --decode-escapes --confirm-bare-footer 2108338713759309824 --report description-preview.jsonl
python3 /path/to/clean-chapter-ads.py scan-descriptions --compose-dir . --book 2108338713759309824 --decode-escapes --confirm-bare-footer 2108338713759309824 --apply --backup description-before.jsonl
python3 /path/to/clean-chapter-ads.py restore-descriptions --compose-dir . --backup description-before.jsonl
python3 /path/to/clean-chapter-ads.py restore-descriptions --compose-dir . --backup description-before.jsonl --apply --restore-backup description-restored.jsonl
```

- 数据库裸尾缀确认必须逐本指定 `--confirm-bare-footer BOOK_ID`，且该 ID 必须同时出现在 `--book` 中；不会把确认扩散到同来源的其他书。
- 写入只更新简介和书籍 `update_time`。完整原文、新文、身份、SHA256 和确定的前后时间先备份并 fsync，再按书籍 ID、身份、原文 UTF-8 字节和更新时间条件更新。并发不匹配跳过，不覆盖上架状态、章节或字数。
- 简介使用独立备份格式和回滚入口，核验数据库目标。回滚只处理已确认写入的记录，且当前简介及时间必须仍匹配清理结果；不会整行恢复书籍或覆盖后续采集。实际回滚还需 `--restore-backup` 独立文件，保存恢复操作的前后内容和确定时间；原备份只读。
- 写入要求 InnoDB，拒绝受写表存在触发器。SEO 表齐全时同事务标记该书和 sitemap 为待生成；SEO 表全部不存在时仅更新书籍并提示；SEO 表仅部分存在时拒绝写入。
- 删除比例默认 35%，超限或出现其他警告时只报告不写库。明确复核后可用 `--max-remove-ratio` 调整阈值；JSON 转义表示还原也会计入字符缩短比例。
- 简介数据库写入应在 Linux/WSL 内运行，以持久化备份文件和父目录。Windows 的离线文件与数据库预览仍可使用。
- 本命令不清空 Redis。提交后需按部署核验首页衍生缓存、公开详情页与搜索描述。采集端解析逻辑未由此修改，重新采集仍可能带回源站污染。
- 简介额外回归：`python -m unittest discover -s tests/tools -p test_clean_book_descriptions.py -v`。完整工具回归：`python -m unittest discover -s tests/tools -p 'test_clean_*.py' -v`。

2026-10-09 验证：91/91 离线回归通过；隔离 MySQL 8.4.11/Python 3.12 中实际验证预览、写入、回滚、来源与书籍交集、触发器拒写，以及原文或更新时间并发变化时 CAS 跳过且不修改 SEO。详见 `doc/book-intro-cleaning-20261009.md` 和 `backups/book-intro-db-test-20261009/runtime-results/`。测试容器已停止。
