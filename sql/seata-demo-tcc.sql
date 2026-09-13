-- =============================================================================
--  seata-demo-tcc.sql —— 本项目 TCC 版全量建库脚本（从零导入，可重复执行）
-- -----------------------------------------------------------------------------
--  用途     ：在任意一台装了 MySQL 8 的机器上，执行本文件即可从零得到本项目
--             可直接跑通的完整数据库（库 + 7 张表 + 基线数据），无需任何前置脚本。
--  版本基线 ：Seata 2.6.0（TC store.mode = file）/ Dubbo 3.3.6 /
--             Spring Boot 2.2.2 / MySQL 8.4
--  依据来源 ：改造后的实体类与 Mapper XML，以及 2026-09-11 从实测库导出并逐列
--             比对过的真实结构（2026-09-11 与当时的线上库逐列 diff 无差异）
--  日期     ：2026-09-11
--  一句话结论：TCC 相比 AT **多四处结构、少一张表** —— 多的是 t_account.frozen、
--             t_storage.frozen、t_order.status、tcc_transaction_control；
--             少的是 undo_log（AT 专属的回滚镜像表，TCC 不写也不需要）。
--             详见脚本内「三、为什么没有 undo_log」一节。
--
--  本脚本做三件事
--    ① DROP DATABASE IF EXISTS `seata-demo-tcc`      —— 连库带表全部清空
--    ② CREATE DATABASE `seata-demo-tcc`              —— utf8mb4 / utf8mb4_0900_ai_ci
--    ③ 建 7 张表 + 灌基线数据 + 末尾自检
--
--  ⚠️⚠️ 危险提示（先读再执行）
--    第 ① 步是 DROP DATABASE，`seata-demo-tcc` 库里**所有数据和表都会消失且不可恢复**。
--    · 只想"重建表、不动库"（比如日常重跑）→ 把下面「步骤 ①」的
--      DROP DATABASE 那一行注释掉即可：本脚本每张表都带 DROP TABLE IF EXISTS。
--    · 只要库里还有你想留的数据，执行前先 mysqldump 备份。
--
--  ✅ 服务要不要重启？**不需要**（2026-09-11 实测）：
--     DROP DATABASE → 重跑本脚本 → **不重启** 4 个业务服务，直接打
--     buy（HTTP 200，amount 4000→3900、count 1000→950、订单 status=1、
--     控制表 3 行 CONFIRMED）与 buy2（HTTP 500 预期异常，3900/950 保持不动、
--     占位单 status=2、控制表再 3 行 CANCELED）均通过。
--     （连接池拿到失效连接会自行重建，同名新库不受影响。）
--
--  本脚本包含的表
--    +-------------------------+-------------+----------------------------------+
--    | 表                      | 归属         | 说明                             |
--    +-------------------------+-------------+----------------------------------+
--    | t_account               | 业务（TCC）  | 账户：amount 可用 / frozen 冻结   |
--    | t_storage               | 业务（TCC）  | 库存：count 可用 / frozen 冻结    |
--    | t_order                 | 业务（TCC）  | 订单：status 0=TRYING 1=CONF 2=CAN|
--    | tcc_transaction_control | TCC 控制表   | 幂等 / 空回滚 / 防悬挂            |
--    | branch_table            | TC 服务端    | store.mode=file，本 demo 用不到   |
--    | global_table            | TC 服务端    | 同上                             |
--    | lock_table              | TC 服务端    | 同上                             |
--    +-------------------------+-------------+----------------------------------+
--    ⚠️ 没有 undo_log —— 那是 AT 专属的回滚镜像表，TCC 不写也不需要（理由见下面「三、」）。
--
--  执行方式
--    命令行（推荐，编码显式指定，避免中文 COMMENT 乱码）：
--      mysql -h127.0.0.1 -uroot -proot --default-character-set=utf8mb4 \
--            < sql/seata-demo-tcc.sql
--    Navicat：右键连接 → 运行 SQL 文件 → 选中本文件 → 编码选 65001(UTF-8)
--    执行完会输出三段自检结果，看到"表清单 7 行 / TCC 专属列 3 行 /
--    基线数据 amount=4000 count=1000"即为正常。
--
--  与同目录其它脚本的关系
--    sql/db-seata.sql              **AT 时代的旧建库脚本**（2026-09-13 从 git master
--                                  原样取回，仅历史留档 / 回退 AT 时参考）——
--                                  它建的是 AT 结构（有 undo_log，无 frozen/status/
--                                  控制表），**别在 TCC 库上执行**。
--    已删除（2026-09-13）：db-seata-tcc-upgrade.sql（只服务"AT 老库原地升级"）、
--                        seata_demo.sql（Navicat 脏快照）。
-- =============================================================================


