# SpringBoot + Dubbo + Seata + Nacos 分布式事务示例项目分析

> 分析对象：`springboot-dubbo-seata-nacos`（本地工作区）
> 分析日期：2026-09-03
> 分析方法：以**工作区实际代码**为准（README.md 为网上搬运的历史版本说明，与当前代码存在版本脱节，详见 2.3 与 9.1）
>
> 一句话结论：这是一个 **「下单扣库存、建订单、扣账户」跨 4 个微服务的分布式事务教学示例**。
> 应用间用 **Dubbo（Nacos 注册）** 做 RPC，业务门面用 **Seata（`@GlobalTransactional`）** 保证跨服务数据一致性，
> **Nacos 同时充当 Dubbo 与 Seata 的注册中心和配置中心**。
>
> ⚠️ **2026-09-11 更新：本项目已由 AT 模式整体改造为 TCC 模式并实测跑通。**
> 本文正文（§2 目录树、§3 调用链、§6.2 时序等）描述的是 **AT 时期的接口结构**（`AccountDubboService` /
> `OrderDubboService` / `StorageDubboService` 已被 TCC 的 `*TccAction` 取代，数据源代理已关闭，`undo_log` 不再写入）。
> **改造后的结构与实测证据请看 [TCC改造说明与实测验证.md](./TCC改造说明与实测验证.md)。**
> 下文所有涉及 Dubbo 接口名、`undo_log`、`DataSourceProxy` 的段落，都请按"AT 时期留档"来读。

---

## 1. 项目定位与业务场景

- **类型**：Spring Boot 多模块 Maven 工程，官方 Seata 示例 `springboot-dubbo-seata`（lidong1665）的本地落地版，已做依赖升级。
- **演示目标**：解决 Dubbo 微服务调用链下的分布式事务问题——一次"用户购买商品"的请求会先后修改 **库存、订单、账户** 三处数据，
  任何一处失败都必须让已写入的数据整体回滚，这正是 Seata 全局事务的用武之地。
- **参与方（4 个服务 + 1 个公共模块）**：

| 服务 | Maven 模块 | 职责 | 是否有数据库 |
|---|---|---|---|
| 业务门面 | samples-business | 接收 HTTP 请求、编排 Dubbo 调用、开启全局事务 | 无（不连库） |
| 库存服务 | samples-storage | 扣减商品库存 | 有 |
| 订单服务 | samples-order | 生成订单（内部再调用账户服务扣款） | 有 |
| 账户服务 | samples-account | 扣减用户账户余额 | 有 |
| 公共模块 | samples-common | Dubbo API、DTO、响应封装、枚举、异常 | 无 |

典型用例：`用户 1 购买商品 C201901140001（水杯）× 50，金额 100` →
库存从 1000 → 950、账户从 4000 → 3900、订单新增 1 条记录，三者要么全部成功、要么全部回滚。

---

## 2. 技术栈与版本

### 2.1 依赖版本清单（根 pom.xml）

| 技术 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 2.2.2.RELEASE | parent + starter-web + starter |
| Java | 1.8 | 编译目标 |
| Apache Dubbo | 2.7.13 | dubbo / dubbo-spring-boot-starter / dubbo-config-spring / dubbo-registry-nacos |
| Seata | 1.4.0 | `seata-spring-boot-starter`（starter 自动装配方式） |
| Nacos Client | 1.3.0 | 注册/配置中心客户端 |
| MyBatis | 1.3.2（starter） | 数据访问 |
| MyBatis-Plus | 2.3 | 在 MyBatis 之上封装（ServiceImpl 基类） |
| Druid | 1.1.10 | 连接池 |
| MySQL | 5.1.47（connector） | 业务库 + Seata 状态库 |
| Lombok | 1.16.22 | 简化样板代码 |
| Netty | 4.1.42.Final | Dubbo/Seata 底层通信 |
| spring-context-support (alibaba) | 1.0.5 | Dubbo Spring 集成辅助 |

> 注：根 pom **未引入** `nacos-config-spring-boot-starter`——本项目没有把"应用自身配置"放进 Nacos 做动态配置，
> Nacos 只服务于 **Dubbo 注册/配置/元数据** 与 **Seata 注册/配置** 两类用途（见第 5、6 章）。

### 2.2 各服务模块依赖关系

```text
samples-business ──► samples-common
samples-order    ──► samples-common
samples-account  ──► samples-common
samples-storage  ──► samples-common
（各业务模块不互相依赖 jar，运行时通过 Dubbo 远程调用）
```

### 2.3 与 README 的版本差异（重要）

README.md 描述的是作者早期的 2019 版（Spring Boot 2.1.5 / Dubbo 2.7.3 / Seata 0.8.0 / Nacos 1.1.3 +
`registry.conf` 文件 + 手写 `DataSourceProxy` / `GlobalTransactionScanner` Bean），而工作区代码已升级为
Spring Boot 2.2.2 / Dubbo 2.7.13 / Seata 1.4.0 / Nacos client 1.3.0，且 Seata 集成方式已切换为 **starter 自动装配**。
README 中的截图、端口、配置均可能失效，阅读时须以代码为准（差异对照见 5.3.2）。

---

## 3. 工程结构与功能模块

### 3.1 模块总览

