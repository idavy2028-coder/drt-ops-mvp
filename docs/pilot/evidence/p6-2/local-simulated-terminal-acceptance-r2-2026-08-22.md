# P6-2 方案 A 本机 Docker 四终端模拟复验报告（R2）

## 结论

**方案 A 修复复验通过，P6-2 基础交付范围固定为注册、鉴权、位置上报和报警。真实终端基础链路验证完成前，P6-2 阶段仍不得正式结束。**

- 混合批污染已关闭：session audit 从普通高优先级 ingress 批次中隔离并逐条投递。新卷中 `REGISTERED + ACCEPTED` 与 `AUTHENTICATED + ACCEPTED` 各 4 条，没有再被附件元数据拒绝连带阻断。
- 鉴权时间戳已关闭：4/4 合成终端的 `last_authenticated_at` 均非空；鉴权失败测试同时证明错误令牌不会更新时间戳。
- 四台纯合成终端均完成注册、管理员激活、鉴权、位置和主动安全报警；每台 9/9 个场景步骤通过，模拟器退出码均为 0。场景中的 `0x1210` 与 `0x1206` 仅作为未启用附件链路的负路径兼容性探测，不属于 P6-2 功能交付。
- 核心业务事实完整：4/4 终端已注册并处于 `ACTIVE`，8 条 GPS 位置覆盖 4/4 终端，4 条前向碰撞报警覆盖 4/4 终端。
- 附件相关普通 ingress 仍被运营 API 拒绝，最终 Outbox 为 12 条死信，readiness 为 HTTP 503。根据 2026-08-22 的范围裁决，这是媒体服务接入前的已知降级状态，不阻塞 P6-2 注册、鉴权、位置和报警基础链路验收；它也不得被解释为附件能力已经可用。
- 本轮只使用合成身份和本机隔离 Docker 项目；未读取四台真实终端值生成场景，未启动真实终端或云端部署。

修复前的失败现场和结论继续保存在 `local-simulated-terminal-acceptance-2026-08-22.md`，旧项目与 `jt-gateway-data-acceptance` 卷没有被删除或改写。

## 测试先行证据

### 混合批污染

RED 用例 `deliversSessionAuditIndependentlyWhenAttachmentMetadataIsRejected` 使用真实 H2 repository、dispatcher 和 `OperationsApiClient`，只替换两个 HTTP 端点。修复前预期 1 条 session audit 成功，实际为 0，测试按预期失败。

GREEN 实现保持普通 ingress 的原子批次语义不变，并完成以下隔离：

- 普通 `HIGH` claim 不再领取 `SESSION_AUDIT`。
- repository 提供只领取 session audit 的独立 claim。
- dispatcher 在普通高优先级 ingress 之前逐条投递 session audit，并按独立 audit API 结果推进状态。
- 附件元数据固定拒绝时，session audit 为 `DELIVERED`，附件项保持自身的失败/重试状态。

### 鉴权时间戳

RED 新增两个 API 用例：成功鉴权应写入 `lastAuthenticatedAt`，错误令牌应保持为空。修复前成功用例按预期失败，错误令牌保护用例通过。

GREEN 在成功摘要校验之后，以注入时钟写入鉴权时间并在事务中持久化；摘要不匹配时在任何写入前返回拒绝。

### 完整回归

最终使用同一串行 Maven 运行完成四模块回归，退出码为 0：

| 模块 | 测试 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| `jt-protocol` | 48 | 0 | 0 | 0 |
| `jt-terminal-simulator` | 11 | 0 | 0 | 0 |
| `jt-gateway` | 106 | 0 | 0 | 6 |
| `api` | 480 | 0 | 0 | 41 |
| **合计** | **645** | **0** | **0** | **47** |

完整回归还覆盖 response-loss 重放、真实 loopback TCP 注册帧、网关运行时、API 合同、鉴权/RBAC、位置、报警和业务事务。最终差异检查 `git diff --check` 退出码为 0；换行符提示属于 Windows 工作树转换提示，不是空白错误。

## 新卷与部署基线

- Docker 项目：`drt-ops-jt-acceptance-r2`。
- 宿主端口：API `28080`、管理端 `25173`、PostgreSQL `25432`、Redis `26379`、算法服务 `28090`、路由模拟器 `28092`、JT 网关 TCP `7611`。
- 三个卷均在 2026-08-22 10:58:17Z 首次创建：`drt-ops-jt-acceptance-r2_postgres-data`、`drt-ops-jt-acceptance-r2_redis-data`、`jt-gateway-data-acceptance-r2`。
- API 镜像：`sha256:3d424d859d086a9233a8f65f7dd5142337b21966c342f99f181f25115b406817`。
- gateway 镜像：`sha256:867abf022ae3ceffa55cdcab591ebf84b332c6621a7d92cc2d765368f252cf71`。

