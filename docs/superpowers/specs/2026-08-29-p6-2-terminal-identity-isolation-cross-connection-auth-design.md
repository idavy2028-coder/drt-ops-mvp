# P6-2 生产/测试终端身份隔离与跨连接鉴权设计

日期：2026-08-29（Asia/Shanghai）

状态：设计已确认，正式规格待人工复核

关联基线：

- `docs/superpowers/specs/2026-08-12-p6-2-jt808-active-safety-gateway-design.md`
- `docs/pilot/evidence/p6-2/cloud-terminal-01-private-capture-acceptance-2026-08-27.md`
- `docs/pilot/evidence/p6-2/cloud-api-preview-registration-consistency-2026-08-28.md`
- `progress.md` 中“P6-2 short-auth 真实 terminal-01 窗口与跨连接鉴权阻断”记录

## 1. 背景与问题定义

### 1.1 已确认事实

现场存在两台曾使用相同终端号和车辆标识的物理设备：

- 一台为实际运营设备，全天运营期间保持上电；
- 一台为测试设备，终端手机号不同，并支持修改终端号和车辆标识。

本规格只在公开文档中使用 `production-terminal-01` 和 `test-terminal-01` 两个安全别名，不记录真实手机号、终端号、车辆标识、鉴权码或其摘要。

最近一次真实窗口已经证明：

1. 型号级 22 字符鉴权码兼容生效，设备不再因鉴权码长度而无法构造 `0x0102`；
2. 设备在 TCP 连接 A 完成 `0x0100 → 0x8100` 后，改用 TCP 连接 B 发送 `0x0102`；
3. 注册成功连接与鉴权连接来自同一公网出口，而被维护策略阻断的运营设备来自另一公网出口；
4. 当前网关只在连接 A 的内存会话中保存 `terminalId`、`vehicleId` 和令牌版本，因此连接 B 被以 `REGISTRATION_REQUIRED` 拒绝；
5. 来源 IP 只能作为私密诊断证据，不能作为终端身份或授权依据。

### 1.2 根因拆分

当前阻断由两个独立问题组成：

1. **物理设备身份冲突**：测试设备曾复用运营设备的终端号和车辆标识，可能污染车辆绑定、审计归属和真实流量判断。
2. **鉴权上下文绑定 TCP 连接**：`0x0102` 到达新连接时，网关没有足够上下文调用现有按 `terminalId + tokenVersion` 的鉴权接口。

两项必须同时关闭。只修复跨连接鉴权而继续允许两台设备共用逻辑身份，会把测试数据写入运营车辆；只拆分身份而不修复跨连接鉴权，真实设备仍无法完成注册后的鉴权。

## 2. 目标、成功标准与非目标

### 2.1 目标

1. 生产设备和测试设备使用互不重叠的终端手机号语义身份、终端号、车辆标识、终端记录和车辆绑定。
2. 生产设备保持正常运营通路，不因测试维护窗口开始、到期或失败而被整体阻断。
3. 测试设备默认隔离，仅在有时限的维护窗口内作为唯一详细验证目标。
4. 支持“连接 A 注册、连接 B 鉴权”，并在鉴权成功后安全重建完整会话上下文。
5. 任何身份交叉、未知设备、错误令牌、非启用终端或失效绑定均 fail closed，且不得自动创建、改绑或覆盖对象。
6. 全流程测试先行、可审计、可停止、可续跑；公开输出只含安全别名、计数、状态和非敏感版本号。

### 2.2 成功标准

- 生产 4 台终端保持各自唯一身份和唯一活动车辆绑定。
- 新增 1 台独立测试终端和 1 辆不可调度测试车辆；测试终端窗口外为 `SUSPENDED`。
- 数据库现有唯一约束全部保留并继续生效。
- 生产设备在测试窗口内仍可正常注册、鉴权和上报，不进入测试设备的详细捕获路径。
- 测试设备只有在精确命中私密指纹和有效 TTL 时才进入详细注册验证。
- 测试设备可完成跨 TCP 连接 `0x0100 → 0x8100 → 0x0102 → 0x8001`，API 返回 `APPROVED`，`last_authenticated_at` 只在成功时更新。
- 测试设备的位置和报警只能归属测试车辆；运营设备的位置和报警只能归属运营车辆。
- 完整四模块回归不得低于当前 701 项基线，失败和错误均为 0；条件跳过必须逐项说明。