```text
springboot-dubbo-seata-nacos
├── pom.xml                    # 父 POM（聚合 5 个 module，统一依赖版本）
├── README.md                  # 历史说明（与代码脱节，慎用）
├── test.http                  # 下单接口测试用例（正常 / 异常回滚）
├── sql/
│   ├── seata-demo-tcc.sql     # 从零导入脚本（DROP/CREATE DATABASE + 业务表 + 控制表 + TC 三表 + 自检；共 7 张表，无 undo_log）
│   └── db-seata.sql           # AT 时代旧建库脚本（自 git master 取回留档；含 undo_log，仅回退 AT 时参考）
├── documents/                 # 本分析文档
├── samples-common/            # 公共模块（Dubbo API 契约 + DTO + 通用返回）
├── samples-account/           # 账户服务（端口/配置见其 application.yml）
├── samples-order/             # 订单服务
├── samples-storage/           # 库存服务
└── samples-business/          # 业务编排服务（HTTP 入口，端口 8104，已确认）
```

### 3.2 服务端模块内部包结构（以 samples-account 为例，其余同构）

```text
io.seata.samples.integration.account
├── AccountExampleApplication.java      # 启动类：@SpringBootApplication + @MapperScan + @EnableDubbo
├── config/
│   └── SeataDataSourceAutoConfig.java  # Druid 数据源 + MyBatis SqlSessionFactory（Bean 化）
├── controller/
│   └── TAccountController.java         # HTTP 直连调试入口（POST /account/dec_account）
├── dubbo/
│   └── AccountDubboServiceImpl.java    # @DubboService 暴露 Provider
├── entity/
│   └── TAccount.java                   # 对应 t_account
├── mapper/
│   └── TAccountMapper.java             # MyBatis-Plus Mapper（SQL 写在 resources/mapper 下）
├── service/
│   ├── ITAccountService.java
│   └── TAccountServiceImpl.java        # 业务逻辑
└── resources/
    ├── application.yml                 # Dubbo / Seata / 数据源配置
    └── mapper/TAccountMapper.xml       # decreaseAccount 扣款 SQL
```

- 三个**有库**服务（account / order / storage）结构完全对称：启动类三大注解 + 同样的 config 包。
- **business** 是特例：包名沿用历史遗留的 `io.seata.samples.integration.call`（与模块名不符，见 9.2）；
  启动类排除 `DataSourceAutoConfiguration`（不连库），只暴露 HTTP Controller，靠 `@DubboReference` 消费下游。

### 3.3 samples-common 公共模块细节

| 类别 | 内容 |
|---|---|
| Dubbo API 契约 | `AccountDubboService.decreaseAccount(AccountDTO)`、`OrderDubboService.createOrder(OrderDTO)`、`StorageDubboService.decreaseStorage(CommodityDTO)`，接口与 Provider/Consumer 共享，版本号 1.0.0 |
| DTO | `BusinessDTO`（userId/commodityCode/name/count/amount）、`OrderDTO`、`AccountDTO`、`CommodityDTO`，全部 `implements Serializable`（Dubbo 传输要求） |
| 响应封装 | `BaseResponse{status=200,message}` → `ObjectResponse<T>{data}` |
| 状态枚举 | `RspStatusEnum`：SUCCESS(200,"成功") / FAIL(999,"失败") / EXCEPTION(500,"系统异常") |
| 异常 | `DefaultException`（携带 RspStatusEnum 的业务异常） |
| 全局异常处理 | `GlobalExceptionHandler`（@ControllerAdvice）把 Exception / DefaultException 统一转为 ObjectResponse，避免 500 裸奔 |

> 设计要点：**Dubbo 接口定义在公共 jar 中**，Provider（@DubboService）与 Consumer（@DubboReference）依赖同一份 API 契约，
> 是 Dubbo 多模块工程的标准姿势，能保证方法签名与版本号天然对齐。

---

## 4. 核心业务：下单场景与模块协作

### 4.1 调用链总览

```text
 HTTP POST :8104/business/dubbo/buy
        │
        ▼
 ┌─────────────────────────────┐
 │ BusinessController          │  @RestController
 └──────────────┬──────────────┘
                ▼
 ┌─────────────────────────────────────────────┐
 │ BusinessServiceImpl.handleBusiness()        │
 │ ① @GlobalTransactional(全局事务起点=TM)      │
 │ ② log XID = RootContext.getXID()            │
 └──────┬───────────────────────┬──────────────┘
        │ Dubbo: 扣库存          │ Dubbo: 建订单
        ▼                       ▼
 ┌─────────────────┐   ┌──────────────────────┐
 │ StorageService  │   │ OrderService         │
 │ (RM)            │   │ (RM)                 │
 │ decreaseStorage │   │ createOrder          │
 └────────┬────────┘   └──────────┬───────────┘
          │                       │ Dubbo: 扣账户（订单内部发起）
          │                       ▼
          │              ┌──────────────────┐
          │              │ AccountService   │
          │              │ (RM)             │
          │              │ decreaseAccount  │
          │              └──────────────────┘
          │                       │
          ▼                       ▼
   t_storage 减库存       t_order 插记录（先扣账户 t_account 减余额）
```

