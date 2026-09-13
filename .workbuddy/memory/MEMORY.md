# 项目长期记忆：springboot-dubbo-seata-nacos

## 技术栈基线（2026-09-11 起：Dubbo 升到 3.x，事务模式由 AT 改为 TCC）
- Spring Boot 2.2.2.RELEASE；编译/运行样例服务用 **JDK 8**（字节码 target 1.8）
- **Dubbo 3.3.6**（2026-09-11 由 2.7.13 升级；细节见 `documents/问题记录.md` #002、手册 §9）
- Seata 2.6.0（`seata-spring-boot-starter`，`org.apache.seata.*` 新包名）；Seata TC 需 **JDK 9+（实测 17）**
  - ⚠️ 判断依据：TC 跑 JDK 8 会在 `GlobalSession.encode` 抛 `NoSuchMethodError: ByteBuffer.clear()`，
    **TC 吞异常不回包**，客户端表现为 `GlobalBeginRequest` RPC timeout 30s，而 TC 侧"看起来全正常"（进程在/端口监听/注册 healthy）。
    排查必看 TC 的 `~/logs/seata/seata-server.8091.error.log`。详见 `documents/问题记录.md` #004。
  - TC 启动脚本已改造：`.../seata-2.6.0/seata-server/bin/seata-server.sh` 里钉了 `SEATA_JAVA_HOME`（jdk-17，原脚本备份 `.orig`）。
    **注入位置必须在 `source seata-setup.sh` 之前**（该脚本按 `JAVACMD` 版本生成 GC 参数）。
    启动行出现 `[INFO] 使用 JDK: .../jdk-17.jdk/...` 且 GC 参数为 `-Xlog:gc*` 即为正确。
- Nacos 2.4.3；MyBatis-Plus 2.3；Druid 1.1.10；MySQL 8.4.8（库 `seata-demo-tcc`，root/root）
- netty **4.1.101.Final**（Dubbo3.3.6 声明 4.2.2 与 Seata2.6.0 声明 4.1.101 的安全交集；
  `netty-all` 自 4.1.91 起是"空壳聚合器"，不可钉回 4.1.42 那种胖包）
- `org.apache.dubbo.extensions:dubbo-filter-seata` **3.3.1**（XID 跨 Dubbo 透传，对齐 Seata 2.6.0）
- **事务模式 = TCC**（2026-09-11 由 AT 整体改造而来，分支名就叫 `TCC`；实测跑通）。
  - 判定当前模式：三个 RM 的 `enable-auto-data-source-proxy: false` 且**没有** `data-source-proxy-mode`；
    库里新增 `tcc_transaction_control` 表 + `t_account.frozen` / `t_storage.frozen` / `t_order.status` 字段；
    事务跑完 `t_order` 的 `status` 会流转（1=CONFIRMED / 2=CANCELED），`frozen` 必须回到 0。
  - `undo_log` **已从 TCC 库与脚本中删除**（2026-09-13）：它是 AT 专属表，TCC 下没有任何代码路径读写它；
    删表 → 重建 → 复跑 `buy`/`buy2` 照常通过。所以**别再拿 `undo_log` 判断模式**（表都不存在了），
    要看 `frozen` / `tcc_transaction_control` 有没有变化。
  - `data-source-proxy-mode` **只接受 `AT` / `XA`**；TCC 不走数据源代理，由 `@TwoPhaseBusinessAction`
    自动识别分支类型 —— 切 TCC 时**不用（也不能）把它写成 `TCC`**，正确做法是把 `enable-auto-data-source-proxy` 关掉。
    ⚠️ **必须关**：AT 代理类不感知 TCC 分支，开着会让 Try 里的 `update ... set frozen=?` 被注册成 AT 分支并写 `undo_log`。
  - `business` **没有数据源、不配 mode**，是纯 TM —— "它里面看不到 AT/TCC 配置"属正常，不是漏配。
  - 三个 RM 的 TCC 三段式：storage/account 走 `frozen` 预留（Try 加、Confirm 扣减并释放、Cancel 只释放）；
    order 走 `status` 流转（Try insert status=0、Confirm 0→1、Cancel 0→2）。
  - **幂等 / 空回滚 / 防悬挂**由 `samples-common` 的 `TccControlSupport` + `tcc_transaction_control` 表统一兜住，
    三方共用一套判定（不要在每个 RM 各写一份）。自检 19 断言全过：`/tmp/tcc-selfcheck/TccSelfCheck.java`。
  - 业务表**初始值**以 `sql/seata-demo-tcc.sql` 为准
    （`t_account.amount=4000.00`、`t_storage.count=1000`，`frozen` 全 0）。
    演示库已于 2026-09-11 用 `sql/seata-demo-tcc.sql` **从零重建**，当前就是这份干净基线。
    `3200→3100` / `3100→3000` / `2800→2700` 那些都是**运行期快照**，多轮用例会让库漂移 —— **比对只看变化量**。
  - 原理见 `documents/Seata事务模式原理-AT-TCC对比分析.md`（四模式原理 + AT vs TCC 对比，**该文"本项目状态"章节写的是 AT 时期留档**）；
    改造方案与实测证据见 `documents/TCC改造说明与实测验证.md`。