### 2.3 非目标

- 不以来源 IP、TCP 端口、设备上电顺序或先到先得方式识别终端。
- 不放宽 `terminal_phone_identity`、`terminal_code`、`plate_number` 或活动绑定唯一约束。
- 不允许测试设备继续复用运营终端号或运营车辆标识。
- 不直接修改数据库，不删除重建现有生产终端，不覆盖历史绑定。
- 不在本轮启用 `0x9208`、`0x1210`、`0x1206` 附件链路。
- 不新增媒体服务、云端公网管理端口、多实例分布式会话或按 IP 的长期白名单。

## 3. 方案比较与裁决

| 方案 | 优点 | 风险 | 裁决 |
| --- | --- | --- | --- |
| A. 生产/测试设备彻底拆分逻辑身份和车辆绑定，跨连接鉴权按协议身份查找 | 身份边界清晰；保留唯一约束；审计和数据归属可证明；不依赖网络拓扑 | 需要受控数据恢复、测试设备改号以及 API/gateway 合同扩展 | **采用** |
| B. 两台设备继续共用终端号和车辆标识，仅按手机号或 IP 分流 | 改动少 | 同一车辆可能收到两套位置/报警；IP/NAT 不稳定；无法形成唯一审计归属 | 不采用 |
| C. 放宽唯一约束，允许多终端同时绑定一辆车 | 短期接入容易 | 破坏 P6-2 强绑定合同，引入不可判定的数据覆盖和安全风险 | 禁止 |

## 4. 身份与数据模型

### 4.1 身份层级

系统区分以下三类身份：

1. **协议头身份**：`protocolVersion + terminalPhone`，来自 JT/T 808 消息头，用于维护路由和跨连接鉴权查找。
2. **注册完整身份**：协议头身份加 `terminalCode + manufacturerId + model + vehicleIdentifier`，来自 `0x0100`，用于首次注册强校验。
3. **平台会话身份**：`terminalId + vehicleId + tokenVersion + capabilityProfile`，只能由受信 API 在校验成功后返回。

协议头身份只能定位候选终端，不能单独完成注册。注册完整身份必须全部指向同一终端记录及其唯一活动车辆绑定。

### 4.2 规范化与精确比较

- `terminalPhone` 继续复用 V18 `TerminalPhoneIdentity` 的 JT808-2013/2019 固定宽度规范化规则。
- `protocolVersion` 先规范为 `JT808_2013` 或 `JT808_2019`，再参与手机号语义身份计算。
- `terminalCode`、`manufacturerId`、`model` 和 `vehicleIdentifier` 继续按协议解码后的 UTF-8/ASCII/GBK 业务值精确比较；不做模糊匹配。
- 规范化只消除协议固定宽度表示差异，不得把两个不同业务值折叠为同一身份。

### 4.3 唯一约束

以下约束不得删除、降级或绕过：

- `jt_terminals.terminal_phone_identity` 全局唯一；
- `jt_terminals.terminal_code` 全局唯一；
- `vehicles.plate_number` 全局唯一；
- 每台终端至多一个活动绑定；
- 每辆车至多绑定一台活动终端。

测试设备改号后，三个身份字段必须同时满足：

- 与 4 台生产设备不重复；
- 与数据库全部现存终端和车辆不重复；
- 与私密清单中所有计划对象不重复。

任一门禁失败均停止，不允许自动生成替代业务值。

### 4.4 平台对象归属

| 对象 | 生产设备 | 测试设备 |
| --- | --- | --- |
| 私密别名 | `production-terminal-01` | `test-terminal-01` |
| 终端记录 | 保留现有生产终端 UUID | 新建独立终端 UUID |
| 车辆记录 | 保留现有运营车辆 UUID | 新建独立测试车辆 UUID |
| 活动绑定 | 生产终端 ↔ 运营车辆 | 测试终端 ↔ 测试车辆 |
| 车辆状态 | 保持原运营状态合同 | `IDLE` |
| `dispatchable` | 保持现有运营配置 | `false` |
| 窗口外终端状态 | 正常业务状态 | `SUSPENDED` |

## 5. 私密资料隔离

### 5.1 目录

- `.private/terminals/`：只保存 4 台生产设备资料。
- `.private/test-terminals/`：只保存测试设备资料。

两个目录均不进入 Git。测试设备不得继续借用 `terminal-01` 生产别名；私有脚本内部使用 `test-terminal-01`，公开输出只显示该安全别名。

