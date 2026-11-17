-- Linux fresh bootstrap, generated from the current canonical schema.
-- NEW EMPTY SCHEMA ONLY. Never use this file as an upgrade/migration script.
-- Use the explicit db-init profile, reviewed SHA256 and ADMIN_INITIAL_PASSWORD.
-- 50 final table definitions; repeated historical CREATEs and ADDs are folded.
-- No DROP/TRUNCATE/DELETE/UPDATE/CREATE DATABASE/USE statements are present.
-- Preserve public system/config seeds; do not import sample reader/business data.
-- Initial username: admin. The client computes the EXACT project Shiro two-round
-- MD5 hash. Only its final 32 hex digits enter the transient session variable.
-- Public initial site name/domain come from WEBSITE_NAME/WEBSITE_DOMAIN via
-- @novel_initial_site_name/@novel_initial_site_domain. Logos are bundled locally.
-- All new permission definitions are appended; no extra role grants are added.
SET NAMES utf8mb4;
SET @novel_bootstrap_lock_name = CONCAT('np-bootstrap-', MD5(DATABASE()));
SET @novel_bootstrap_lock = GET_LOCK(@novel_bootstrap_lock_name, 15);
SET @novel_bootstrap_objects = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE())
    + (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE())
    + (SELECT COUNT(*) FROM information_schema.events WHERE event_schema = DATABASE());
SET @novel_bootstrap_gate = IF(DATABASE() IS NOT NULL AND @novel_bootstrap_lock=1 AND @novel_bootstrap_objects=0
    AND @novel_initial_admin_hash IS NOT NULL AND REGEXP_LIKE(@novel_initial_admin_hash, '^[0-9a-f]{32}$', 'c')
    AND CHAR_LENGTH(@novel_initial_site_name) BETWEEN 1 AND 50
    AND CHAR_LENGTH(@novel_initial_site_domain) BETWEEN 1 AND 50,
    'SELECT ''Verified empty schema, admin hash and initial website config'' AS status',
    'SELECT * FROM information_schema.novel_linux_bootstrap_refused_existing_objects_or_invalid_admin_hash');
PREPARE novel_bootstrap_guard FROM @novel_bootstrap_gate;
EXECUTE novel_bootstrap_guard;
DEALLOCATE PREPARE novel_bootstrap_guard;

CREATE TABLE `author`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        bigint(20)  DEFAULT NULL COMMENT '用户ID',
    `invite_code`    varchar(20) DEFAULT NULL COMMENT '邀请码',
    `pen_name`       varchar(20) DEFAULT NULL COMMENT '笔名',
    `tel_phone`      varchar(20) DEFAULT NULL COMMENT '手机号码',
    `chat_account`   varchar(50) DEFAULT NULL COMMENT 'QQ或微信账号',
    `email`          varchar(50) DEFAULT NULL COMMENT '电子邮箱',
    `work_direction` tinyint(4)  DEFAULT NULL COMMENT '作品方向，0：男频，1：女频',
    `status`         tinyint(4)  DEFAULT '0' COMMENT '0：正常，1：封禁',
    `create_time`    datetime    DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 3
  DEFAULT CHARSET = utf8mb4 COMMENT ='作者表';