## 端口
| 服务 | HTTP | Dubbo | 事务角色 |
|---|---|---|---|
| business | 8104 | 10001 | TM（`@GlobalTransactional`） |
| order | 8101 | 20880 | RM（再调 account） |
| account | 8102 | 20883 | RM |
| storage | 8109 | 20888 | RM |

Seata TC 8091 / Nacos 8848。

## 项目约定
- 文档集中在 `documents/`：启动手册 `本地编译启动手册-实测跑通.md`、问题台账 `问题记录.md`
  （编号顺延 + 索引登记；只写实证结论，拒绝推测，每条要"现象→根因→修复→验证"闭环）、
  项目分析 `项目分析-SpringBoot-Dubbo-Seata-Nacos.md`、走读 `学习指南-代码走读路线.md`、
  模式原理 `Seata事务模式原理-AT-TCC对比分析.md`（AT/TCC/SAGA/XA 原理 + AT vs TCC 对比）、
  **TCC 改造 `TCC改造说明与实测验证.md`**（改造方案 + 代码结构 + 三道防护 + 端到端实测证据）。
  文档风格：中文、表格化、**ASCII 图（不用 mermaid）**、blockquote 头部写"用途/版本基线/依据来源/日期/一句话结论"。
  ⚠️ 项目分析 / 学习指南 / 启动手册的正文写于 AT 时期，三篇头部都已加"已改造为 TCC"的声明并指向 TCC 改造文档；
  正文里的 `*DubboService` / `undo_log` / `DataSourceProxy` 段落按"AT 时期留档"读。

### TCC 改造（2026-09-11）关键约定
- **契约与共用件放 `samples-common`**：`StorageTccAction` / `AccountTccAction` / `OrderTccAction`（`@LocalTCC` +
  `@TwoPhaseBusinessAction`）、`TccActionStatus`、`TccContextUtil`、`TccControlSupport`、`TccTransactionControlMapper`(.java/.xml)。
- ⚠️ **`@BusinessActionContextParameter` 必须"标两遍"** —— 接口一次 + **实现类方法参数再一次**。
  实现类标了 `@Transactional` 走 CGLIB 代理，Seata 的 `ActionInterceptorHandler` 读的是**实现类方法**的
  `getParameterAnnotations()`，接口注解不继承。漏标 → 二阶段参数全 `null` → Confirm/Cancel 影响行数 0 → TC 无限重试。
  核对命令：`javap -v -p <Impl>.class | grep -c RuntimeVisibleParameterAnnotations`（同期同类应一致）。
- **调用方对 Try 的 `BusinessActionContext` 一律传 `null`**，真上下文由 Seata 在提供者侧注入。
- order 是链路唯一"既当提供者又当消费者"的一环：它的 Try 内部再调 account 的 Try，**注册成两个独立分支**。
- 迁移坑：加 `status` 列后历史订单全是 0(TRYING)，增量脚本里有一条**默认注释**的一次性订正
  `UPDATE t_order SET status=1 WHERE status=0;`，只在首次执行时手工放行。