### 5.2 文件结构

生产 CSV 继续使用当前 `real-terminal-intake.csv` 表头，以 `private_terminal_file_ref` 引用私密 JSON。测试目录使用同结构的独立 intake CSV。真实身份值只保存在引用的 `.secret.json` 中，键包括：

- `terminal_id`
- `terminal_phone`
- `vehicle_identifier`
- 首配坐标及坐标系

CSV 中的 `terminal_alias`、`bound_plate_alias` 和设备能力字段可用于脱敏计划；任何控制台、JUnit 报告、公开 Markdown、Compose 基线或镜像元数据均不得输出私密 JSON 值。

### 5.3 私密预检

私有预检一次性读取两个目录，构建内存中的规范身份集合并检查：

1. UTF-8 严格解码，无 BOM/代码页歧义；
2. 引用文件存在且 schema 完整；
3. 每个安全别名唯一；
4. 规范手机号、终端号和车辆标识跨目录唯一；
5. 生产 4 台和测试 1 台数量精确；
6. 附件相关字段为空或禁用；
7. 输出只含别名、字段名、`MATCH/DIFF`、计数和文件 SHA-256。

原始值和原始值摘要均不进入公开输出。私密 manifest 保存于 `.private`，权限继承现有私密目录策略。

## 6. 维护路由设计

### 6.1 两阶段路由

维护路由只在 `0x0100`/`0x0102` 前置阶段使用协议头身份，不解析或信任来源 IP。

第一阶段根据私密配置中的规范身份摘要分类：

| 分类 | 条件 | 行为 |
| --- | --- | --- |
| `TARGET_TEST` | 精确命中测试设备摘要，且维护 TTL 有效 | 进入测试设备详细注册/鉴权验证和私密诊断 |
| `PRODUCTION_PASSTHROUGH` | 精确命中任一生产设备摘要 | 始终进入标准生产注册/鉴权路径，不进入测试捕获 |
| `BLOCKED` | 测试摘要但 TTL 未开始或已到期；未知摘要；配置冲突 | 快速拒绝，限频审计，不调用会改变业务状态的 API |

第二阶段由注册 API 校验完整身份和车辆绑定。即使协议头命中 `TARGET_TEST`，只要终端号或车辆标识仍指向生产对象，也必须以 `DEVICE_IDENTITY_COLLISION` 拒绝。

### 6.2 TTL 语义

- TTL 只控制测试设备的 `TARGET_TEST` 权限。
- TTL 到期后，测试设备转为 `BLOCKED`，原因 `MAINTENANCE_WINDOW_EXPIRED`。
- 生产设备不受测试 TTL 到期影响，仍为 `PRODUCTION_PASSTHROUGH`。
- 配置缺失、格式错误、摘要重复或测试摘要与生产摘要相同，gateway 启动失败，`7611` 不监听。

### 6.3 私密配置

生产/测试身份摘要、TTL 和诊断路径只存在于独立私密 env/override 中。基础 `.env.example`、Compose、镜像、健康详情和公开运维文档只声明空占位键，不包含实际值。

健康接口只允许暴露：策略是否加载、TTL 是否有效、三类计数、配置条目数量和安全别名；不得暴露摘要或原始身份。

### 6.4 生产设备保护

- 生产设备的注册、鉴权、位置和报警不进入测试 fail-fast 计数。
- 测试设备失败可以停止测试观察器，但不得主动断开已鉴权生产会话。
- 测试窗口关闭时，只移除测试 TTL/捕获配置；生产路径保持标准行为。
- 如果无法证明分类唯一，gateway 必须保持停止态，而不是让两台冲突设备竞争同一平台身份。

## 7. 注册碰撞处理

### 7.1 一致映射规则

注册验证必须分别查询：

- 规范手机号对应的终端；
- `terminalCode` 对应的终端；
- `vehicleIdentifier` 对应的车辆；
- 候选终端的活动绑定。

只有四项最终收敛到同一终端和同一车辆，才继续比较制造商、型号、协议和能力。

出现以下任一情况即为碰撞：

- 手机号和终端号分别命中不同终端；
- 终端号命中生产终端，但手机号属于测试终端；
- 车辆标识命中与候选终端活动绑定不同的车辆；
- 同一注册帧混合了生产和测试资料；
- 私密计划和云端现状形成多候选映射。

