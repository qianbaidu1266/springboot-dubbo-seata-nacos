# Seata 事务模式原理与 AT / TCC 对比分析

> 用途：系统梳理 Seata 四种事务模式（**AT / TCC / SAGA / XA**）的原理与差异，并把本项目
> 「AT 时期状态 → 切到 TCC 要改什么」的分析固化成文档，避免重复推导。
>
> ⚠️ **本文是「原理篇」，其中"本项目状态"的描述对应 AT 时期（2026-09-11 之前）。**
> 本项目已于 2026-09-11 **整体改造为 TCC 模式并实测跑通**，改造方案、代码结构、三道防护与实测
> 证据请看 [TCC改造说明与实测验证.md](./TCC改造说明与实测验证.md)。
>
> 版本基线：Seata **2.6.0**（`org.apache.seata.*` 新包名）+ Spring Boot 2.2.2 + Dubbo 3.3.6 + Nacos 2.4.3
>
> 依据来源：
>
> 1. **工作区实际代码**（`samples-*/src/main/resources/application.yml`、`sql/seata-demo-tcc.sql`、`BusinessServiceImpl`）
> 2. Seata 官方文档「事务模式」章节（AT / TCC / SAGA / XA）
> 3. 官方示例 `springboot-dubbo-seata` 的 TCC 写法（接口拆分与注解形态）
>
> 编写日期：2026-09-11
>
> **一句话结论**：AT 与 TCC 的本质差异是 **「框架替你补偿」vs「你自己补偿」** —— AT 改造成本≈0、
> 但强依赖关系库且全局锁有并发损耗；TCC 无全局锁、可跨异构资源，但必须自行实现
> **Confirm / Cancel 的幂等、空回滚与悬挂防护**（业务侵入性大幅上升）。
> 本项目改造前是 **AT 模式**（3 个 RM 均配 `data-source-proxy-mode: AT`，库里有 `undo_log`），
> **当前已是 TCC 模式**（AT 数据源代理已关闭，`undo_log` 表已从库中删除）—— 见 §八 的对照说明。

---

## 目录

