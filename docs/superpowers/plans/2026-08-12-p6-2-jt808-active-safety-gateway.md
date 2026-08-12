# P6-2 实时车辆定位与主动安全接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以测试先行方式交付可承载 100 个并发连接的 JT/T 808 + T/JSATL12 + JT/T 1078 控制信令网关，完成 4 台白名单终端的定位质量治理、主动安全报警、附件控制、管理端处置与两层验收。

**Architecture:** 新增纯 Java 协议库、独立 Netty 网关和终端模拟器；网关负责 TCP、协议、会话及本地持久化缓冲，运营 API 负责终端档案、坐标/质量、不可变位置、报警事实、Outbox/SSE 和权限，管理端通过 REST 与带授权头的流式 SSE 展示终端及报警。网关不直写运营数据库，媒体二进制始终由外部媒体服务承载。

**Tech Stack:** Java 21、Spring Boot 3.5.3、Netty（由 Spring Boot 依赖管理锁定版本）、Flyway、H2 文件型网关缓冲、PostgreSQL 16/PostGIS、Vue 3、TypeScript、Vitest、Playwright、JUnit 5、Testcontainers、Micrometer。

## Global Constraints

- 规格基线：`docs/superpowers/specs/2026-08-12-p6-2-jt808-active-safety-gateway-design.md`。规格与计划冲突时先暂停实现并修订文档，不在代码中暗改口径。
- 全部生产代码遵循 RED → GREEN → REFACTOR；每个任务先保留可复现的失败输出，再写最小实现，再执行本任务测试和相关回归。
- JT/T 808 以 2019 为主，兼容 2013 公共定位链路；苏标 T/JSATL12 按终端能力显式启用，不依靠长度或车牌猜协议。
- `0x1206` 只作为 JT/T 1078 文件上传完成通知；苏标报警事实来自带 T/JSATL12 扩展的 `0x0200`。
- 固定十六进制协议样本必须来自标准文档或目标终端脱敏实报。缺少经核验样本时，只能完成框架，不能自行编造字段布局或宣称苏标/附件兼容通过。
- 设备原始坐标和摘要可追溯；业务地图、围栏和调度只使用一次标准化后的 GCJ-02。人工位置链路保持兼容。
- 鉴权码、完整终端手机号、完整原始报文、上传凭证、永久媒体地址不得进入日志、指标、SSE、管理 API、测试快照或 Git。
- 面向终端只开放 `7611/TCP`。网关管理 HTTP 端口 `7612` 仅限容器/管理网络；媒体端口不由本系统监听。
- 网关只有服务级 API 权限，不持有运营数据库写权限；运营 API 不接收调度员 JWT 作为网关凭证。
- 位置、报警、附件元数据写入网关持久化缓冲成功后才允许协议成功应答；缓冲失败或满载不得伪成功。
- 第一层技术验收通过不等于 P6-2 完成；只有 4 台真实终端第二层验收和人工审阅通过后才能收口。
- 真机报警只能使用厂商安全测试模式或静态回放；禁止在公共道路上以危险驾驶动作制造报警。
- 不升级 ETA 口径，所有 ETA 继续标注“仅作参考”。

---

## 0. 实现边界与文件地图

### 0.1 固化实现选择

1. `libs/jt-protocol` 是无 Spring 业务依赖的协议库，供网关和模拟器复用。
2. `apps/jt-gateway` 是 Spring Boot 管理进程 + 原生 Netty TCP 服务：`7611` 承载终端协议，`7612` 承载健康、指标和受保护控制 API。
3. 网关缓冲使用独立 H2 MVStore 文件库，挂载 `/var/lib/jt-gateway`；它只保存规范化 DTO 和投递状态，不依赖运营 PostgreSQL 可用性。
4. 网关 → API 使用 `Authorization: Bearer` 服务凭证和 `X-Service-Credential-Version`；API 仅保存允许版本的 SHA-256 摘要并以常量时间比较，支持双版本轮换。
5. API → 网关的强制断开和附件下行使用独立控制凭证，不能复用浏览器 JWT。
6. 浏览器使用 `fetch` + `ReadableStream` 消费 SSE，以便携带现有 Bearer Token；禁止把 access token 放在 URL 查询串。
7. 报警 Outbox 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 认领，保留 7 天；SSE 断线按 `Last-Event-ID` 补读，超窗返回重拉列表信号。
8. 外部媒体通过 `AlarmAttachmentMediaPort` 接入；未配置时提供明确的不可用适配器，使报警可用而附件进入 `WAITING_MEDIA_SERVICE`。

### 0.2 目标目录

```text
libs/jt-protocol/
  pom.xml
  src/main/java/com/idavy/drtops/jt/protocol/
    codec/                # 帧、转义、校验、消息头、分包
    core/                 # 808 公共消息 DTO/编解码
    jsatl12/              # 苏标扩展 SPI 与实现
    jt1078/               # 文件/附件控制 DTO/编解码
  src/test/java/com/idavy/drtops/jt/protocol/
  src/test/resources/protocol-fixtures/

apps/jt-gateway/
  pom.xml
  Dockerfile
  src/main/java/com/idavy/drtops/jtgateway/
    JtGatewayApplication.java
    config/               # 端口、限流、凭证和容量配置
    netty/                # 服务启动、pipeline、背压、空闲检测
    session/              # 注册、鉴权、接管、离线
    dispatch/             # 协议模块路由和业务工作队列
    ingress/              # 规范化 DTO、本地缓冲、API 投递
    attachment/           # 受保护控制入口与下行消息
    observability/        # 指标、健康、脱敏
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__create_gateway_outbox.sql
  src/test/java/com/idavy/drtops/jtgateway/

tools/jt-terminal-simulator/
  pom.xml
  src/main/java/com/idavy/drtops/jtsimulator/
  src/test/java/com/idavy/drtops/jtsimulator/

apps/api/src/main/java/com/idavy/drtops/
  config/                 # 双向服务认证和安全路由
  domain/terminal/        # 终端档案、绑定、会话审计
  domain/location/        # GPS 接入、转换、质量、快照扩展
  domain/alarm/           # 报警、动作、附件、Outbox、SSE
  integration/jtgateway/  # 控制客户端
  integration/media/      # AlarmAttachmentMediaPort

apps/admin-web/src/
  api/terminals.ts
  api/vehicleAlarms.ts
  api/alarmEvents.ts
  pages/TerminalManagementPage.vue
  components/AlarmBoard.vue
  components/AlarmDetailPanel.vue
  components/AlarmActionDialog.vue

apps/api/src/main/resources/db/migration/
  V13__create_jt_terminal_registry.sql
  V14__extend_gps_location_quality.sql
  V15__create_vehicle_alarm_domain.sql

infra/docker-compose.pilot.yml
docs/pilot/jt-gateway-operations.md
docs/pilot/evidence/p6-2/
```