### 7.2 处理结果

- API 业务响应：HTTP 200、`approved=false`、`reasonCode=DEVICE_IDENTITY_COLLISION`。
- gateway 对终端：返回现有 JT808 通用注册失败结果，不暴露冲突字段或平台对象。
- 审计：`eventType=PROTOCOL_REJECTED`、`result=REJECTED`、`reasonCode=DEVICE_IDENTITY_COLLISION`。
- 状态：不创建、不纠正、不改绑、不轮换令牌、不更新时间戳。
- 连接：发送失败应答后关闭。

审计和日志只能记录安全别名、协议版本、消息 ID、gateway 实例和时间；不得记录冲突原值。

## 8. 跨连接鉴权 API 合同

### 8.1 新接口

新增内部服务凭证保护的接口：

`POST /internal/jt-gateway/authentications/verify-by-identity`

请求体：

```json
{
  "protocolVersion": "JT808_2019",
  "terminalPhone": "<JT808 消息头中的终端手机号>",
  "tokenSha256": "<64 位小写十六进制摘要>",
  "gatewayInstance": "<网关实例名>"
}
```

`terminalPhone` 和鉴权码原文都不得写日志。gateway 只发送鉴权码 SHA-256；API 不接收鉴权码原文。

成功响应：

```json
{
  "data": {
    "approved": true,
    "terminalId": "<UUID>",
    "vehicleId": "<UUID>",
    "sourceCoordinateSystem": "GCJ02",
    "activeSafetyStandard": "<可为空>",
    "activeSafetyModules": [],
    "tokenVersion": 1,
    "reasonCode": null
  }
}
```

拒绝响应保持 HTTP 200，`approved=false`，平台上下文字段为空。允许的内部原因码为：

- `IDENTITY_NOT_FOUND`
- `PROTOCOL_VERSION_MISMATCH`
- `TERMINAL_DISABLED`
- `REGISTRATION_REQUIRED`
- `BINDING_INACTIVE`
- `TOKEN_MISMATCH`

终端侧始终只收到 JT808 通用鉴权失败，不回显内部原因。

### 8.2 API 校验顺序

同一事务内按以下顺序执行：

1. 校验服务凭证版本和 `gatewayInstance`；
2. 规范协议版本和消息头手机号；
3. 以 `terminal_phone_identity` 唯一查询并锁定候选终端；
4. 精确校验候选终端协议版本；
5. 要求状态为 `ACTIVE` 且 `last_registered_at` 非空；
6. 要求恰好存在一个活动车辆绑定；
7. 校验存储摘要格式和当前令牌版本；
8. 使用常量时间比较存储摘要与 `tokenSha256`；
9. 成功时更新 `last_authenticated_at`，返回会话上下文；
10. 任一失败不更新时间戳、不推进版本、不返回平台 UUID。

同时到达的合法鉴权可以串行更新最近鉴权时间，但每次都必须重新读取当前令牌摘要。时间戳只在成功事务提交时推进。认证轮换一旦提交，旧摘要立即失效。

注册完成不会绕过现有启用门禁。终端在 `registrations/{terminalId}/complete` 后仍需由受控管理流程从 `PENDING/SUSPENDED` 激活为 `ACTIVE`，按身份鉴权接口才可批准。验收观察器必须在看到目标注册完成后执行带 `expectedVersion` 的单目标激活；激活前到达的 `0x0102` 可被通用失败应答拒绝，设备后续重试才进入成功断言。观察器不得激活非目标测试终端，也不得以跳过 `ACTIVE` 校验来消除时序竞争。

### 8.3 保留现有接口

现有 `POST /internal/jt-gateway/authentications/verify` 继续服务同一 TCP 连接内已知 `terminalId + tokenVersion` 的鉴权，以降低回归范围。新接口仅在当前连接尚未解析出 `terminalId` 时使用。

两条接口必须共享同一核心鉴权服务，避免状态、绑定、摘要和时间戳规则漂移。

### 8.4 HTTP 与重试语义

- 业务拒绝：HTTP 200，gateway 返回失败并按现有连续失败规则处理。
- 请求格式错误：HTTP 400，gateway fail closed。
- 服务凭证错误：HTTP 401/403，gateway fail closed，并标记运营 API 不可用。
- API 超时或 5xx：不重试当前鉴权帧、不更新时间戳，记录安全原因码后关闭连接。

