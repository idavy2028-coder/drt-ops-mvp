# P6-2 本机 Docker 四终端模拟接入验收报告

## 技术结论

**验收结论：不通过，P6-2 阶段不得据此正式结束。**

- 四台纯合成终端均完成注册、管理员激活、鉴权、位置、主动安全报警、`0x1210` 附件信息和 `0x1206` 文件上传完成通知；每台 9/9 个场景步骤通过，模拟器退出码均为 0。
- PostgreSQL 中 4/4 终端已注册并处于 `ACTIVE`，8 条 GPS 位置覆盖 4/4 终端，4 条前向碰撞报警覆盖 4/4 终端。
- 附件元数据按已确认边界被运营 API 拒绝，这是预期结果；但网关把附件元数据与 session audit 混入同一原子批次，附件拒绝连带导致鉴权审计进入重试/死信。
- 冻结现场时 readiness 为 HTTP 503，Outbox 为 12 条死信、4 条待处理；`AUTHENTICATED` 审计未落库，`jt_terminals.last_authenticated_at` 仍全部为空。协议成功应答不能替代这些持久化与运维门禁。
- 本轮仅使用合成身份和本机隔离 Docker 项目，没有启动真实终端接入或云端部署，也没有读取四台真实终端值参与场景生成。

## 验收范围、口径和时间

- 验收对象：本机 Docker 项目 `drt-ops-jt-acceptance`。
- 合成终端口径：`terminal-01` 至 `terminal-04` 四个不可关联到真实资料的别名；每个终端绑定不同车辆。
- 协议口径：JT/T 808—2013 公共链路，主动安全能力为 `T/JSATL12-2017`，模块为 ADAS、DMS，并启用 JT/T 1078 控制信令能力。
- 业务链路口径：注册 → 管理员激活 → 鉴权 → 位置 → `0x0200` 主动安全报警 → `0x1210` → `0x1206` → 断开。
- 证据时间采用 UTC；对应北京时间需加 8 小时。
- 注册窗口：2026-08-22 08:05:10Z 至 08:07:28Z。
- GPS 入库窗口：2026-08-22 08:05:26Z 至 08:07:44Z。
- 报警入库窗口：2026-08-22 08:05:28Z 至 08:07:44Z。
- 审计入库窗口：2026-08-22 08:05:10Z 至 08:07:44Z。

## 部署与迁移门禁通过，但最终 readiness 失败

隔离环境使用以下宿主端口：API `18080`、管理端 `15173`、PostgreSQL `15432`、Redis `16379`、算法服务 `18090`、路由模拟器 `18092`、JT 网关 TCP `7611`。现有 `drt-ops-pilot` 项目未被停止、重建或删除。

镜像首次构建暴露并修复了两项部署基线问题：API Dockerfile 未复制根 POM 声明的全部模块；API/网关的 `dependency:go-offline` 无法解析 Netty `${os.detected.classifier}`。移除有缺陷的预取层并补齐 API 构建上下文后，API、网关、管理端、算法服务和路由模拟器镜像全部构建成功。

环境启动后，API、管理端、数据库、Redis、算法服务和路由模拟器均健康，网关 liveness 为 `UP`，宿主机 `7611` 存在监听。实际 PostgreSQL 迁移核验结果如下：

| 门禁 | 结果 |
| --- | --- |
| Flyway 最新版本 | `17:true` |
| `jt_gateway_audit_events.idempotency_key` 非空 | 通过 |
| session audit 幂等唯一约束 | 1 个有效约束 |
| V17 ingress receipt 新增列 | 3/3 存在 |

四终端流量完成后，网关 readiness 转为 HTTP 503：

| readiness 项 | 冻结现场值 |
| --- | ---: |
| TCP listener | `true` |
| 持久缓冲可写 | `true` |
| 运营 API 匿名探针 | `UP` |
| 运营 API ingress 状态 | `DOWN` |
| 最近投递成功 | `false` |
| Outbox pending | 4 |
| Outbox delivering | 0 |
| Outbox dead-letter | 12 |

为避免等待复核期间继续改变现场，已保存 readiness JSON，并仅停止隔离项目的 `jt-gateway` 服务。命名卷 `jt-gateway-data-acceptance`、API 数据库和其余容器均保留；未执行 `down -v`、未删除卷、未直接读取或修改 H2。

## 测试先行的模拟器扩展已通过回归

现有模拟器把注册字段固定为单一值，无法合法模拟四台不同终端。本轮按已确认设计增加场景级 `manufacturerId`、`model` 和 `terminalCode`，缺省行为保持兼容。

RED 证据：`ScenarioRunnerTest` 共 11 项，2 项按预期失败、0 错误；失败分别证明新增 JSON 字段被忽略，以及真实 TCP 收到的 `0x0100` 仍包含旧固定值。默认兼容用例在变更前通过。

GREEN 与回归证据：

| 模块 | 测试 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| `jt-protocol` | 48 | 0 | 0 | 0 |
| `jt-terminal-simulator` | 11 | 0 | 0 | 0 |

注册帧测试通过真实 loopback TCP、生产解码器和生产编码器核对 0x0100，而不是只断言内部对象或 mock 调用。

## 四台终端协议步骤全部通过

每台终端均使用同一状态机：先完成预录入、能力和唯一车辆绑定；模拟器收到注册成功响应后等待 15 秒，激活脚本检测 `registrationCompleted` 并调用管理 API 激活，再由同一 TCP 会话继续鉴权和业务上报。