-- =============================================================================
-- 步骤 ① 清库（危险：库内所有数据将丢失）
-- =============================================================================
DROP DATABASE IF EXISTS `seata-demo-tcc`;


-- =============================================================================
-- 步骤 ② 建库
--   显式写死 utf8mb4 + utf8mb4_0900_ai_ci，避免受服务端默认字符集影响。
--   （MySQL 5.7 无 utf8mb4_0900_ai_ci 排序规则，若在 5.7 上跑请把 COLLATE 行删掉）
-- =============================================================================
CREATE DATABASE `seata-demo-tcc`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `seata-demo-tcc`;

-- 客户端连接字符集：保证本文件里的中文 COMMENT / 中文商品名按 utf8mb4 解析
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


-- =============================================================================
-- 步骤 ③ 建表与初始化数据
-- =============================================================================

-- =============================================================================
-- 一、业务表（TCC 三段式）
--     三个 RM 的共同套路：Try 只动「预留量」，Confirm 才动「可用量」，Cancel 只回退预留。
--     可用量与预留量分列存放，是 TCC 与 AT 最本质的差别 —— AT 靠数据库行锁挡住并发，
--     TCC 靠 frozen 字段把「中间态」显式暴露出来，不必持有长事务锁。
-- =============================================================================

-- ----------------------------
-- t_account：账户表
--   Try     : frozen += amount（前置校验 amount - frozen >= amount）
--   Confirm : amount -= amount, frozen -= amount
--   Cancel  : frozen -= amount
-- ----------------------------
DROP TABLE IF EXISTS `t_account`;
CREATE TABLE `t_account` (
  `id`      int          NOT NULL AUTO_INCREMENT,
  `user_id` varchar(255) DEFAULT NULL            COMMENT '用户 ID',
  `amount`  double(14,2) DEFAULT '0.00'          COMMENT '可用余额（仅在 Confirm 阶段扣减）',
  `frozen`  double(14,2) NOT NULL DEFAULT '0.00' COMMENT 'TCC 预留(冻结)金额：Try 加、Confirm/Cancel 减',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户表（TCC 模式）';

-- ----------------------------
-- Records of t_account —— 基线：可用 4000.00，冻结 0.00
-- ----------------------------
INSERT INTO `t_account` (`id`, `user_id`, `amount`, `frozen`) VALUES (1, '1', 4000.00, 0.00);

-- ----------------------------
-- t_storage：库存表
--   Try     : frozen += count（前置校验 count - frozen >= count）
--   Confirm : count -= count, frozen -= count
--   Cancel  : frozen -= count
-- ----------------------------
DROP TABLE IF EXISTS `t_storage`;
CREATE TABLE `t_storage` (
  `id`             int          NOT NULL AUTO_INCREMENT,
  `commodity_code` varchar(255) DEFAULT NULL       COMMENT '商品编码',
  `name`           varchar(255) DEFAULT NULL       COMMENT '商品名称',
  `count`          int          DEFAULT '0'        COMMENT '可用库存（仅在 Confirm 阶段扣减）',
  `frozen`         int          NOT NULL DEFAULT 0 COMMENT 'TCC 预留(冻结)库存：Try 加、Confirm/Cancel 减',
  PRIMARY KEY (`id`),
  UNIQUE KEY `commodity_code` (`commodity_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存表（TCC 模式）';

-- ----------------------------
-- Records of t_storage —— 基线：可用 1000，冻结 0
-- ----------------------------
INSERT INTO `t_storage` (`id`, `commodity_code`, `name`, `count`, `frozen`)
VALUES (1, 'C201901140001', '水杯', 1000, 0);

-- ----------------------------
-- t_order：订单表
--   Try     : INSERT status = 0（占位单，此时订单对业务不可见）
--   Confirm : status 0 -> 1（订单生效）
--   Cancel  : status 0 -> 2（订单作废，行保留，不物理删除）
--
--   uk_order_no 说明：orderNo 由业务生成，是 Confirm/Cancel 定位订单的键。
--   唯一索引是「重复 Try」的第二道防线（第一道是 tcc_transaction_control 的幂等判定）。
--   注意 MySQL 唯一索引允许多个 NULL，历史数据 order_no 为空不影响。
-- ----------------------------
DROP TABLE IF EXISTS `t_order`;
CREATE TABLE `t_order` (
  `id`             int          NOT NULL AUTO_INCREMENT,
  `order_no`       varchar(255) DEFAULT NULL       COMMENT '业务生成的订单号（Confirm/Cancel 定位键）',
  `user_id`        varchar(255) DEFAULT NULL       COMMENT '用户 ID',
  `commodity_code` varchar(255) DEFAULT NULL       COMMENT '商品编码',
  `count`          int          DEFAULT '0'        COMMENT '购买数量',
  `amount`         double(14,2) DEFAULT '0.00'     COMMENT '订单金额',
  `status`         tinyint      NOT NULL DEFAULT 0 COMMENT 'TCC 订单状态: 0=TRYING 1=CONFIRMED 2=CANCELED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表（TCC 模式）';

-- ----------------------------
-- Records of t_order —— 空表：订单由 buy / buy2 用例运行时生成
-- ----------------------------


-- =============================================================================
-- 二、TCC 事务控制表
--     承载「幂等 / 空回滚 / 防悬挂」三道防护，由 samples-common 的 TccControlSupport
--     统一实现（三个 RM 服务共用同一张表）。
--
--   状态机（status 列）：
--     1 = TRIED       Try 已执行、资源已预留
--     2 = CONFIRMED   二阶段提交完成（终态）
--     3 = CANCELED    二阶段回滚完成（终态）
--
--   Try     : INSERT status = 1
--             若已存在 status = 3 的记录  -> 判定为「悬挂」，直接拒绝执行并抛异常
--   Confirm : status 1 -> 2；已是 2 则直接返回（幂等）
--   Cancel  : 无记录       -> INSERT status = 3（空回滚：Try 从未成功，留下悬挂判据）
--             status 1     -> 3，同时释放 frozen 预留（回滚）
--             已 3          -> 直接返回（幂等）
-- =============================================================================
DROP TABLE IF EXISTS `tcc_transaction_control`;
CREATE TABLE `tcc_transaction_control` (
  `xid`          varchar(128) NOT NULL COMMENT '全局事务 ID',
  `branch_id`    bigint       NOT NULL COMMENT '分支事务 ID',
  `action_name`  varchar(64)  NOT NULL COMMENT '@TwoPhaseBusinessAction.name',
  `status`       tinyint      NOT NULL COMMENT '1=TRIED 2=CONFIRMED 3=CANCELED',
  `gmt_create`   datetime     NOT NULL COMMENT '创建时间',
  `gmt_modified` datetime     NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`xid`, `branch_id`),
  KEY `idx_status` (`status`),
  KEY `idx_gmt_modified` (`gmt_modified`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TCC 事务控制表：幂等/空回滚/防悬挂';

-- ----------------------------
-- Records of tcc_transaction_control —— 空表：分支记录由 Seata 二阶段回调写入
-- ----------------------------


-- =============================================================================
-- 三、为什么没有 undo_log？（TCC 不需要，本脚本不建）
--     undo_log 是 AT 模式专属的回滚镜像表：AT 在一阶段把每条被修改记录的前镜像
--     写进 undo_log，二阶段回滚时据此自动生成反向 SQL。
--     TCC 不走这条路 —— 回滚依据是业务自己写的 Cancel 方法，加上业务字段
--     （t_account.frozen / t_storage.frozen / t_order.status）与
--     tcc_transaction_control；Seata 只负责「在正确的时机回调它们」。
--     所以 TCC 下没有任何代码路径会读写 undo_log：
--       · 改造时已把三个 RM 的 enable-auto-data-source-proxy 置为 false，
--         AT 数据源代理根本没启用（开着才会「无条件」把 SQL 注册成 AT 分支并写它）；
--       · 实测 buy / buy2 全程零行，干脆连表一起去掉；
--       · 若某天要切回 AT，需重建该表（结构见
--         documents/Seata事务模式原理-AT-TCC对比分析.md 的 AT 时序一节）。
--     · 判据提醒：别再用 undo_log 判断模式，看 frozen / tcc_transaction_control 是否变化。
-- =============================================================================


-- =============================================================================
-- 四、Seata Server(TC) 服务端表 —— db 存储模式专用
--     本 demo 的 TC 以 `-m file` 启动（conf/application.yml 中 store.mode: file），
--     会话落在 bin/sessionStore 目录，**这三张表不会被访问**。
--     保留它们是为了沿袭原示例结构：若哪天把 TC 切成 db 模式，直接可用。
--     切换时注意：TC 的 conf/application.yml 里 db url 指向的是另一个库
--     （jdbc:mysql://127.0.0.1:3306/seata），届时需把本节内容导到那个库；
--     并且 db 模式还需要 distributed_lock、vgroup_table 等表，
--     请以 Seata 官方 script/server/db/mysql.sql 为准补齐。
-- =============================================================================

-- ----------------------------
-- Table structure for branch_table
-- ----------------------------
DROP TABLE IF EXISTS `branch_table`;
CREATE TABLE `branch_table` (
  `branch_id`         bigint        NOT NULL,
  `xid`               varchar(128)  NOT NULL,
  `transaction_id`    bigint        DEFAULT NULL,
  `resource_group_id` varchar(32)   DEFAULT NULL,
  `resource_id`       varchar(256)  DEFAULT NULL,
  `branch_type`       varchar(8)    DEFAULT NULL,
  `status`            tinyint       DEFAULT NULL,
  `client_id`         varchar(64)   DEFAULT NULL,
  `application_data`  varchar(2000) DEFAULT NULL,
  `gmt_create`        datetime(6)   DEFAULT NULL,
  `gmt_modified`      datetime(6)   DEFAULT NULL,
  PRIMARY KEY (`branch_id`),
  KEY `idx_xid` (`xid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for global_table
-- ----------------------------
DROP TABLE IF EXISTS `global_table`;
CREATE TABLE `global_table` (
  `xid`                       varchar(128) NOT NULL,
  `transaction_id`            bigint       DEFAULT NULL,
  `status`                    tinyint      NOT NULL,
  `application_id`            varchar(32)  DEFAULT NULL,
  `transaction_service_group` varchar(128) DEFAULT NULL,
  `transaction_name`          varchar(128) DEFAULT NULL,
  `timeout`                   int          DEFAULT NULL,
  `begin_time`                bigint       DEFAULT NULL,
  `application_data`          varchar(2000) DEFAULT NULL,
  `gmt_create`                datetime     DEFAULT NULL,
  `gmt_modified`              datetime     DEFAULT NULL,
  PRIMARY KEY (`xid`),
  KEY `idx_gmt_modified_status` (`gmt_modified`, `status`),
  KEY `idx_transaction_id` (`transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for lock_table
-- ----------------------------
DROP TABLE IF EXISTS `lock_table`;
CREATE TABLE `lock_table` (
  `row_key`        varchar(128) NOT NULL,
  `xid`            varchar(96)  DEFAULT NULL,
  `transaction_id` bigint       DEFAULT NULL,
  `branch_id`      bigint       NOT NULL,
  `resource_id`    varchar(256) DEFAULT NULL,
  `table_name`     varchar(32)  DEFAULT NULL,
  `pk`             varchar(36)  DEFAULT NULL,
  `gmt_create`     datetime     DEFAULT NULL,
  `gmt_modified`   datetime     DEFAULT NULL,
  PRIMARY KEY (`row_key`),
  KEY `idx_branch_id` (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;


-- =============================================================================
-- 五、自检（执行完核对这三段输出即可确认导入成功）
-- =============================================================================

-- [1/3] 库与表的字符集：库应为 utf8mb4 / utf8mb4_0900_ai_ci，且返回 7 张表
SELECT '--- [1/3] 库字符集 ---' AS `section`;
SELECT DEFAULT_CHARACTER_SET_NAME AS charset, DEFAULT_COLLATION_NAME AS collation
  FROM information_schema.SCHEMATA
 WHERE SCHEMA_NAME = 'seata-demo-tcc';

SELECT '--- [1/3] 表清单（应 7 行，且字符集全 utf8mb4）---' AS `section`;
SELECT TABLE_NAME, TABLE_COMMENT, TABLE_COLLATION
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = 'seata-demo-tcc'
 ORDER BY TABLE_NAME;

-- [2/3] TCC 专属结构：应返回 3 行列 + 1 行控制表
SELECT '--- [2/3] TCC 专属列（应返回 3 行）---' AS `section`;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = 'seata-demo-tcc'
   AND ((TABLE_NAME = 't_account' AND COLUMN_NAME = 'frozen')
     OR (TABLE_NAME = 't_storage' AND COLUMN_NAME = 'frozen')
     OR (TABLE_NAME = 't_order'   AND COLUMN_NAME = 'status'))
 ORDER BY TABLE_NAME;

SELECT '--- [2/3] 其它 TCC 物证（应返回 2 行：控制表 + 订单唯一键）---' AS `section`;
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = 'seata-demo-tcc'
   AND ((TABLE_NAME = 'tcc_transaction_control' AND INDEX_NAME = 'PRIMARY')
     OR (TABLE_NAME = 't_order'               AND INDEX_NAME = 'uk_order_no'))
 GROUP BY TABLE_NAME, INDEX_NAME
 ORDER BY TABLE_NAME;

-- [3/3] 基线数据：amount=4000.00 / count=1000 / frozen 与 status 均为 0 / 两张空表
SELECT '--- [3/3] 基线数据（amount=4000.00 / count=1000 / frozen 全 0）---' AS `section`;
SELECT id, user_id, amount, frozen FROM `t_account`;
SELECT id, commodity_code, name, count, frozen FROM `t_storage`;
SELECT COUNT(*) AS t_order_rows FROM `t_order`;
SELECT COUNT(*) AS control_rows FROM `tcc_transaction_control`;

-- 导入成功时最后两行应为：t_order_rows=0、control_rows=0