## 9. Gateway 会话重建

### 9.1 同连接路径

若 `session.terminalId` 已存在，继续使用现有按 ID 鉴权路径。成功后调用 `session.authenticated(at)`，抢占同终端旧会话并记录 `DUPLICATE_LOGIN`。

### 9.2 跨连接路径

若新连接收到 `0x0102` 且 `session.terminalId` 为空：

1. 从帧头读取协议版本和终端手机号；
2. 解析令牌字节并立即计算 SHA-256；
3. 在 `finally` 中清零令牌字节；
4. 调用 `verify-by-identity`；
5. 成功后以返回的 `terminalId`、`vehicleId`、坐标系、能力和令牌版本初始化会话身份；
6. 校验会话保存的消息头身份摘要与当前帧一致；
7. 将会话转为 `AUTHENTICATED`，发送成功 `0x8001`；
8. 通过 `TerminalSessionRegistry.claim` 接管同终端旧连接；
9. 后续位置、报警和心跳只使用平台返回的 `vehicleId`。

`TerminalSession` 应新增语义明确的“由鉴权恢复身份”入口，不复用名称为 `registrationAccepted` 的方法伪造注册事件。该入口和注册入口共享同一组不变量：终端、车辆、坐标系、能力、令牌版本均非空且来源于受信 API。

### 9.3 失败处理

- 未知身份、错误令牌、非启用状态或绑定失效：返回鉴权失败，累计失败次数。
- 连续三次失败：记录锁定审计并关闭连接。
- API 调用异常：释放当前帧、清零令牌、移除会话注册表条目并关闭连接。
- 所有异常路径必须通过 Mock/Spy 验证帧释放、会话移除和 channel 关闭，禁止连接泄漏。

## 10. 受控数据恢复与测试终端建档

### 10.1 前置门禁

执行前必须同时满足：

- gateway 为 stopped/created，`7611` 无监听；
- API 健康且当前版本已通过完整回归；
- 云端数据库备份和校验值已生成；
- 当前生产终端、车辆、绑定和审计快照已保存到私密目录；
- 测试设备已在硬件侧改为唯一终端号和唯一测试车辆标识；
- 两套私密资料跨目录唯一性预检通过；
- dry-run 只显示安全别名和差异字段名。

### 10.2 恢复生产对象

现有生产终端 UUID 和运营车辆 UUID 必须保留，不删除重建：

1. 受控轮换认证，使终端进入无有效令牌的 `SUSPENDED`/未注册状态；
2. 扩展现有身份纠正门禁，使“已轮换、`SUSPENDED`、`last_registered_at` 为空、令牌摘要为平台未注册标记、仍有唯一活动绑定”的终端可执行受控纠正；
3. 从生产私密清单恢复运营设备手机号、终端号、制造商、型号、协议和车辆标识；
4. 使用 `expectedVersion`、字段级 preview 和全局唯一性检查；
5. 只修改 dry-run 明确列出的字段；
6. 写入 `JT_TERMINAL_IDENTITY_CORRECTED`，车辆标识变化时同时写入 `VEHICLE_IDENTIFIER_CORRECTED`；
7. 由运营设备重新注册、激活和鉴权，不人工写入令牌或时间戳。

历史 `last_authenticated_at` 只作为历史事实保留；是否当前可鉴权由状态、`last_registered_at`、当前令牌摘要和版本共同决定。

### 10.3 创建测试对象

测试对象按现有管理 API 顺序创建：

1. 创建不可调度、`IDLE` 的测试车辆；
2. 预置独立测试终端；
3. 配置已批准的 ADAS/DMS 能力，附件能力保持禁用；
4. 建立测试终端到测试车辆的唯一活动绑定；
5. 将未注册测试终端置为 `SUSPENDED`，记录 `PRE_ACCEPTANCE` 审计；
6. 生成只含 UUID、版本、安全别名、步骤状态和哈希的私密 manifest。

现有 suspend 业务操作需支持将未注册 `PENDING` 终端转为 `SUSPENDED`，该路径不请求断开不存在的会话；ACTIVE 路径保持现有断连语义。

### 10.4 失败与续跑

- 每个业务步骤成功后才更新私密 manifest。
- 任一步失败立即停止，不自动重复写操作。
- 续跑前以 UUID、版本和唯一字段重新核对现状；现状与 manifest 不一致时停止。
- 备份不得被脚本盲目回灌。回滚若会恢复生产/测试碰撞身份，必须禁止。
- 已恢复正确的生产身份不得因后续测试对象创建失败而自动回退。