- **建库脚本（2026-09-13 定版）**：
  | 脚本 | 用途 | 关键点 |
  |---|---|---|
  | `sql/seata-demo-tcc.sql` | **TCC 唯一在用**（新环境/交付/重跑） | `DROP DATABASE` + `CREATE DATABASE`(utf8mb4/0900_ai_ci) + 7 表 + 基线 + 自检；**想"不删库"就注释掉 `DROP DATABASE` 那一行**（每表都带 `DROP TABLE IF EXISTS`） |
  | `sql/db-seata.sql` | **AT 时代旧脚本（历史留档）** | 2026-09-13 从 git `master` 原样取回：8 表含 `undo_log`、utf8mb3、带 `int(11)`；**不含 `USE`/`CREATE DATABASE`**，执行要 `-D <库>`；**别在 TCC 库上跑** |
  - 已删除（2026-09-13）：`sql/db-seata-tcc-upgrade.sql`（只服务"AT 老库原地升级"）、`sql/seata_demo.sql`（用户 Navicat 脏快照）。
  - ⚠️ **用户把 `sql/db-seata.sql` 当成"AT 原版留档"**，2026-09-13 已按此复原；
    被替换掉的原 TCC（不删库）版存 `.workbuddy/backup-sql/db-seata-tcc-nodrop-20260913.sql`。
    回退 AT = 这份 AT 脚本 + 代码一起 checkout 回改造前。
  - 全新环境/重建：`mysql -h127.0.0.1 -uroot -proot --default-character-set=utf8mb4 < sql/seata-demo-tcc.sql`
  - **库名 `seata-demo-tcc` 含短横线** —— DDL 里必须反引号包裹（`` DROP DATABASE `seata-demo-tcc` ``），
    jdbc url 里可直接写（`jdbc:mysql://127.0.0.1:3306/seata-demo-tcc?...`）。
  - **`DROP DATABASE` 后不需要重启业务服务**（实测：连接池自行重连同名新库，`buy`/`buy2` 照常通过）。
  - 列定义**不写显示宽度**（`int(11)`/`tinyint(4)` 自 MySQL 8.0.17 废弃）；TCC 脚本只保留 `double(14,2)`，
    字符集显式 `utf8mb4`（`utf8` = utf8mb3）。AT 留档那份**保持原貌**（仍有 `int(11)` + utf8mb3）。
  - 老库 `seata_demo` **保留未删**（对照/回退用）；四个服务已全部指向 `seata-demo-tcc`
    （account/order/storage 三个 yml 有 jdbc url，business 是纯 TM 无数据源）。
- **启动顺序是硬性的**：account/storage → order → business（`@DubboReference` 默认 `check=true`）。
  Dubbo 3 下失败被延后约 30s（`dubbo.module.check-reference-timeout` 默认 30000ms），
  中途会打印 `Application is ready` —— **别当成启动成功**。
- Dubbo 的 `parameters` 配置**一律用点号**（如 `dubbo.force.tag: "true"`），Spring 的方括号写法绑不进去。
- tag 隔离：4 个服务统一 `dubbo.provider.tag=dev` / `dubbo.consumer.tag=dev`，消费端另加 `dubbo.force.tag=true` 做严格隔离。
- 复用脚本 `/tmp/svc.py <account|order|storage|business> [serverPort] [额外 java 参数]`（`start_new_session=True` 防被回收）。
  ⚠️ **必须带上 `serverPort`**：Agent shell 注入了 `SERVER__PORT=51286`，Spring Boot 宽松绑定会把它当成
  `server.port`（优先级高于 yml），应用会"启动成功但监听 51286"。svc.py 会自动补 `--server.port=<端口>` 覆盖它。
  同理启动任何服务都建议显式传 `--server.port`。详见 `问题记录.md` #007。