调用顺序：**先扣库存 → 再建订单（建订单内部先扣账户再插单）**。三个库表各改一处，
全程在一个 `@GlobalTransactional` 包裹下。

### 4.2 关键代码走查

**（1）事务入口：BusinessServiceImpl.handleBusiness（业务门面）**

```java
@DubboReference(version = "1.0.0")
private StorageDubboService storageDubboService;

@DubboReference(version = "1.0.0")
private OrderDubboService orderDubboService;

@GlobalTransactional(timeoutMills = 300000, name = "dubbo-gts-seata-example")
@Override
public ObjectResponse handleBusiness(BusinessDTO businessDTO) {
    log.info("开始全局事务，XID = " + RootContext.getXID());
    // 1、扣减库存
    CommodityDTO commodityDTO = new CommodityDTO();
    commodityDTO.setCommodityCode(businessDTO.getCommodityCode());
    commodityDTO.setCount(businessDTO.getCount());
    ObjectResponse storageResponse = storageDubboService.decreaseStorage(commodityDTO);

    // 2、创建订单（内部会先扣账户）
    OrderDTO orderDTO = new OrderDTO();
    orderDTO.setUserId(businessDTO.getUserId());
    ...
    ObjectResponse<OrderDTO> response = orderDubboService.createOrder(orderDTO);

    // 任一服务返回非 200，主动抛业务异常 -> 触发全局回滚
    if (storageResponse.getStatus() != 200 || response.getStatus() != 200) {
        throw new DefaultException(RspStatusEnum.FAIL);
    }
    ...
}
```

- `@GlobalTransactional` 是 Seata 的**全局事务开启注解**（TM 角色）：方法进入前向 TC 申请全局事务拿到 XID，
  方法正常返回则提交，抛出未捕获异常则回滚。
- 各远程服务返回 `status` 而非抛异常（Dubbo 默认吞异常返回 RPC Result），因此这里用 **状态码判断 + 主动抛 DefaultException** 的方式让异常穿过事务边界触发回滚——这是本示例最重要的"触发回滚"模式。

**（2）失败回滚演示：handleBusiness2**

```java
@GlobalTransactional(timeoutMills = 300000, name = "dubbo-gts-seata-example")
public ObjectResponse handleBusiness2(BusinessDTO businessDTO) {
    // ...同 handleBusiness：先扣库存、再建订单（内部扣账户）...
    if (!flag) {                                    // flag 恒为 false
        throw new RuntimeException("测试抛异常后，分布式事务回滚！");  // 手动制造异常
    }
    ...
}
```

库存与订单都已写入后抛异常，用于验证：**已提交的分支（库存-950、账户-100、订单+1 条）被整体撤销**。
对应测试入口 `POST :8104/business/dubbo/buy2`。

**（3）Provider 暴露（三个服务一致，以 order 为例）**

```java
@DubboService(version = "1.0.0", protocol = "${dubbo.protocol.id}",
        application = "${dubbo.application.id}", registry = "${dubbo.registry.id}",
        timeout = 3000)
public class OrderDubboServiceImpl implements OrderDubboService {
    @Autowired
    private ITOrderService orderService;

    @Override
    public ObjectResponse<OrderDTO> createOrder(OrderDTO orderDTO) {
        log.info("全局事务id ：" + RootContext.getXID());   // 观察 XID 是否跨服务透传
        return orderService.createOrder(orderDTO);
    }
}
```

每个 Dubbo Provider 实现类第一行都打印 `RootContext.getXID()`——**这是验证 Seata XID 跨 Dubbo 链路透传是否成功的观察点**：
若下游打印的 XID 与上游一致，说明全局事务上下文已随 Dubbo RPC 传递。

**（4）订单内部再编排：TOrderServiceImpl.createOrder**

```java
@DubboReference(version = "1.0.0")
private AccountDubboService accountDubboService;

public ObjectResponse<OrderDTO> createOrder(OrderDTO orderDTO) {
    // 先远程扣账户
    accountDTO.setUserId(orderDTO.getUserId());
    accountDTO.setAmount(orderDTO.getOrderAmount());
    ObjectResponse objectResponse = accountDubboService.decreaseAccount(accountDTO);
    // 再本地插订单
    orderDTO.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
    ...
    baseMapper.createOrder(tOrder);
    if (objectResponse.getStatus() != 200) { ... return FAIL; }
    ...
}
```

说明调用链是**两跳**：business → order → account。所有参与修改数据库的远程方法都在同一全局事务内。

**（5）底层扣款/扣库存 SQL（Mapper XML）**

```xml
<!-- t_account -->
<update id="decreaseAccount">
  update t_account set amount = amount - ${amount} where user_id = #{userId}
</update>

<!-- t_storage -->
<update id="decreaseStorage">
  update t_storage set count = count - ${count} where commodity_code = #{commodityCode}
</update>

<!-- t_order -->
<insert id="createOrder" keyProperty="id" useGeneratedKeys="true" ...>
  insert into t_order values(null, #{order.orderNo}, #{order.userId}, #{order.commodityCode}, ${order.count}, ${order.amount})
</insert>
```

> ⚠️ SQL 中 `amount / count` 使用 `${}` 直接拼接（仅 `userId/commodityCode` 用 `#{}`）。
> 教学示例可接受，生产必须改 `#{}` 参数化（见 9.4）。