## 11. 审计、脱敏与安全

### 11.1 审计动作

新增或复用以下安全动作和原因：

- `DEVICE_IDENTITY_COLLISION`
- `CROSS_CONNECTION_AUTHENTICATION_APPROVED`
- `CROSS_CONNECTION_AUTHENTICATION_REJECTED`
- `TEST_DEVICE_WINDOW_CLOSED`
- `MAINTENANCE_WINDOW_EXPIRED`
- `TEMPORARILY_BLOCKED_FOR_MAINTENANCE`
- 现有 `JT_TERMINAL_AUTH_ROTATED`
- 现有 `JT_TERMINAL_IDENTITY_CORRECTED`
- 现有 `VEHICLE_IDENTIFIER_CORRECTED`

协议审计继续使用现有事件类型集合，新增语义放入 `reasonCode`，避免为本轮引入无必要的审计表枚举迁移。

### 11.2 禁止输出

以下内容不得进入普通日志、异常消息、健康接口、JUnit XML、公开报告、Git、镜像层或 Outbox：

- 终端手机号、终端号、车辆标识、VIN；
- 鉴权码原文、服务凭证、JWT、Cookie；
- 身份摘要、令牌摘要；
- 私密文件路径中的真实设备命名；
- API 请求/响应完整正文。

允许公开输出：安全别名、字段名、计数、HTTP 状态、版本号、固定原因码、文件自身 SHA-256 和是否匹配。

### 11.3 内存清理

- 鉴权码字节在摘要计算后立即清零。
- 客户端请求对象不得缓存原始令牌。
- 维护指纹只保存摘要字节并使用常量时间比较。
- channel 异常、API 异常和超时路径必须释放 Netty 帧并关闭/移除会话。

## 12. 测试先行验收矩阵

### 12.1 API RED

1. JT808-2013 固定宽度手机号可按身份查找并鉴权。
2. JT808-2019 固定宽度手机号可按身份查找并鉴权。
3. 协议版本不匹配被拒绝。
4. 未知身份被拒绝且不返回 UUID。
5. 非 `ACTIVE`、未注册或无活动绑定被拒绝。
6. 错误令牌被拒绝，`last_authenticated_at` 不变。
7. 正确令牌获批，返回完整会话上下文，时间戳只推进一次业务成功。
8. 认证轮换后的旧令牌被拒绝。
9. 手机号、终端号和车辆标识命中不同对象时返回 `DEVICE_IDENTITY_COLLISION`。
10. 数据库唯一约束继续拒绝重复手机号语义身份、终端号、车牌和活动绑定。
11. 生产身份纠正只在严格的已轮换 `SUSPENDED` 状态允许。
12. PENDING 测试终端可受控转为 `SUSPENDED`，且不发无意义断连请求。

### 12.2 Gateway RED

1. 连接 A 注册成功并收到 `0x8100`，关闭 A 后连接 B 发送 `0x0102`，修复前得到 `REGISTRATION_REQUIRED`。
2. GREEN 后连接 B 调用按身份鉴权并重建会话，收到成功 `0x8001`。
3. 同连接注册/鉴权路径保持兼容。
4. 跨连接成功后位置和报警使用 API 返回的测试 `vehicleId`。
5. 测试设备不能写入运营车辆。
6. 生产和测试设备并发时，生产为 `PRODUCTION_PASSTHROUGH`，测试为 `TARGET_TEST`。
7. 测试 TTL 到期只阻断测试设备，生产设备继续通行。
8. 未知或冲突设备为 `BLOCKED`，不调用注册完成 API。
9. 错误令牌连续三次关闭连接。
10. API 超时/5xx、帧解析异常和会话初始化异常均释放帧、清除令牌、移除会话并关闭 channel。
11. Mock/Spy 证明异常路径确实执行资源清理，不以“无异常”替代断言。
12. 日志捕获证明无原始身份、令牌或摘要。

### 12.3 私有脚本 RED

1. 生产/测试目录出现重复规范手机号、终端号或车辆标识时 fail closed。
2. UTF-8 无 BOM 文件在 Windows PowerShell 5.1 下仍按严格 UTF-8 读取。
3. dry-run 不写 API，只输出别名和差异字段。
4. 备份校验失败时禁止 Apply。
5. 单步失败时 manifest 只记录已提交步骤。
6. 续跑遇到版本或 UUID 漂移时停止。
7. 附件字段非空时停止。
8. 输出扫描对 5 份生产/测试私密值及其常见编码形式命中为 0。

