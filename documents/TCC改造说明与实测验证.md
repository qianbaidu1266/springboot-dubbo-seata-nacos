# TCC 改造说明与实测验证（AT → TCC 全链路落地）

> **用途**：记录本项目从 Seata **AT 模式**改造为 **TCC 模式**的完整方案、代码结构、三道防护设计与端到端实测证据。
> 想知道"AT 与 TCC 的原理差异"请看 [Seata事务模式原理-AT-TCC对比分析.md](./Seata事务模式原理-AT-TCC对比分析.md)，本文只讲"改了什么、怎么改的、实测跑通了没有、踩了哪些坑"。
>
> **版本基线**：Spring Boot 2.2.2.RELEASE ｜ Dubbo 3.3.6 ｜ Seata 2.6.0（`seata-spring-boot-starter` + TC）｜ Nacos 2.4.3 ｜ MyBatis-Plus 2.3 ｜ MySQL 8.4.8（库 `seata-demo-tcc`）｜ 编译运行 JDK 8
>
> **依据来源**：
> 1. 工作区实际代码（`samples-*/src/main/java`、`samples-*/src/main/resources`、`sql/*.sql`）
> 2. **运行日志**（4 个服务 `stdout` + TC `~/logs/seata/seata-server.8091.all.log`）
> 3. **数据库实测快照**（`buy` / `buy2` 前后逐表对比）
> 4. Seata 2.6.0 字节码核实（`seata-all-2.6.0.jar`：`ActionInterceptorHandler`、`TccActionInterceptor`、`SpringProxyFactory`）
>
> **日期**：2026-09-11
>
> **一句话结论**：改造已完成并**端到端实测跑通** —— `buy` 三个分支 `Try→Confirm` 全提交、`buy2` 触发全局回滚后三个分支 `Cancel` 全释放，库中**已删除 `undo_log`**（AT 数据源代理彻底退出链路；删表后重建并复跑 `buy`/`buy2` 依旧全通过）；幂等 / 空回滚 / 防悬挂三道防护经独立自检 **19/19 通过**。

---

## 目录