### 4.3 HTTP 调试入口汇总

| 服务 | 方法 | 路径 | 作用 |
|---|---|---|---|
| business | POST | `/business/dubbo/buy` | 正常下单（成功提交） |
| business | POST | `/business/dubbo/buy2` | 异常下单（验证全局回滚） |
| order | POST | `/order/create_order` | 直连订单服务（绕过门面，无全局事务） |
| account | POST | `/account/dec_account` | 直连账户服务 |
| storage | POST | `/storage/dec_storage` | 直连库存服务 |

`test.http` 已内置 buy / buy2 两个用例（均打 `http://localhost:8104`）。

---

## 5. 技术架构设计

### 5.1 总体架构（角色划分）

```text
                    ┌───────────────────────────────────────────────┐
                    │                 Nacos (127.0.0.1:8848)        │
                    │  namespace=40508bb4-179e-4c98-a2f1-c2c031c20b3c │
                    │                                               │
                    │ ① Dubbo 注册中心    ② Dubbo 配置/元数据中心     │
                    │ ③ Seata 注册中心    ④ Seata 配置中心(SEATA_GROUP)│
                    └───▲─────────▲──────────▲──────────▲───────────┘
       注册/订阅/发现     │         │          │          │  读 service.vgroup_mapping.*
        ┌───────────────┴──┐   ┌──┴───────────┴───┐   ┌─┴─────────────────┐
        │  4 个 SpringBoot  │   │                  │   │ Seata Server(TC)  │
        │  应用（Dubbo）     │   │  Seata Client    │   │ (db 存储,注册为    │
        │                  │   │  TM/RM 内置       │   │  serverAddr 服务)  │
        └───┬──────┬───────┘   └──────▲───────▲───┘   └────────▲──────────┘
            │      │                  │       │                │
   business │      │ account/order/   │       │  2PC 协调       │
  (无DB)    │      │ storage (有DB)    │       │                │
            │      │                  ▼       ▼                │
            │      │         MySQL (库 seata)                  │
            │      │   t_account / t_order / t_storage        │
            │      │   undo_log(每张被管表所在库都要有)          │
            └──── Dubbo RPC（业务数据流）───────────────────────┘
```

核心思想：
- **注册/发现**：4 个应用把自己注册进 Nacos（dubbo 服务名 = spring.application.name）；Seata Server 注册为 `serverAddr` 服务。
- **事务协调**：Seata Client（内置 TM/RM）与 Seata Server（TC）通过 Netty 直连通信，不经过 Nacos 转发数据。
- **数据一致**：RM 端 SQL 走 `DataSourceProxy`，AT 模式自动记录 undo_log，TC 按 2PC 决定提交或回滚。

### 5.2 Dubbo 设计与配置

**角色与用法**：

| 要素 | 实现 |
|---|---|
| API 契约 | samples-common 中定义接口（供两端引用） |
| Provider | `@DubboService(version="1.0.0", protocol/application/registry 引用配置 id, timeout=3000)` |
| Consumer | `@DubboReference(version="1.0.0")`（business 引用 storage/order；order 引用 account） |
| 扫描 | 启动类 `@EnableDubbo(scanBasePackages = "io.seata.samples.integration.xxx")` |
| 注册中心 | `dubbo.registry.address = nacos://127.0.0.1:8848?namespace=40508bb4-...` |
| 配置中心/元数据中心 | `dubbo.config-center.address` / `dubbo.metadata-report.address`（同上 Nacos） |
| 元数据模式 | `dubbo.application.metadata-type: remote`（实例级注册，元数据上报 Nacos） |

**business 模块 yml 中的 Dubbo 段（其余服务同构，application/protocol 端口不同）**：

```yaml
dubbo:
  application:
    id: dubbo-business-example
    name: dubbo-business-example
    qosEnable: false          # 关闭 Dubbo QoS 端口
    metadata-type: remote     # 元数据存远程（Nacos），服务实例间不再传递全量元数据
  protocol:
    id: dubbo
    name: dubbo
    port: 10001               # business 的 Dubbo 端口
  registry:
    id: dubbo-business-example-registry
    address: nacos://127.0.0.1:8848?namespace=40508bb4-179e-4c98-a2f1-c2c031c20b3c
  config-center:
    address: nacos://127.0.0.1:8848?namespace=40508bb4-179e-4c98-a2f1-c2c031c20b3c
  metadata-report:
    address: nacos://127.0.0.1:8848?namespace=40508bb4-179e-4c98-a2f1-c2c031c20b3c
  consumer:
    provided-by: dubbo-account-example,dubbo-order-example,dubbo-order-example   # ⚠️ 疑为笔误，见 9.3
```

设计要点：
1. **registry / config-center / metadata-report 三处齐配**，是 Dubbo 2.7.x 使用 Nacos 的推荐完整形态；
   `@DubboService` 注解里通过 `${dubbo.protocol.id}` 等占位符回引 yml 中的显式 id，要求 id 与注解占位严格一致。
2. `@DubboReference(version="1.0.0")` 与 `@DubboService(version="1.0.0")` 版本号必须一致才能匹配（本示例统一 1.0.0）。
3. `consumer.provided-by` 在实例级注册模式下用于声明"本消费者可能消费哪些 Provider"，当前值重复了 order 且未含 storage，按 Dubbo 语义可能导致 consumer 侧服务定位异常，建议修正为 `dubbo-account-example,dubbo-order-example,dubbo-storage-example`。