| 合成别名 | 步骤结果 | 模拟器退出码 | 私密日志 SHA-256 |
| --- | ---: | ---: | --- |
| terminal-01 | 9/9 PASS | 0 | `5755fbdd15fbdea8c2aa8ec4db263eec43da70e5d0b0da56c80f1e740a12abde` |
| terminal-02 | 9/9 PASS | 0 | `f2a7342aa0477c514e89394c79f1f991315a5f740eb988dc4db5f4ce837cc040` |
| terminal-03 | 9/9 PASS | 0 | `24767b70167f09d42b6fc7eae44522261cf9b3aebe80e1890081f5244a563bd9` |
| terminal-04 | 9/9 PASS | 0 | `8648bc34aa8a4194268a8861b1d9240e4e604e04018a4aa0adad9cbb7fb53955` |

每个场景的九个步骤为 `connect`、`register`、`expectSilence`、`authenticate`、`position`、`activeSafetyAlarm`、`attachmentInfo`、`fileUploadCompleteNotification`、`disconnect`。日志只包含场景别名、掩码终端别名、步骤和协议应答结果。

## 核心业务事实完整，鉴权审计闭环缺失

| 验证项 | 聚合结果 | 判定 |
| --- | ---: | --- |
| 终端总数 / ACTIVE / 已注册 | 4 / 4 / 4 | 通过 |
| 模拟器鉴权成功应答 | 4/4 | 协议层通过 |
| `last_authenticated_at` 非空 | 0/4 | 不通过 |
| GPS 位置 / 覆盖终端 | 8 / 4 | 通过；主动安全 0x0200 自带定位，因此总数大于终端数 |
| 报警事实 / 覆盖终端 | 4 / 4 | 通过 |
| 前向碰撞报警 | 4 | 通过 |
| `REGISTERED + ACCEPTED` 审计 | 4 | 通过 |
| `AUTHENTICATED + ACCEPTED` 审计 | 0 | 不通过 |
| 附件相关 `PROTOCOL_REJECTED + REJECTED` 审计 | 8 | 符合 2 条附件元数据消息 × 4 台的当前拒绝边界 |
| `LOCATION + ACCEPTED` ingress receipt | 8 | 通过 |

数据库事实与四份模拟器报告相互印证注册、位置和报警；鉴权只能由协议成功应答证明，缺少 API 侧时间戳和成功审计，因此不得宣称鉴权持久化闭环完成。

## 阻断根因是不同投递语义被混成一个原子批次

当前普通 ingress 合同明确要求：批内任一项被拒绝时整批保持重试，避免 2xx 响应下静默丢失。该语义已有单测保护，不应在本轮被弱化。

同时，`SESSION_AUDIT` 使用独立 `/internal/jt-gateway/audit-events` 接口，接受 `ACCEPTED` 或 `REPLAYED`。但是 `GatewayOutboxDispatcher` 的高优先级 claim 会把附件元数据和 session audit 放入同一批；`OperationsApiClient` 先投递普通 ingress，遇到附件固定 `REJECTED` 后直接跳过 session audit，调度器再把整批统一计次。结果是附件拒绝污染注册/鉴权审计的重试状态，最终产生死信。

此外，API 的鉴权校验当前只返回决策，没有更新 `jt_terminals.last_authenticated_at`；该字段即使 session audit 后续能够投递，也不会自动得到值。这是独立的可观测性缺口。

## 限制、稳健性和安全边界

- 本报告只证明本机合成协议互通，不是厂商终端兼容性、真实蜂窝网络、生产负载或云端部署证明。
- `0x1210` 与 `0x1206` 已证明被网关解码、持久化并成功应答，但运营 API 尚无获批附件业务合同；本轮不把它们写成附件业务入库成功。
- 四台终端串行执行，未形成并发容量结论。
- 真实资料的受跟踪 CSV 已恢复为空白模板；完整资料只保存在 Git 忽略的 `.private/terminals/`。本轮场景未引用该目录中的真实值。
- 公开报告不包含完整终端手机号、terminalCode、车牌、车辆/终端 UUID、鉴权令牌、凭证、原始帧或附件载荷。
- 私密证据位于 `.private/acceptance-evidence/`、`.private/acceptance-scenarios/` 和 `.private/acceptance-synthetic-terminals.json`，均不进入 Git。

## 建议的修复与复验门禁

1. 以失败测试复现“一个固定拒绝的附件元数据与一个可接受的 session audit 同时待投递”场景。
2. 保持普通 ingress 的整批确认语义不变，把 `SESSION_AUDIT` 从普通高优先级 claim 中隔离，确保它只按独立 audit API 的结果推进状态。
3. 为成功鉴权补齐 `last_authenticated_at` 的事务更新和测试，且不把令牌或摘要写入日志。
4. 不对当前 H2 死信执行人工 UPDATE、DELETE 或伪造 redrive。保留失败卷作为证据，在新的隔离项目/新卷中重建镜像并重新执行四终端场景。
5. 复验必须同时满足：4/4 协议场景通过、4/4 鉴权时间戳、4 条成功鉴权审计、普通业务事实完整、session audit 无死信；附件元数据仍按已确认合同单独显示拒绝，不得污染其他 kind。
6. 上述本机模拟复验通过后，仍需另行获得授权，才能进入真实终端和云端验收。

## 分支与现场状态

- 工作树：`D:\codex-projects\.worktrees\jt-gateway-deployment`
- 分支：`feat/jt-gateway-deployment`
- 基线 HEAD：`2f690a255bbee3d3ffe29b5e9ed65d7c4447b1fa`
- 本轮未推送、未合并、未创建 PR。
- 待审改动包括两个 Dockerfile 构建修复、模拟器字段扩展及测试、本验收报告；`.env`、私密脚本、场景、日志和真实资料均被 Git 忽略。
- 隔离 `jt-gateway` 已停止，`jt-gateway-data-acceptance` 卷保留；API、PostgreSQL、Redis、管理端、算法服务和路由模拟器保持健康，便于只读复核。