启动后七个服务全部通过 Docker 健康检查，API health 为 `UP`，宿主机 `7611` 可连接。流量前 gateway readiness 为 HTTP 503，原因为尚无已鉴权投递，Outbox 三项计数均为 0；该冷启动状态作为基线记录，不认定为卷污染。

PostgreSQL 迁移和合同门禁如下：

| 门禁 | 结果 |
| --- | --- |
| Flyway 最新版本 | `17:true` |
| `jt_gateway_audit_events.idempotency_key` 非空 | 通过 |
| session audit 幂等唯一约束 | 1 个有效约束 |
| V17 ingress receipt 新增列 | 3/3 存在 |

## 四终端协议结果

四台终端串行执行同一状态机：预录入、能力和唯一车辆绑定后，模拟器发出真实 `0x0100` 注册帧并在同一 TCP 会话静默 15 秒；激活脚本观察 `registrationCompleted` 后调用管理 API 激活，模拟器再继续鉴权和业务上报。

| 合成别名 | 步骤结果 | 模拟器退出码 | 私密日志 SHA-256 |
| --- | ---: | ---: | --- |
| terminal-01 | 9/9 PASS | 0 | `1f88d0a2eee8c5bd7c4b71eac483ea1201312e6cd0209313f0930c316efba520` |
| terminal-02 | 9/9 PASS | 0 | `b56f8bc01f48de5516815c28e9f8f96d2491a2aa501593f07910426b46691f57` |
| terminal-03 | 9/9 PASS | 0 | `d02b7efd6ad26c192ef0076a149a136e970b12bca1080c1e6e84301b9bc1cad4` |
| terminal-04 | 9/9 PASS | 0 | `5c1f8469fa5dee16b77e8b02ae7320b188b71b82d4528040f2e3919905ed7a09` |

每份日志只包含场景别名、掩码终端别名、步骤和协议应答结果。每个场景的九个步骤为 `connect`、`register`、`expectSilence`、`authenticate`、`position`、`activeSafetyAlarm`、`attachmentInfo`、`fileUploadCompleteNotification`、`disconnect`。

第一次启动 terminal-01 时，PowerShell 将未加引号的 Maven `-Dexec.mainClass` 参数误解析；模拟器 JVM 未启动，gateway 未收到流量，激活脚本按 45 秒门禁退出。修正为完整参数加引号后，从仍未注册的预置状态重新执行成功。该异常没有通过改库、清卷或跳过状态机处理。

## 最终聚合事实与时间

时间采用 UTC；北京时间需加 8 小时。

| 验证项 | 聚合结果 | 判定 |
| --- | ---: | --- |
| 终端总数 / ACTIVE / 已注册 | 4 / 4 / 4 | 通过 |
| `last_authenticated_at` 非空 | 4/4 | 通过 |
| GPS 位置 / 覆盖终端 | 8 / 4 | 通过；主动安全 `0x0200` 自带定位 |
| 报警事实 / 覆盖终端 | 4 / 4 | 通过 |
| 前向碰撞报警 | 4 | 通过 |
| `REGISTERED + ACCEPTED` 审计 | 4 | 通过 |
| `AUTHENTICATED + ACCEPTED` 审计 | 4 | 通过 |
| 附件相关 `PROTOCOL_REJECTED + REJECTED` 审计 | 8 | 符合当前附件拒绝边界 |
| `LOCATION + ACCEPTED` ingress receipt | 8 | 通过 |

| 时间窗口 | 起始 | 结束 |
| --- | --- | --- |
| 注册持久化 | 2026-08-22 11:07:10.707Z | 2026-08-22 11:10:27.561Z |
| 鉴权时间戳 | 2026-08-22 11:07:25.876Z | 2026-08-22 11:10:42.721Z |
| GPS 网关接收 | 2026-08-22 11:07:25.933Z | 2026-08-22 11:10:42.769Z |
| 报警网关接收 | 2026-08-22 11:07:26.069Z | 2026-08-22 11:10:42.769Z |
| 成功鉴权审计 | 2026-08-22 11:07:25.908Z | 2026-08-22 11:10:42.740Z |

这些聚合结果证明修复前的两个缺口均已关闭：成功鉴权既有协议应答，也有终端时间戳和审计事实；附件拒绝不再阻止注册/鉴权 session audit 入库。

## 附件边界、readiness 与现场冻结

根据 2026-08-22 的正式范围裁决，附件功能不属于 P6-2 交付范围。在真实媒体服务接入并另行完成设计、配置和验收前，以下链路均不启用：

- 平台下发附件上传指令 `0x9208`；
- 终端附件信息上报 `0x1210`；
- 终端文件上传完成通知 `0x1206`。