### 5.3 Seata 设计与配置

#### 5.3.1 当前代码的集成方式（seata-spring-boot-starter 1.4.0，自动装配）

有库的 account/order/storage 三个模块 yml 中统一配置（business 无数据源也配置了事务分组以便开启全局事务）：

```yaml
seata:
  enabled: true
  application-id: xxx-seata-example            # 如 account-seata-example / business-seata-example
  tx-service-group: xxx-service-seata-service-group   # 如 account-service-seata-service-group
  registry:
    type: nacos                                 # 从 Nacos 发现 TC(seata-server)
    nacos:
      server-addr: localhost:8848
      namespace: 40508bb4-179e-4c98-a2f1-c2c031c20b3c
      cluster: default
  config:
    type: nacos                                 # 从 Nacos 读全局事务配置
    nacos:
      namespace: 40508bb4-179e-4c98-a2f1-c2c031c20b3c
      server-addr: localhost:8848
      group: SEATA_GROUP
```

对应代码侧的职责分工：

| 谁 | 做什么 |
|---|---|
| `seata-spring-boot-starter` | 自动完成：① 把每个数据源包装成 Seata `DataSourceProxy`（BeanPostProcessor 方式，无需手写）；② 自动装配全局事务扫描器，识别 `@GlobalTransactional` |
| `SeataDataSourceAutoConfig`（代码保留类） | 只负责 Druid 数据源与 MyBatis `SqlSessionFactory` 的 Bean 化，**不再**手动创建 `DataSourceProxy` / `GlobalTransactionScanner`（升级后的正确姿势） |
| `@GlobalTransactional` | 业务门面方法上声明全局事务（TM 开启/提交/回滚） |
| 数据库 | AT 模式下需在被管表所在库建 `undo_log`；**本项目已切 TCC，该表已删除** |

account 模块的 `SeataDataSourceAutoConfig`：

```java
@Configuration
public class SeataDataSourceAutoConfig {
    @Autowired
    private DataSourceProperties dataSourceProperties;

    @Bean
    @Primary
    public DruidDataSource druidDataSource() { ... }        // 读 spring.datasource.*，手工组装池参数

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);               // 注入的 DataSource 会被 Seata 自动代理
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/*.xml"));
        return factoryBean.getObject();
    }
}
```

#### 5.3.2 新旧两种集成方式对比（本仓库历史演进）

| 维度 | README 旧版（Seata 0.8.0） | 当前代码（Seata 1.4.0） |
|---|---|---|
| 依赖 | seata-all | seata-spring-boot-starter |
| 服务端配置来源 | 本地 conf/registry.conf + nacos-config.txt | （服务端部署物，不在本工程） |
| 数据源代理 | 手写 `new DataSourceProxy(druidDataSource)` | starter 自动包装（默认开启） |
| 全局事务扫描 | 手写 `@Bean GlobalTransactionScanner(txGroup)` | starter 自动扫描 |
| 客户端 registry/config | conf/registry.conf 文件 | application.yml 中 `seata.registry/config.*` |
| 事务分组映射 | nacos 配置 `service.vgroup_mapping.<group>=default` | 同左（SEATA_GROUP 下），必须存在 |

升级要点：**1.4 后一切"客户端注册/配置"下沉到 yml，代码里只需保留数据源/MyBatis 装配**。
order/storage 的 config 类仍残留 `import io.seata.spring.annotation.GlobalTransactionScanner;` 与
"dataSourceProxy" 注释（升级时未清理，无功能影响），account 已清理干净——属维护不一致（见 9.2）。

### 5.4 Nacos 的双重角色

| Nacos 角色 | 服务方 | 使用方式 |
|---|---|---|
| Dubbo 注册中心 | 4 个应用 | `dubbo.registry.address=nacos://...`，Provider 注册、Consumer 订阅 |
| Dubbo 配置中心 | 4 个应用 | `dubbo.config-center.address=nacos://...`（读取全局配置，本示例无实际配置项） |
| Dubbo 元数据中心 | 4 个应用 | `dubbo.metadata-report.address=nacos://...`，实例级注册时上报接口元数据 |
| Seata 注册中心 | Seata Server(TC) + 客户端 | 服务端注册为 `serverAddr`；客户端经 `seata.registry.type=nacos` 发现 TC |
| Seata 配置中心 | Seata 客户端 | `seata.config.type=nacos`，group=`SEATA_GROUP`，读取 `service.vgroup_mapping.*` 等 |

关键约定：
- **namespace 隔离**：dubbo 三个地址与 seata 的 registry/config 都指向 `40508bb4-179e-4c98-a2f1-c2c031c20b3c`
  （非默认 public），本地 Nacos 需存在该 namespace，否则注册/发现互相隔离、事务会报 `no available server to connect`。
- **事务分组映射必须存在**：Nacos 配置中心 `SEATA_GROUP` 组下要有
  `service.vgroup_mapping.account-service-seata-service-group=default`（order/storage/business 各一条），
  把"事务服务分组"路由到 Seata 集群 `default`。分组名与 yml `seata.tx-service-group` 严格一致。