### 0.3 阶段门禁

| 门禁 | 最晚到位任务 | 未到位时允许继续 | 未到位时禁止声明 |
| --- | --- | --- | --- |
| JT/T 808 2019/2013 经核验固定报文 | Task 2 | 模块骨架 | 协议兼容通过 |
| 目标终端苏标 ADAS/DMS 脱敏报文 | Task 10 | SPI 与拒绝路径 | 苏标解析通过 |
| 厂商附件消息样本与上传参数口径 | Task 14 | 媒体端口与状态机 | 附件控制通过 |
| 外部媒体服务测试环境和回调密钥 | Task 14 | `WAITING_MEDIA_SERVICE` 降级 | 附件全链路通过 |
| 4 台终端白名单资料和安全回放方式 | Task 17 | 自动化及模拟器验收 | P6-2 完成 |

---

### Task 1: 建立 Maven 模块和可启动网关骨架

**Files:**
- Modify: `pom.xml`
- Create: `libs/jt-protocol/pom.xml`
- Create: `apps/jt-gateway/pom.xml`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/JtGatewayApplicationTest.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/JtGatewayApplication.java`
- Create: `apps/jt-gateway/src/main/resources/application.yml`

**Interfaces:**
- 产出 `JtGatewayApplication` Spring Boot 进程；此任务不监听公网端口。
- 根 reactor 顺序：`libs/jt-protocol` → `apps/jt-gateway` → `apps/api`。

- [ ] **Step 1: 记录当前基线**

Run: `git status --short --branch`

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test`

Expected: 工作树无意外改动；现有 API 测试 PASS。若基线失败，先记录为独立问题，不把失败混入 P6-2。

- [ ] **Step 2: 写网关上下文 RED 测试并接入 reactor**

在 `JtGatewayApplicationTest` 写：

```java
@SpringBootTest(properties = {
        "jt.gateway.tcp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:gateway_boot;DB_CLOSE_DELAY=-1"
})
class JtGatewayApplicationTest {
    @Test void startsWithoutOpeningTheDevicePort() {}
}
```

只创建三个 POM 和测试，不创建 `JtGatewayApplication`。

- [ ] **Step 3: 运行 RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway -am test`

Expected: FAIL，提示 `JtGatewayApplication` 不存在或无法找到启动配置。

- [ ] **Step 4: 实现最小启动类和安全默认配置**

```java
@SpringBootApplication
public class JtGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(JtGatewayApplication.class, args);
    }
}
```

`application.yml` 默认使用 `7611`、`7612`，设备监听默认关闭，仅在显式环境变量中启用；Actuator 只暴露 `health,prometheus`。

- [ ] **Step 5: 运行 GREEN 与 reactor 回归**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway -am test`

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q test`

Expected: PASS。

- [ ] **Step 6: 提交骨架**

```powershell
git add pom.xml libs/jt-protocol apps/jt-gateway
git commit -m "build: scaffold JT gateway modules"
```

---

### Task 2: 以固定报文实现 JT/T 808 帧、消息头和分包内核

**Files:**
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/codec/ProtocolVersion.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/codec/Jt808MessageHeader.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/codec/Jt808Frame.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/codec/Jt808FrameDecoder.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/codec/Jt808FrameEncoder.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/codec/Jt808SubpackageAssembler.java`
- Create: `libs/jt-protocol/src/test/java/com/idavy/drtops/jt/protocol/codec/Jt808FrameCodecTest.java`
- Create: `libs/jt-protocol/src/test/java/com/idavy/drtops/jt/protocol/codec/Jt808SubpackageAssemblerTest.java`
- Create: `libs/jt-protocol/src/test/resources/protocol-fixtures/jt808-core-fixtures.json`

**Interfaces:**

```java
public record Jt808Frame(Jt808MessageHeader header, ByteBuf body, byte checksum) {}

public interface Jt808MessageCodec<T> {
    int messageId();
    Class<T> payloadType();
    T decode(Jt808MessageHeader header, ByteBuf body);
    void encode(T value, ByteBuf target);
}
```

- [ ] **Step 1: 固化样本来源和摘要**

将经核验的 2019/2013 注册、鉴权、心跳、位置样本写入 JSON，仅保留脱敏十六进制、标准版本、消息 ID、预期字段和来源说明；运行敏感信息扫描确认无真实手机号和鉴权码。

- [ ] **Step 2: 写帧处理 RED 测试**

覆盖粘包、半包、连续 `0x7e`、`0x7d01/0x7d02` 转义、XOR 校验、超长帧、非法 BCD、2019/2013 头识别和“解码后重新编码协议等价”。

- [ ] **Step 3: 运行帧 RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol -Dtest=Jt808FrameCodecTest test`

Expected: FAIL，缺少 codec 类型或断言不成立。

- [ ] **Step 4: 实现最小帧编解码**

解码器只处理边界、转义、长度、校验和消息头；业务消息留给注册表。拒绝原因使用枚举，不把完整报文写入异常文本。

- [ ] **Step 5: 写分包 RED 测试**

覆盖乱序到达、重复子包、总包数冲突、缺包、60 秒超时清理和内存上限；断言一个终端不能污染另一终端的组包键。

- [ ] **Step 6: 运行分包 RED 并实现有界组包器**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol -Dtest=Jt808SubpackageAssemblerTest test`

Expected first run: FAIL。

实现键 `terminalIdentity + messageId + serialNo`，设置最大在途组数、最大总字节数和定时清理。

- [ ] **Step 7: 运行 GREEN 与协议库回归**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol test`

Expected: PASS。

- [ ] **Step 8: 提交协议内核**

```powershell
git add libs/jt-protocol
git commit -m "feat: implement JT808 protocol framing"
```

---

### Task 3: 实现 Netty pipeline、会话状态、白名单注册和鉴权

**Files:**
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/netty/JtGatewayServer.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/netty/JtChannelInitializer.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/netty/ConnectionAdmissionHandler.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSession.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSessionRegistry.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalRegistryPort.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandler.java`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandlerTest.java`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/netty/JtGatewayServerIntegrationTest.java`

**Interfaces:**