CREATE TABLE `author_code`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `invite_code`    varchar(100) DEFAULT NULL COMMENT '邀请码',
    `validity_time`  datetime     DEFAULT NULL COMMENT '有效时间',
    `is_use`         tinyint(1)   DEFAULT '0' COMMENT '是否使用过，0：未使用，1:使用过',
    `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
    `create_user_id` bigint(20)   DEFAULT NULL COMMENT '创建人ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_code` (`invite_code`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 6
  DEFAULT CHARSET = utf8mb4 COMMENT ='作家邀请码表';

CREATE TABLE `book`
(
    `id`                     bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    `work_direction`         tinyint(1)             DEFAULT NULL COMMENT '作品方向，0：男频，1：女频''',
    `cat_id`                 int(11)                DEFAULT NULL COMMENT '分类ID',
    `cat_name`               varchar(50)            DEFAULT NULL COMMENT '分类名',
    `pic_url`                varchar(200)  NOT NULL COMMENT '小说封面',
    `book_name`              varchar(50)   NOT NULL COMMENT '小说名',
    `author_id`              bigint(20)             DEFAULT NULL COMMENT '作者id',
    `author_name`            varchar(50)   NOT NULL COMMENT '作者名',
    `book_desc`              varchar(2000) NOT NULL COMMENT '书籍描述',
    `score`                  float         NOT NULL COMMENT '评分，预留字段',
    `book_status`            tinyint(1)    NOT NULL DEFAULT '0' COMMENT '书籍状态，0：连载中，1：已完结',
    `visit_count`            bigint(20)             DEFAULT '103' COMMENT '点击量',
    `word_count`             int(11)                DEFAULT NULL COMMENT '总字数',
    `comment_count`          int(11)                DEFAULT '0' COMMENT '评论数',
    `yesterday_buy` int(11) DEFAULT '0' COMMENT '昨日订阅数',
    `last_index_id`          bigint(20)             DEFAULT NULL COMMENT '最新目录ID',
    `last_index_name`        varchar(50)            DEFAULT NULL COMMENT '最新目录名',
    `last_index_update_time` datetime               DEFAULT NULL COMMENT '最新目录更新时间',
    `is_vip`                 tinyint(1)             DEFAULT '0' COMMENT '是否收费，1：收费，0：免费',
    `status`                 tinyint(1)             DEFAULT '0' COMMENT '状态，0：入库，1：上架',
    `update_time`            datetime      NOT NULL COMMENT '更新时间',
    `create_time`            datetime               DEFAULT NULL COMMENT '创建时间',
    `crawl_source_id`        int(11)                DEFAULT NULL COMMENT '爬虫源站ID',
    `crawl_book_id`          varchar(32)            DEFAULT NULL COMMENT '抓取的源站小说ID',
    `crawl_last_time`        datetime               DEFAULT NULL COMMENT '最后一次的抓取时间',
    `crawl_is_stop`          tinyint(1)             DEFAULT '0' COMMENT '是否已停止更新，0：未停止，1：已停止',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_bookName_authorName` (`book_name`, `author_name`) USING BTREE,
    KEY `key_lastIndexUpdateTime` (`last_index_update_time`) USING BTREE,
    KEY `key_createTime` (`create_time`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1262260513468559361
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说表';

CREATE TABLE `book_author`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `invite_code`    varchar(20) DEFAULT NULL COMMENT '邀请码',
    `pen_name`       varchar(20) DEFAULT NULL COMMENT '笔名',
    `tel_phone`      varchar(20) DEFAULT NULL COMMENT '手机号码',
    `chat_account`   varchar(50) DEFAULT NULL COMMENT 'QQ或微信账号',
    `email`          varchar(50) DEFAULT NULL COMMENT '电子邮箱',
    `work_direction` tinyint(4)  DEFAULT NULL COMMENT '作品方向，0：男频，1：女频',
    `status`         tinyint(4)  DEFAULT NULL COMMENT '0：待审核，1：审核通过，正常，2：审核不通过',
    `create_time`    datetime    DEFAULT NULL COMMENT '创建时间',
    `create_user_id` bigint(20)  DEFAULT NULL COMMENT '申请人ID',
    `update_time`    datetime    DEFAULT NULL COMMENT '更新时间',
    `update_user_id` bigint(20)  DEFAULT NULL COMMENT '更新人ID',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 1254957873655066625
  DEFAULT CHARSET = utf8mb4 COMMENT ='作者表';

CREATE TABLE `book_category`
(
    `id`             int(11)     NOT NULL AUTO_INCREMENT COMMENT '主键',
    `work_direction` tinyint(1)           DEFAULT NULL COMMENT '作品方向，0：男频，1：女频''',
    `name`           varchar(20) NOT NULL COMMENT '分类名',
    `sort`           tinyint(4)  NOT NULL DEFAULT '10' COMMENT '排序',
    `create_user_id` bigint(20)           DEFAULT NULL,
    `create_time`    datetime             DEFAULT NULL,
    `update_user_id` bigint(20)           DEFAULT NULL,
    `update_time`    datetime             DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 8
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说类别表';

CREATE TABLE `book_comment`
(
    `id`              bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `book_id`         bigint(20)   DEFAULT NULL COMMENT '小说ID',
    `comment_content` varchar(512) DEFAULT NULL COMMENT '评价内容',
    location varchar(50) DEFAULT NULL COMMENT '地理位置',
    `reply_count`     int(11)      DEFAULT '0' COMMENT '回复数量',
    `audit_status`    tinyint(1)   DEFAULT '0' COMMENT '审核状态，0：待审核，1：审核通过，2：审核不通过',
    `create_time`     datetime     DEFAULT NULL COMMENT '评价时间',
    `create_user_id`  bigint(20)   DEFAULT NULL COMMENT '评价人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_bookid_userid` (`book_id`, `create_user_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 13
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说评论表';

CREATE TABLE `book_comment_reply`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `comment_id`     bigint(20)   DEFAULT NULL COMMENT '评论ID',
    `reply_content`  varchar(512) DEFAULT NULL COMMENT '回复内容',
    `location` varchar(50) DEFAULT NULL COMMENT '地理位置',
    `audit_status`   tinyint(1)   DEFAULT '0' COMMENT '审核状态，0：待审核，1：审核通过，2：审核不通过',
    `create_time`    datetime     DEFAULT NULL COMMENT '回复用户ID',
    `create_user_id` bigint(20)   DEFAULT NULL COMMENT '回复时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说评论回复表';

CREATE TABLE `book_content`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 3347665
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_index`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `book_id`     bigint(20) NOT NULL COMMENT '小说ID',
    `index_num`   int(11)    NOT NULL COMMENT '目录号',
    `index_name`  varchar(100) DEFAULT NULL COMMENT '目录名',
    source_index_id varchar(64) DEFAULT NULL COMMENT '来源站点章节ID（章节身份）',
    `word_count`  int(11)      DEFAULT NULL COMMENT '字数',
    `is_vip`      tinyint(4)   DEFAULT '0' COMMENT '是否收费，1：收费，0：免费',
    `book_price` int(3) DEFAULT 0 COMMENT '章节费用（屋币）',
    storage_type varchar(10) NOT NULL DEFAULT 'db' COMMENT '存储方式',
    `create_time` datetime     DEFAULT NULL,
    `update_time` datetime     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_bookId_indexNum` (`book_id`, `index_num`) USING BTREE,
    KEY `key_bookId` (`book_id`) USING BTREE,
    KEY `key_indexNum` (`index_num`) USING BTREE,
    index idx_book_source (book_id, source_index_id)
) ENGINE = InnoDB
  AUTO_INCREMENT = 1262260612777095169
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说目录表';

CREATE TABLE `book_screen_bullet`
(
    `id`            bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `content_id`    bigint(20)   NOT NULL COMMENT '小说内容ID',
    `screen_bullet` varchar(512) NOT NULL COMMENT '小说弹幕内容',
    `create_time`   datetime     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `key_contentId` (`content_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 79
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说弹幕表';

CREATE TABLE `book_setting`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT,
    `book_id`        bigint(20) DEFAULT NULL COMMENT '小说ID',
    `sort`           tinyint(4) DEFAULT NULL COMMENT '排序号',
    `type`           tinyint(1) DEFAULT NULL COMMENT '类型，0：轮播图，1：顶部小说栏设置，2：本周强推，3：热门推荐，4：精品推荐',
    `create_time`    datetime   DEFAULT NULL COMMENT '创建时间',
    `create_user_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
    `update_time`    datetime   DEFAULT NULL COMMENT '更新时间',
    `update_user_id` bigint(20) DEFAULT NULL COMMENT '更新人ID',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 64
  DEFAULT CHARSET = utf8mb4 COMMENT ='首页小说设置表';

CREATE TABLE `crawl_batch_task`
(
    `id`                  bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `source_id`           int(11)    DEFAULT NULL COMMENT '爬虫源ID',
    `crawl_count_success` int(11)    DEFAULT NULL COMMENT '成功抓取数量',
    `crawl_count_target`  int(11)    DEFAULT NULL COMMENT '目标抓取数量',
    `task_status`         tinyint(1) DEFAULT '1' COMMENT '任务状态，1：正在运行，0已停止',
    `start_time`          datetime   DEFAULT NULL COMMENT '任务开始时间',
    `end_time`            datetime   DEFAULT NULL COMMENT '任务结束时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='批量抓取任务表';

CREATE TABLE `crawl_single_task`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `source_id`      int(11)      DEFAULT NULL COMMENT '爬虫源ID',
    `source_name`    varchar(50)  DEFAULT NULL COMMENT '爬虫源名',
    `source_book_id` varchar(255) DEFAULT NULL COMMENT '源站小说ID',
    `cat_id`         int(11)      DEFAULT NULL COMMENT '分类ID',
    `book_name`      varchar(50)  DEFAULT NULL COMMENT '爬取的小说名',
    `author_name`    varchar(50)  DEFAULT NULL COMMENT '爬取的小说作者名',
    `task_status`    tinyint(1)   DEFAULT '2' COMMENT '任务状态，0：失败，1：成功，2；未执行',
    `exc_count`      tinyint(2)   DEFAULT '0' COMMENT '已经执行次数，最多执行5次',
    crawl_chapters int DEFAULT 0 COMMENT '采集章节数量',
    `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 8
  DEFAULT CHARSET = utf8mb4 COMMENT ='抓取单本小说任务表';

CREATE TABLE `crawl_source`
(
    `id`            int(11) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `source_name`   varchar(50) DEFAULT NULL COMMENT '源站名',
    `crawl_rule`    text COMMENT '爬取规则（json串）',
    `source_status` tinyint(1)  DEFAULT '0' COMMENT '爬虫源状态，0：关闭，1：开启',
    `create_time`   datetime    DEFAULT NULL COMMENT '创建时间',
    `update_time`   datetime    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 5
  DEFAULT CHARSET = utf8mb4 COMMENT ='爬虫源表';

CREATE TABLE `friend_link`
(
    `id`             int(11)      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `link_name`      varchar(50)  NOT NULL COMMENT '链接名',
    `link_url`       varchar(100) NOT NULL COMMENT '链接url',
    `sort`           tinyint(4)   NOT NULL DEFAULT '11' COMMENT '排序号',
    `is_open`        tinyint(1)   NOT NULL DEFAULT '1' COMMENT '是否开启，0：不开启，1：开启',
    `create_user_id` bigint(20)            DEFAULT NULL COMMENT '创建人id',
    `create_time`    datetime              DEFAULT NULL COMMENT '创建时间',
    `update_user_id` bigint(20)            DEFAULT NULL COMMENT '更新者用户id',
    `update_time`    datetime              DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 6
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `news`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `cat_id`         int(11)      DEFAULT NULL COMMENT '类别ID',
    `cat_name`       varchar(50)  DEFAULT NULL COMMENT '分类名',
    `source_name`    varchar(50)  DEFAULT NULL COMMENT '来源',
    `title`          varchar(100) DEFAULT NULL COMMENT '标题',
    `content`        text COMMENT '内容',
    `read_count` BIGINT NOT NULL DEFAULT '0' COMMENT '阅读量',
    `create_time`    datetime     DEFAULT NULL COMMENT '发布时间',
    `create_user_id` bigint(20)   DEFAULT NULL COMMENT '发布人ID',
    `update_time`    datetime     DEFAULT NULL COMMENT '更新时间',
    `update_user_id` bigint(20)   DEFAULT NULL COMMENT '更新人ID',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 3
  DEFAULT CHARSET = utf8mb4 COMMENT ='新闻表';

CREATE TABLE `news_category`
(
    `id`             int(11)     NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`           varchar(20) NOT NULL COMMENT '分类名',
    `sort`           tinyint(4)  NOT NULL DEFAULT '10' COMMENT '排序',
    `create_user_id` bigint(20)           DEFAULT NULL,
    `create_time`    datetime             DEFAULT NULL,
    `update_user_id` bigint(20)           DEFAULT NULL,
    `update_time`    datetime             DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 4
  DEFAULT CHARSET = utf8mb4 COMMENT ='新闻类别表';

CREATE TABLE `order_pay`
(
    `id`           bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `out_trade_no` bigint(20) NOT NULL COMMENT '商户订单号',
    `trade_no`     varchar(64)         DEFAULT NULL COMMENT '支付宝/微信交易号',
    `pay_channel`  tinyint(1) NOT NULL DEFAULT '1' COMMENT '支付渠道，1：支付宝，2：微信',
    `total_amount` int(11)    NOT NULL COMMENT '交易金额(单位元)',
    `user_id`      bigint(20) NOT NULL COMMENT '支付用户ID',
    `pay_status`   tinyint(1)          DEFAULT '2' COMMENT '支付状态：0：支付失败，1：支付成功，2：待支付',
    `create_time`  datetime            DEFAULT NULL COMMENT '创建时间',
    `update_time`  datetime            DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 15
  DEFAULT CHARSET = utf8mb4 COMMENT ='充值订单';

CREATE TABLE `sys_data_perm`
(
    `id`              bigint(20) NOT NULL AUTO_INCREMENT,
    `name`            varchar(50) DEFAULT NULL COMMENT '权限名称',
    `table_name`      varchar(50) DEFAULT NULL COMMENT '数据表名称',
    `module_name`     varchar(50) DEFAULT NULL COMMENT '所属模块',
    `crl_attr_name`   varchar(50) DEFAULT NULL COMMENT '用户权限控制属性名',
    `crl_column_name` varchar(50) DEFAULT NULL COMMENT '数据表权限控制列名',
    `perm_code`       varchar(50) DEFAULT NULL COMMENT '权限code，all_开头表示查看所有数据的权限，sup_开头表示查看下级数据的权限，own_开头表示查看本级数据的权限',
    `order_num`       int(11)     DEFAULT NULL COMMENT '排序',
    `gmt_create`      datetime    DEFAULT NULL COMMENT '创建时间',
    `gmt_modified`    datetime    DEFAULT NULL COMMENT '修改时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 216
  DEFAULT CHARSET = utf8 COMMENT ='数据权限管理';

CREATE TABLE `sys_dept`
(
    `dept_id`   bigint(20) NOT NULL AUTO_INCREMENT,
    `parent_id` bigint(20)  DEFAULT NULL COMMENT '上级部门ID，一级部门为0',
    `name`      varchar(50) DEFAULT NULL COMMENT '部门名称',
    `order_num` int(11)     DEFAULT NULL COMMENT '排序',
    `del_flag`  tinyint(4)  DEFAULT '0' COMMENT '是否删除  -1：已删除  0：正常',
    PRIMARY KEY (`dept_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 17
  DEFAULT CHARSET = utf8 COMMENT ='部门管理';

CREATE TABLE `sys_dict`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT '编号',
    `name`        varchar(100) COLLATE utf8_bin DEFAULT NULL COMMENT '标签名',
    `value`       varchar(100) COLLATE utf8_bin DEFAULT NULL COMMENT '数据值',
    `type`        varchar(100) COLLATE utf8_bin DEFAULT NULL COMMENT '类型',
    `description` varchar(100) COLLATE utf8_bin DEFAULT NULL COMMENT '描述',
    `sort`        decimal(10, 0)                DEFAULT NULL COMMENT '排序（升序）',
    `parent_id`   bigint(20)                    DEFAULT '0' COMMENT '父级编号',
    `create_by`   int(11)                       DEFAULT NULL COMMENT '创建者',
    `create_date` datetime                      DEFAULT NULL COMMENT '创建时间',
    `update_by`   bigint(20)                    DEFAULT NULL COMMENT '更新者',
    `update_date` datetime                      DEFAULT NULL COMMENT '更新时间',
    `remarks`     varchar(255) COLLATE utf8_bin DEFAULT NULL COMMENT '备注信息',
    `del_flag`    char(1) COLLATE utf8_bin      DEFAULT '0' COMMENT '删除标记',
    PRIMARY KEY (`id`),
    KEY `sys_dict_value` (`value`),
    KEY `sys_dict_label` (`name`),
    KEY `sys_dict_del_flag` (`del_flag`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 142
  DEFAULT CHARSET = utf8
  COLLATE = utf8_bin COMMENT ='字典表';

CREATE TABLE `sys_file`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT,
    `type`        int(11)      DEFAULT NULL COMMENT '文件类型',
    `url`         varchar(200) DEFAULT NULL COMMENT 'URL地址',
    `create_date` datetime     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 183
  DEFAULT CHARSET = utf8 COMMENT ='文件上传';

CREATE TABLE `sys_gen_columns`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `table_name`     varchar(64) CHARACTER SET utf8   DEFAULT '' COMMENT '表名',
    `column_name`    varchar(64) CHARACTER SET utf8   DEFAULT '' COMMENT '列名',
    `column_type`    varchar(64) CHARACTER SET utf8   DEFAULT NULL COMMENT '列类型',
    `java_type`      varchar(64) CHARACTER SET utf8   DEFAULT NULL COMMENT '映射java类型',
    `column_comment` varchar(1024) CHARACTER SET utf8 DEFAULT '' COMMENT '列注释',
    `column_sort`    tinyint(4)                       DEFAULT NULL COMMENT '列排序（升序）',
    `column_label`   varchar(64)                      DEFAULT NULL COMMENT '鍒楁爣绛惧悕',
    `page_type`      tinyint(4)                       DEFAULT '1' COMMENT '页面显示类型：1、文本框 2、下拉框 3、数值4、日期 5、文本域6、富文本 7、上传图片【单文件】 8、上传图片【多文件】9、上传文件【单文件】 10、上传文件【多文件】11、隐藏域 12、不显示',
    `is_required`    tinyint(1)                       DEFAULT NULL COMMENT '是否必填',
    `dict_type`      varchar(100) CHARACTER SET utf8  DEFAULT '' COMMENT '页面显示为下拉时使用，字典类型从字典表中取出',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 815
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `sys_gen_table`
(
    `id`                   bigint(20)   NOT NULL COMMENT '主键',
    `table_name`           varchar(64)  NOT NULL COMMENT '表名',
    `class_name`           varchar(100) NOT NULL COMMENT '实体类名称',
    `comments`             varchar(500) NOT NULL COMMENT '表说明',
    `category`             tinyint(1)   NOT NULL DEFAULT '0' COMMENT '分类：0：数据表，1：树表',
    `package_name`         varchar(500)          DEFAULT NULL COMMENT '生成包路径',
    `module_name`          varchar(30)           DEFAULT NULL COMMENT '生成模块名',
    `sub_module_name`      varchar(30)           DEFAULT NULL COMMENT '生成子模块名',
    `function_name`        varchar(200)          DEFAULT NULL COMMENT '生成功能名，用于类描述',
    `function_name_simple` varchar(50)           DEFAULT NULL COMMENT '生成功能名（简写），用于功能提示，如“保存xx成功”',
    `author`               varchar(50)           DEFAULT NULL COMMENT '生成功能作者',
    `src_dir`              varchar(1000)         DEFAULT NULL COMMENT 'src目录',
    `options`              varchar(1000)         DEFAULT NULL COMMENT '其它生成选项',
    `create_by`            bigint(20)   NOT NULL COMMENT '创建者',
    `create_date`          datetime     NOT NULL COMMENT '创建时间',
    `update_by`            bigint(20)   NOT NULL COMMENT '更新者',
    `update_date`          datetime     NOT NULL COMMENT '更新时间',
    `remarks`              varchar(500)          DEFAULT NULL COMMENT '备注信息',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8 COMMENT ='代码生成表';

CREATE TABLE `sys_gen_table_column`
(
    `id`           bigint(20)   NOT NULL COMMENT '主键',
    `table_id`     bigint(20)   NOT NULL COMMENT '表id',
    `column_name`  varchar(64)  NOT NULL COMMENT '列名',
    `column_sort`  decimal(10, 0) DEFAULT NULL COMMENT '列排序（升序）',
    `column_type`  varchar(100) NOT NULL COMMENT '类型',
    `column_label` varchar(50)    DEFAULT NULL COMMENT '列标签名',
    `comments`     varchar(500) NOT NULL COMMENT '列备注说明',
    `attr_name`    varchar(200) NOT NULL COMMENT '类的属性名',
    `attr_type`    varchar(200) NOT NULL COMMENT '类的属性类型',
    `is_pk`        char(1)        DEFAULT NULL COMMENT '是否主键',
    `is_null`      char(1)        DEFAULT NULL COMMENT '是否可为空',
    `is_insert`    char(1)        DEFAULT NULL COMMENT '是否插入字段',
    `is_update`    char(1)        DEFAULT NULL COMMENT '是否更新字段',
    `is_list`      char(1)        DEFAULT NULL COMMENT '是否列表字段',
    `is_query`     char(1)        DEFAULT NULL COMMENT '是否查询字段',
    `query_type`   varchar(200)   DEFAULT NULL COMMENT '查询方式',
    `is_edit`      char(1)        DEFAULT NULL COMMENT '是否编辑字段',
    `show_type`    varchar(200)   DEFAULT NULL COMMENT '表单类型',
    `options`      varchar(1000)  DEFAULT NULL COMMENT '其它生成选项',
    PRIMARY KEY (`id`),
    KEY `idx_gen_table_column_tn` (`table_id`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8 COMMENT ='代码生成表列';

CREATE TABLE `sys_log`
(
    `id`         bigint(20) NOT NULL AUTO_INCREMENT,
    `user_id`    bigint(20)    DEFAULT NULL COMMENT '用户id',
    `username`   varchar(50)   DEFAULT NULL COMMENT '用户名',
    `operation`  varchar(50)   DEFAULT NULL COMMENT '用户操作',
    `time`       int(11)       DEFAULT NULL COMMENT '响应时间',
    `method`     varchar(200)  DEFAULT NULL COMMENT '请求方法',
    `params`     varchar(5000) DEFAULT NULL COMMENT '请求参数',
    `ip`         varchar(64)   DEFAULT NULL COMMENT 'IP地址',
    `gmt_create` datetime      DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 1412
  DEFAULT CHARSET = utf8 COMMENT ='系统日志';

CREATE TABLE `sys_menu`
(
    `menu_id`      bigint(20) NOT NULL AUTO_INCREMENT,
    `parent_id`    bigint(20)   DEFAULT NULL COMMENT '父菜单ID，一级菜单为0',
    `name`         varchar(50)  DEFAULT NULL COMMENT '菜单名称',
    `url`          varchar(200) DEFAULT NULL COMMENT '菜单URL',
    `perms`        varchar(500) DEFAULT NULL COMMENT '授权(多个用逗号分隔，如：user:list,user:create)',
    `type`         int(11)      DEFAULT NULL COMMENT '类型   0：目录   1：菜单   2：按钮',
    `icon`         varchar(50)  DEFAULT NULL COMMENT '菜单图标',
    `order_num`    int(11)      DEFAULT NULL COMMENT '排序',
    `gmt_create`   datetime     DEFAULT NULL COMMENT '创建时间',
    `gmt_modified` datetime     DEFAULT NULL COMMENT '修改时间',
    PRIMARY KEY (`menu_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 234
  DEFAULT CHARSET = utf8 COMMENT ='菜单管理';

CREATE TABLE `sys_role`
(
    `role_id`        bigint(20) NOT NULL AUTO_INCREMENT,
    `role_name`      varchar(100) DEFAULT NULL COMMENT '角色名称',
    `role_sign`      varchar(100) DEFAULT NULL COMMENT '角色标识',
    `remark`         varchar(100) DEFAULT NULL COMMENT '备注',
    `user_id_create` bigint(20)   DEFAULT NULL COMMENT '创建用户id',
    `gmt_create`     datetime     DEFAULT NULL COMMENT '创建时间',
    `gmt_modified`   datetime     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`role_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 62
  DEFAULT CHARSET = utf8 COMMENT ='角色';

CREATE TABLE `sys_role_data_perm`
(
    `id`      bigint(20) NOT NULL AUTO_INCREMENT,
    `role_id` bigint(20) DEFAULT NULL COMMENT '角色ID',
    `perm_id` bigint(20) DEFAULT NULL COMMENT '权限ID',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 79
  DEFAULT CHARSET = utf8 COMMENT ='角色与数据权限对应关系';

CREATE TABLE `sys_role_menu`
(
    `id`      bigint(20) NOT NULL AUTO_INCREMENT,
    `role_id` bigint(20) DEFAULT NULL COMMENT '角色ID',
    `menu_id` bigint(20) DEFAULT NULL COMMENT '菜单ID',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 4830
  DEFAULT CHARSET = utf8 COMMENT ='角色与菜单对应关系';

CREATE TABLE `sys_user`
(
    `user_id`        bigint(20) NOT NULL AUTO_INCREMENT,
    `username`       varchar(50)  DEFAULT NULL COMMENT '用户名',
    `name`           varchar(100) DEFAULT NULL,
    `password`       varchar(50)  DEFAULT NULL COMMENT '密码',
    `dept_id`        bigint(20)   DEFAULT NULL,
    `email`          varchar(100) DEFAULT NULL COMMENT '邮箱',
    `mobile`         varchar(100) DEFAULT NULL COMMENT '手机号',
    `status`         tinyint(4)   DEFAULT NULL COMMENT '状态 0:禁用，1:正常',
    `user_id_create` bigint(20)   DEFAULT NULL COMMENT '创建用户id',
    `gmt_create`     datetime     DEFAULT NULL COMMENT '创建时间',
    `gmt_modified`   datetime     DEFAULT NULL COMMENT '修改时间',
    `sex`            bigint(20)   DEFAULT NULL COMMENT '性别',
    `birth`          datetime     DEFAULT NULL COMMENT '出身日期',
    `pic_id`         bigint(20)   DEFAULT NULL,
    `live_address`   varchar(500) DEFAULT NULL COMMENT '现居住地',
    `hobby`          varchar(255) DEFAULT NULL COMMENT '爱好',
    `province`       varchar(255) DEFAULT NULL COMMENT '省份',
    `city`           varchar(255) DEFAULT NULL COMMENT '所在城市',
    `district`       varchar(255) DEFAULT NULL COMMENT '所在地区',
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 139
  DEFAULT CHARSET = utf8;

CREATE TABLE `sys_user_role`
(
    `id`      bigint(20) NOT NULL AUTO_INCREMENT,
    `user_id` bigint(20) DEFAULT NULL COMMENT '用户ID',
    `role_id` bigint(20) DEFAULT NULL COMMENT '角色ID',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 137
  DEFAULT CHARSET = utf8 COMMENT ='用户与角色对应关系';

CREATE TABLE `user`
(
    `id`              bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`        varchar(50)  NOT NULL COMMENT '登录名',
    `password`        varchar(100) NOT NULL COMMENT '登录密码',
    `nick_name`       varchar(50)           DEFAULT NULL COMMENT '昵称',
    `user_photo`      varchar(100)          DEFAULT NULL COMMENT '用户头像',
    `user_sex`        tinyint(1)            DEFAULT NULL COMMENT '用户性别，0：男，1：女',
    `account_balance` bigint(20)   NOT NULL DEFAULT '0' COMMENT '账户余额',
    `status`          tinyint(1)   NOT NULL DEFAULT '0' COMMENT '用户状态，0：正常',
    `create_time`     datetime     NOT NULL COMMENT '创建时间',
    `update_time`     datetime     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_username` (`username`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1255664783722586113
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `user_bookshelf`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        bigint(20) NOT NULL COMMENT '用户ID',
    `book_id`        bigint(20) NOT NULL COMMENT '小说ID',
    `pre_content_id` bigint(20) DEFAULT NULL COMMENT '上一次阅读的章节内容表ID',
    `create_time`    datetime   DEFAULT NULL,
    `update_time`    datetime   DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_userid_bookid` (`user_id`, `book_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 42
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户书架表';

CREATE TABLE `user_buy_record`
(
    `id`              bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         bigint(20) NOT NULL COMMENT '用户ID',
    `book_id`         bigint(20)   DEFAULT NULL COMMENT '购买的小说ID',
    `book_name`       varchar(50)  DEFAULT NULL COMMENT '购买的小说名',
    `book_index_id`   bigint(20)   DEFAULT NULL COMMENT '购买的章节ID',
    `book_index_name` varchar(100) DEFAULT NULL COMMENT '购买的章节名',
    `buy_amount`      int(11)      DEFAULT NULL COMMENT '购买使用的屋币数量',
    `create_time`     datetime     DEFAULT NULL COMMENT '购买时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_userId_indexId` (`user_id`, `book_index_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 3
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户消费记录表';

CREATE TABLE `user_feedback`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id',
    `user_id`     bigint(20)   DEFAULT NULL COMMENT '用户id',
    `content`     varchar(512) DEFAULT NULL COMMENT '反馈内容',
    `create_time` datetime     DEFAULT NULL COMMENT '反馈时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 9
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `user_read_history`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        bigint(20) NOT NULL COMMENT '用户ID',
    `book_id`        bigint(20) NOT NULL COMMENT '小说ID',
    `pre_content_id` bigint(20) DEFAULT NULL COMMENT '上一次阅读的章节内容表ID',
    `create_time`    datetime   DEFAULT NULL,
    `update_time`    datetime   DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_userid_bookid` (`user_id`, `book_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 119
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户阅读记录表';

CREATE TABLE `book_content0`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1155
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_content1`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 406
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_content2`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1222
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_content3`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 410
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_content4`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1188
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_content5`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 416
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_content6`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1180
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_content7`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 404
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_content8`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1134
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `book_content9`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `index_id` bigint(20) DEFAULT NULL COMMENT '目录ID',
    `content`  mediumtext COMMENT '小说章节内容',
    PRIMARY KEY (`id`),
    UNIQUE KEY `key_uq_indexId` (`index_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 415
  DEFAULT CHARSET = utf8mb4 COMMENT ='小说内容表';

CREATE TABLE `author_income_detail`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        bigint(20) NOT NULL COMMENT '用户ID',
    `author_id`      bigint(20) NOT NULL COMMENT '作家ID',
    `book_id`        bigint(20) NOT NULL DEFAULT '0' COMMENT '作品ID,0表示全部作品',
    `income_date`    date       NOT NULL COMMENT '收入日期',
    `income_account` int(11)    NOT NULL DEFAULT '0' COMMENT '订阅总额',
    `income_count`   int(11)    NOT NULL DEFAULT '0' COMMENT '订阅次数',
    `income_number`  int(11)    NOT NULL DEFAULT '0' COMMENT '订阅人数',
    `create_time`    datetime            DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='稿费收入明细统计表';

CREATE TABLE `author_income`
(
    `id`               bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          bigint(20) NOT NULL COMMENT '用户ID',
    `author_id`        bigint(20) NOT NULL COMMENT '作家ID',
    `book_id`          bigint(20) NOT NULL COMMENT '作品ID',
    `income_month`     date       NOT NULL COMMENT '收入月份',
    `pre_tax_income`   bigint(20) NOT NULL DEFAULT '0' COMMENT '税前收入（分）',
    `after_tax_income` bigint(20) NOT NULL DEFAULT '0' COMMENT '税后收入（分）',
    `pay_status`       tinyint(1) NOT NULL DEFAULT '0' COMMENT '支付状态，0：待支付，1：已支付',
    `confirm_status`   tinyint(1) NOT NULL DEFAULT '0' COMMENT '稿费确认状态，0：待确认，1：已确认',
    `detail`           varchar(255)        DEFAULT NULL COMMENT '详情',
    `create_time`      datetime            DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='稿费收入统计表';

CREATE TABLE `website_info`
(
    id             bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    name           varchar(50)  NOT NULL COMMENT '网站名',
    domain         varchar(50)  NOT NULL COMMENT '网站域名',
    keyword        varchar(300) NOT NULL COMMENT 'SEO关键词',
    description    varchar(512) NOT NULL COMMENT '网站描述',
    qq             varchar(20)  NOT NULL COMMENT '站长QQ',
    logo           varchar(200) NOT NULL COMMENT '网站logo图片（默认）',
    logo_dark      varchar(200) NOT NULL COMMENT '网站logo图片（深色）',
    create_time    datetime     null comment '创建时间',
    create_user_id bigint       null comment '创建人ID',
    update_time    datetime     null comment '更新时间',
    update_user_id bigint       null comment '更新人ID',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='网站信息表';

-- Public system/configuration seed records.
  
        

  
        

  
        

  
        

  
        

  
        

  
        

  
       

  
       

  
          

  
          

  
          

  
          

  
          

  
          

  
     

  
     

  
     

  
     

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
             

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
    

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
     
             

  
           

  
           

  
           

  
           

  
          
        

  
           

  
          
        

  
           

  
       
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
       
           

  
           

  
           

  
         
         

  
          
        

  
          
        

  
          
        

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
           

  
          
        

  
           

  
           

  
           

  
           

  
          
        

  
           

  
           

  
                
         

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
          

  
       

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
         
               
         

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

  
   

          
                       
          

          
                       
          

          
                       
          

          
                       
          

          
                       
          

          
                       
          

          
                       
          

          
                       
          

          
                       
          

          
                       
          

          
                       
          

          
                       
          

          
                       
          

    
   

    
   

    
   

    
   

    
   

    
   

    
   

    
   

    
   

    
   

    
   

    
   

    
   

            
                           
     
         
         
            

            
          


        
        

    
  

    
  

          
        

          
        

          
        

          
        

          
        

          
        

    
  

    
  

    
  

    
  

    
  

    
  

            
          


        
        

    
  

    
  

            
          


        
        

    
  

    
  

            
          


        
        

    
  

    
  

          
        

    
  


        
        

    
  

          
        

    
  

           
                        
            

           
                        
            

           
                        
            

           
                        
            

           
                        
            

          
        

          
        

          
        

          
        

          
        

          
        

    
  

    
  

    
  

    
  

    
  

    
  

          
        

    
  

          
        

    
  

-- 2026-10-08 additive permission definitions for CURRENT novel-admin controllers.
-- Apply AFTER the canonical current schema on fresh installations. Existing
-- installations require their own schema/data backup and permission review.
-- This file is idempotent: adds only missing definitions, never roles/grants.
-- No UPDATE, DELETE, DROP, TRUNCATE, wildcard authorization or data replacement.
-- New menu IDs exceed BOTH existing menu IDs and orphan role_menu references.
-- Reading history already uses user_read_history(pre_content_id) with the unique
-- (user_id, book_id) key. Chapter reports reuse user_feedback.content varchar(512).
-- These SELECTs validate their required columns without creating imaginary tables.
SELECT id,user_id,book_id,pre_content_id,create_time,update_time FROM user_read_history LIMIT 0;
SELECT id,user_id,content,create_time FROM user_feedback LIMIT 0;
SET @admin_migration_lock = GET_LOCK(CONCAT('novel-admin-perms-v1:', MD5(DATABASE())), 10);
SET @admin_migration_gate = IF(@admin_migration_lock=1,
    'SELECT ''Permission migration lock acquired'' AS status',
    'SELECT * FROM information_schema.novel_admin_permission_migration_refused_lock');
PREPARE admin_migration_guard FROM @admin_migration_gate;
EXECUTE admin_migration_guard;
DEALLOCATE PREPARE admin_migration_guard;
START TRANSACTION;
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'author列表','novel/author','novel:author:author',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:author:author',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'author新增','','novel:author:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:author:author' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:author:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'author批量删除','','novel:author:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:author:author' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:author:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'author查看','','novel:author:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:author:author' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:author:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'author修改','','novel:author:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:author:author' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:author:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'author删除','','novel:author:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:author:author' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:author:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'authorCode列表','novel/authorCode','novel:authorCode:authorCode',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:authorCode:authorCode',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'authorCode新增','','novel:authorCode:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:authorCode:authorCode' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:authorCode:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'authorCode批量删除','','novel:authorCode:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:authorCode:authorCode' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:authorCode:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'authorCode查看','','novel:authorCode:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:authorCode:authorCode' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:authorCode:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'authorCode修改','','novel:authorCode:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:authorCode:authorCode' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:authorCode:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'authorCode删除','','novel:authorCode:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:authorCode:authorCode' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:authorCode:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'书籍列表','novel/book','novel:book:book',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:book:book',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'书籍新增','','novel:book:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:book:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'书籍批量删除','','novel:book:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:book:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'书籍查看','','novel:book:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:book:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'书籍修改','','novel:book:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:book:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'书籍删除','','novel:book:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:book:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'评论列表','novel/bookComment','novel:bookComment:bookComment',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookComment:bookComment',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'评论新增','','novel:bookComment:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookComment:bookComment' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookComment:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'评论批量删除','','novel:bookComment:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookComment:bookComment' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookComment:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'评论查看','','novel:bookComment:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookComment:bookComment' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookComment:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'评论修改','','novel:bookComment:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookComment:bookComment' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookComment:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'评论删除','','novel:bookComment:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookComment:bookComment' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookComment:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'正文列表','novel/bookContent','novel:bookContent:bookContent',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookContent:bookContent',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'正文新增','','novel:bookContent:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookContent:bookContent' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookContent:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'正文批量删除','','novel:bookContent:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookContent:bookContent' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookContent:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'正文查看','','novel:bookContent:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookContent:bookContent' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookContent:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'正文修改','','novel:bookContent:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookContent:bookContent' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookContent:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'正文删除','','novel:bookContent:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookContent:bookContent' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookContent:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'章节目录列表','novel/bookIndex','novel:bookIndex:bookIndex',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookIndex:bookIndex',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'章节目录新增','','novel:bookIndex:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookIndex:bookIndex' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookIndex:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'章节目录批量删除','','novel:bookIndex:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookIndex:bookIndex' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookIndex:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'章节目录查看','','novel:bookIndex:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookIndex:bookIndex' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookIndex:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'章节目录修改','','novel:bookIndex:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookIndex:bookIndex' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookIndex:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'章节目录删除','','novel:bookIndex:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookIndex:bookIndex' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookIndex:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'bookSetting列表','novel/bookSetting','novel:bookSetting:bookSetting',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookSetting:bookSetting',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'bookSetting新增','','novel:bookSetting:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookSetting:bookSetting' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookSetting:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'bookSetting批量删除','','novel:bookSetting:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookSetting:bookSetting' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookSetting:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'bookSetting查看','','novel:bookSetting:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookSetting:bookSetting' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookSetting:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'bookSetting修改','','novel:bookSetting:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookSetting:bookSetting' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookSetting:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'bookSetting删除','','novel:bookSetting:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:bookSetting:bookSetting' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:bookSetting:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'category列表','novel/category','novel:category:category',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:category:category',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'category新增','','novel:category:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:category:category' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:category:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'category批量删除','','novel:category:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:category:category' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:category:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'category查看','','novel:category:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:category:category' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:category:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'category修改','','novel:category:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:category:category' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:category:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'category删除','','novel:category:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:category:category' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:category:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'friendLink列表','novel/friendLink','novel:friendLink:friendLink',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:friendLink:friendLink',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'friendLink新增','','novel:friendLink:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:friendLink:friendLink' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:friendLink:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'friendLink批量删除','','novel:friendLink:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:friendLink:friendLink' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:friendLink:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'friendLink查看','','novel:friendLink:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:friendLink:friendLink' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:friendLink:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'friendLink修改','','novel:friendLink:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:friendLink:friendLink' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:friendLink:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'friendLink删除','','novel:friendLink:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:friendLink:friendLink' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:friendLink:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'news列表','novel/news','novel:news:news',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:news:news',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'news新增','','novel:news:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:news:news' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:news:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'news批量删除','','novel:news:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:news:news' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:news:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'news查看','','novel:news:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:news:news' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:news:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'news修改','','novel:news:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:news:news' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:news:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'news删除','','novel:news:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:news:news' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:news:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'支付列表','novel/pay','novel:pay:pay',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:pay:pay',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'支付新增','','novel:pay:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:pay:pay' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:pay:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'支付批量删除','','novel:pay:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:pay:pay' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:pay:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'支付查看','','novel:pay:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:pay:pay' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:pay:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'支付修改','','novel:pay:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:pay:pay' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:pay:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'支付删除','','novel:pay:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:pay:pay' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:pay:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'用户列表','novel/user','novel:user:user',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:user:user',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'用户新增','','novel:user:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:user:user' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:user:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'用户批量删除','','novel:user:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:user:user' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:user:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'用户查看','','novel:user:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:user:user' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:user:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'用户修改','','novel:user:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:user:user' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:user:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'用户删除','','novel:user:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:user:user' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:user:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'反馈列表','novel/userFeedback','novel:userFeedback:userFeedback',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:userFeedback:userFeedback',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'反馈新增','','novel:userFeedback:add',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:userFeedback:userFeedback' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:userFeedback:add',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'反馈批量删除','','novel:userFeedback:batchRemove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:userFeedback:userFeedback' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:userFeedback:batchRemove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'反馈查看','','novel:userFeedback:detail',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:userFeedback:userFeedback' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:userFeedback:detail',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'反馈修改','','novel:userFeedback:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:userFeedback:userFeedback' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:userFeedback:edit',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'反馈删除','','novel:userFeedback:remove',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:userFeedback:userFeedback' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:userFeedback:remove',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,parent_id,'websiteInfo列表','novel/websiteInfo','novel:websiteInfo:websiteInfo',1,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:book:book' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:websiteInfo:websiteInfo',REPLACE(perms,' ',''))>0);
SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) SELECT @admin_permission_id,menu_id,'websiteInfo修改','','novel:websiteInfo:edit',2,90,NOW(),NOW() FROM sys_menu WHERE perms='novel:websiteInfo:websiteInfo' AND @admin_migration_lock=1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('novel:websiteInfo:edit',REPLACE(perms,' ',''))>0);
COMMIT;
SELECT JSON_OBJECT('migrationLockAcquired',@admin_migration_lock);
SELECT RELEASE_LOCK(CONCAT('novel-admin-perms-v1:', MD5(DATABASE())));

SET @novel_initial_admin_hash = NULL;
SET @novel_initial_site_name = NULL;
SET @novel_initial_site_domain = NULL;
SELECT RELEASE_LOCK(@novel_bootstrap_lock_name);