本轮合成场景发送的 `0x1210` 与 `0x1206` 只证明网关可以按当前未启用合同安全解码、持久缓冲、应答和拒绝，不代表附件业务已启用。当前无媒体服务时继续返回 `ALARM_IDENTIFIER_UNAVAILABLE`，本次不修改附件相关实现、不人工改写 Outbox，也不把拒绝改写为成功。

因此，附件拒绝导致的 readiness HTTP 503 被登记为已知降级状态：它不阻塞 P6-2 注册、鉴权、位置和报警基础链路验收，但明确阻止任何“附件链路可用”或“完整生产就绪”的结论。

异步重试稳定后：

| readiness 项 | 冻结现场值 |
| --- | ---: |
| TCP listener | `true` |
| 持久缓冲可写 | `true` |
| 运营 API 匿名探针 | `UP` |
| 运营 API registry 状态 | `STALE` |
| 运营 API ingress 状态 | `DOWN` |
| 最近投递成功 | `false` |
| Outbox pending | 0 |
| Outbox delivering | 0 |
| Outbox dead-letter | 12 |
| readiness HTTP | 503 |

readiness 证据文件 SHA-256 为 `0b66e71a76aef750c291f023c6de8d2ebeb1e91baeaeb208611ee4f0f248e0be`。完成查询后仅停止 R2 `jt-gateway`，从而关闭 `7611` 并冻结 H2 状态；R2 PostgreSQL、Redis、API、管理端和两个模拟服务继续保留，三个新卷均未删除。修复前 R1 gateway 与卷也继续保留。

## 安全边界与后续入口

- 本报告只证明本机合成协议互通和方案 A 修复，不是厂商终端兼容性、真实蜂窝网络、生产负载或云端部署证明。
- 真实资料只保存在 Git 忽略的 `.private/terminals/`；R2 脚本和场景没有读取该目录。
- 公开报告不包含完整终端手机号、terminalCode、车牌、车辆/终端 UUID、鉴权令牌、凭证、原始帧或附件载荷。
- `.env.r2`、R2 Compose 覆盖文件、场景、manifest 和证据日志均被 Git 忽略。
- 不对 R1 或 R2 H2 执行人工 UPDATE、DELETE 或伪造 redrive；两个现场都可供后续只读复核。
- 用户已在 2026-08-22 授权使用四台真实终端执行注册、鉴权、位置和报警基础链路验证；真实验收不得启用或以任何方式补做附件链路。
- 第三轮真实终端预检已完成车辆资料闭环：4/4 secret JSON 的车辆标识唯一，首配经纬度合法且为 `GCJ02`；独立 `drt-ops-jt-real-acceptance` 项目和新卷已创建，4 辆真实车辆、4 台 PENDING 终端、能力和唯一绑定已预录入。处理过程中仅机械修复了四个 secret JSON 的全角分隔逗号，并在私密脚本中兼容 CSV 手机科学计数法与 `GCJ-02→GCJ02` 规范化，没有修改资料业务值或生产代码。gateway 短暂启动后宿主机 7611 本地可达，但 Windows 当前没有 7611 入站允许规则，真实终端 0/4 连接；创建临时 Public/Private TCP 7611 规则因缺少显式主机级授权被安全策略拒绝。gateway 和激活观察器已停止，数据库保持 0 注册、0 鉴权、0 JT808 位置、0 报警，基础服务和三个真实验收卷保留。恢复前须由用户明确授权该临时防火墙规则及验收后删除。
- 用户随后明确授权临时 Windows 规则。规则按 Inbound/Allow、Public + Private、TCP 7611、RemoteAddress Any 创建并逐项核验；gateway 在 2026-08-22 14:24:36Z 至 14:34:24Z 保持健康。观察窗口内始终为 0 ESTABLISHED、0 注册、0 鉴权、0 JT808 位置、0 报警和 0 session audit，也没有协议拒绝，失败边界为终端或上游网络未向 7611 发起连接。按门禁先停止 gateway，再删除规则；独立数据库和三个卷保留。详细证据见 `real-terminal-acceptance-2026-08-22.md`，P6-2 仍不得正式收口。

## 分支状态

- 工作树：`D:\codex-projects\.worktrees\jt-gateway-deployment`
- 分支：`feat/jt-gateway-deployment`
- 基线 HEAD：`2f690a255bbee3d3ffe29b5e9ed65d7c4447b1fa`
- 本轮未推送、未合并、未创建 PR。
- 待审改动包括 Docker 构建修复、模拟器注册字段扩展、方案 A 的 Outbox 隔离与鉴权时间戳修复、相应测试，以及 R1/R2 两份验收报告。