| 节 | 内容 |
|---|---|
| [一](#一改造总览at--tcc-的落点) | 改造总览：AT → TCC 的落点 |
| [二](#二数据模型变更) | 数据模型变更（DDL） |
| [三](#三代码结构) | 代码结构（新增 / 删除 / 修改） |
| [四](#四三段式语义三个-rm-各自做什么) | 三段式语义：三个 RM 各自做什么 |
| [五](#五三道防护幂等--空回滚--防悬挂) | 三道防护：幂等 / 空回滚 / 防悬挂 |
| [六](#六端到端实测验证) | 端到端实测验证（含原始证据） |
| [七](#七改造中踩的三个坑) | 改造中踩的三个坑 |
| [八](#八如何回退到-at-模式) | 如何回退到 AT 模式 |
| [附录 A](#附录-a验证与复现命令) | 验证与复现命令 |

---

## 一、改造总览：AT → TCC 的落点

**一句话**：把"框架替你做补偿"换成"业务自己写三段式"。AT 下一步数据库操作就自动完成的事，TCC 下要显式拆成 `Try / Confirm / Cancel` 三个方法，并把补偿依据落到**业务字段**（`frozen` / `status`）上。

```
                   AT（改造前）                          TCC（改造后）
        ┌──────────────────────────────┐    ┌──────────────────────────────┐
        │ business  @GlobalTransactional│    │ business  @GlobalTransactional│
        └──────────────┬───────────────┘    └──────────────┬───────────────┘
                       │ Dubbo 调「业务方法」                 │ Dubbo 调 prepare（Try）
        ┌──────────────▼───────────────┐    ┌──────────────▼───────────────┐
        │ RM 直接 update 业务表          │    │ RM 只做「预留」：             │
        │  └ 代理自动写 undo_log（前镜像）│    │  frozen += n / status=TRYING │
        │  └ 全局行锁持有到二阶段结束     │    │  └ 不持有全局行锁            │
        └──────────────┬───────────────┘    └──────────────┬───────────────┘
                       │                                    │
        二阶段：框架按 undo_log 反向生成 SQL   二阶段：TC 回调业务自己的
        （提交→删镜像 / 回滚→补偿）                      Confirm 或 Cancel 方法
```

| 维度 | AT（改造前） | TCC（改造后） |
|---|---|---|
| 业务方法 | `decreaseStorage` / `createOrder` 一步到位 | `prepare` / `confirm` / `cancel` 三段式 |
| 补偿依据 | `undo_log` 里的**数据前镜像**（框架自动生成） | 业务字段 **`frozen`** / **`status`**（业务自己维护） |
| 数据源代理 | **必须开**（`enable-auto-data-source-proxy: true`） | **必须关**（否则 Try 的 SQL 会被顺手注册成 AT 分支） |
| `undo_log` 表 | 核心机制，每笔写操作都落镜像 | **不需要，已删除**（TCC 无任何代码路径依赖它） |
| 全局锁 | 有（本地行锁 + Seata 全局行锁） | 无（用 `frozen` 字段表达"已预留但未生效"） |
| 中间态可见性 | 业务数据已被改（未提交但已落库） | 业务数据**未动**，只有 `frozen` 增加 |
| 幂等 / 空回滚 / 悬挂 | 框架兜住 | **业务自己兜**（本项目用 `tcc_transaction_control`） |

> **注意 `data-source-proxy-mode` 不要写成 `TCC`**：该配置项只接受 `AT` / `XA` 两个值。TCC 分支类型由 `@TwoPhaseBusinessAction` 注解自动识别，因此切 TCC 的正确做法是把 `enable-auto-data-source-proxy` 关掉（本项目三个 RM 均如此），而不是"把 mode 改成 TCC"。

---

## 二、数据模型变更

TCC 只用一个脚本；同目录另一份是 AT 时代的旧脚本，仅作留档：

| 脚本 | 用途 | 可重复执行 |
|---|---|---|
| `sql/seata-demo-tcc.sql` | **从零导入**（新环境 / 交付给他人用）：`DROP DATABASE` → `CREATE DATABASE`（utf8mb4）→ 7 张表 + 基线数据（**不含 `undo_log`**），末尾带三段自检输出。**唯一会连库带表清空的脚本** | ✅ 是（2026-09-11 实测连导两次零报错零警告） |
| `sql/db-seata.sql` | **AT 时代旧建库脚本（历史留档）**：2026-09-13 从 git `master` 原样取回 —— 建的是 AT 结构（含 `undo_log`，业务表无 `frozen`/`status`，无 `tcc_transaction_control`） | ❌ **不是 TCC 脚本，别在 TCC 库上执行**；只作回退 AT 的参考 |

> 选哪个：TCC 场景**一律用 `seata-demo-tcc.sql`**（会删库，执行前先备份）；**只想"重建表、不动库"就把它的「步骤 ① `DROP DATABASE`」那一行注释掉** —— 该脚本每张表都带 `DROP TABLE IF EXISTS`，效果与旧的"不删库"脚本相同。
> **TCC 版脚本不创建 `undo_log`（共 7 张表）**；而 AT 版那份是**含 `undo_log` 的另一套结构**（`utf8mb3`、带 `int(11)` 显示宽度），**两版结构不同、不能混用**。
> **2026-09-13 清理 + 复原**：`sql/db-seata-tcc-upgrade.sql`（只服务"AT 老库原地升级"的增量脚本）与 `sql/seata_demo.sql`（21:50 用 Navicat 从跑过用例的库导出的脏快照）**已删除** —— 库名已改、且已从零重建，两条路径都不再存在。
> 同一轮把 `sql/db-seata.sql` **复原成 AT 时代的旧建库脚本**（自 git `master` 原样取回，2020-12-16 Navicat dump：7 张表含 `undo_log`，业务表无 `frozen`/`status`，字符集 utf8mb3 —— 与当前库的 utf8mb4 不同，属历史原貌）。

### 2.1 变更清单

| 表 | 变更 | 说明 |
|---|---|---|
| `t_account` | `+ frozen double(14,2) NOT NULL DEFAULT 0.00` | 预留（冻结）金额；Try 加、Confirm/Cancel 减 |
| `t_storage` | `+ frozen int NOT NULL DEFAULT 0` | 预留（冻结）库存，语义同上 |
| `t_order` | `+ status tinyint NOT NULL DEFAULT 0` | `0=TRYING`（占位）/ `1=CONFIRMED`（生效）/ `2=CANCELED`（已取消） |
| `t_order` | `+ UNIQUE KEY uk_order_no (order_no)` | `orderNo` 是 Confirm/Cancel 的定位键；唯一索引是"重复 Try"的第二道防线 |
| `tcc_transaction_control` | **新表** | 承载幂等 / 空回滚 / 防悬挂，主键 `(xid, branch_id)` |
| `undo_log` | **已删除** | AT 专属回滚镜像表；TCC 下没有任何代码路径读写它，故连表一起去掉（要切回 AT 需重建） |

> 列定义里没有写 `int(11)` / `tinyint(4)` 这类**显示宽度** —— MySQL 8.0.17 起已废弃，8.4 会直接忽略。
> 唯一需要保留宽度的是 `double(14,2)`：它约束的是**实际小数位数**（金额两位），去掉会退化成全精度 double。

### 2.2 控制表结构

```sql
CREATE TABLE `tcc_transaction_control` (
  `xid`          varchar(128) NOT NULL COMMENT '全局事务 ID',
  `branch_id`    bigint       NOT NULL COMMENT '分支事务 ID',
  `action_name`  varchar(64)  NOT NULL COMMENT '@TwoPhaseBusinessAction.name',
  `status`       tinyint      NOT NULL COMMENT '1=TRIED 2=CONFIRMED 3=CANCELED',
  `gmt_create`   datetime     NOT NULL COMMENT '创建时间',
  `gmt_modified` datetime     NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`xid`,`branch_id`),
  KEY `idx_status` (`status`),
  KEY `idx_gmt_modified` (`gmt_modified`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TCC 事务控制表：幂等/空回滚/防悬挂';
```

**为什么主键是 `(xid, branch_id)`**：Seata 的 TCC 拦截器在 `ActionInterceptorHandler#proceed` 里是**先注册分支、后执行 Try 方法**的（已用 `javap -c` 确认调用顺序）。所以 Try 方法体内拿到的 `context.getBranchId()` 一定是有效值，可以直接作为控制表的幂等键。

### 2.3 迁移时的一个坑：历史订单会全部停在 `status=0`

加列时 `status` 默认 0（=TRYING），**升级前已存在的订单会全部变成"占位"状态**。增量脚本里为此准备了一条一次性订正语句，但**默认注释掉**：

```sql
-- ⚠️ 只在首次执行本脚本时手工放行一次，之后请保持注释状态
-- UPDATE `t_order` SET `status` = 1 WHERE `status` = 0;
```

本次升级执行了这一条（把 13 条历史订单的 `status` 从 0 订正为 1），之后就注释回去了 —— 否则重复执行会把真正处于 TRYING 的新订单一并改掉。

> 该演示库已于同日 21:50 用 `sql/db-seata.sql` 整体重建（`t_order` 清空），所以这条订正只对**当年从 AT 升级上来的老库**有意义；全新环境从零重建不涉及这个问题。

---

## 三、代码结构

### 3.1 模块视图

```
samples-common/                                     ← 契约与共用件集中在这里
  dubbo/
    StorageTccAction.java        ★ 新增  @LocalTCC + @TwoPhaseBusinessAction
    AccountTccAction.java        ★ 新增  同上
    OrderTccAction.java          ★ 新增  同上
    AccountDubboService.java     ✗ 删除  AT 路径的旧契约
    OrderDubboService.java       ✗ 删除
    StorageDubboService.java     ✗ 删除
  enums/TccActionStatus.java     ★ 新增  TRIED(1) / CONFIRMED(2) / CANCELED(3)
  util/TccContextUtil.java       ★ 新增  二阶段上下文取值（含类型退化处理）
  tcc/TccControlSupport.java     ★ 新增  三道防护的状态机，三方共用
  mapper/TccTransactionControlMapper.java  ★ 新增  控制表访问接口
  resources/mapper/
    TccTransactionControlMapper.xml        ★ 新增

samples-account/
  tcc/AccountTccActionImpl.java  ★ 新增  Try: frozen+=   Confirm: amount-=,frozen-=   Cancel: frozen-=
  mapper/TAccountMapper.java     ~ 修改  + freezeAmount / confirmFreeze / releaseFreeze
  resources/mapper/TAccountMapper.xml  ~ 修改
  entity/TAccount.java           ~ 修改  + frozen
  dubbo/AccountDubboServiceImpl.java   ✗ 删除
  resources/application.yml      ~ 修改  关闭 AT 数据源代理

samples-storage/                 ← 与 account 同构（freezeStorage / confirmFreeze / releaseFreeze）
samples-order/
  tcc/OrderTccActionImpl.java    ★ 新增  Try: insert(status=0)+调 account.Try
                                          Confirm: status 0→1   Cancel: status 0→2
  mapper/TOrderMapper.java       ~ 修改  + insertTryingOrder / confirmOrder / cancelOrder
  entity/TOrder.java             ~ 修改  + status

samples-business/
  call/service/BusinessServiceImpl.java  ~ 修改  改调 prepare，@GlobalTransactional 不变

sql/
  seata-demo-tcc.sql             ★ 新增  从零导入脚本（DROP DATABASE + CREATE DATABASE + 7 表 + 基线数据 + 自检）
  db-seata.sql                   ~ 复原  AT 时代旧建库脚本（2026-09-13 自 git master 原样取回，含 undo_log；仅留档/回退参考）
  （db-seata-tcc-upgrade.sql、seata_demo.sql 已于 2026-09-13 清理删除）
```

> 控制表访问层（`TccTransactionControlMapper` + `TccControlSupport`）刻意放在 `samples-common` 而**不是三个 RM 各写一份** —— 三个服务共用同一个库、同一张控制表，判定逻辑必须完全一致。

### 3.2 调用链（含嵌套分支）

```
business  @GlobalTransactional
   │
   ├─(1) storageTccAction.prepare(null, commodityCode, count)   → 分支 A：storageTccAction
   │
   └─(2) orderTccAction.prepare(null, orderNo, userId, ...)     → 分支 B：orderTccAction
            │  Try 方法体内继续调用：
            └─ accountTccAction.prepare(null, userId, amount)   → 分支 C：accountTccAction
```

**订单是链路上唯一"既当提供者、又当消费者"的一环**：它的 Try 内部继续发起对账户的 Try 调用，两者共享同一个 XID（靠 `dubbo-filter-seata` 透传），因此**注册成两个独立分支**，二阶段由 TC 分别驱动。

> 调用方对 Try 的第一个参数 `BusinessActionContext` **一律传 `null`** —— 真正的分支上下文由 Seata 在**提供者侧**注入，调用方传什么都会被覆盖。

---

## 四、三段式语义：三个 RM 各自做什么

### 4.1 一览

| 服务 | Try（`prepare`） | Confirm | Cancel |
|---|---|---|---|
| **storage** | `frozen += count`<br>（校验 `count - frozen >= count`） | `count -= count`，`frozen -= count` | `frozen -= count` |
| **account** | `frozen += amount`<br>（校验 `amount - frozen >= amount`） | `amount -= amount`，`frozen -= amount` | `frozen -= amount` |
| **order** | `insert t_order(status=0)` + 调 account 的 Try | `status 0 → 1` | `status 0 → 2` |

**关键点：Try 阶段业务数据一点没动。** 库存的 `count` 还是原值、账户的 `amount` 还是原值，只有 `frozen` 在增加 —— 这就是 TCC "把执行和落地拆开"的落地形态。相比 AT 的"改完靠 undo_log 回滚"，TCC 的中间态是**业务上可解释的**：`可用量 = count - frozen`。

### 4.2 状态机

```
                          t_order.status
     ┌─────────┐  Confirm   ┌────────────┐
     │ TRYING  │───────────▶│ CONFIRMED  │   0 → 1
     │   (0)   │            │    (1)     │
     └────┬────┘            └────────────┘
          │ Cancel           终态，不再流转
          ▼
     ┌────────────┐
     │  CANCELED  │   0 → 2
     │    (2)     │
     └────────────┘

     每条流转 SQL 都带前置状态条件（`where status = 0`），
     影响行数为 0 即说明"该状态已经流转过"，天然幂等。
```

### 4.3 为什么 `frozen` 校验里带 `frozen >= count` 条件

`confirmFreeze` / `releaseFreeze` 的 SQL 都写了 `and frozen >= #{count}`：

```sql
update t_storage
   set count = count - #{count},
       frozen = frozen - #{count}
 where commodity_code = #{commodityCode}
   and frozen >= #{count}
```

这个条件是**防超扣的最后一道闸**：一旦控制表因为任何原因判断失误导致重复扣减，`frozen` 会先变负，SQL 直接拦下并让影响行数变 0，业务方法随即抛 `IllegalStateException` 让本地事务整体回滚 —— 而不是悄悄把库存扣成负数。

> 三个 RM 的 Confirm/Cancel 都遵循同一模式：**影响行数为 0 就抛异常**。因为控制表此时已经被置为终态，一旦业务 SQL 静默失败，控制表与业务数据就会永久撕裂；抛异常让本地事务回滚、控制表状态一并回退，TC 重试时才有机会修好。

---

## 五、三道防护：幂等 / 空回滚 / 防悬挂

TCC 把补偿责任交给业务，代价就是这三个经典问题必须自己兜住。本项目用 `TccControlSupport` 统一实现，三个 RM 共用。

### 5.1 状态机（控制表视角）

```
        (无记录)
            │
   Try ─────┼───────────────────────────────┐
            │                               │
            ▼                               │
        ┌────────┐  Confirm   ┌────────────┐│
        │ TRIED  │───────────▶│ CONFIRMED  ││  正常提交后收到 Cancel → 忽略
        │  (1)   │            │    (2)     ││  （资源已真正扣减，不能重复释放）
        └───┬────┘            └────────────┘│
            │ Cancel                         │
            ▼                                │
        ┌──────────┐                         │
        │ CANCELED │◀────────────────────────┘
        │   (3)    │   Cancel 先到（无记录）→ 空回滚，插 CANCELED
        └────┬─────┘
             │
             │ 迟到的 Try 查到 CANCELED → 抛异常拒绝执行  ← 防悬挂
             ▼
        该分支作废
```

### 5.2 每个阶段的判定表

| 阶段 | 查到的状态 | 返回值 | 业务动作 |
|---|---|---|---|
| **Try** | 无记录 | `true` | 插 `TRIED`，**执行**资源预留 |
| | `TRIED`（重复 Try） | `false` | **跳过**（防止 `frozen` 被加两次） |
| | `CANCELED` | **抛异常** | **拒绝执行**（悬挂） |
| | `CONFIRMED` | `false` | 跳过 |
| **Confirm** | 无记录 | `false` | **跳过**，补插 `CONFIRMED` 兜住迟到的 Try（空提交） |
| | `TRIED` | `true` | 流转 `CONFIRMED`，**执行**真正扣减 |
| | `CONFIRMED`（重试） | `false` | 跳过（幂等） |
| | `CANCELED` | `false` | 跳过（已回滚，不该再提交） |
| **Cancel** | 无记录 | `false` | **跳过**，插 `CANCELED`（空回滚 + 留悬挂判据） |
| | `TRIED` | `true` | 流转 `CANCELED`，**执行**释放预留 |
| | `CANCELED`（重试） | `false` | 跳过（幂等） |
| | `CONFIRMED` | `false` | 跳过（已提交，不能误释放） |

### 5.3 自检结果：19/19 通过

三道防护是纯逻辑，用一个内存桩 mapper（语义对齐 `tcc_transaction_control` 的 PK 约束与前置状态流转）直接驱动**真实的 `TccControlSupport` + `TccActionStatus`** 跑了一遍：

| 场景 | 断言数 | 结果 |
|---|---|---|
| [1] 正常提交（Try → Confirm，重试 Confirm 幂等） | 5 | ✅ PASS |
| [2] 正常回滚（Try → Cancel，重试 Cancel 幂等） | 4 | ✅ PASS |
| [3] 重复 Try 幂等（预留不重复累加） | 2 | ✅ PASS |
| [4] 空回滚（无 Try 却收到 Cancel，留下 CANCELED 判据） | 2 | ✅ PASS |
| [5] 防悬挂（Cancel 先到，迟到的 Try 被拒绝） | 2 | ✅ PASS |
| [6] 已 Confirm 分支收到 Cancel（不误释放已扣减资源） | 2 | ✅ PASS |
| [7] 空提交（无 Try 却收到 Confirm，留下 CONFIRMED 判据） | 2 | ✅ PASS |
| **合计** | **19** | **19 PASS / 0 FAIL** |

场景 5 的实际输出（悬挂判定的原始证据）：

```
       抛出：TCC 悬挂：分支已被 Cancel，拒绝执行 Try [xid=x5, branchId=1, action=a]
  [PASS] 迟到的 Try 被拒绝执行  期望=true 实际=true
  [PASS] 状态仍是 CANCELED，未被 Try 覆盖  期望=true 实际=true
```

自检代码见 [附录 A](#附录-a验证与复现命令)。

---

## 六、端到端实测验证

**环境**：TC（JDK 17 / store.mode=file / 注册 IP `192.168.5.3`）＋ Nacos 8848 ＋ 4 个服务（account 8102 / storage 8109 → order 8101 → business 8104）。

**起点基线**：本节含两轮实测，数值核对以**第二轮（重建后的干净库）**为准。

> 阅读提示：下面各轮记录里出现的 `sql/db-seata.sql`，一律指**当时那份 TCC 版**脚本（第五轮还在用它）；
> 2026-09-13 第六轮起该文件已被 **AT 原版**替换（见 §二），历史记录按原样保留、不作改写。

*第一轮（2026-09-11 晚间，沿用跑过多轮 AT 用例的漂移库）*：

| 对象 | 值 |
|---|---|
| `t_account` | `amount=2800.00`，`frozen=0.00` |
| `t_storage` | `count=300`，`frozen=0` |
| `t_order` | 12 行，全部 `status=1` |
| `tcc_transaction_control` | 0 行 |
| `undo_log` | 已删除（当轮实测为 0 行） |

> 这一轮的 `amount=2800` / `count=300` 与初始值（`4000.00` / `1000`）不等，是演示库跑过多轮 AT 用例漂移所致 —— **比对只看变化量**，不要看绝对值。

*第二轮（同日 21:50，`DROP DATABASE seata_demo` 后用 `sql/db-seata.sql` 重建）*：

| 对象 | 值 |
|---|---|
| `t_account` | `amount=4000.00`，`frozen=0.00` |
| `t_storage` | `count=1000`，`frozen=0` |
| `t_order` | 0 行 |
| `tcc_transaction_control` | 0 行 |
| `undo_log` | 已删除（当轮实测为 0 行） |

> 重建动作本身也做了实测：删库 → 跑脚本 → 重启 4 个服务 → `buy` / `buy2` 全部符合预期。
> 也就是说 **`sql/db-seata.sql` 是验证过"从零到可跑"的**，不是只做了语法检查。

*第三轮（同日 22:0x，`sql/seata-demo-tcc.sql` 从零导入）*：

| 检查项 | 结果 |
|---|---|
| 导入过程 | 零报错、零警告（无 `int(11)` 显示宽度的 deprecation 警告），中文 COMMENT 与商品名 `水杯` 无乱码 |
| 结构一致性 | 与第二轮 `db-seata.sql` 产物用 `mysqldump --no-data` 逐字 `diff` → **无差异**；库与表均为 `utf8mb4 / utf8mb4_0900_ai_ci` |
| 复位后基线 | `amount=4000.00`、`count=1000`、`frozen` 全 0、`t_order` / `tcc_transaction_control` / `undo_log` 均 0 行 |
| **是否需重启服务** | **不需要** —— `DROP DATABASE` 后不重启 4 个业务服务，直接打用例：`buy` HTTP 200（4000→3900、1000→950、订单 `status=1`、控制表 3 行 `CONFIRMED`）；`buy2` HTTP 500（预期异常，3900/950 保持不动、占位单 `status=2`、控制表再 3 行 `CANCELED`） |
| 幂等性 | 同一脚本连跑两次均成功（第二次为复位动作） |

> 该轮验证方式值得一提：先在**临时库名**（`sed 's/seata_demo/seata_demo_tcc_verify/g'`）下导入并与现网库 diff 结构，确认无误后再对真实库执行，避免"边验边毁"。这一手法已沉淀进 skill `~/.workbuddy/skills/seata-at-to-tcc-migration/`。

*第四轮（同日 22:1x，库名由 `seata_demo` 改为 `seata-demo-tcc`）*：

| 改动 | 内容 |
|---|---|
| 库名 | 新建 `seata-demo-tcc`（**含短横线，DDL 里必须反引号包裹**），由 `sql/seata-demo-tcc.sql` 从零导入 |
| 服务配置 | `samples-account` / `samples-order` / `samples-storage` 三个 `application.yml` 的 jdbc url 改为 `.../seata-demo-tcc`；**`samples-business` 是纯 TM、本就没有数据源配置，无需改** |
| 重新打包 | `JAVA_HOME=<jdk8> mvn clean package -DskipTests`（5 个模块 SUCCESS），4 个服务重启后生效 |
| 端到端 | `buy` HTTP 200（4000→3900、1000→950、订单 `status=1`、控制表 3×`CONFIRMED`）；`buy2` HTTP 500（3900/950 不动、占位单 `status=2`、再 3×`CANCELED`）；**老库 `seata_demo` 全程未被写入**（仍 4000/1000） |
| TC 侧 | error 日志零新增（末条仍是 21:29 的旧记录） |
| 终态 | `seata-demo-tcc` 复位为纯基线：8 表（当时仍含 `undo_log`）/ utf8mb4 / `amount=4000.00` / `count=1000` / 三张空表 0 行 |

> 顺带清理：`.workbuddy/backup-dubbo3/`（Dubbo 2.7.13 升级前的 pom 与 4 个 yml 备份）经比对与 `master` 分支逐字节相同、4 个 `*.java` 亦在 HEAD 中，**已确认冗余并删除**。
> 老库 `seata_demo` **未删除**（保留以便对照/回退）。

*第五轮（2026-09-13，删除 `undo_log` 后重建并复跑）*：

| 动作 | 结果 |
|---|---|
| 原因 | `undo_log` 是 **AT 专属**的回滚镜像表；TCC 的回滚依据是业务自己的 Cancel + `frozen`/`status` + `tcc_transaction_control`，该表在 TCC 下既不被写也不被读，属冗余 |
| 脚本改动 | `sql/seata-demo-tcc.sql` / `sql/db-seata.sql` 均**不再创建该表**，库从 8 张表变 **7 张表**；脚本内新增「三、为什么没有 undo_log」说明段，并同步自检项 |
| 验证方式 | 先以临时库名（`seata-demo-tcc-verify`）导入核对表清单（确认 7 张、无 `undo_log`），再对真库执行 `DROP DATABASE` + 重跑脚本 |
| 端到端（**未重启任何服务**） | `buy` HTTP 200（4000→3900、1000→950、订单 `status=1`、控制表 3×`CONFIRMED`）；`buy2` HTTP 500（3900/950 不动、占位单 `status=2`、再 3×`CANCELED`）；TC error 日志零新增 |
| 结论 | **删掉 `undo_log` 对 TCC 链路零影响** —— 三个 RM 的 `enable-auto-data-source-proxy` 已是 false，没有任何代码路径会碰这张表 |

> 要回退到 AT：`sql/db-seata.sql` 已复原为 AT 原版（含 `undo_log`），可直接拿它建表；代码也要一并 checkout 回改造前（见 §八 步骤 4）。

*第六轮（2026-09-13，脚本清理与复原）*：`sql/db-seata-tcc-upgrade.sql` 与 `sql/seata_demo.sql` 已删除（前者只服务"AT 老库原地升级"，库已改名 + 从零重建；后者是跑过用例的脏快照）；`sql/db-seata.sql` **复原为 AT 时代的旧建库脚本**（自 git `master` 原样取回，含 `undo_log`，仅留档 / 回退 AT 参考）。TCC 建库只剩 `sql/seata-demo-tcc.sql` 一份；"不删库重跑"改为注释掉它的 `DROP DATABASE` 那一行。

### 6.1 用例一：`buy` —— 正常流程走全局提交

```bash
curl -X POST http://localhost:8104/business/dubbo/buy \
  -H 'Content-Type: application/json' \
  -d '{"userId":1,"commodityCode":"C201901140001","name":"fan","count":50,"amount":"100"}'
# → {"status":200,"message":"成功","data":"114e6183ea6047c29373ed6f5144faa5"}
```

**服务侧日志（原始摘录）**：

```
[TCC-Try][storage]    xid=...769, branchId=...770  commodityCode=C201901140001, count=50
[TCC-Try][storage]    冻结成功
[TCC-Try][order]      xid=...769, branchId=...771  orderNo=579ac744..., userId=1, count=50, amount=100.0
[TCC-Try][order]      占位订单已写入 orderNo=579ac744..., status=TRYING
[TCC-Try][account]    xid=...769, branchId=...772  userId=1, amount=100.0
[TCC-Try][account]    冻结成功
[TCC-Confirm][storage]  扣减成功 commodityCode=C201901140001, count=50
[TCC-Confirm][order]    订单生效 orderNo=579ac744...
[TCC-Confirm][account]  扣款成功 userId=1, amount=100.0
```

> 注意分支 ID 递增顺序：storage(`...778`) → order(`...779`) → account(`...780`)，其中 account 的 `...780` 是在 order 的 Try **内部**注册的 —— 嵌套分支确实产生了独立分支。

**TC 侧日志**：3 次 `BranchCommit` → `Committing global`。

**数据核对**：

| 对象 | 期望 | 实测 | 结论 |
|---|---|---|---|
| `t_account.amount` | 4000 → 3900 | `3900.00` | ✅ |
| `t_account.frozen` | 回 0 | `0.00` | ✅ |
| `t_storage.count` | 1000 → 950 | `950` | ✅ |
| `t_storage.frozen` | 回 0 | `0` | ✅ |
| `t_order` 新增行 | `status=1` | `status=1` | ✅ |
| `tcc_transaction_control` | 3 行 `status=2` | 3 行均 `TRIED(1)` → `CONFIRMED(2)` | ✅ |
| `undo_log` | 已删除 | 表不存在 | ✅ **AT 代理确未介入** |

### 6.2 用例二：`buy2` —— 抛异常触发全局回滚

```bash
curl -X POST http://localhost:8104/business/dubbo/buy2 ... 
# → HTTP 500 {"message":"测试抛异常后，分布式事务回滚！"}
```

**服务侧日志（原始摘录）**：

```
[TCC-Try][storage]    xid=...773, branchId=...774  count=50
[TCC-Try][storage]    冻结成功
[TCC-Try][order]      xid=...773, branchId=...775  orderNo=7b415bdc..., status=TRYING
[TCC-Cancel][order]   orderNo=7b415bdc...  → 订单已取消
[TCC-Cancel][storage] 解冻成功 commodityCode=C201901140001, count=50
[TCC-Cancel][account] 解冻成功 userId=1, amount=100.0
```

> 二阶段线程池从 `h_RMROLE_1_1_20`（提交）切到 `h_RMROLE_1_2_20`（回滚），是两条不同通路。Cancel 的**顺序与 Try 相反**（account 先于 order 完成）—— TC 并行驱动各分支，不保证顺序，也不需要保证。

**TC 侧日志**：3 次 `BranchRegister` → `GlobalRollbackRequest` → 3 次 `BranchRollback` → `Rollback global`。

**数据核对**：

| 对象 | 期望 | 实测 | 结论 |
|---|---|---|---|
| `t_account.amount` | 保持 3900（未提交） | `3900.00` | ✅ |
| `t_account.frozen` | 回 0（预留已释放） | `0.00` | ✅ |
| `t_storage.count` | 保持 950 | `950` | ✅ |
| `t_storage.frozen` | 回 0 | `0` | ✅ |
| 第二笔订单 | `status=2` | `status=2`（CANCELED） | ✅ |
| `tcc_transaction_control` | 累计 6 行 = 3×`CONFIRMED` + 3×`CANCELED` | 完全一致 | ✅ |
| `undo_log` | 已删除 | 表不存在 | ✅ |

### 6.3 TC 错误日志：本次测试全程无新错误

`~/logs/seata/seata-server.8091.error.log` 最后修改时间停在 **21:29**（是修复前那一笔失败事务的重试记录），重建后的 `buy` / `buy2` 发生在 **21:51**，error 日志**零新增**。

---

## 七、改造中踩的三个坑

### 坑 1：`@BusinessActionContextParameter` 必须**重复标在实现类方法参数**上（最隐蔽）

**现象**：`buy` 的 Try 三个阶段全部正常、数据预留也对，但业务侧响应成功后二阶段 Confirm 报错：

```
commit TCC resource error
...
Confirm 扣减影响行数为 0：commodityCode=null, count=null
```

`confirm` 分明被调到了，参数却全是 `null`。

**根因**：Seata 的 `ActionInterceptorHandler#fetchActionRequestContext` 是从 **`invocation.getMethod().getParameterAnnotations()`** 里读业务参数的，而本项目因为实现类上标了 `@Transactional`，Bean 走 **CGLIB 代理**，这里拿到的是**实现类方法** —— 注解只写在接口上，接口注解不会被继承。

**修复**：三个实现类的 `prepare` 方法参数上**再标一遍**同样的注解（接口上保留，作为契约文档）。

```java
// 接口 AccountTccAction
boolean prepare(BusinessActionContext context,
                @BusinessActionContextParameter(paramName = "userId") String userId,
                @BusinessActionContextParameter(paramName = "amount") double amount);

// 实现类 AccountTccActionImpl —— 必须重复标注！
public boolean prepare(BusinessActionContext context,
                       @BusinessActionContextParameter(paramName = "userId") String userId,
                       @BusinessActionContextParameter(paramName = "amount") double amount) {
```

**验证**：用 `javap -v` 对比三个实现类的 `RuntimeVisibleParameterAnnotations` 段数，修复后完全一致（均为 2）。

### 坑 2：Agent shell 注入的 `SERVER__PORT` 把应用端口顶掉

**现象**：以脚本启动服务时，日志里端口变成 `51286`，`8109` 无人监听，但应用**启动看起来是成功的**（打印了 `Started StorageExampleApplication`）。

**根因**：执行环境注入了 `SERVER__PORT=51286`。Spring Boot 的**宽松绑定**会把它识别成 `server.port`，优先级高于 `application.yml`。

**修复**：启动时显式追加 `--server.port=<端口>`（命令行参数优先级最高）。

> **排查手法**：`env | grep -i server_port`。这类"配置对、启动成功、但端口不对"的现象，先怀疑环境变量，不要先怀疑配置文件。

### 坑 3：TC 的 `sessionStore` 里存着坏会话，二阶段重试**永远**失败

**现象**：修完坑 1、重启服务后，TC 仍在按周期重试那一笔失败事务的二阶段，且每次都失败。服务端日志显示 TC 回传的 `applicationData` 里**没有任何业务参数**。

**根因**：**Try 时的业务参数会在那一刻固化上报到 TC**（存入 TC 的会话存储），二阶段用的是同一份。坑 1 期间产生的会话，其 `applicationData` 已经是空的 —— **改代码救不回来，因为 TC 存的就是那份空上下文**。重试次数再多也没用。

**修复**：把这笔"中毒"的事务丢弃。停 TC → **移走（不是删除）** `seata-2.6.0/seata-server/bin/sessionStore` → 重启 TC：

```bash
cd seata-2.6.0/seata-server/bin
./seata-server-stop.sh
mv sessionStore sessionStore.bak-<时间戳>     # 可逆，保留现场便于回溯
nohup ./seata-server.sh -p 8091 -m file > /tmp/seata-tc-start.log 2>&1 &
```

重启后 `sessionStore/8091/root.data` 为 **0 字节**，即 TC 已无任何会话。随后清理业务侧残留（`frozen` 归零、删掉占位订单、`TRUNCATE tcc_transaction_control`）。

> **可复用经验**：TCC 下"二阶段反复失败"时，先看 **TC 回传的 `applicationData` 里有没有参数**。没有参数 = Try 上报那一刻就丢了，属于会话级中毒，必须丢弃会话；有参数但业务报错 = 业务 SQL / 状态判断的问题，改代码 + 重启服务即可。

---

## 八、如何回退到 AT 模式

改造全部在 `TCC` 分支上，回退成本很低：

| 步骤 | 操作 |
|---|---|
| 1 | `git checkout` 回 `master`（AT 代码未删除，仅是当前分支上被替换） |
| 2 | 三个 RM 的 `application.yml` 恢复 `enable-auto-data-source-proxy: true` + `data-source-proxy-mode: AT` |
| 3 | 数据库**不需要**回退：`frozen` / `status` 是新加列，AT 代码不读它们；`t_order.status` 有默认值 0，不影响 AT 的 insert |
| 4 | **先重建 `undo_log` 表**：TCC 版脚本已不再创建它，回退 AT 前需按建表 DDL 补上（结构见 `Seata事务模式原理-AT-TCC对比分析.md`） |
| 5 | `tcc_transaction_control` 表可留着不管（AT 下没人写） |

> **注意**：AT 代码的 `createOrder` 不写 `status`，但 `t_order.status` 是 `NOT NULL DEFAULT 0`，所以能正常插入；只是那些行会显示为 `TRYING`。若在意语义，回退后跑一次 `UPDATE t_order SET status=1 WHERE status=0;` 即可。

---

## 附录 A：验证与复现命令

### A.1 确认当前模式（TCC）

```bash
# 1) 三个 RM 必须已关闭 AT 数据源代理
grep -n -A3 "enable-auto-data-source-proxy" samples-*/src/main/resources/application.yml
#    期望：三个 RM 均为 false，且没有 data-source-proxy-mode；business 无数据源配置

# 2) 库里的 TCC 结构
mysql -h127.0.0.1 -uroot -proot -e "
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA='seata-demo-tcc'
   AND ((TABLE_NAME='t_account' AND COLUMN_NAME='frozen')
     OR (TABLE_NAME='t_storage' AND COLUMN_NAME='frozen')
     OR (TABLE_NAME='t_order'   AND COLUMN_NAME='status'));
SHOW TABLES FROM `seata-demo-tcc` LIKE 'tcc_transaction_control';"
```

### A.2 端到端跑一遍

```bash
# 正常提交
curl -X POST http://localhost:8104/business/dubbo/buy  -H 'Content-Type: application/json' \
  -d '{"userId":1,"commodityCode":"C201901140001","name":"fan","count":50,"amount":"100"}'
# 抛异常回滚
curl -X POST http://localhost:8104/business/dubbo/buy2 -H 'Content-Type: application/json' \
  -d '{"userId":1,"commodityCode":"C201901140001","name":"fan","count":50,"amount":"100"}'

# 结果核对（重点：frozen 提交/回滚后都必须回到 0；控制表 status 2=提交 3=回滚）
mysql -h127.0.0.1 -uroot -proot -e "
SELECT id, amount, frozen FROM seata-demo-tcc.t_account;
SELECT id, count, frozen  FROM seata-demo-tcc.t_storage;
SELECT order_no, count, amount, status FROM seata-demo-tcc.t_order ORDER BY id DESC LIMIT 3;
SELECT xid, branch_id, action_name, status FROM seata-demo-tcc.tcc_transaction_control ORDER BY xid, branch_id;"
```

### A.3 三道防护自检（19 断言）

```bash
JH=/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home
MYBATIS=$(find ~/.m2/repository/org/mybatis/mybatis -name "mybatis-3*.jar" ! -name "*sources*" | head -1)
CP="samples-common/target/classes:$MYBATIS"
$JH/bin/javac -cp "$CP" -d /tmp/tcc-selfcheck /tmp/tcc-selfcheck/TccSelfCheck.java
$JH/bin/java  -cp "/tmp/tcc-selfcheck:$CP" TccSelfCheck
# 期望输出末尾：== 结果：PASS=19, FAIL=0 ==
```

### A.4 TC 侧观察点

```bash
# 二阶段事件（提交/回滚）
grep -aE "Committing global|Rollback global|BranchCommitResponse|BranchRollbackResponse" \
  ~/logs/seata/seata-server.8091.all.log | tail -20

# TC 错误（若二阶段失败，这里必然有记录 —— 注意看 applicationData 是否为空）
tail -30 ~/logs/seata/seata-server.8091.error.log

# TC 是否还有未了结的会话（0 字节 = 干净）
ls -la seata-2.6.0/seata-server/bin/sessionStore/8091/root.data
```

### A.5 相关文档

| 文档 | 关系 |
|---|---|
| [Seata事务模式原理-AT-TCC对比分析.md](./Seata事务模式原理-AT-TCC对比分析.md) | 四种模式原理 + AT/TCC 逐维度对比（本文的"原理篇"） |
| [问题记录.md](./问题记录.md) | #006~#008 对应本文 §7 的三个坑 |
| [本地编译启动手册-实测跑通.md](./本地编译启动手册-实测跑通.md) | 环境搭建、启动顺序、TC/Nacos 注意事项 |
| [项目分析-SpringBoot-Dubbo-Seata-Nacos.md](./项目分析-SpringBoot-Dubbo-Seata-Nacos.md) | 项目整体架构与领域模型 |
