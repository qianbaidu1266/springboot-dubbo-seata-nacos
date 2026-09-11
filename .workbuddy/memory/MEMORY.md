# 项目长期记忆：springboot-dubbo-seata-nacos

## 技术栈基线（2026-09-11 起，Dubbo 已升级到 3.x）
- Spring Boot 2.2.2.RELEASE；编译/运行样例服务用 **JDK 8**（字节码 target 1.8）
- **Dubbo 3.3.6**（2026-09-11 由 2.7.13 升级；细节见 `documents/问题记录.md` #002、手册 §9）
- Seata 2.6.0（`seata-spring-boot-starter`，`org.apache.seata.*` 新包名）；Seata TC 需 **JDK 9+（实测 17）**
  - ⚠️ 判断依据：TC 跑 JDK 8 会在 `GlobalSession.encode` 抛 `NoSuchMethodError: ByteBuffer.clear()`，
    **TC 吞异常不回包**，客户端表现为 `GlobalBeginRequest` RPC timeout 30s，而 TC 侧"看起来全正常"（进程在/端口监听/注册 healthy）。
    排查必看 TC 的 `~/logs/seata/seata-server.8091.error.log`。详见 `documents/问题记录.md` #004。
  - TC 启动脚本已改造：`.../seata-2.6.0/seata-server/bin/seata-server.sh` 里钉了 `SEATA_JAVA_HOME`（jdk-17，原脚本备份 `.orig`）。
    **注入位置必须在 `source seata-setup.sh` 之前**（该脚本按 `JAVACMD` 版本生成 GC 参数）。
    启动行出现 `[INFO] 使用 JDK: .../jdk-17.jdk/...` 且 GC 参数为 `-Xlog:gc*` 即为正确。
- Nacos 2.4.3；MyBatis-Plus 2.3；Druid 1.1.10；MySQL 8.4.8（库 `seata_demo`，root/root）
- netty **4.1.101.Final**（Dubbo3.3.6 声明 4.2.2 与 Seata2.6.0 声明 4.1.101 的安全交集；
  `netty-all` 自 4.1.91 起是"空壳聚合器"，不可钉回 4.1.42 那种胖包）
- `org.apache.dubbo.extensions:dubbo-filter-seata` **3.3.1**（XID 跨 Dubbo 透传，对齐 Seata 2.6.0）

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
  （编号顺延 + 索引登记；只写实证结论，拒绝推测，每条要"现象→根因→修复→验证"闭环）。
- **启动顺序是硬性的**：account/storage → order → business（`@DubboReference` 默认 `check=true`）。
  Dubbo 3 下失败被延后约 30s（`dubbo.module.check-reference-timeout` 默认 30000ms），
  中途会打印 `Application is ready` —— **别当成启动成功**。
- Dubbo 的 `parameters` 配置**一律用点号**（如 `dubbo.force.tag: "true"`），Spring 的方括号写法绑不进去。
- tag 隔离：4 个服务统一 `dubbo.provider.tag=dev` / `dubbo.consumer.tag=dev`，消费端另加 `dubbo.force.tag=true` 做严格隔离。
- 复用脚本 `/tmp/svc.py <account|order|storage|business> [serverPort] [额外 java 参数]`（`start_new_session=True` 防被回收）。
- 升级前备份放在 `.workbuddy/backup-dubbo3/`。
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
- 复用 skill：`~/.workbuddy/skills/nacos-seata-registry-ip-debug/`（注册 IP 漂移排查 + 假实例验证手法）、
  `~/.workbuddy/skills/dubbo2-to-dubbo3-upgrade/`。