- 升级前备份曾放 `.workbuddy/backup-dubbo3/`，**2026-09-11 已确认冗余并删除**
  （4 个 yml + pom 与 `master` 分支逐字节相同，4 个 `*.java` 仍在 HEAD 中）。
- **换网络 / 开机后必做自检**：本机 IP 会变，而 Nacos 里的注册 IP 是 TC（及各服务）**启动那一刻**算出来写死的，不会自更新。
  先比对 `ipconfig getifaddr en0` 与 Nacos 里 `seata-server` 的注册 IP；**不一致 → 重启 TC，并重启已运行的全部服务**
  （只重启 TC 不够，Dubbo 侧注册仍是旧 IP）。报错形态：`can not connect to [旧IP:8091]` / `connect failed, can not connect to services-server`。
  另注意：**"TC 在监听" ≠ "注册 IP 对"**。详见 `documents/问题记录.md` #001 / #003。
- **Nacos 里是两条注册，别搞混**（详见 #003 三）：`SEATA_GROUP` 的 `seata-server` 由 **Seata 客户端在启动时**读 → 错则**启动即失败**；
  `DEFAULT_GROUP` 的 `providers:*` 由 **Dubbo 消费者在调用时**解析 → 错则启动正常、**调用才报** `No provider available`。
- **重启范围**（#003 六）：TC 无热更新 → IP 变**必须重启 TC**；业务服务对 Seata 而言**不用重启**
  （客户端 10s 周期重连且会重新 `lookup()` 注册表，实测 ≤6s 能跟到新地址）。
  换网络时仍要重启业务服务，是**为了修它们自己的 Dubbo 注册**，不是为 Seata。
  **开关 VPN 通常不用重启** —— TUN 是 198.18.x/10.x，`preferred-networks: 192.168.*` 会跳过它。
  另：`SIGTERM` 优雅关闭会主动注销注册，`kill -9` 才会残留死地址。
- 打包用 JDK 8：`mvn clean package -DskipTests`（`target/` 被清过后需重新打包才能用 `java -jar` 启动）。
- **排障三条"假故障"辨识**（详见 #004/#005）：
  1. `BindException: Address already in use` → 该服务**已有一份在跑**（端口被旧 JVM 占），与 Seata/IP 无关。
  2. 客户端 `RPC timeout` 30s + TC 侧表象正常 → **查 TC 的 error 日志**（多半是 TC 跑错 JDK，见上）。
  3. **注册凭空消失**（`invokers: 0[]`）+ `curl` 返回 `HTTP=000` + 进程仍在 → `jstack <pid> | grep -n "at breakpoint"`，
     多半是 IDE 调试断点冻结了整个 JVM（`Thread.State` 仍是 RUNNABLE，只有行尾 `at breakpoint` 是标志）。
     另：`HTTP=404` = 应用活着（无此路由）；`HTTP=000` = 应用不响应。
- **TCC 专项排障（#006/#008）**：
  1. 二阶段 Confirm/Cancel 报"影响行数为 0"且参数为 `null` → 查 `@BusinessActionContextParameter` 有没有标在**实现类**上（见上）。
  2. 二阶段**反复重试永远失败** → **先看 TC 回传的 `applicationData` 里有没有业务参数**：
     没有 = Try 上报那一刻就丢了，属**会话级中毒**，改代码救不回来 → 必须丢弃会话：
     停 TC → `mv .../seata-server/bin/sessionStore sessionStore.bak-<时间戳>`（**移走不删**）→ 重启 TC，
     重启后 `sessionStore/8091/root.data` 为 0 字节即为干净；再清理业务侧残留（`frozen` 归零、删占位订单、`TRUNCATE tcc_transaction_control`）。
     有参数但业务报错 = 业务 SQL / 状态判断问题，改代码 + 重启业务服务即可，TC 重试能自愈。
- 复用 skill：`~/.workbuddy/skills/nacos-seata-registry-ip-debug/`（注册 IP 漂移排查 + 假实例验证手法）、
  `~/.workbuddy/skills/dubbo2-to-dubbo3-upgrade/`、`~/.workbuddy/skills/seata-at-to-tcc-migration/`。