```java
public interface TerminalRegistryPort {
    RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity);
    AuthenticationDecision verifyAuthentication(
            UUID terminalId, int tokenVersion, String presentedTokenSha256);
    void recordSessionAudit(SessionAuditIngress event);
}
```

- [ ] **Step 1: 写状态机 RED 测试**

覆盖：30 秒内只允许 `0x0100/0x0102`、未预置/暂停/绑定失效拒绝、三次鉴权失败断开、心跳刷新、180 秒离线、未鉴权位置拒绝、新会话接管旧连接。

- [ ] **Step 2: 运行 RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway -Dtest=RegistrationAuthenticationHandlerTest test`

Expected: FAIL。

- [ ] **Step 3: 实现最小会话状态机**

状态只允许 `CONNECTED_UNAUTHENTICATED → AUTHENTICATED → CLOSED`；鉴权码使用 `SecureRandom` 生成，内存中只保留当前会话所需值，日志只输出终端脱敏别名。

- [ ] **Step 4: 写 pipeline 和背压 RED 测试**

使用 Netty `EmbeddedChannel` 和真实 loopback 端口验证 handler 顺序、每 IP 连接上限、消息速率限制、`IdleStateHandler`、业务队列高水位时 `autoRead=false`、恢复后重新读取、持续拥塞关闭连接。

- [ ] **Step 5: 运行 RED 并实现最小 Netty 服务**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway -Dtest=JtGatewayServerIntegrationTest test`

Expected first run: FAIL；实现后 PASS。数据库、HTTP、H2 和媒体调用不得运行在 EventLoop。

- [ ] **Step 6: 执行 GREEN 与线程阻塞断言**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway test`

Expected: PASS；测试记录 I/O 线程名，并断言阻塞端口只在有界 worker executor 调用。

- [ ] **Step 7: 提交接入与会话层**

```powershell
git add apps/jt-gateway
git commit -m "feat: add authenticated JT terminal sessions"
```

---

### Task 4: 建立网关独立持久化缓冲和内部投递客户端

**Files:**
- Create: `apps/jt-gateway/src/main/resources/db/migration/V1__create_gateway_outbox.sql`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/GatewayIngressEnvelope.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/GatewayOutboxRepository.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/GatewayIngressBuffer.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/OperationsApiClient.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/GatewayOutboxDispatcher.java`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/ingress/GatewayIngressBufferTest.java`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/ingress/GatewayOutboxDispatcherTest.java`

**Interfaces:**

```java
public record GatewayIngressEnvelope(
        int schemaVersion,
        UUID idempotencyKey,
        IngressKind kind,
        Instant gatewayReceivedAt,
        String payloadJson) {}
```

- [ ] **Step 1: 写持久化和优先级 RED 测试**

覆盖进程重启后恢复、幂等键唯一、位置 50 条/1 秒批次、报警/附件高优先级不被位置饿死、投递状态、指数退避、死信 7 天和正文确认后删除。

- [ ] **Step 2: 运行 RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway -Dtest=GatewayIngressBufferTest,GatewayOutboxDispatcherTest test`

Expected: FAIL。

- [ ] **Step 3: 实现 H2 文件型缓冲**

表包含 `idempotency_key`、`kind`、`schema_version`、`payload_json`、`status`、`attempt_count`、`next_attempt_at`、`created_at`、`delivered_at`、`last_error_code`。不得包含设备鉴权码、上传凭证或附件 URL。

- [ ] **Step 4: 写内部 HTTP 安全 RED 测试**

使用 `MockRestServiceServer` 验证 Bearer 凭证和版本头存在、日志不含 token、401 不丢消息、429/5xx 退避、2xx 才确认。

- [ ] **Step 5: 实现投递客户端和健康细分**

分别暴露 `bufferWritable`、`operationsApiReachable`；API 不可用不影响既有已鉴权会话写缓冲，缓冲不可写则停止读取且不应答成功。

- [ ] **Step 6: 运行 GREEN 与重启恢复测试**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway test`

Expected: PASS。

- [ ] **Step 7: 提交缓冲与投递层**

```powershell
git add apps/jt-gateway
git commit -m "feat: persist gateway ingress deliveries"
```

---

### Task 5: 创建终端、绑定、安全审计与位置质量数据库迁移

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V13__create_jt_terminal_registry.sql`
- Create: `apps/api/src/main/resources/db/migration/V14__extend_gps_location_quality.sql`
- Create: `apps/api/src/test/java/com/idavy/drtops/P6TerminalLocationMigrationTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java`

- [ ] **Step 1: 写迁移 RED 测试**

在 PostGIS Testcontainers 中从 V12 基线迁移，断言终端手机号/代码唯一、每终端/车辆最多一个有效绑定、人工/GPS 字段约束、质量枚举、JSONB、BRIN/GIST/B-tree 索引和历史人工位置回填。

- [ ] **Step 2: 运行 RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=P6TerminalLocationMigrationTest -Ddrt.integration.postgis=true test`

Expected: FAIL，V13/V14 不存在。

- [ ] **Step 3: 实现 V13 终端域迁移**

创建 `jt_terminals`、`jt_terminal_vehicle_bindings`、`jt_gateway_audit_events`，通过部分唯一索引保证强绑定；鉴权只存 SHA-256 摘要和版本。

- [ ] **Step 4: 实现 V14 兼容位置迁移**

扩展 `vehicle_location_events` 和 `vehicles` 快照；将历史人工位置回填为 `GOOD/LEGACY_NONE`。调整 `recorded_by` 和地址可空约束时使用来源条件 CHECK，不破坏 V6 不可变触发器。

- [ ] **Step 5: 运行 GREEN、迁移重放和兼容核对**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=P6TerminalLocationMigrationTest,DatabaseMigrationTest -Ddrt.integration.postgis=true test`

Expected: PASS；迁移前后人工位置数量、排序、快照引用一致。

- [ ] **Step 6: 提交迁移**

```powershell
git add apps/api/src/main/resources/db/migration apps/api/src/test/java/com/idavy/drtops/P6TerminalLocationMigrationTest.java apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java
git commit -m "feat: add JT terminal and location quality schema"
```

---

### Task 6: 实现终端领域、双向服务认证和强绑定管理 API

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminal.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalVehicleBinding.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtGatewayAuditEvent.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/GatewayRegistryController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/config/GatewayServiceAuthenticationFilter.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/jtgateway/JtGatewayControlClient.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/auth/Permission.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalManagementServiceTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalApiTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/config/GatewayServiceAuthenticationTest.java`

**Interfaces:**