| 章节 | 内容 |
|---|---|
| [一](#一先看骨架seata-全局事务的三个角色) | 先看骨架：TC / TM / RM 三个角色 |
| [二](#二两阶段两阶段模型的通用语义) | 两阶段：两阶段模型的通用语义 |
| [三](#三at-模式原理无侵入的自动补偿) | AT 模式原理（无侵入的自动补偿） |
| [四](#四tcc-模式原理业务自主补偿) | TCC 模式原理（业务自主补偿） |
| [五](#五saga-模式原理长流程正向--补偿) | SAGA 模式原理（长流程正向 + 补偿） |
| [六](#六xa-模式原理依赖数据库-xa-的强一致) | XA 模式原理（依赖数据库 XA 的强一致） |
| [七](#七四种模式横向对比) | 四种模式横向对比 |
| [八](#八本项目-at-时期状态实证改造前) | 本项目 AT 时期状态实证（改造前） |
| [九](#九at-vs-tcc-逐维度分析) | AT vs TCC 逐维度分析 |
| [十](#十若本项目切换到-tcc改造清单已落地) | 若本项目切换到 TCC：改造清单（已落地） |
| [十一](#十一选型建议) | 选型建议 |
| [附录 a](#附录-a排障与验证命令) | 排障与验证命令 |

---

## 一、先看骨架：Seata 全局事务的三个角色

四种模式**共用同一套调度骨架**，差异只落在 RM（资源管理器）这一侧：一阶段做什么、二阶段怎么收尾。

| 角色 | 全称 | 在本项目中的实例 | 职责 |
|---|---|---|---|
| **TC** | Transaction Coordinator | `seata-server`（8091 端口，独立进程） | 全局事务的**协调者**：分配 XID、维护全局/分支状态、驱动二阶段、保管全局锁 |
| **TM** | Transaction Manager | `samples-business`（**无数据库**，纯门面） | 全局事务的**发起者**：`@GlobalTransactional` 开启 / 提交 / 回滚全局事务 |
| **RM** | Resource Manager | `samples-order` / `samples-account` / `samples-storage` | 分支事务的**执行者**：注册分支、上报状态、执行二阶段（提交或回滚） |

两个关键标识：

- **XID**（全局事务 ID）：TM 向 TC 申请得到，随 Dubbo 调用链透传（本项目靠 `dubbo-filter-seata` 传递），
  RM 端用它把自己挂到同一条全局事务下。
- **BranchId**（分支事务 ID）：每个 RM 的每次资源操作注册成一个分支，`(xid, branch_id)` 在 `undo_log` 表里是唯一键。

```
                       ① 开启全局事务 → 返回 XID
     ┌──────────┐   ──────────────────────────────────▶   ┌────────────┐
     │    TM    │                                          │     TC     │
     │ business │   ◀──── ② 全局提交 / 回滚指令 ─────────    │ seata-server│
     └────┬─────┘                                          └─────┬──────┘
          │ ③ Dubbo RPC（XID 随调用链透传）                        │
          ▼                                                      │
     ┌──────────┐  ④ 注册分支 + 上报分支状态                       │
     │    RM    │   ──────────────────────────────────────────────▶│
     │ order /  │   ◀──── ⑤ 二阶段指令：BranchCommit / BranchRollback
     │ account /│                                               ┘
     │ storage  │
     └──────────┘
```

---

## 二、两阶段：两阶段模型的通用语义

Seata 的四种模式全部是「两阶段提交（2PC）的变体」，但**每个阶段的语义按模式重新定义**：

| 阶段 | AT | TCC | SAGA | XA |
|---|---|---|---|---|
| **一阶段** | 执行业务 SQL + 记录 undo_log + **本地事务直接提交** | 只执行 Try（**资源预留**，业务数据不变） | 执行业务动作 + **本地事务直接提交** | `XA PREPARE`（**不提交**，资源被挂起） |
| **二阶段·提交** | 异步删除 undo_log（**极轻**） | 调用 Confirm（真正扣减） | 无需动作（一阶段已生效） | `XA COMMIT` |
| **二阶段·回滚** | 按 undo_log 的 before image **生成反向 SQL** | 调用 Cancel（释放预留） | 调用各步的**补偿动作** | `XA ROLLBACK` |

> 关键分水岭：**一阶段是否"已生效"**。
> AT / SAGA 一阶段就落库（靠锁或补偿保证正确性）；TCC / XA 一阶段不生效
> （TCC 靠业务预留字段，XA 靠数据库把资源挂起）。

---

## 三、AT 模式原理：无侵入的自动补偿

### 3.1 核心思路

把「数据库连接」包一层代理（`DataSourceProxy`），**拦截业务 SQL 并自动记录前后镜像**，
回滚时由框架生成反向 SQL。业务代码只管写 SQL，几乎零改造 —— 这是 AT 最大的卖点。

### 3.2 一阶段流程

```
  业务方法（同一个本地事务内）
    │
    ├─ ① 解析 SQL：定位表名、主键、WHERE 条件
    ├─ ② 前置查询：SELECT ... FOR UPDATE 取出 before image
    ├─ ③ 执行业务 SQL（UPDATE / INSERT / DELETE）
    ├─ ④ 后置查询：取出 after image
    ├─ ⑤ 写入 undo_log（before image + after image，一条记录）
    ├─ ⑥ 向 TC 注册分支，并申请该行的全局锁
    └─ ⑦ 提交本地事务 ← 注意：数据在这里就"看得见"了，行锁仍被持有
```

对应到本项目 `sql/db-seata.sql` 里的落库结构：

```sql
CREATE TABLE `undo_log` (
  `branch_id`     bigint(20)   NOT NULL COMMENT 'branch transaction id',
  `xid`           varchar(100) NOT NULL COMMENT 'global transaction id',
  `context`       varchar(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` longblob     NOT NULL COMMENT 'rollback info',      -- 前后镜像序列化后存这里
  `log_status`    int(11)      NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created`   datetime(6)  NOT NULL,
  `log_modified`  datetime(6)  NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`,`branch_id`)                        -- 幂等锚点
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='AT transaction mode undo table';
```

### 3.3 二阶段：提交与回滚

| 场景 | TC 动作 | RM 动作 |
|---|---|---|
| 全局提交 | 通知各分支 `BranchCommit` | **异步**删除对应 `undo_log` 记录，释放全局锁（极轻，几乎无成本） |
| 全局回滚 | 通知各分支 `BranchRollback` | 读 `undo_log` → 校验脏写 → 执行反向 SQL → 删除记录、释放锁 |

反向 SQL 的生成规则（由 before / after image 决定）：

| 原始操作 | 镜像特征 | 生成的回滚 SQL |
|---|---|---|
| INSERT | before image 为空 | `DELETE FROM t WHERE pk = ?` |
| DELETE | after image 为空 | `INSERT INTO t (...) VALUES (before image)` |
| UPDATE | 两者都有 | `UPDATE t SET 各列 = before image WHERE 主键 = before image 主键` |

回滚前还会做**脏写校验**（默认开启）：拿当前库里的数据与 after image 比对，不一致说明数据被
AT 之外的途径改过，直接抛异常而不是盲目覆盖。

### 3.4 全局锁与隔离性（AT 最需要理解的一点）

- 一阶段数据**已提交**，所以从全局视角看，AT 的默认隔离级别是 **读未提交**：
  别的全局事务可能读到中间态数据。
- Seata 用**全局行锁**（TC 侧记录 `表名 + 主键`）保证「不同全局事务不能同时写同一行」，
  配合数据库的本地行锁，形成两层防护；拿不到全局锁会按 `client.rm.lock.*` 的间隔与次数重试。
- 需要「读已提交」语义时，读侧要用 `@GlobalLock`，并把查询写成 `SELECT ... FOR UPDATE` 才会去 TC 校验全局锁。
- **代价**：全局锁会一直被持有到二阶段结束，高并发写同一热点行时，AT 的性能损耗就在这里。

### 3.5 约束

| 约束 | 说明 |
|---|---|
| 必须是关系库 | 要有本地 ACID 事务，且走 `DataSourceProxy` 代理 |
| 每个库都要建 `undo_log` | 真实拆库场景下**每个业务库各自建一张**，否则回滚报 `Table 'xxx.undo_log' doesn't exist` |
| 只支持 DML | DDL 不纳入；复杂 SQL（多表关联更新、部分子查询）解析能力有限，需以实际解析结果为准 |
| 需能定位主键 | 无主键/无唯一索引的表做镜像与全局锁会退化为全表锁定的效果 |

---

## 四、TCC 模式原理：业务自主补偿

### 4.1 核心思路

把一个业务操作**拆成三个显式方法**，由业务代码自己实现「预留 → 确认 / 取消」。
Seata 在 TCC 下只做两件事：**传播 XID、按时调用你写的方法**；补偿逻辑框架完全不管。

```
   Try（一阶段）            Confirm（二阶段·提交）        Cancel（二阶段·回滚）
  ┌──────────────┐         ┌──────────────┐            ┌──────────────┐
  │ 预留资源      │  成功   │ 真正扣减      │   失败     │ 释放预留      │
  │ frozen +100  │ ──────▶ │ balance -100 │            │ frozen -100  │
  │ balance 不变  │         │ frozen  -100 │            │ balance 不变  │
  └──────────────┘         └──────────────┘            └──────────────┘
      由 TM 调用               TC 驱动提交时调用            TC 驱动回滚时调用
```

### 4.2 代码形态（Dubbo 场景）

```java
@LocalTCC
public interface AccountTccAction {

    @TwoPhaseBusinessAction(name = "accountTccAction",
            commitMethod = "confirm", rollbackMethod = "cancel")
    boolean prepare(BusinessActionContext ctx,
                    @BusinessActionContextParameter(paramName = "userId") String userId,
                    @BusinessActionContextParameter(paramName = "money") int money);

    boolean confirm(BusinessActionContext ctx);   // 幂等
    boolean cancel(BusinessActionContext ctx);    // 幂等 + 防空回滚 + 防悬挂
}
```

- `BusinessActionContext` 可取出 `xid` / `branchId`，以及 Try 阶段用 `@BusinessActionContextParameter`
  挂上去的业务参数（框架会自动透传到 Confirm / Cancel）。
- 调用方式不变：业务门面仍是 `@GlobalTransactional` + Dubbo 调用各参与方的 Try。

### 4.3 必须自己处理的三个坑

这是 TCC 的**真正的成本**，根因都来自「Confirm / Cancel 是异步的、可能重试、可能乱序」：

| 坑 | 触发场景 | 现象 | 处理方式 |
|---|---|---|---|
| **空回滚** | Try 根本没执行成功（网络超时），TC 仍下发 Cancel | Cancel 误扣 / 报错 | Cancel 先查事务控制表，判断「该分支从未 Try 过」→ 直接返回成功 |
| **幂等** | Confirm / Cancel 被 TC 重试多次 | 同一笔扣减执行了两遍 | 建事务控制表，以 `(xid, branch_id)` 唯一键约束；已处理过的直接返回 |
| **悬挂** | Cancel 先到，被延迟的 Try 后到 | 预留的资源永远无人解冻 | 后到的 Try 先查控制表，发现已被 Cancel 过 → **拒绝执行** |

Seata 对这三件事提供了 `@Fence` 注解 + `tcc_fence_log` 表（TCC 防悬挂），能自动化掉大部分控制逻辑；
但只要 Try 里包含**外部系统调用**（如第三方支付、短信），控制表与外部调用之间的一致性仍需自行保证。

### 4.4 约束

| 约束 | 说明 |
|---|---|
| 业务必须能"拆分" | 扣减动作要能拆出「可预留」的中间态（冻结额、占用库存、待确认额度） |
| Confirm / Cancel 不能失败 | 二阶段失败只会重试，**最终必须成功**；写不下去时要落"待人工处理"的落地日志 |
| 需要额外字段/表 | 业务表通常要加 `frozen` 之类的预留字段，或独立建资源锁定表 |
| 不再需要 undo_log | `undo_log` 是 AT 专属，TCC 下可以不要 —— **本项目已直接删表** |

---

## 五、SAGA 模式原理：长流程「正向 + 补偿」

### 5.1 核心思路

把长事务拆成一串**本地事务**（每个动作都有独立的本地提交），失败时**逆序执行补偿动作**。
补偿动作是业务自己写的「反向操作」（如「已发货」的补偿是「发起退货」），而不是数据库回滚。

```
   T1 ──▶ T2 ──▶ T3 ──▶ T4        全部成功 → 结束（无需二阶段动作）
    │      │      │
    │      │      └── C3  ◀── 失败点
    │      └───────── C2
    └──────────────── C1            逆序执行补偿：C3 → C2 → C1
```

### 5.2 特点与约束

- **无锁、无预留**：一阶段提交后不占任何全局锁，天然适合长流程与高并发。
- **补偿不是回滚**：补偿动作的语义是「业务上把影响消掉」，可能出现"不可逆"环节（如已发出的短信无法收回），
  这时只能在业务上做补偿（补发道歉券）。
- **编排方式**：Seata 提供状态机引擎（JSON DSL 描述流程）或 `@SagaTransactional` 注解式编排。
- **幂等与空补偿由状态机记录**（它维护 forward / compensate 状态），但仍要求补偿动作本身幂等。
- **典型场景**：跨企业/跨系统的长流程（订单 → 支付 → 物流 → 通知）、已有正向接口只需补反向接口的存量系统。

---

## 六、XA 模式原理：依赖数据库 XA 的强一致

### 6.1 核心思路

把数据库原生的 XA 协议接入 Seata 的全局事务：一阶段 `XA PREPARE`（**不提交，资源挂起**），
二阶段由 TC 决定 `XA COMMIT` 或 `XA ROLLBACK`。RM 侧使用 XA 数据源，业务代码无侵入。

### 6.2 特点与约束

| 项 | 说明 |
|---|---|
| 一致性 | **强一致**，隔离性直接遵循数据库本地隔离级别（可做到读已提交），没有 AT 的"读未提交"问题 |
| 代价 | 一阶段后**连接与锁一直持有到二阶段结束**，长事务场景下资源占用严重，性能明显低于 AT |
| 依赖 | 要求数据库支持 XA（MySQL InnoDB 支持；部分云数据库/中间件不支持或有限制） |
| 启用 | `seata.data-source-proxy-mode: XA` |
| 适用 | 内部系统、事务链路短、对一致性要求高于吞吐的场景 |

---

## 七、四种模式横向对比

| 维度 | **AT** | **TCC** | **SAGA** | **XA** |
|---|---|---|---|---|
| 一阶段动作 | 业务 SQL 立即生效 + 写 undo_log | 只做资源预留，业务数据不变 | 各步本地事务立即提交 | `XA PREPARE`，资源挂起 |
| 回滚方式 | 框架按 before image 生成反向 SQL | 业务自写 Cancel | 业务自写补偿动作，逆序执行 | 数据库 `XA ROLLBACK` |
| 业务侵入性 | **极低**（加注解 + 建表） | **高**（拆三方法 + 预留字段） | 中（每个正向动作配一个补偿） | **极低** |
| 全局锁 | 有（持到二阶段结束） | 无 | 无 | 相当于有（数据库锁持到二阶段） |
| 隔离性 | 全局层面默认读未提交 | 由业务预留字段体现中间态 | 无隔离保证（最终一致） | 强（数据库本地隔离级别） |
| 幂等/空回滚/悬挂 | 框架兜底 | **必须自己处理** | 状态机兜底 + 补偿需幂等 | 由数据库保证 |
| 异构资源 | 不支持（必须关系库） | 支持（NoSQL / HTTP / 第三方接口） | 支持 | 不支持（必须支持 XA 的库） |
| 性能 | 高（二阶段轻） | 高（无锁，但业务成本高） | 高（无锁） | 低（资源占用时间长） |
| 典型场景 | 内部 CRUD 链路，SQL 能覆盖 | 高并发 / 长事务 / 跨异构资源 | 长流程编排、存量系统补补偿 | 短链路、强一致要求 |

**一句话定位**：
AT「最省事」、TCC「最可控但要自己收尾」、SAGA「最适合长流程」、XA「一致性强但最重」。

---

## 八、本项目 AT 时期状态实证（改造前）

> 📌 **本节记录的是 2026-09-11 改造之前的状态，用于留档对照。**
> 改造后的现状（TCC 的配置证据与实测数据）见
> [TCC改造说明与实测验证.md](./TCC改造说明与实测验证.md) §二 / §六。
> 最直观的一条差异：**TCC 根本不需要 `undo_log`**（本项目已把该表删掉），而 AT 下它每笔写操作都会落镜像。

### 8.1 配置证据

| 文件 | 关键配置 | 结论 |
|---|---|---|
| `samples-order/src/main/resources/application.yml` | `enable-auto-data-source-proxy: true`<br>`data-source-proxy-mode: AT` | RM，AT 模式 |
| `samples-account/src/main/resources/application.yml` | 同上 | RM，AT 模式 |
| `samples-storage/src/main/resources/application.yml` | 同上 | RM，AT 模式 |
| `samples-business/src/main/resources/application.yml` | 只有 `seata.enabled / application-id / tx-service-group / registry / config`，**无数据源、无 proxy-mode** | 纯 **TM**，不直接操作数据库，因此不需要 undo_log |
| `sql/db-seata.sql` | 建了 `undo_log` 表（唯一键 `ux_undo_log(xid, branch_id)`） | AT 专属回滚日志已就绪 |
| `samples-business/.../BusinessServiceImpl.java` | `@GlobalTransactional(timeoutMills = 300000, name = "dubbo-gts-seata-example")` | 全局事务入口（TM） |

> 注意：`samples-business/target/classes/application.yml` 中看不到 mode 配置是正常的 ——
> **AT 的实际体现点在 3 个 RM 服务**，门面服务不连库。
>
> **改造后这三行配置已变为：`enable-auto-data-source-proxy: false`，且 `data-source-proxy-mode` 整行删除。**
> 为什么必须关掉：AT 数据源代理类不感知「当前是否处于 TCC 分支」，若继续开着，TCC 的 Try 里那几条
> `update ... set frozen = frozen + ?` 会被顺手注册成 AT 分支、并写入 `undo_log` —— 一个事务里混入两种分支类型。

### 8.2 实测行为（与 `问题记录.md` / 启动手册一致）

| 用例 | 观察到的结果 |
|---|---|
| `POST /business/dubbo/buy`（正常） | storage 450→400、account 3100→3000、t_order +1；TC 日志 3× `PhaseTwo_Committed`；`undo_log` 归零 |
| `POST /business/dubbo/buy2`（故意抛异常） | 三库数据保持不变；TC 日志 `PhaseTwo_Rollbacked`；`undo_log` 归零 |

二阶段提交后 `undo_log` 被清空，正是 AT「提交即删镜像」的直接证据。

> 上表数值为 **2026-09-11 的实测快照**，与 `sql/db-seata.sql` 的初始值
> （`t_account.balance` = 4000.00、`t_storage.count` = 1000）不同 —— 演示库经多轮用例后已累计变化，
> 比对时只看**变化量**（`-amount` / `-count` / 订单 +1）即可，别被绝对值误导。

---

## 九、AT vs TCC 逐维度分析

### 9.1 对比表

| 维度 | **AT（改造前）** | **TCC** |
|---|---|---|
| 代码改造 | 加 `@GlobalTransactional` + 建 `undo_log`，**业务 SQL 零改动** | 每个参与方手写 Try / Confirm / Cancel，接口要拆 |
| 一阶段动作 | 业务 SQL **立即生效**，本地事务当场提交 | 只做资源预留，业务数据**不变** |
| 锁与隔离 | 全局行锁 + 本地行锁；全局层面默认"读未提交"，需 `@GlobalLock` / `FOR UPDATE` 补强 | Seata 不加全局锁，靠业务字段（如 `frozen`）表达中间态 |
| 回滚依据 | `undo_log` 的 before image，框架自动生成反向 SQL | 你写的 Cancel 逻辑，Seata 只负责"调它" |
| 数据源要求 | 必须是支持本地 ACID 的关系库，且**每个库都要建 `undo_log`** | 无要求，可跨 NoSQL / 外部 HTTP / 第三方支付接口 |
| 空回滚 / 幂等 / 悬挂 | 框架兜底，业务基本不用管 | **必须自己处理**（可用 `@Fence` + `tcc_fence_log` 辅助） |
| 性能特征 | 二阶段轻，但全局锁在高并发热点行上会成为瓶颈 | 无全局锁；代价转移到业务实现复杂度与额外字段/表 |
| 适用场景 | 内部 CRUD、SQL 能覆盖的链路 | 高并发、长事务、非关系库、要调外部服务 |

### 9.2 本质差异（一张图）

```
   AT：一阶段就"真改"                    TCC：一阶段只"占位"
   ┌────────────────────────┐            ┌────────────────────────┐
   │ UPDATE 直接执行         │            │ frozen +100            │
   │ 数据已变 + 持锁          │            │ 数据未变 + 不持全局锁    │
   │ 回滚靠"反向 SQL 自动生成" │            │ 回滚靠"你写的 Cancel"    │
   └────────────────────────┘            └────────────────────────┘
       省事 ←──────────────→ 可控
```

---

## 十、若本项目切换到 TCC：改造清单（已落地）

> ✅ **本节清单已于 2026-09-11 全部落地并实测跑通**，实际改动与本节的差异（例如在
> `samples-common` 里额外抽出了三方共用的 `TccControlSupport` 控制表状态机）见
> [TCC改造说明与实测验证.md](./TCC改造说明与实测验证.md) §三 / §五。

把 `buy` 链路（business → order → account）整体换成 TCC，需要动的文件与内容如下 —— 这份清单**已在 2026-09-11 落地**，括号里是最终实现与当初设想的出入：

| 模块 | 改动项 | 说明 |
|---|---|---|
| `samples-common` | 新增 TCC 动作接口 | 如 `OrderTccAction` / `AccountTccAction`，用 `@LocalTCC` + `@TwoPhaseBusinessAction` 声明，Dubbo 暴露 |
| `samples-order` | 实现 `OrderTccAction` | Try 建"预下单/占位订单"，Confirm 置为已确认，Cancel 置为已取消 |
| `samples-account` | 实现 `AccountTccAction` + **表结构加 `frozen`** | Try：`frozen += money`（并校验可用额）；Confirm：`balance -= money, frozen -= money`；Cancel：`frozen -= money` |
| `samples-storage` | 实现 `StorageTccAction` | Try 冻结库存，Confirm 真正扣减，Cancel 释放冻结 |
| 全 RM | 建 **事务控制表**（或用 `@Fence` + `tcc_fence_log`） | 解决幂等 / 空回滚 / 悬挂。**最终选了自建 `tcc_transaction_control`**（主键 `(xid, branch_id)`）而非官方 Fence —— 判定逻辑显式、能用 SQL 直接查表看状态，三个 RM 共用 `samples-common` 里同一份 `TccControlSupport` |
| 全 RM | **无需**配 `data-source-proxy-mode` | TCC 不走数据源代理（该配置只接受 `AT` / `XA`），由 `@TwoPhaseBusinessAction` 注解自动识别分支类型；Try 里的预留操作走普通本地事务 |
| `sql/seata-demo-tcc.sql` | TCC 版全量建库脚本：新增 `frozen` 列、`status` 列、`tcc_transaction_control` 表；**不创建 `undo_log`** | 库里不再有 `undo_log`；回退 AT 用已复原的 `sql/db-seata.sql`（AT 原版，含该表） |
| `samples-business` | **几乎不动** | 仍是 `@GlobalTransactional` + Dubbo 调用，只是调用的方法名从业务方法变成 Try 方法 |

接口骨架（account 侧）：

```java
@LocalTCC
public interface AccountTccAction {

    @TwoPhaseBusinessAction(name = "accountTccAction",
            commitMethod = "confirm", rollbackMethod = "cancel")
    boolean prepare(BusinessActionContext ctx,
                    @BusinessActionContextParameter(paramName = "userId") String userId,
                    @BusinessActionContextParameter(paramName = "money") int money);

    boolean confirm(BusinessActionContext ctx);
    boolean cancel(BusinessActionContext ctx);
}
```

改造时特别注意：

1. **Confirm / Cancel 必须幂等**，且都不能因为"业务校验不通过"而返回失败 —— 二阶段只有重试，没有别的退路。
2. **Cancel 必须能在 Try 未执行时安全返回**（空回滚），否则整个链路会因为一次网络抖动而卡死。
3. **Try 里不要做不可逆动作**（发短信、调第三方支付扣款），这类动作应放在 Confirm 之后的本地事务里。
4. Dubbo 的 `dubbo.force.tag` / 超时 / tag 隔离配置**不需要改**，XID 透传机制与 AT 相同（`dubbo-filter-seata`）。

---

## 十一、选型建议

**优先 AT**，只要同时满足：

- 链路上所有资源都是支持本地事务的**关系库**；
- 业务操作能表达为 **SQL**（能被 Seata 解析）；
- 并发压力不在同一批热点行上反复争抢（否则全局锁会明显拖慢）。

**才考虑 TCC**，当出现以下任一情况：

- 链路里有**非关系库**（Redis / MongoDB）或**外部接口**（第三方支付、物流）；
- 事务链路较长，AT 的全局锁持有时间过长；
- 高并发场景，AT 的全局锁成为实测瓶颈；
- 团队愿意承担"三方法 + 幂等/空回滚/悬挂"的开发与测试成本。

**SAGA / XA** 的适用面较窄：SAGA 用于长流程编排（且每步都有可写的反向动作）；
XA 用于链路短、对强一致要求高、且数据库支持 XA 的场景。

> **注意区分"教学示例的合理选择"与"本项目最终做了什么"**：
> 从工程选型看，本项目全是单库 MySQL + 纯 SQL 操作，**AT 本来就是更划算的选择**，没有必须上 TCC 的理由。
> 但本项目是**教学示例**，2026-09-11 已经整体改造为 TCC，目的正是把「幂等 / 空回滚 / 悬挂」这三个
> AT 里框架兜住的问题**亲手实现一遍**，以及验证「关掉数据源代理后 `undo_log` 归零」这一判定。
> 真实项目选型请仍以上面的标准为准，不要因为这份示例而默认选 TCC。

---

## 附录 A：排障与验证命令

### A.1 确认模式生效

```bash
# 三个 RM 服务的模式配置
grep -n "data-source-proxy-mode\|enable-auto-data-source-proxy" \
  samples-{order,account,storage}/src/main/resources/application.yml
```

### A.2 观察两阶段结果

```sql
-- ⚠️ 本节为 AT 时期留档：TCC 版库 `seata-demo-tcc` 已删除 undo_log 表，下面这条只适用于 AT 模式或老库 `seata_demo`
-- 全局事务结束后应为 0（提交时删除镜像、回滚时补偿后删除）
SELECT COUNT(*) FROM seata_demo.undo_log;

-- 业务数据核对（入场参数：userId=1、amount=100、count=50，见根目录 test.http）
-- ⚠️ 数值会漂移：建库脚本的初始值只有 t_account.amount=4000.00、t_storage.count=1000，
--    而下面是 2026-09-11 的实测快照（库中数据已跑过多轮用例），对不上属正常，看"变化量"即可。
SELECT balance FROM seata_demo.t_account WHERE id = 1;      -- buy: 3100.00 → 3000.00（-amount）
SELECT count   FROM seata_demo.t_storage WHERE id = 1;      -- buy: 450     → 400（-count）
SELECT * FROM seata_demo.t_order ORDER BY id DESC LIMIT 1;  -- buy: 新增 1 条
```

### A.3 TC 日志关键字

```bash
grep -E "PhaseTwo_Committed|PhaseTwo_Rollbacked|Committing global transaction is successfully done" \
  ~/logs/seata/seata-server.log
```

- 出现 3 次 `PhaseTwo_Committed` → 三分支全部提交成功；
- 出现 `PhaseTwo_Rollbacked` → 走了回滚补偿路径（`buy2` 用例的预期行为）。

### A.4 相关文档

| 文档 | 用途 |
|---|---|
| [TCC改造说明与实测验证.md](./TCC改造说明与实测验证.md) | **本项目 AT→TCC 的改造方案、代码结构、三道防护设计与端到端实测证据** |
| `项目分析-SpringBoot-Dubbo-Seata-Nacos.md` | 全项目结构与调用链分析 |
| `本地编译启动手册-实测跑通.md` | 环境搭建与端到端验证步骤（含 undo_log 归零的实测记录） |
| `问题记录.md` | 注册 IP 漂移、Dubbo 3 升级、TC JDK 版本、TCC 改造踩坑等闭环 |
| `学习指南-代码走读路线.md` | 9 步代码走读路线 |