### 5.5 数据层设计

- 持久层：MyBatis + MyBatis-Plus（ServiceImpl 基类封装 CRUD），SQL 集中在 `resources/mapper/*.xml`。
- 连接池：Druid（手工 Bean，`@Primary`）。
- **库表拓扑（演示简化版）**：4 张业务表（t_account/t_order/t_storage）+ undo_log 全部放在**同一个 MySQL 库 seata**，
  三个服务各自用独立数据源指向它。README 明确这是"为简化演示而把三张表放进一个库"。生产环境应每服务独立库，
  且**每个库都要有 undo_log 表**；Seata Server 若用 db 模式存储，还需要 global_table/branch_table/lock_table 三张状态表。

---

## 6. Dubbo / Seata / Nacos 三者配合机制（重点）

### 6.1 启动/装配阶段（谁先注册到哪、配置从哪来）

```text
① Nacos 启动（standalone）
② 业务库初始化（`seata-demo-tcc.sql` 从零导入：业务表 + tcc_transaction_control + TC 三表；不想删库就注释掉它的 `DROP DATABASE` 行）
③ Seata Server 启动（db 模式）→ 向 Nacos 注册服务：serverAddr（cluster=default）
④ 4 个 SpringBoot 应用依次启动：
   ├─ 从 yml 读 Dubbo 配置 → Provider/接口元数据注册到 Nacos（registry/config-center/metadata-report）
   ├─ 从 yml 读 Seata 配置 → 向 Nacos(SEATA_GROUP) 拉取 service.vgroup_mapping.* 等
   │     → 按分组 default 在 Nacos 注册中心里找到 TC(serverAddr) → 建立 Netty 长连接
   └─ seata-spring-boot-starter 完成数据源代理 + @GlobalTransactional 切面就绪
```

> Nacos 在这里是 **"服务注册表 + 配置仓库"**：Dubbo 用它做服务发现，Seata 用它做 TC 寻址与配置下发，
> 两套体系互不感知，只是共用同一个注册中心。

### 6.2 一次下单的分布式事务时序（AT 模式）

```text
Business                          TC(seata-server)          Storage/Order/Account(RM)      MySQL
   │  @GlobalTransactional 进入         │                         │                        │
   │────1. begin(注册全局事务,申请XID)──▶│ 写 global_table         │                        │
   │◀──2. 返回 XID (ip:port:txId) ─────│                         │                        │
   │ 3. RootContext.setXID             │                         │                        │
   │ 4. Dubbo 调用(扣库存)  XID 随RPC透传│                         │                        │
   │──────────────────────────────────▶│──── 注册分支事务 ───────▶│                        │
   │                                   │                         │ 5. 执行 UPDATE          │
   │                                   │                         │   ├─ 前置镜像 snapshot  │
   │                                   │                         │   ├─ 写 undo_log        │
   │                                   │                         │   ├─ 更新业务数据        │
   │                                   │                         │   └─ 获取行锁(lock_table)│
   │ 6. Dubbo 调用(建订单→扣账户)  同理   │                         │                        │
   │                                   │                         │                        │
   │ 7. handleBusiness 正常返回         │                         │                        │
   │────8. 全局提交(commit)────────────▶│ 改 global_table 状态     │                        │
   │                                   │──9. 通知各RM异步提交────▶│ 删 undo_log/释放行锁    │
   │                                   │                         │ 10. 事务最终生效         │
```

回滚路径（buy2 / 服务返回非 200 → 抛异常）：

```text
   │ 7'. 抛出 RuntimeException/DefaultException
   │────8'. 全局回滚(rollback)────────▶│ 改 global_table 状态     │
   │                                   │──9'. 通知各RM补偿───────▶│ 依据 undo_log 反向 SQL  │
   │                                   │                         │  恢复 before 镜像       │
   │                                   │                         │ 10'. 清 undo_log/行锁   │
   │◀──11'. 返回 Rollbacked(日志) ─────│                         │                        │
```

### 6.3 XID 跨服务传播（打通 Dubbo 与 Seata 的关键机制）

Seata 1.4 内置了 **Dubbo 集成（seata-dubbo 自动生效于 starter 依赖链）**，机制如下：

1. 上游 `RootContext.getXID()` 拿到全局事务 XID。
2. 发起 Dubbo RPC 前，Seata 的过滤器把 XID 写入 **RPC 隐式传参**（attachment）；
3. 下游 Provider 收到请求后，Seata 过滤器从 attachment 取出 XID 写入本地 `RootContext`；
4. 下游业务代码（各 `*DubboServiceImpl`）`RootContext.getXID()` 即可读到同一 XID，从而把本地分支事务挂到全局事务上。

验证手段：观察各服务日志。一次成功下单应看到 4 个服务打印**相同**的 XID，形如 `127.0.0.1:8091:xxxxxxxxxx`；
最后 business 日志出现 `commit status: Committed`（或异常时 `rollback status: Rollbacked`）。

> 前提约束：**整个 Dubbo 调用链必须是同步调用**，且中途不能更换线程（XID 放在 ThreadLocal）；
> 若中间隔了 MQ/异步线程，需手动传递 XID。

### 6.4 回滚触发点小结