```java
public record RegistrationVerificationRequest(
        String terminalPhone,
        String terminalCode,
        String manufacturerId,
        String model,
        String vehicleIdentifier,
        String protocolVersion) {}

public record AuthenticationVerificationRequest(
        UUID terminalId, int tokenVersion, String tokenSha256, String gatewayInstance) {}
```

- [ ] **Step 1: 写领域 RED 测试**

覆盖预置、激活、暂停、退役、绑定、换机、鉴权轮换、版本冲突、停用时强制断开、历史绑定不可覆盖和全部审计追加。

- [ ] **Step 2: 运行 RED 并实现最小领域服务**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TerminalManagementServiceTest test`

Expected first run: FAIL；实现后 PASS。

- [ ] **Step 3: 写权限与服务凭证 RED 测试**

验证 `TERMINAL_READ/TERMINAL_MANAGE` 角色矩阵、无服务凭证/错误版本/错误摘要拒绝、双版本轮换、浏览器 JWT 不能访问 `/internal/jt-gateway/**`、服务凭证不能访问普通运营 API。

- [ ] **Step 4: 实现双认证域隔离**

内部入口使用专用 `SecurityFilterChain` 或先于 JWT 的窄路径过滤器，认证主体固定为 `JT_GATEWAY_SERVICE`；摘要使用 `MessageDigest.isEqual` 比较。错误响应不回显 token、摘要或终端完整身份。

- [ ] **Step 5: 写管理/注册 API RED 测试**

验证普通 API 只返回脱敏手机号和业务标识，不返回内部 UUID/鉴权摘要；内部注册核验同时检查手机号、代码、厂商、型号、车辆标识和有效绑定；注册成功只接收网关生成的摘要。

- [ ] **Step 6: 实现 API 与强制断开控制客户端**

API → 网关只发送终端内部 ID 和原因码；控制凭证独立配置。网关不可用时管理动作保留一致状态并返回明确的“已停用、断开待确认”，不得回滚安全停用。

- [ ] **Step 7: 运行 GREEN 与 API 回归**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TerminalManagementServiceTest,TerminalApiTest,GatewayServiceAuthenticationTest test`

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test`

Expected: PASS。

- [ ] **Step 8: 提交终端后端**

```powershell
git add apps/api
git commit -m "feat: manage whitelisted JT terminals"
```

---

### Task 7: 交付终端管理页和前端权限

**Files:**
- Modify: `apps/admin-web/src/auth/permissions.ts`
- Modify: `apps/admin-web/src/router/index.ts`
- Modify: `apps/admin-web/src/layouts/AppLayout.vue`
- Create: `apps/admin-web/src/api/terminals.ts`
- Create: `apps/admin-web/src/api/terminals.test.ts`
- Create: `apps/admin-web/src/pages/TerminalManagementPage.vue`
- Create: `apps/admin-web/src/pages/terminal-management-page.test.ts`
- Modify: `apps/admin-web/e2e/auth-rbac.spec.ts`

- [ ] **Step 1: 写 API 和页面 RED 测试**

覆盖脱敏手机号、协议能力、在线/离线、最近注册/鉴权/位置、绑定历史和安全审计；断言页面不渲染鉴权摘要、完整手机号、原始报文和内部 UUID。

- [ ] **Step 2: 运行 RED**

Run: `npm.cmd test -- src/api/terminals.test.ts src/pages/terminal-management-page.test.ts`

Expected: FAIL。

- [ ] **Step 3: 实现最小 API、路由和页面**

只有 `TERMINAL_READ` 显示入口；预置、换机、暂停、退役、轮换和强制断开仅对 `TERMINAL_MANAGE` 显示，并要求二次确认和原因。

- [ ] **Step 4: 写角色矩阵 E2E RED 测试**

验证系统管理员可管理；调度员、运营、审计员不能进入终端管理；直接输入 URL 也被路由守卫和 API 双重拒绝。

- [ ] **Step 5: 运行 GREEN 与前端回归**

Run: `npm.cmd test -- src/api/terminals.test.ts src/pages/terminal-management-page.test.ts src/layouts/app-layout.test.ts`

Run: `npm.cmd run typecheck`

Run: `npm.cmd run e2e -- auth-rbac.spec.ts --workers=1`

Expected: PASS。

- [ ] **Step 6: 提交终端管理端**

```powershell
git add apps/admin-web
git commit -m "feat: add JT terminal administration"
```

---

### Task 8: 解码 0x0200 并投递规范化位置

**Files:**
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/core/LocationReport.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/core/LocationReportCodec.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/core/Jt808CoreModule.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistry.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/CanonicalPositionIngress.java`
- Create: `libs/jt-protocol/src/test/java/com/idavy/drtops/jt/protocol/core/LocationReportCodecTest.java`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/ingress/CanonicalPositionIngressTest.java`

**Interfaces:**

```java
public record CanonicalPositionIngress(
        UUID terminalId,
        UUID vehicleId,
        String protocolVersion,
        int messageSerialNo,
        BigDecimal rawLongitude,
        BigDecimal rawLatitude,
        String rawCoordinateSystem,
        Instant terminalLocatedAt,
        Instant gatewayReceivedAt,
        int alarmBits,
        int statusBits,
        BigDecimal speedKph,
        Integer directionDegrees,
        Integer altitudeMeters,
        Integer satelliteCount,
        String payloadDigest) {}
```

- [ ] **Step 1: 写 0x0200 RED 测试**

使用 Task 2 固定样本覆盖 2019/2013、符号/状态位、速度单位、方向、海拔、终端 BCD 时间、可选卫星数、未知附加项保留和错误长度拒绝。

- [ ] **Step 2: 运行 RED 并实现 codec**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol -Dtest=LocationReportCodecTest test`

Expected first run: FAIL；实现后 PASS。

- [ ] **Step 3: 写身份绑定和幂等 RED 测试**

断言 `vehicleId` 只能来自已鉴权会话，不接受报文车牌覆盖；幂等键由终端、协议、消息 ID、流水号、终端时间和报文摘要稳定生成。

- [ ] **Step 4: 实现模块路由和规范化 DTO**

`Jt808CoreModule` 只解析公共位置，产生 DTO 并写缓冲；未知合法消息记录计数并通用应答，不进入位置域。

- [ ] **Step 5: 运行 GREEN 与回归**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol,apps/jt-gateway -am test`

Expected: PASS。

- [ ] **Step 6: 提交位置协议链路**

```powershell
git add libs/jt-protocol apps/jt-gateway
git commit -m "feat: decode JT808 location reports"
```

---

### Task 9: 实现 GPS 坐标标准化、四级质量和不可变位置入库

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/GpsLocationIngressController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/GpsLocationIngressService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/CoordinateTransformer.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationQualityEvaluator.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationQualityStatus.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEvent.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationView.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/fleet/Vehicle.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/CoordinateTransformerTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/LocationQualityEvaluatorTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/GpsLocationIngressIntegrationTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/e2e/VehicleLocationFlowIntegrationTest.java`

**Interfaces:**

```java
public record LocationQualityDecision(
        LocationQualityStatus status,
        Set<LocationQualityReason> reasons,
        boolean persistEvent,
        boolean applySnapshot) {}
```

- [ ] **Step 1: 写坐标转换 RED 测试**

使用核准基准点覆盖 WGS84→GCJ-02 ≤ 5 米、GCJ-02 不重复转换、非法坐标拒绝和转换版本 `WGS84_GCJ02_V1/IDENTITY_GCJ02_V1`。

- [ ] **Step 2: 运行 RED 并实现转换器**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=CoordinateTransformerTest test`

Expected first run: FAIL；实现后 PASS。

- [ ] **Step 3: 写四级质量 RED 测试**

覆盖 ≤30 秒、30–120 秒、>120 秒、未来 >120 秒、乱序、零/越界、定位无效、120/140/180 km/h 阈值、服务区外、缺可选字段和连续隔离。

- [ ] **Step 4: 运行 RED 并实现纯函数质量规则**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=LocationQualityEvaluatorTest test`

Expected first run: FAIL；实现后 PASS。所有原因使用枚举集合并序列化 JSON，不生成不可解释总分。

- [ ] **Step 5: 写入库/快照/幂等 RED 集成测试**

断言 `GOOD/WARNING` 写历史并只由更新事件更新快照；`QUARANTINED` 写历史不更新快照；`REJECTED` 只写网关审计；重复批次不重复；GPS `recorded_by=null`；人工位置仍按原行为工作。

- [ ] **Step 6: 实现内部批量位置入口**

单批最多 50 条，逐条返回 `ACCEPTED/REPLAYED/REJECTED` 和稳定原因码；整个批次使用服务身份，不能用终端字段选择其他车辆。

- [ ] **Step 7: 运行 GREEN 与人工位置回归**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=CoordinateTransformerTest,LocationQualityEvaluatorTest,GpsLocationIngressIntegrationTest,VehicleLocationFlowIntegrationTest test`

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test`

Expected: PASS。

- [ ] **Step 8: 提交位置质量治理**

```powershell
git add apps/api
git commit -m "feat: govern GPS location quality"
```

---

### Task 10: 实现 T/JSATL12 主动安全解析和扩展 SPI

**Files:**
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/jsatl12/ActiveSafetyExtension.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/jsatl12/ActiveSafetyExtensionRegistry.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/jsatl12/Jsatl12ActiveSafetyModule.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/jsatl12/Jsatl12AlarmExtensionCodec.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/CanonicalVehicleAlarm.java`
- Create: `libs/jt-protocol/src/test/java/com/idavy/drtops/jt/protocol/jsatl12/Jsatl12AlarmExtensionCodecTest.java`
- Create: `libs/jt-protocol/src/test/resources/protocol-fixtures/jsatl12-alarm-fixtures.json`

**Interfaces:**

```java
public interface ActiveSafetyExtension {
    String standardCode();
    boolean supports(TerminalCapabilityProfile profile);
    List<DecodedActiveSafetyAlarm> decode(LocationReport position, ByteBuf extension);
}
```

- [ ] **Step 1: 验证报警样本门禁**

固定样本必须覆盖 ADAS 前向碰撞、车道偏离，DMS 疲劳驾驶、接打电话，以及 START/END、一帧多报警和未知合法类型。记录样本来源、标准版本和 SHA-256；若门禁不满足，暂停本任务，不编造字段。

- [ ] **Step 2: 写苏标 RED 测试**

断言标准位置先成功解析；苏标扩展按终端能力启用；基础位置有效但扩展损坏时只拒绝报警扩展并产生审计；未知合法代码保留原码和“未知报警类型”。

- [ ] **Step 3: 运行 RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol -Dtest=Jsatl12AlarmExtensionCodecTest test`

Expected: FAIL。

- [ ] **Step 4: 实现最小苏标解码和规范化映射**

一条 `0x0200` 可生成一条位置和多条报警；报警幂等键包含终端、标准、报警标识、类型、开始时间和摘要。不得把 `0x1206` 注册为报警解码器。

- [ ] **Step 5: 写 SPI 隔离 RED 测试**

登记但未实现粤标能力时返回 `UNSUPPORTED_ACTIVE_SAFETY_STANDARD`，不尝试苏标解码；一个扩展实现抛错不得破坏公共位置。

- [ ] **Step 6: 运行 GREEN 与固定样本复算**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol,apps/jt-gateway -am test`

Expected: PASS。

- [ ] **Step 7: 提交主动安全协议模块**

```powershell
git add libs/jt-protocol apps/jt-gateway
git commit -m "feat: decode JSATL12 active safety alarms"
```

---

### Task 11: 创建报警、处置、附件和 Outbox 数据模型

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V15__create_vehicle_alarm_domain.sql`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarm.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmAction.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmAttachment.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmAttachmentTransfer.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmOutboxEvent.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmIngressService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmActionService.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/P6VehicleAlarmMigrationTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/alarm/VehicleAlarmIngressServiceTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/alarm/VehicleAlarmActionServiceTest.java`

- [ ] **Step 1: 写 V15 RED 迁移测试**

断言报警事实不可更新/删除，只有受控存储过程或服务事务可更新处理字段；动作追加式；同附件一个活动传输；deduplication key 唯一；状态、时间、车辆和 BRIN 索引存在。

- [ ] **Step 2: 运行 RED 并实现 V15**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=P6VehicleAlarmMigrationTest -Ddrt.integration.postgis=true test`

Expected first run: FAIL；实现后 PASS。

- [ ] **Step 3: 写报警接入 RED 测试**

覆盖一位置多报警、无可信位置仍保留报警坐标、隔离位置不丢报警、重复投递、START/END、乱序 END、未知类型、报警与 Outbox 同事务。

- [ ] **Step 4: 实现内部报警批量入口**

事实字段创建后不可由领域对象修改；END 以业务键关联 START 并只补 `ended_at`/状态关系，不覆盖原始事实；每个新事实和状态变化都写 Outbox。

- [ ] **Step 5: 写处置状态机 RED 测试**

覆盖全部合法/非法转换、原因必填、系统管理员受控重开、乐观锁冲突 409、动作与审计追加、权限二次校验。

- [ ] **Step 6: 实现最小处置服务**

使用 `@Version` 和事务；更新报警处理字段、追加动作、追加通用审计、写 Outbox 必须原子完成。

- [ ] **Step 7: 运行 GREEN 与数据库回归**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=P6VehicleAlarmMigrationTest,VehicleAlarmIngressServiceTest,VehicleAlarmActionServiceTest -Ddrt.integration.postgis=true test`

Expected: PASS。

- [ ] **Step 8: 提交报警领域**

```powershell
git add apps/api
git commit -m "feat: persist active safety alarm workflow"
```

---

### Task 12: 实现报警 REST、权限、Outbox 发布和 SSE 补读

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/auth/Permission.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmQueryService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/AlarmEventStreamController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/AlarmEventStreamService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/AlarmOutboxPublisher.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/alarm/VehicleAlarmApiTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/alarm/AlarmEventStreamIntegrationTest.java`

**Interfaces:**

```http
GET  /api/vehicle-alarms?level=&status=&vehicleId=&module=&hasAttachment=
GET  /api/vehicle-alarms/{publicId}
POST /api/vehicle-alarms/{publicId}/actions
GET  /api/vehicle-alarms/events
```

- [ ] **Step 1: 写权限矩阵 RED 测试**

新增 `VEHICLE_ALARM_READ/HANDLE/ATTACHMENT_REQUEST/ATTACHMENT_READ`；验证系统管理员、调度员、运营员、审计员矩阵，无权限用户不能列表、详情、处置或订阅。

- [ ] **Step 2: 运行 RED 并实现 REST 查询/处置**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=VehicleAlarmApiTest test`

Expected first run: FAIL；实现后 PASS。响应使用 `publicId`，隐藏内部 UUID、完整终端标识和报文摘要。

- [ ] **Step 3: 写 SSE RED 测试**

覆盖 Bearer 鉴权、15 秒心跳、事件摘要脱敏、`Last-Event-ID` 补读、超出 7 天窗口的重拉提示、发布器重启、重复发布不重复显示和无权限断开。

- [ ] **Step 4: 实现 Outbox 认领和流服务**

使用 `FOR UPDATE SKIP LOCKED` 小批认领；事件 ID 使用 Outbox 单调序列/UUID + 创建时间游标，不使用数组下标；清理任务只删除 7 天前已发布记录。

- [ ] **Step 5: 运行 GREEN 和 P95 组件测试**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=VehicleAlarmApiTest,AlarmEventStreamIntegrationTest test`

Expected: PASS；本地集成测试记录 API 入库到 emitter 的 P95，阈值 2 秒。

- [ ] **Step 6: 提交报警 API 与 SSE**

```powershell
git add apps/api
git commit -m "feat: stream authorized vehicle alarms"
```

---

### Task 13: 在调度工作台增加报警看板、地图联动和降级轮询

**Files:**
- Modify: `apps/admin-web/src/auth/permissions.ts`
- Create: `apps/admin-web/src/api/vehicleAlarms.ts`
- Create: `apps/admin-web/src/api/alarmEvents.ts`
- Create: `apps/admin-web/src/api/alarm-events.test.ts`
- Create: `apps/admin-web/src/components/AlarmBoard.vue`
- Create: `apps/admin-web/src/components/AlarmDetailPanel.vue`
- Create: `apps/admin-web/src/components/AlarmActionDialog.vue`
- Create: `apps/admin-web/src/components/alarm-board.test.ts`
- Modify: `apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- Modify: `apps/admin-web/src/pages/dispatch-workbench.test.ts`
- Modify: `apps/admin-web/src/components/DispatchMap.vue`
- Modify: `apps/admin-web/src/components/dispatch-map.test.ts`
- Create: `apps/admin-web/e2e/vehicle-alarm-flow.spec.ts`

- [ ] **Step 1: 写流客户端 RED 测试**

断言 `fetch` 带 Bearer Token 和 `Last-Event-ID`，正确解析多行 SSE，支持 `AbortController`，401 走一次刷新，断线指数退避，超过阈值启动 5 秒轮询；URL 不含 token。

- [ ] **Step 2: 运行 RED 并实现 SSE 客户端**

Run: `npm.cmd test -- src/api/alarm-events.test.ts`

Expected first run: FAIL；实现后 PASS。

- [ ] **Step 3: 写报警看板 RED 组件测试**

覆盖未确认数、最高等级、最近时间、等级/状态/车辆/ADAS-DMS/附件筛选、新报警置顶、位置可疑、附件暂不可用、脱敏和全部二次确认操作。

- [ ] **Step 4: 实现看板与详情组件**

报警区域使用可折叠侧抽屉/底部面板，不覆盖地图主视野；无处理权限时按钮不存在；完整终端标识、原始报文、内部 UUID 和永久 URL 永不渲染。

- [ ] **Step 5: 写地图联动 RED 测试**

点击报警后选择对应车辆、递增 `vehicleFocusRequest`、打开车辆浮层；有未处理高等级报警的车辆 marker 显示非闪烁徽标；报警无可信位置时只选车辆，不强行飞到 `(0,0)`。

- [ ] **Step 6: 实现页面状态和地图徽标**

将现有 `LOCATION_POLL_INTERVAL_MS` 从 15,000 调整为规格要求的 10,000；位置不改为推送。SSE 正常时显示“实时”，降级时显示“实时推送已降级”且 5 秒轮询报警；恢复 SSE 后停止报警轮询。

- [ ] **Step 7: 运行 GREEN、类型检查和 E2E**

Run: `npm.cmd test -- src/api/alarm-events.test.ts src/components/alarm-board.test.ts src/pages/dispatch-workbench.test.ts src/components/dispatch-map.test.ts`

Run: `npm.cmd run typecheck`

Run: `npm.cmd run e2e -- vehicle-alarm-flow.spec.ts --workers=1`

Expected: PASS。

- [ ] **Step 8: 提交报警管理端**

```powershell
git add apps/admin-web
git commit -m "feat: add dispatch active safety alarm board"
```

---

### Task 14: 实现主动安全附件和 JT/T 1078 文件控制

**Files:**
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/jt1078/Jt1078ControlModule.java`
- Create: `libs/jt-protocol/src/main/java/com/idavy/drtops/jt/protocol/jt1078/AlarmAttachmentMessageCodec.java`
- Create: `libs/jt-protocol/src/test/java/com/idavy/drtops/jt/protocol/jt1078/AlarmAttachmentMessageCodecTest.java`
- Create: `libs/jt-protocol/src/test/resources/protocol-fixtures/attachment-control-fixtures.json`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/attachment/GatewayControlController.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/attachment/AttachmentCommandService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/media/AlarmAttachmentMediaPort.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/media/UnavailableAlarmAttachmentMediaAdapter.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/AlarmAttachmentService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/alarm/MediaCallbackController.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/alarm/AlarmAttachmentServiceTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/alarm/MediaCallbackSecurityTest.java`

- [ ] **Step 1: 验证附件样本和媒体门禁**

核对 `0x9208/0x1210/0x1211/0x1212/0x9212` 与 `0x9206/0x9207/0x1206` 固定样本来源；确认外部媒体服务一次性目标、验签、摘要、大小和短时查看 URL 合同。门禁缺失时保留不可用适配器，不宣称全链路完成。

- [ ] **Step 2: 写消息语义 RED 测试**

断言 `0x1206` 只产生“文件上传完成”元数据，不创建报警；附件消息与报警标识、通道、媒体类型、流水号正确关联；二进制内容不进入 DTO。

- [ ] **Step 3: 运行 RED 并实现协议 codec**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol -Dtest=AlarmAttachmentMessageCodecTest test`

Expected first run: FAIL；实现后 PASS。

- [ ] **Step 4: 写附件状态机和安全 RED 测试**

覆盖终端离线/无能力、媒体不可用、活动传输冲突、重复请求/通知/回调、超时、摘要不一致、大小/类型白名单、文件名清洗、回调验签和防重放、短时 URL 到期及访问审计。

- [ ] **Step 5: 实现媒体端口、控制 API 和状态机**

状态只允许 `WAITING_MEDIA_SERVICE → REQUESTED → UPLOADING → AVAILABLE|FAILED|EXPIRED` 的受控转换；一次性目标只存在于调用内存和下行编码，不落报警表、不写日志。

- [ ] **Step 6: 写故障隔离 RED 测试**

媒体服务 5xx/超时期间位置和报警入口仍成功；附件进入 `WAITING_MEDIA_SERVICE`；恢复后人工重试产生一个新传输，不重复附件。

- [ ] **Step 7: 运行 GREEN 与跨模块回归**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol,apps/jt-gateway,apps/api -am test`

Expected: PASS。

- [ ] **Step 8: 提交附件控制**

```powershell
git add libs/jt-protocol apps/jt-gateway apps/api
git commit -m "feat: control active safety attachments"
```

---

### Task 15: 增加模拟器、端到端协议验证和故障恢复测试

**Files:**
- Modify: `pom.xml`
- Create: `tools/jt-terminal-simulator/pom.xml`
- Create: `tools/jt-terminal-simulator/src/main/java/com/idavy/drtops/jtsimulator/JtTerminalSimulatorApplication.java`
- Create: `tools/jt-terminal-simulator/src/main/java/com/idavy/drtops/jtsimulator/SimulatedTerminal.java`
- Create: `tools/jt-terminal-simulator/src/main/java/com/idavy/drtops/jtsimulator/ScenarioRunner.java`
- Create: `tools/jt-terminal-simulator/src/test/java/com/idavy/drtops/jtsimulator/ScenarioRunnerTest.java`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/e2e/GatewayOperationsFlowIntegrationTest.java`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/e2e/GatewayFailureRecoveryIntegrationTest.java`

- [ ] **Step 1: 写模拟场景 RED 测试**

场景 DSL 固定支持注册、鉴权、心跳、位置、苏标报警、附件状态、断网、半包、粘包、重复登录、错误校验和速率突发；输出只能含模拟终端别名。

- [ ] **Step 2: 运行 RED 并实现最小模拟器**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl tools/jt-terminal-simulator -am test`

Expected first run: FAIL；实现后 PASS。

- [ ] **Step 3: 写全链路 RED 测试**

使用随机 loopback 端口、H2 临时文件和 Testcontainers PostGIS 验证：白名单注册→鉴权→位置→质量→报警→SSE Outbox→处置；全程不直接向业务表插入结果。

- [ ] **Step 4: 实现缺失接线并运行 GREEN**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway -Dtest=GatewayOperationsFlowIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 写故障恢复 RED 测试**

覆盖 API 中断 10 分钟后补投、网关重启、数据库短时不可用、半包断连、重复会话、缓冲高水位、缓冲写失败、SSE 发布器重启、媒体中断和人工位置降级。

- [ ] **Step 6: 运行故障 GREEN**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway -Dtest=GatewayFailureRecoveryIntegrationTest test`

Expected: PASS；无丢失、无重复业务事件、无伪成功。

- [ ] **Step 7: 提交模拟器和恢复测试**

```powershell
git add pom.xml tools/jt-terminal-simulator apps/jt-gateway
git commit -m "test: add JT gateway simulator scenarios"
```

---

### Task 16: 增加可观测性、容器部署和运维安全基线

**Files:**
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/observability/JtGatewayMetrics.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/observability/GatewayHealthContributor.java`
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/observability/SensitiveDataSanitizer.java`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/observability/SensitiveDataSanitizerTest.java`
- Create: `apps/jt-gateway/Dockerfile`
- Modify: `infra/docker-compose.pilot.yml`
- Create: `docs/pilot/jt-gateway-operations.md`
- Create: `.env.example`

- [ ] **Step 1: 写指标、健康和脱敏 RED 测试**

断言连接、在线、版本/消息、鉴权失败、校验失败、分包超时、解码耗时、队列、补投、投递延迟、质量、报警、附件、SSE 指标存在；健康区分进程、TCP、缓冲、API、媒体；敏感字段扫描为零。

- [ ] **Step 2: 运行 RED 并实现观测层**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway -Dtest=SensitiveDataSanitizerTest test`

Expected first run: FAIL；实现后 PASS。媒体不健康只降低附件能力，不将位置接入标为 DOWN。

- [ ] **Step 3: 写 Compose 配置 RED 检查**

用配置解析检查：仅 `7611` 映射给终端网络；`7612` 不公开宿主端口；H2 目录挂持久卷；服务 token 无默认生产值；API 不获得网关 H2 文件访问；媒体端口未声明。

- [ ] **Step 4: 实现 Dockerfile 和 pilot 编排**

增加网关健康依赖、资源限制、只读根文件系统（数据卷除外）和非 root 用户。所有凭证只用环境变量/密钥文件引用。

- [ ] **Step 5: 编写脱敏运维手册**

覆盖预置、首次接入、换机、停用、轮换、端口、离线、坐标、报警、附件、SSE、积压、人工降级、数据核对和恢复步骤；不得包含真实凭证或完整身份。

- [ ] **Step 6: 运行配置、镜像和安全验证**

Run: `docker compose -f infra/docker-compose.pilot.yml config`

Run: `docker compose -f infra/docker-compose.pilot.yml build jt-gateway`

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/jt-gateway test`

Expected: PASS；镜像以非 root 运行，健康细分符合预期。

- [ ] **Step 7: 执行依赖许可与漏洞门禁**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl libs/jt-protocol,apps/jt-gateway dependency:tree`

再使用仓库批准的依赖扫描器生成报告。严重漏洞必须为 0；高危项必须修复或形成经人工批准的书面接受，许可证不兼容则替换依赖。

- [ ] **Step 8: 提交部署和运维基线**

```powershell
git add apps/jt-gateway infra/docker-compose.pilot.yml docs/pilot/jt-gateway-operations.md .env.example
git commit -m "ops: deploy and observe JT gateway"
```

---

### Task 17: 执行性能、容量、敏感信息和两层验收

**Files:**
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/performance/JtGatewayPerformanceTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/alarm/VehicleAlarmCapacityIntegrationTest.java`
- Create: `docs/pilot/evidence/p6-2/protocol-compatibility-matrix.md`
- Create: `docs/pilot/evidence/p6-2/technical-acceptance-report.md`
- Create: `docs/pilot/evidence/p6-2/real-terminal-acceptance-log.csv`
- Create: `docs/pilot/evidence/p6-2/security-sensitive-data-scan.md`
- Modify: `progress.md`

- [ ] **Step 1: 写性能测试的自校验 RED 断言**

测试必须先验证发送数、接收数、成功数、重复数、延迟采样数、报警混入数和退出码，避免“测试没产生负载却通过”。未达到目标条数时测试失败。

- [ ] **Step 2: 执行 100 连接持续测试**

100 个连接持续 1 小时，每终端 10 秒上报，总位置不少于 36,000；另跑 100 条/秒 5 分钟并混入每秒 5 条报警/附件状态。

Expected: 有效成功率 ≥99.9%；缓冲后应答 P95 ≤500 ms、P99 ≤2 s；网关到 API P95 ≤2 s、P99 ≤5 s；无 EventLoop 阻塞、死锁、不明丢失或持续内存增长；突发后 10 分钟内恢复稳定。

- [ ] **Step 3: 执行隔离数据库容量测试**

写入至少 200 万位置、10 万报警、20 万动作/附件元数据，验证快照 P95 ≤500 ms、单车单日历史/质量筛选 P95 ≤2 s、报警列表和详情 P95 ≤1 s，并使用执行计划确认调度候选不扫描历史大表。

- [ ] **Step 4: 执行全量自动化**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q test`

Run: `npm.cmd test`

Run: `npm.cmd run typecheck`

Run: `npm.cmd run build`

Run: `npm.cmd run e2e -- --workers=1`

Expected: 全部 PASS。若既有测试夹具因时间或环境失效，单独修复且不放宽 P6-2 断言。

- [ ] **Step 5: 执行敏感信息扫描**

扫描 Git diff、测试输出、日志、指标样本、SSE、REST 响应和证据目录，确认鉴权码、完整手机号、上传凭证、永久媒体地址和完整原始报文为 0 命中；允许的模拟标识必须在报告列出白名单规则。

- [ ] **Step 6: 形成第一层技术验收报告**

报告逐条引用协议、准入、位置质量、报警、附件、SSE、性能、容量、恢复、依赖和扫描证据。门禁全部通过后只标记“技术接入完成”，不得标记 P6-2 完成。

- [ ] **Step 7: 执行 4 台真实终端验收**

每台完成白名单、注册、鉴权、绑定、10/60 秒频率、连续在线 2 小时、真实测试路线和安全回放报警；全批覆盖 ADAS/DMS；至少一条附件全链路；统一演练断电/断网恢复、换绑、SSE 降级和人工位置降级。

- [ ] **Step 8: 复核真机证据和收口**

CSV 只存脱敏终端别名、车辆公开标识、测试时间窗、指标和证据相对路径，不存乘客信息、完整终端号或凭证。人工审阅通过后更新 `progress.md` 为“P6-2 已完成”；任何真机门禁未过则保持“第一层技术接入完成，第二层待验收”。

- [ ] **Step 9: 最终一致性验证和提交**

Run: `git diff --check`

Run: `git status --short`

Run: `rg -n "仅作参考" docs progress.md`

Expected: 无空白错误；仅目标文件有改动；ETA 口径仍为“仅作参考”。

```powershell
git add docs/pilot/evidence/p6-2 progress.md
git commit -m "docs: close P6-2 terminal acceptance"
```

---

## 18. 复核检查点与提交策略

### 18.1 必须人工复核的检查点

1. **Task 2 后：** 2019/2013 固定报文来源和编解码等价性。
2. **Task 6 后：** 白名单字段、服务凭证双向隔离、换机和强制断开语义。
3. **Task 9 后：** 坐标基准点与四级质量阈值复算，确认不污染调度快照。
4. **Task 10 后：** 厂商苏标字段映射，尤其报警类型、标识、开始/结束和一帧多报警。
5. **Task 12 后：** RBAC、SSE 脱敏、补读及 Outbox 一致性。
6. **Task 14 后：** 外部媒体边界、`0x1206` 语义和附件凭证不落盘。
7. **Task 17 后：** 两层验收报告和最终 P6-2 收口决定。

### 18.2 每个提交的共同验证

```powershell
git diff --check
git status --short
```

只暂存任务列出的文件。发现共享工作树存在无关改动时立即停止，不使用 `git reset --hard`、`git checkout --` 或清理命令覆盖用户工作。

### 18.3 计划完成定义

- 每个任务都有先失败后通过的测试证据；
- 17 个任务按顺序完成，或在明确门禁处暂停并记录原因；
- 自动化、性能、容量、安全和恢复证据可复算；
- 4 台真机验收及人工审阅通过；
- 没有密钥、个人信息、完整终端身份或永久媒体地址进入仓库；
- ETA 仍只作参考，1078 媒体服务仍为外部依赖；
- `progress.md` 只在第二层验收通过后标记 P6-2 完成。