### 12.4 完整回归

按串行方式执行：

- JT 协议模块；
- `jt-gateway`；
- API；
- 终端模拟器；
- 私有 PowerShell 测试；
- Compose 配置解析；
- `git diff --check`。

总测试数不得低于当前 701 项公开基线。Windows Maven/Surefire 必须将 `TEMP`、`TMP` 和 `java.io.tmpdir` 指向 worktree 本地临时目录，禁止并行 Maven 运行。

## 13. 部署与真实复验顺序

1. 完成 RED/GREEN、独立 GREEN 复核和完整回归。
2. 生成源码指纹、JAR/镜像 SHA-256 和敏感值零命中证据。
3. 先停止态部署 API，再停止态部署 gateway；此时 `7611` 必须无监听。
4. 执行数据库备份、生产对象恢复和测试对象独立建档。
5. 对生产 4 台及测试 1 台分别调用只读 registration-verify，全部得到预期结果。
6. 先验证生产设备路径不受测试策略影响。
7. 刷新测试目标私密摘要和 UTC+15 分钟 TTL。
8. 先启动单目标观察器并确认 `OBSERVER_READY=YES`。
9. 启动 gateway，确认新镜像、健康检查和 `7611` 单监听。
10. 对测试设备执行一次受控网络重连；观察器只在目标注册完成后以当前版本激活该终端。
11. 若激活前的首次 `0x0102` 被拒绝，等待设备在激活后按协议重试；超过观察器规定的激活/重试期限则 fail-fast，不由脚本伪造鉴权帧。
12. 观察跨连接鉴权成功后，再验证位置与报警均归属测试车辆。
13. 窗口结束立即移除测试 TTL/捕获配置，将测试终端恢复 `SUSPENDED`；生产连接不因收口被主动断开。

真实复验成功必须同时满足：

- `REGISTERED/ACCEPTED/APPROVED`；
- `AUTHENTICATED/ACCEPTED/APPROVED`；
- `last_authenticated_at` 更新；
- 会话映射到测试终端和测试车辆；
- 至少一条位置事实归属测试车辆；
- 按现场可安全触发的方法产生的报警事实归属测试车辆；
- 生产设备没有碰撞、错误改绑或测试捕获记录；
- 附件链路仍禁用。

## 14. 回滚与故障边界

### 14.1 代码回滚

- API 或 gateway 新版本异常时，先停止 gateway 并关闭 `7611`，再回滚对应镜像。
- 回滚到不支持跨连接鉴权的 gateway 后，不得继续真实测试窗口。
- API 回滚前必须确认数据库迁移兼容；本设计预计复用 V18，不要求放宽或删除约束。

### 14.2 数据回滚

- 所有数据变更通过受控 API 和 `expectedVersion` 完成。
- 生产身份恢复成功后，不回滚到生产/测试冲突状态。
- 测试对象创建失败时，保持其不可调度且未激活；由后续受控续跑完成，不删除生产对象。
- 无法证明对象归属时，gateway 保持停止，数据交由人工复核。

### 14.3 fail-fast 条件

出现以下任一条件立即停止窗口：

- 生产和测试摘要重复；
- 任一唯一约束冲突；
- `DEVICE_IDENTITY_COLLISION`；
- 测试事件落到生产车辆；
- 生产设备被测试 TTL 阻断；
- API 健康失败、服务凭证异常或 Outbox 死信增加；
- 原始身份或令牌进入非私密输出；
- gateway 无法在停止后关闭 `7611`。

## 15. 交付物

实现阶段必须交付：

1. API 按身份鉴权合同、共享鉴权核心服务和碰撞检测；
2. gateway 三态维护路由、跨连接会话恢复和资源清理；
3. 生产身份受控恢复和测试终端独立建档脚本；
4. 私密资料跨目录一致性检查及脱敏输出测试；
5. RED/GREEN、独立复核、完整回归和离线镜像证据；
6. 云端停止态部署记录、真实窗口记录和最终验收报告；
7. `progress.md` 收口记录，包括例外、残余风险和下一入口。

在本规格获人工复核通过前，不进入实施计划或代码修改阶段。