| 场景 | 触发方式 | 结果 |
|---|---|---|
| 服务返回非 200 | `throw new DefaultException(RspStatusEnum.FAIL)` | 全局回滚（已提交分支被补偿） |
| 业务异常（buy2） | 抛 `RuntimeException` | 全局回滚 |
| 本地 DB 异常（如插入失败） | 异常上抛 | 全局回滚 |
| 超时 | `timeoutMills=300000`，TC 判定超时 | TC 主动回滚 |

注意：Dubbo 服务间若不抛异常而只返回状态码，Seata 拦截器看不到异常，**不会回滚**——所以示例里必须在门面层把
"非 200 状态"翻译成异常（见 4.2(1)）。这是集成 Dubbo + Seata 最容易踩的坑。

---

## 7. 数据模型与建库脚本（sql/seata-demo-tcc.sql）

> **本节已按 TCC 改造后的结构更新**（2026-09-11）。库名 `seata-demo-tcc`，脚本自带 `DROP DATABASE` + `CREATE DATABASE`（utf8mb4 / utf8mb4_0900_ai_ci），可从零导入。
> 想"不删库、只重建表"就把 `seata-demo-tcc.sql` 的 `DROP DATABASE` 那一行注释掉。同目录的 `db-seata.sql` 是 **AT 时代旧脚本（留档）**，别当 TCC 脚本用。
> 2026-09-13 清理：增量升级脚本 `db-seata-tcc-upgrade.sql` 与 Navicat 脏快照 `seata_demo.sql` 已删除。

### 7.1 业务表（TCC 三段式的落库载体）

| 表 | 字段 | 初始数据 | TCC 语义 |
|---|---|---|---|
| t_account | id, user_id, amount, **frozen** | (1, '1', 4000.00, 0.00) | Try `frozen += amt` → Confirm `amount -= amt; frozen -= amt` → Cancel `frozen -= amt` |
| t_storage | id, commodity_code(唯一), name, count, **frozen** | (1, 'C201901140001', '水杯', 1000, 0) | 同上，按库存数计 |
| t_order | id, order_no(唯一), user_id, commodity_code, count, amount, **status** | 空 | Try 插入 `status=0` 占位单 → Confirm `0→1` → Cancel `0→2`（不物理删除） |

**加粗列是 TCC 相比 AT 新增的**：`frozen` 承载"已预留、未落账"的中间态，`status` 承载订单的占位态 —— AT 靠数据库行锁挡并发，TCC 靠显式字段表达中间态。

### 7.2 Seata 相关表（脚本一并提供）

| 表 | 归属 | 作用 |
|---|---|---|
| tcc_transaction_control | 业务库 seata-demo-tcc（三个 RM 共用） | **TCC 核心表**：主键 `(xid, branch_id)`，status `1=TRIED / 2=CONFIRMED / 3=CANCELED`，承载幂等 / 空回滚 / 防悬挂 |
| ~~undo_log~~ | ~~业务库 seata-demo-tcc~~ | AT 模式回滚镜像表；切 TCC 后没有任何代码路径读写它，**已从库与脚本中删除**（回退 AT 需自行重建） |
| global_table / branch_table / lock_table | Seata Server 的 db 存储模式 | 全局/分支事务状态与全局行锁。**本 demo 的 TC 用 `store.mode=file`，这三张表不会被访问**，保留仅沿袭原示例（真要切 db 模式时，TC 配置里的 url 指向的是另一个库 `seata`） |

### 7.3 生产注意事项

- 脚本 `DEFAULT CHARSET=utf8mb4`（2026-09-11 重建时由 utf8mb3 升级）；列定义省略了 `int(11)` 这类**显示宽度**（MySQL 8.0.17 起废弃），但 `double(14,2)` 的精度必须保留 —— 它约束的是实际小数位数。
- `amount` 用 `double(14,2)`，精度敏感场景应改 `decimal`。
- 演示把 3 业务表 + 控制表放同一库（TCC 已不需要 `undo_log`，脚本不再创建）；若将来回退 AT 成分，每个业务库各自都要建 `undo_log`，否则报 `Table 'xxx.undo_log' doesn't exist`。
- 重建动作已实测：`mysql < sql/seata-demo-tcc.sql`（含 `DROP DATABASE`）→ **不重启 4 个业务服务** → `buy`/`buy2` 通过（连接池会自行重连同名新库）。

---

## 8. 部署与启动

### 8.1 依赖环境

| 组件 | 说明 |
|---|---|
| MySQL | 执行 `sql/seata-demo-tcc.sql`（从零导入：`DROP DATABASE` + `CREATE DATABASE seata-demo-tcc` + 3 业务表 + tcc_transaction_control + TC 三表） |
| Nacos Server | standalone 启动（本机实测走 **public** namespace，Dubbo 与 Seata 均注册在此） |
| Seata Server | **file 模式**（启动参数 `-m file`，会话落在 `bin/sessionStore`；`conf/application.yml` 里虽有 db 段但 `store.mode: file` 生效）；conf 中 registry/config 指 Nacos，启动后注册为 `seata-server` |
| Nacos 配置 | SEATA_GROUP 组下预置 `service.vgroup_mapping.<各服务tx-service-group>=default`（若缺失，客户端启动报 no available server to connect） |

### 8.2 启动顺序

```text
1. 启动 Nacos（standalone）
2. 初始化 MySQL：`mysql -h127.0.0.1 -uroot -proot --default-character-set=utf8mb4 < sql/seata-demo-tcc.sql`
3. 启动 Seata Server（注册进 Nacos）
4. 依次启动 samples-account / samples-order / samples-storage / samples-business
5. Nacos 控制台确认：4 个 dubbo 应用服务 + serverAddr 均已注册
```

### 8.3 验证

- 正常：`POST http://localhost:8104/business/dubbo/buy`
  body：`{"userId":1,"commodityCode":"C201901140001","name":"fan","count":50,"amount":"100"}`
  → 返回 `{"status":200,"message":"成功"}`；日志出现 `Begin new global transaction [XID]` 与 `commit status:Committed`；
  库表三处数据同步变化。
- 回滚：`POST http://localhost:8104/business/dubbo/buy2`（同 body）
  → 日志 `rollback status:Rollbacked`；库存/账户/订单数据均恢复原值（三表无残留变更）。

---

## 9. 踩坑与改进建议（代码审查结论）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 9.1 | README 与代码严重脱节（版本、端口、Seata 集成方式、截图全部是 2019 旧版） | README.md | 按当前代码重写 README 或标记 deprecated |
| 9.2 | business 包名 `io.seata.samples.integration.call` 与模块名不符（历史遗留）；order/storage 的 SeataDataSourceAutoConfig 残留未使用的 `import GlobalTransactionScanner` 及旧注释 | 各模块 | 统一包名/清理 import，保持与 account 一致 |
| 9.3 | `dubbo.consumer.provided-by` 写成 `dubbo-account-example,dubbo-order-example,dubbo-order-example`（order 重复、缺 storage） | business application.yml | 修正为 `dubbo-account-example,dubbo-order-example,dubbo-storage-example` |
| 9.4 | Mapper XML 用 `${amount}` / `${count}` 字符串拼接，存在 SQL 注入与类型风险 | 3 个 Mapper.xml | 全部改 `#{}` 参数化 |
| 9.5 | 版本偏老：Spring Boot 2.2.2 / JDK8 / mysql-connector 5.1.47 / Nacos client 1.3.0，README 时代组合 | pom.xml | 学习示例无碍；上生产应整体升级并核对 CVE |
| 9.6 | namespace 硬编码 UUID，换环境（或改回 public）需同步 dubbo 3 处 + seata 2 处 + Seata Server conf | 各 yml / 服务端 | 抽取统一环境变量或配置中心管理 |
| 9.7 | 三服务共库是演示简化（TCC 已不需要 undo_log）；若回退 AT 成分，拆库后需每库建 undo_log | sql/ 部署 | 生产按"一服务一库"规划 |
| 9.8 | Dubbo 服务间只返回状态码不抛异常，Seata 默认感知不到失败，必须门面层翻译为异常 | BusinessServiceImpl | 保持现状写法并注释说明原因 |
| 9.9 | 状态判断用魔法数字 200/999 | RspStatusEnum 调用处 | 统一引用枚举常量 |
| 9.10 | 注解里 `${dubbo.protocol.id}` 等占位符与 yml id 强耦合，改配置易漏 | 3 个 @DubboService | 显式 id 与占位符保持一致，或用 @EnableDubbo 默认规则简配 |

---

## 附录 A：关键配置速查（以 business 为模板，各服务替换名称/端口）

```yaml
# ==================== Dubbo ====================
dubbo:
  application: { id: dubbo-xxx-example, name: dubbo-xxx-example, qosEnable: false, metadata-type: remote }
  protocol:    { id: dubbo, name: dubbo, port: <各自不同> }
  registry:    { id: dubbo-xxx-example-registry, address: "nacos://127.0.0.1:8848?namespace=<ns>" }
  config-center:    { address: "nacos://127.0.0.1:8848?namespace=<ns>" }
  metadata-report:  { address: "nacos://127.0.0.1:8848?namespace=<ns>" }

# ==================== Seata ====================
seata:
  enabled: true
  application-id: xxx-seata-example
  tx-service-group: xxx-service-seata-service-group
  registry:
    type: nacos
    nacos: { server-addr: localhost:8848, namespace: "<ns>", cluster: default }
  config:
    type: nacos
    nacos: { server-addr: localhost:8848, namespace: "<ns>", group: SEATA_GROUP }

# ==================== 有库服务追加（account/order/storage） ====================
spring:
  datasource: { driver-class-name, url: "jdbc:mysql://127.0.0.1:3306/seata?...", username, password }
mybatis:
  mapper-locations: classpath*:/mapper/*.xml
```

**代码侧三件套**：
1. 启动类：`@SpringBootApplication` + `@MapperScan`（有库服务）+ `@EnableDubbo(scanBasePackages=...)`
2. Provider：`@DubboService(version="1.0.0", protocol/application/registry=..., timeout=3000)`
3. Consumer：`@DubboReference(version="1.0.0")`；事务入口方法加 `@GlobalTransactional(timeoutMills=..., name=...)`

---

*文档基于 2026-09-03 工作区代码整理；端口号仅 business(8104) 经 test.http 与配置文件双重确认，
其余服务 HTTP/Dubbo 端口以其各自 application.yml 为准。*
