# P6-2 腾讯云真实终端接入验收 R1（2026-08-24）

## 结论

**本轮真实终端验收不通过，P6-2 不得正式收口。**

云端 gateway liveness 和 TCP 监听正常，腾讯云安全组限定终端来源网段后，服务器实际收到真实终端发往 `7611` 的数据包和 JT808 注册请求。但注册验证全部被拒，4 台终端均保持 `PENDING`，未形成鉴权、JT808 位置或主动安全报警事实。

附件不属于本阶段范围。本轮未启用 `0x9208`、`0x1210`、`0x1206`，未修改附件代码，`ALARM_IDENTIFIER_UNAVAILABLE` 边界保持不变。

## 云端预录入基线

- 云端业务车辆总数为 6，其中 4 辆属于真实终端验收，均为 `IDLE` 且 `dispatchable=false`。
- 4 台终端均为 `PENDING`，ADAS/DMS/JT1078 能力配置 4/4，唯一活动绑定 4/4。
- 四类 `PRE_ACCEPTANCE` 审计均为 4 条：车辆创建、终端预置、能力配置、车辆绑定。
- 私有 manifest 为 `COMPLETE`，4/4 项完成，共记录 16 个步骤，失败字段已清空。
- gateway 启动前 API health 为 `UP`；附件配置字段写入数量为 0。

## 首轮并行观察结果

- 观察器先于 gateway 启动，只输出 `terminal-01` 至 `terminal-04` 和聚合计数。
- gateway 容器以 UID/GID `10001:10001` 运行，只读根文件系统，liveness 为 `UP`，宿主 `7611` 存在监听；API 和其他内部端口未作为本轮终端入口开放。
- 20 分钟观察器最终状态为 `TIMEOUT`：注册 0、激活 0、鉴权 0。
- 计数型抓包确认至少有一个入站 `7611` 数据包到达；未保存 pcap、IP、载荷或原始帧。
- PostgreSQL 共形成 841 条 `REGISTERED/REJECTED` 审计，原因均为 `NOT_PREPROVISIONED`；匹配到已预录入 terminal UUID 的数量为 0。
- 冻结时终端状态为：PENDING 4、ACTIVE 0、已注册 0、已鉴权 0；JT808 位置 0、报警 0。
- 观察器超时后先停止 gateway，再确认 `7611` 监听 0、ESTABLISHED 连接 0；数据库、H2 和命名卷均保留。

## terminal-01 单机复检

- 用户现场确认只对 `terminal-01` 单独开机测试。
- 受控诊断窗口最长 60 秒，gateway 健康检查通过，结束时由 trap 自动停止。
- 该窗口新增 3 条 `REGISTERED/REJECTED`，总拒绝数由 841 增至 844，原因仍为 `NOT_PREPROVISIONED`。
- 这证明 terminal-01 到腾讯云的 TCP/JT808 注册路径可达，失败边界位于注册字段匹配，而不是终端未发流量或安全组完全阻断。
- 复检后仍为 PENDING 4、ACTIVE 0、注册 0、鉴权 0；gateway 为 `exited`，`7611` 无监听。

## 已确认的注册合同缺陷

注册验证要求终端号、消息头手机号、厂商、型号、车辆标识和协议版本全部一致。当前审计仅返回通用原因码，不能排除多个字段同时不匹配。

已确认一个必然阻断项：预录入把资料描述值 `JT/T 808-2019` 原样保存，而 gateway 固定提交规范枚举 `JT808_2019`；API 原实现使用字节级完全相等比较。因此即使其他五项一致，协议版本也必然不匹配。

## 方案 1 修复与验证

用户批准方案 1 后完成测试先行修复：

- API 对明确允许的 2013/2019 描述值和规范值做白名单规范化；未知值继续 fail closed。
- 新预录入统一写入 `JT808_2013` 或 `JT808_2019`；私有 dry-run 和观察器可将现存旧描述值与规范值判为等价。
- API 返回不含实际字段值的安全逐项原因码；gateway 映射并记录对应原因码，未知码仍退回 `NOT_PREPROVISIONED`。
- 未直接更新 PostgreSQL，未删除或重建终端，未绕过 API 审计边界。

RED 证据：PowerShell 规范化断言 1 项失败；API 新增 3 项测试全部失败；gateway 测试因缺少 `PROTOCOL_VERSION_MISMATCH` 原因码编译失败。

GREEN 证据：

| 模块 | 测试 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| JT 协议 | 48 | 0 | 0 | 0 |
| 终端模拟器 | 11 | 0 | 0 | 0 |
| jt-gateway | 107 | 0 | 0 | 0 |
| API | 484 | 0 | 0 | 41 |

私有预录入测试和云端观察器测试均为 `TEST_RESULT=PASS`，`git diff --check` 退出码为 0。

## 修复版本离线部署

- 修复差异摘要：`a0b8ff7c7aa6ddb8cd28e5f02fed456606835fe8`；镜像标签统一为 `p6-2-registration-a0b8ff7`。
- API 镜像 ID：`sha256:a681f98671054366aaeb16c23aa6b794fcaaa11482a0e49ea3f0f8b81cf9b4c1`。
- gateway 镜像 ID：`sha256:c6445a519e34b3f16352230f199ca4d04486d52b6b496fa17c4e0b19969a22e6`。
- 离线镜像压缩包 SHA-256：`581f0e7c215cced5f32c2d4196a276402bf0d2e2e5517220a3a59cdd83e74ff1`；服务器校验为 `OK`，加载后的两个 image ID 与本地一致。
- 新版 API 已按 last override 重建并通过 health `UP`；失败自动回滚门禁未触发。
- 新版 gateway 仅通过 `compose create` 重建为 `created`，未执行 `up` 或 `start`；UID/GID、只读根文件系统、`cap_drop=ALL` 和 `no-new-privileges` 均保持，`7611` 监听为 0。
- 离线注册验证使用现存预录入数据和规范协议版本调用只读内部验证接口，结果为 `APPROVED`；未调用注册完成、激活、鉴权或写审计接口。
- 部署后数据库仍为 PENDING 4、ACTIVE 0、注册 0、鉴权 0；历史注册拒绝总数仍为 844，四类 `PRE_ACCEPTANCE` 审计仍各为 4。

## 安全异常

排查过程中，一次内部只读工具调用误将旧私有 manifest 中的终端/车辆标识类字段写入受控会话工具输出。未包含管理员密码、终端鉴权码、服务凭证或 token。发现后立即停止原始 manifest 输出，后续检查全部改为安全别名、布尔值和聚合计数；本报告不包含这些真实值。该事件不改变终端鉴权材料，但作为脱敏流程异常保留。

## 当前状态与下一门禁

- 工作树：`D:\codex-projects\.worktrees\jt-gateway-deployment`。
- 分支：`feat/jt-gateway-deployment`；方案 1 修复当前尚未提交，但新版 API/gateway 镜像已构建、哈希校验并完成离线部署。
- 2026-08-24 真实流量窗口已于 18:20 结束；窗口结束后未重新启动 gateway。
- 云端 gateway 当前为 `created`，`7611` 监听为 0；新版 API health 为 `UP`，数据库和命名卷保留。
- 用户正在修正 terminal-01 终端侧数据，但消息中的窗口起始时间仍是占位符。下一步必须取得明确时间并再次确认 terminal-01 就绪、安全组规则仍有效，再运行只读门禁、启动观察器和 gateway。

新版部署后的首次单机注册仍按 fail-fast 处理：若失败，只读取新安全原因码并立即停止，不继续扩大到其他终端。

## 设备实际注册资料受控纠正（2026-08-25）

用户在 Git 忽略目录中按车载设备实际 `0x0100` 注册值更新四份 secret JSON 和 intake CSV。当前管理端和原 API 没有身份编辑入口，因此新增了仅供受控纠正使用的 API 与私有脚本。

### 实现门禁

- 只允许 `PENDING`、尚未注册、尚未鉴权且具有活动绑定的终端。
- 每次请求必须携带 `expectedVersion`；终端代码、消息头身份和车辆标识均做唯一性校验。
- 原终端 UUID、活动绑定 UUID、能力配置、鉴权摘要与版本保持不变。
- 绑定车辆仅在 `IDLE`、不可调度状态下允许纠正标识；本轮 dry-run 表明四台车辆标识均无需修改。
- preview 只返回变更字段名和版本，不返回手机号、终端代码或车辆标识实际值。
- apply 审计仅保存 `changedFields`、`beforeDigest`、`afterDigest`；不保存字段明文。
- gateway 始终为 `created`，`7611` 始终无监听。

RED/GREEN 证据：新增受控纠正、唯一性、状态、绑定保留、审计脱敏、preview 不写入和私有事件脱敏测试。完整回归结果为 JT 协议 48、模拟器 11、gateway 107、API 488，失败 0、错误 0、跳过 41；私有纠正测试为 `TEST_RESULT=PASS`。

身份纠正 API 镜像：

- 标签：`drt-ops-jt-cloud-api:p6-2-identity-0192c46`。
- image ID：`sha256:501572f2b899eea762c4ce1c451d950ed2b7ec5d67d56031da48baba738b7b9b`。
- 离线包 SHA-256：`464019b32eea98df7f0f6dc022df672035c25244a8ac976cc1255544fbccb626`。
- 部署后 API health 为 `UP`；失败回滚门禁未触发。

### dry-run 与备份

四台 dry-run 均只发现两个差异字段：`terminalPhone`、`protocolVersion`；终端代码、厂商、型号、坐标系及车辆标识均已匹配。

写入前 PostgreSQL 全量备份：

- Backup ID：`identity-correction-20260825T022635Z`。
- mode：600。
- 归档条目：186。
- SHA-256：`e5b5ba9a9c108b98d8108d00d3611a363fa9995c5fe1034a88f5d5e1047dba67`。

### 顺序 apply 与验证

按 terminal-01 → terminal-04 顺序执行，每台均仅修改 `terminalPhone` 和 `protocolVersion`，版本由 2 升至 3；每台 apply 后都先调用内部只读注册验证，均返回 `APPROVED`，再继续下一台。

最终聚合结果：

| 门禁 | 结果 |
| --- | ---: |
| 内部注册验证 | 4/4 APPROVED |
| 注册验证拒绝 | 0 |
| `JT_TERMINAL_IDENTITY_CORRECTED` 审计 | 4 |
| 审计字段集合符合预期 | 4 |
| 车辆标识纠正审计 | 0 |
| `JT808_2019` 规范协议记录 | 4 |
| ADAS/DMS/JT1078 能力 | 4 |
| 活动绑定 / 唯一终端 / 唯一车辆 | 4 / 4 / 4 |
| PENDING / ACTIVE / 已注册 / 已鉴权 | 4 / 0 / 0 / 0 |

私密资料 SHA-256：

| 文件别名 | SHA-256 |
| --- | --- |
| intake CSV | `8ebc791b746e7907f6a05fd46d1f024fdddfa24442570d585333a130ec67dede` |
| terminal-01 secret | `ae8f2ca5c1a74fc9625f6073147af8dcd76f1b8f92362ef51f7059c39e131ab1` |
| terminal-02 secret | `7d7731d85f349091ee73d9b821ff7ad957f3c798f3b7eaf9b8c784819826c607` |
| terminal-03 secret | `21f00aaa56e5846a06e8abbfe5362099469a55ec8a2903ce5e2eabf2da8b9bfb` |
| terminal-04 secret | `0d81bcce8221973bd407ab66506ee6f67ce25fe64ca73fbbf37f7d96b460f216` |

身份资料纠正完成不等于真实终端验收通过。下一门禁仍为用户确认新的 terminal-01 上电窗口、安全组规则和终端侧配置后，观察器先启动、gateway 后启动，完成 terminal-01 注册、激活、鉴权、位置和报警，再扩展到其余三台。

## 四台并发复检与临时注册维护白名单（2026-08-25）

### 并发复检失败事实

- 观察器在 gateway 前启动并返回 `OBSERVER_READY=YES`；修复版 gateway 使用三文件 Compose override 启动，liveness、非 root、只读根文件系统、`cap_drop=ALL`、`no-new-privileges` 和 7611 监听门禁均通过。
- 有效尝试窗口为 `2026-08-25T05:10:36.722222956Z` 至 `2026-08-25T05:13:25.115400973Z`。窗口内形成 87 条 `REGISTERED/REJECTED`、0 条 `REGISTERED/ACCEPTED`，拒绝原因全部为 `NOT_PREPROVISIONED`；去除源端口后共观察到 3 个来源地址组。
- 四台终端仍为 PENDING，ACTIVE、已注册、已鉴权、JT808 位置和报警均为 0。观察器到期后按 fail-fast 停止 gateway，并复核 7611 监听和连接均为 0。
- 观察器脱敏日志 SHA-256 为 `2ac2821bbf5c34808721f53bb765f09a1bbfdadbfa925ab4560243531e328948`；状态快照 SHA-256 为 `2665d3b6dba75993cb4138dd98f9c6e445233efb4a6abd91ce3ee2769d799aef`。

### 只读诊断边界

- gateway/API 服务凭证版本、摘要、内部 base URL 和共享 Compose 网络均匹配；从宿主及 gateway 同网络短生命周期容器执行的四条只读注册验证均为 4/4 `APPROVED`。
- 因此预录资料、服务凭证和容器网络不是本轮通用拒绝的解释范围；失败位于真实 `0x0100` 解析字段进入 Java verify/complete 流程之后。旧实现把 HTTP、网络和非法响应统一折叠成 `NOT_PREPROVISIONED`，无法安全区分失败阶段。

### 临时白名单与脱敏诊断 GREEN

- 用户确认 terminal-02～04 无法断网，并批准选项 1：维护窗口允许其持续重试但不成功上线，只保证不改变云端业务状态。
- 新增临时 `RegistrationMaintenancePolicy`：使用 `protocolVersion + NUL + terminalIdentity` 的 SHA-256 私密摘要，只允许目标终端进入注册解析/API 流程；其他终端快速返回失败，首次及每 60 秒记录 `TEMPORARILY_BLOCKED_FOR_MAINTENANCE`，其余重试只计数。
- 配置缺失、摘要非法或启动时已过期均 fail closed；运行中到期后拒绝全部新注册并降低 readiness。健康详情只提供启用/过期、允许/拦截、拦截身份和审计抑制计数，不公开摘要或身份。
- 五个注册必填字段在调用 API 前使用固定空值码拒绝；运营 API 错误按 `VERIFY` / `COMPLETE` 和 400、401/403、409、5xx/网络分类，响应正文及异常消息不进入诊断结果。
- 私有 PowerShell 库能够规范协议、生成 NUL 分隔摘要、验证到期时间，并生成不含真实身份的私密环境文件和 Compose override；测试 14/14 通过。

### 测试证据与当前门禁

- RED 依次复现：阶段分类 2 个断言失败、空字段 5 个断言失败、维护策略类型缺失、运行时配置被忽略、HTTP/网络矩阵 7 个断言失败、私有工件 writer 缺失。
- GREEN 四模块串行回归退出码为 0：JT 协议 48、gateway 128、API 488、模拟器 11，共 675 项，失败 0、错误 0、跳过 41。
- 本轮没有构建或部署新镜像，也没有重新开放 7611。下一门禁为构建新 gateway 离线镜像、生成仅含 terminal-01 摘要且带明确 UTC 到期时间的私密维护 override，复核后再部署；部署前 gateway 必须继续停止。

### 临时维护镜像与停止态部署

- 发布源码摘要为 `35ac844d5dac4ff9298533211befdf69a220c1e5ec3654154b80396a132d4760`；宿主离线 package 生成的 gateway JAR 为 35,068,614 字节，SHA-256 `39a4c08e3d73658501870724ee72fc3a68f53bc302391586c3dba95ef731fa2f`。
- 标准 Dockerfile 首次构建因构建容器无法连接 Maven Central 而停止，没有生成候选镜像。随后以云端已验证的 `p6-2-registration-a0b8ff7` 运行时镜像为基底，只替换上述已测试 JAR；未修改 JRE、curl、用户、入口或系统层。
- 新镜像为 `drt-ops-jt-cloud-gateway:p6-2-maintenance-35ac844`，image ID `sha256:e12da8371edbcbda9469263ad1136a2ab32287832365cb93229d01ffd81000e1`；隔离容器复核为 `linux/amd64`、UID/GID 10001、无网络、只读、`cap_drop=ALL`，容器内 JAR 哈希与宿主一致。
- 单镜像离线压缩包为 176,067,611 字节，SHA-256 `f0df1f89a644f2bd39b18ab9f95df27cdff8a2669480d192e1cf67cebd683530`；云端上传后 `sha256sum -c`、tar 结构、解压 tar 哈希及 `docker load` 全部通过，发布目录和文件权限分别为 700、600。
- 真实 terminal-01 维护环境文件与 Compose override 的 SHA-256 分别为 `0c3acf6e80e89c5eb844e4bfc08c3541d63f8bcd3b37fa38bc89e7f6de9cce34`、`af54a0adbb1ea99c862f2af4e85b2d3eb6b7af7aa37c0dc14dea58bf4ce65588`；公开证据不记录允许摘要，工件和 override 内容扫描不含原始终端身份。
- Compose 依次合并基础、offline、registration、identity、新 gateway 镜像和 maintenance override，解析结果保持 API `p6-2-identity-0192c46`、gateway `p6-2-maintenance-35ac844`。第一次停止态验证因 `docker compose ps -q` 不返回 created 容器而自动回滚；确认 `ps -aq` 语义后重试成功。
- 新 gateway 容器最终为 `created`，image ID 与本地一致；API 容器未重建，`jt-gateway-data-cloud-test-01` 原卷保持，非 root、只读、capabilities 和 no-new-privileges 门禁通过，7611 监听和连接均为 0。终端仍为 PENDING 4，ACTIVE/注册/鉴权均为 0。
- 本次工件到期时间为 `2026-08-25T09:45:26.2425467Z`；最终部署核验时仅余 280 秒，不足 10 分钟诊断窗口，因此该停止态容器只证明发布结构正确，不得直接启动。收到实际启动指令后，必须重新生成“当前 UTC + 15 分钟”的环境文件、重新校验并再次停止态重建，才能启动观察器和 gateway。

### terminal-01 维护白名单真实流量窗口

- 启动前重新生成并上传 15 分钟有效工件；最终生效环境文件 SHA-256 为 `28131fbe6fbf58a001888d19f980d614fad39ebadd23e7ddcc1471b85d1db971`，Compose override SHA-256 仍为 `af54a0adbb1ea99c862f2af4e85b2d3eb6b7af7aa37c0dc14dea58bf4ce65588`。真实身份和允许摘要未进入输出。
- 观察器先返回 `OBSERVER_READY=YES`，随后 gateway 重建并启动。有效窗口为 `2026-08-25T10:02:57.496796782Z` 至 `2026-08-25T10:11:01.437784414Z`；liveness、安全属性、7611 和非 gateway 公网端口门禁均通过。
- 维护策略累计拦截 265 次注册尝试，覆盖 3 个不同非目标身份；首次及每 60 秒审计限流后写入 24 条 `REGISTERED/REJECTED + TEMPORARILY_BLOCKED_FOR_MAINTENANCE`，抑制 241 次重复审计。Outbox pending 0、dead-letter 0，证明快速拒绝和持久审计链路正常且未形成积压。
- terminal-01 允许尝试计数始终为 0；观察器在 600 秒门禁内始终为注册 0、ACTIVE 0、鉴权 0并最终超时。数据库终态仍为 PENDING 4、ACTIVE/注册/鉴权 0；本窗口 JT808 位置和报警覆盖均为 0。
- 按 fail-fast 停止 gateway 后复核状态 `exited`、7611 监听和连接均为 0、API health `UP`。观察器日志 SHA-256 为 `a3d760157211f1c2d0ddbd79154df6f1716575d3f421fed0972254907d0911d8`，状态快照 SHA-256 为 `feb637dcfe1dbe918f635e72b4f22532269eb1035269298aa3f2032a4e898411`。
- 本轮只证明 3 个非目标身份可被安全隔离；没有证据证明 terminal-01 在窗口内发送了匹配白名单的 `0x0100`，因此不能进入字段级诊断，更不能判定注册、鉴权、位置或报警通过。下一门禁为取得 terminal-01 设备侧连接/重试结果或执行明确的网络重连，再使用新的即时 TTL 复验。

### terminal-01 设备重连后的维护复验 R2

- 用户确认 terminal-01 已执行设备侧网络重连/服务器连接重试后，重新启动独立 600 秒观察器并刷新维护工件；最终环境文件 SHA-256 为 `f12b11e681d7d35490e96e520fe23438474e35781924d7a63d7a65be87b33e9d`，真实身份和允许摘要仍未进入输出。
- R2 有效 gateway 窗口为 `2026-08-25T10:36:37.617392139Z` 至 `2026-08-25T10:43:47.342764028Z`。维护策略拦截 235 次、覆盖 3 个非目标身份，写入 21 条 `TEMPORARILY_BLOCKED_FOR_MAINTENANCE` 审计并抑制 214 次重复；Outbox pending/dead-letter 为 0。
- terminal-01 允许计数再次保持 0，观察器仍为注册/ACTIVE/鉴权 0并超时；终态 PENDING 4、ACTIVE/注册/鉴权 0，本窗口 JT808 位置和报警覆盖 0。两次独立维护窗口均只观察到 3 个非目标身份，没有第四个或允许身份到达。
- fail-fast 后 gateway 为 `exited`、7611 监听为 0。R2 观察器日志 SHA-256 为 `a3d760157211f1c2d0ddbd79154df6f1716575d3f421fed0972254907d0911d8`，状态快照 SHA-256 为 `f655f1b7419421c3655b4f19478214a4d90a274c5a0cd8e06c39763429d05fb4`。
- 重复相同窗口已不再提供新信息。继续前必须取得 terminal-01 设备侧连接尝试时间、目标地址/端口确认和错误结果，核对其当前出口地址是否仍在安全组范围；若厂商无法提供，需另行审批只保存已知身份 HMAC/摘要对比结果、不保存原始消息头的私密指纹诊断。

### 19:10 配置确认与维护复验 R3

- 设备截图确认服务器 `124.223.109.157`、TCP 端口 7611、协议 `JT808-2019`；此前把文字中的“端口 2019”当成真实配置的判断已撤回。设备侧记录在 19:10 执行过网络重连，但该动作早于 R3 gateway 在线窗口。
- R3 有效窗口为 `2026-08-25T11:26:04.049984758Z` 至 `2026-08-25T11:35:55.838013965Z`。维护策略拦截 332 次注册尝试，覆盖 4 个不同摘要身份并抑制 300 次重复审计；持久化 32 条 `TEMPORARILY_BLOCKED_FOR_MAINTENANCE`。
- 本窗口首次观察到 4 个不同来源 IP，证明第四台设备流量已到达云端；但 terminal-01 允许计数仍为 0，说明至少第四个线上 `(protocolVersion, terminalIdentity)` 仍不等于当前 terminal-01 私密资料计算的允许摘要。
- 另形成 47 条 `PROTOCOL_REJECTED + MESSAGE_NOT_ALLOWED_BEFORE_AUTHENTICATION`，表明存在终端在未完成注册/鉴权时发送其他消息。终端仍为 PENDING 4、注册/ACTIVE/鉴权 0，Outbox pending/dead-letter 为 0。
- fail-fast 后 gateway 为 `exited`、7611 为 0。R3 观察器日志 SHA-256 为 `97d1e31dced80e74d5a36c998d20209b89741b4d315cbe290eff27ee6b9e374b`，状态快照 SHA-256 为 `725a06f27fff4a4d8321cba3ad31a35108574a2a810c61a6b38359ae3bc1cbd9`。
- 现有聚合计数不能区分线上协议版本不符、消息头终端身份不符或设备使用未知身份。下一步需测试先行增加私密指纹比对：预装四台已知身份摘要，只输出 `terminal-01…04/UNKNOWN` 和协议匹配布尔值，不保存、不输出原始消息头、手机号或摘要。

### terminal-01 同步在线连接重试

- 现场先停留在连接重试界面；观察器以 900 秒准备时限登录并 READY 后，刷新私密维护工件并启动 gateway，再明确通知现场点击。最终环境文件 SHA-256 为 `06205be672f41da9c0624345c1452b2dd8990b2a5a9bdee5882f1d0908afb4a3`。
- gateway 在线窗口为 `2026-08-25T13:29:43.994101779Z` 至 `2026-08-25T13:37:14.944923932Z`。窗口因等待聊天点击确认持续约 451 秒，超过计划的 300 秒短窗口，但仍在 15 分钟维护 TTL 内；确认结果后立即停止，未延长 TTL。
- 点击后的窗口只有 1 个来源 IP和 1 个摘要身份。维护策略记录 28 次拦截、抑制 24 次重复审计；数据库形成 4 条 `REGISTERED/REJECTED + TEMPORARILY_BLOCKED_FOR_MAINTENANCE` 和 28 条 `PROTOCOL_REJECTED + MESSAGE_NOT_ALLOWED_BEFORE_AUTHENTICATION`。
- terminal-01 允许计数仍为 0，终端状态保持 PENDING 4、ACTIVE/注册/鉴权 0。该同步证据排除了“未点击重试”和“流量未到云端”，确认现场 terminal-01 的线上 `(protocolVersion, terminalIdentity)` 与后台允许摘要不一致。
- gateway 已停止为 `exited`，7611 为 0，Outbox pending/dead-letter 为 0。观察器日志 SHA-256 为 `2470a982aa8ded574431131df90ad28010960fe94c0e25e71f65d374d41642c3`，状态快照 SHA-256 为 `1dc9b52413e6c3a1129eb16bdb9b7fb3462a792ec8bed8a533bd16bb66bac432`。
- 不再重复相同白名单窗口。下一步必须执行私密身份指纹比对，分别判断消息头身份是否匹配 terminal-01 已知身份、协议版本是否匹配；只允许输出安全别名和布尔结果。

### 私密身份指纹诊断镜像与云端停止态部署

- 测试先行新增身份摘要与协议版本独立比对：仅映射 `terminal-01…04/UNKNOWN`，健康详情只公开 `identityMatch`、`protocolMatch` 和计数，不保存或输出原始消息头身份及摘要。四模块回归共 677 项，失败 0、错误 0；私有 PowerShell 27/27 通过。
- 本地发布 `p6-2-fingerprint-5199ac8` 的源码摘要为 `5199ac83c515be9d0be0f678789b4d78289ae0dbaabe85af84278af6e5515edf`；JAR SHA-256 为 `c5d52fb36b726b6797739ae1229d6af2d71f01b3b47c9e8709e0828d330f6cb9`。镜像 ID 为 `sha256:c09af6863de43ae36a093b7c7919e41f6eff6bfa9daa4ac40096b70a5e42394e`。
- 离线包 176,073,280 字节、SHA-256 `3e94ffe562dbb477865281d7774b29f694600983c1bd3c1b247abba471f9f755`；上传清单 SHA-256 `53461381c1484eb8637c0ae5953cdf76a793434b95d9f721a6ff3857246f6bc1`。云端 4 个载荷逐项 `OK` 后才将临时目录晋升为正式私密目录，目录 mode 700、文件均为 600。
- 云端解压出的 Docker tar SHA-256 为 `c78a488c7ee67e62c6a786c474851df8fccfa6f2f510d79818f34caf24b77424`；`docker load` 后 image ID、linux/amd64、UID 10001、发布/源码标签和镜像内维护环境变量数量 0 均通过。
- 私密停止态环境 SHA-256 为 `49835d606081315b0c442deb52f0966fae7bda025781c4e195d3901cc2a1d5fe`，注册 override SHA-256 为 `e4590a4676f0ce3235b17b0e6768c88d535e0ae3090e87ceca13140f9eaa27fc`。停止态环境使用 2000 年过期哨兵，不能直接启动；必须在真实窗口前重新生成 UTC + 15 分钟环境。
- `docker compose create --no-deps` 首次因 v5.5.0 不支持该参数而在解析阶段失败，回滚命令也因相同参数未执行；现场复核证明容器尚未改变。查阅实际帮助并执行 `up --dry-run --no-start --no-deps` 后，确认只计划重建 gateway；正式执行相同命令（去掉 dry-run）完成停止态重建。
- 终态 gateway 为 `created`、未运行，使用新镜像且继续挂载原 `jt-gateway-data-cloud-test-01` 卷；UID/GID 10001、只读根文件系统、`cap_drop=ALL`、no-new-privileges 均通过。API identity 镜像保持运行且 health `UP`。
- 7611 监听和连接均为 0；全部内部端口仍只绑定回环地址。终端数据库状态保持 PENDING 4、注册 0、鉴权 0。本阶段没有真实流量，也不构成注册、鉴权、位置或报警验收通过。

### 私密身份指纹首次真实窗口与无效裁决

- 观察器登录并进入 RUNNING 后刷新实时环境，gateway 在线窗口为 `2026-08-26T12:02:56Z` 至 `2026-08-26T12:16:52Z`。terminal-01 不提供手动连接重试入口，窗口使用持续上电设备的自动重连/周期流量。
- 脱敏快照为允许 0、拦截 336、4 个不同组合摘要、抑制审计 306、Outbox pending/dead-letter 0；唯一观察为 `Alias=UNKNOWN`、`identityMatch=false`、`protocolMatch=false`、尝试 336。最终持久审计为 `TEMPORARILY_BLOCKED_FOR_MAINTENANCE` 41、`MESSAGE_NOT_ALLOWED_BEFORE_AUTHENTICATION` 71。
- Java 字段追踪确认消息头 `terminalIdentity` 进入 `TerminalRegistrationIdentity.terminalNumber`，再映射为内部注册请求 `terminalPhone`；注册体 `terminalCode` 是独立字段。私密身份纠正也分别维护 `terminalPhone` 与 `terminalCode`。
- 本次指纹种子却由 `secret.terminal_id/terminalCode` 生成，未使用消息头对应的 `secret.terminal_phone`。因此该 `UNKNOWN` 是错误候选集的预期结果，不能用于判定设备身份或协议；本窗口作废，不计为真实终端失败。
- 按 fail-fast 门禁，gateway 已恢复为新镜像的 2000 年过期哨兵停止态：`created`、未运行、7611 为 0、原卷不变、API `UP`。终端保持 PENDING 4、注册/鉴权 0，观察器最终 TIMEOUT。
- 下一轮必须先写失败测试证明指纹配置取自 TerminalPhone 而不是 TerminalCode，再重新生成和上传私密指纹环境。该修正不涉及生产解析代码或镜像重建。

### TerminalPhone 指纹修正与无样本窗口

- 测试先行新增 provisioning-plan 指纹构建器：RED 为缺失函数 1 项失败；GREEN 证明消息头摘要使用 TerminalPhone，TerminalCode 变化不影响摘要、TerminalPhone 变化必须影响摘要，配置输出不含原始值。私有测试 35/35 通过。
- 新私密工件 `fingerprint-phone-3339c13` 的环境 SHA-256 为 `3339c13fa1cd231f12fc10c1e106a2d212ee189e97c9b890300b5ec3a3150de4`，原始值泄漏扫描 0；本地和云端 SHA 均为 2/2，云端目录 mode 700、文件均为 600。生产 JAR/镜像未改变。
- 观察器 RUNNING 后启动电话型指纹窗口，范围为 `2026-08-26T13:16:26Z` 至 `2026-08-26T13:22:38Z`，实时环境 SHA-256 `f20496a099173ad8e26e8ec3a603ef9e7ae65f3e38776ae3c5470a0e81e39db8`。
- gateway 进程和 liveness 正常；整体/readiness 503 为已知降级。但整个窗口 TCP established 0、维护允许/拦截/身份/抑制计数均为 0、指纹观察数组为空，数据库没有新增注册审计。
- 观察器 TIMEOUT，终端仍为 PENDING 4、注册/ACTIVE/鉴权 0。该窗口没有样本，不能形成身份或协议裁决，也不计为终端失败。
- 按 fail-fast 恢复电话型过期哨兵停止态：gateway `created`、未运行、7611 为 0、原卷和 API 保持。下一次开窗前必须确认可执行的设备重启/断电重启动作或厂商自动重连周期，不能继续假设存在界面重连按钮。

### 固定宽度 BCD 指纹与 API 电话合同裁决

- terminal-01 获准并完成设备重启。电话型摘要首次仍观察到 `UNKNOWN` 后，协议源码确认 2019 header 固定读取 10 字节 BCD 并保留 20 位字符串，而四台预置 TerminalPhone 为 12 位。
- 测试先行把 header 固定宽度写入工件构建器：2019 左补零到 20 位、2013 左补零到 12 位。RED 两个摘要断言失败；GREEN 后私有测试 35/35 通过。新工件 `fingerprint-bcd-b9d30ff` 的环境 SHA-256 为 `b9d30ff35dd3928ccc77b2748d32fc178f7dbf1185f27b41f6415e6d8beb7c67`，原始值匹配 0、云端 SHA 2/2。
- BCD 真实窗口为 `2026-08-26T14:23:51Z` 至 `2026-08-26T14:29:21Z`。维护策略允许 4、拦截 0；安全观察唯一条目为 `Alias=terminal-01`、`identityMatch=true`、`protocolMatch=true`、尝试 4，Outbox pending/dead-letter 均为 0。
- 该结果证明 terminal-01 实际消息头身份与协议版本符合后台资料的固定宽度 BCD 表示。但进入 API 的 4 次请求全部以 `TERMINAL_PHONE_MISMATCH` 拒绝，注册/鉴权仍为 0。
- API 当前先按 terminalCode 找到预置终端，再对存储 TerminalPhone 与 gateway 上报的 20 位 header identity 直接执行严格 `secureEquals`；未依据 protocolVersion 对两侧做固定宽度规范化。这是本轮唯一确定阻断点。
- fail-fast 后 gateway 已恢复 BCD 过期哨兵停止态，7611 为 0，原卷与 API 保持。推荐在 API 侧把两侧号码验证为数字并左补零到协议宽度后常量时间比较；不要在 gateway 去除全部前导零，以免改变合法身份语义。该变更需要新的 API RED/GREEN 和离线镜像部署授权。

### API 固定宽度电话规范化部署复验（2026-08-27）

- 修复位置限定在 API 注册验证的电话比较边界。2019/2013 分别按 20/12 位数字 BCD 固定宽度左补零后常量时间比较；超宽数字拒绝，非数字历史兼容值仍精确比较。gateway 解码、终端资料、数据库和附件路径均未修改。
- RED 证明 2019 与 2013 固定宽度场景在旧实现中失败，同时错误号码、非 BCD 和超宽值仍被拒绝；GREEN 后定向 3/3。部署后新鲜回归为 680 项、失败 0、错误 0、跳过 41。
- 发布镜像为 `drt-ops-jt-cloud-api:p6-2-phone-2efdf6a`，image ID `sha256:c64892d3a90ce6e3de6d33b11dc62e797c9a6b9ce821b528aeeacbb7487903ba`；镜像内 JAR SHA-256 `28b3cd032a5345c75e481d23d0882b029b12923a792458d6ca3050ed965cee0a`。
- 离线包 SHA-256 `8b35e94c22118588b77ba1648e8896a8f76f474907f4a2b4cdee64790470b669`。本地、上传后、解压后和 `docker load` 后的校验链均通过；云端目录 mode 700、文件 mode 600。
- API 使用原实际 Compose 链加新 override 进行 `--no-deps` 单服务重建，终态镜像 ID 精确匹配、`running/healthy`、restart count 0、根健康端点 200/UP、部署后 ERROR/FATAL 日志计数 0。
- `/actuator/health/liveness` 与 `/readiness` 的未认证 401 符合既有安全配置只公开根健康端点的行为；Compose 也仅使用根健康端点，因此不影响本次 API 部署门禁。
- 本地 SSH 隧道退出导致第一次观察器 pre-auth 失败；恢复 localhost-only 15173/18080 转发后，pre-auth 门禁通过。gateway 始终保持 `created`、未运行，7611 监听为 0。
- 本节仅确认 API 修复已部署并满足停止态复验，不代表真实 terminal-01 已完成注册。真实注册、鉴权、位置和报警仍需新的实时窗口与 gateway 启动授权。

### API 电话修复后的真实 terminal-01 窗口（2026-08-27）

- 观察器在四台均 PENDING、注册/ACTIVE/鉴权 0 的基线进入 RUNNING；实时 BCD 工件 SHA-256 为 `2e45ed4c4e34d2c11c7975aa70ee000a356a2b34cbf15feffc5cabae69aee3a6`，有效期 `2026-08-26T21:47:59Z` 至 `22:02:59Z`。
- gateway 启动门禁通过：镜像 `p6-2-fingerprint-5199ac8`、healthy、UID/GID 10001、只读、`cap_drop=ALL`、no-new-privileges，7611 唯一监听为 `0.0.0.0:7611`；API 新电话修复镜像继续健康。
- 真实窗口为 `2026-08-26T21:49:36Z` 至 `21:56:20Z`。允许 30、拦截 0，唯一安全指纹观察为 `Alias=terminal-01`、`identityMatch=true`、`protocolMatch=true`、尝试 30，Outbox 无积压或死信。
- 30 次注册全部不再出现 `TERMINAL_PHONE_MISMATCH`，证明 API 固定宽度电话规范化在真实流量中生效；但全部在下一字段以 `MODEL_MISMATCH` 拒绝，注册/ACTIVE/鉴权仍为 0。
- 2019 注册解析按 30 字节 ASCII 读取型号，在 NUL 截断后裁剪空白；后台期望型号为 8 个 ASCII 字节。因审计只保留原因码，本窗口不能推导设备实际型号，不允许据此猜测修改后台或放宽合同。
- fail-fast 后 gateway 已恢复 2000 年过期哨兵停止态，`created`、未运行，7611 监听/连接均为 0，API `UP`，观察器停止。冻结快照 SHA-256 为 `d87d1a1ced72eb19134a773aa59915a57c4cfb1adadf11da7cba0c32ca32d4ca`。
- 私密长度诊断曾误输出 intake 的标识类 alias；未包含密码、鉴权码、服务凭证、token 或原始报文，公开报告不记录该值。后续输出固定使用安全别名。
- 本轮结论为真实流量“电话合同通过、型号合同失败”，不是 P6-2 验收通过。合并保持阻塞，等待准确设备型号或另行批准的私密型号诊断。

### terminal-01 型号纠正与车辆标识阻断（2026-08-27）

- terminal-01 真实型号写入私密 CSV 后，terminal-02～04 从云端当前值只读回填。两份本地备份可恢复，最终 CSV SHA-256 `697a8eb79c61bb6e4adffa06af3300b3c9f935da0d28b021e4532e3afd225e15`；公开输出不包含型号值。
- DryRun 最终严格为 terminal-01=`model`、terminal-02～04=`NONE`。Apply 前备份 SHA-256 `f13066d6b9f2d77595b4da815e29e06f748b47c1c424f0873ac79785e25f0815`、186 个归档条目；Apply 仅修改 terminal-01 型号，版本 3→4，审计 1，仍为 PENDING、未注册、未鉴权、活动绑定不变。
- 新工件 SHA-256 `66995b3a5b422a61b36ac8e5474ea671f26de3faeabbddc1047855eaab925a6c`，有效期 `2026-08-27T00:21:40Z` 至 `00:36:40Z`。gateway 于 `00:21:57Z` 健康启动并通过全部安全门禁。
- terminal-01 允许 24、身份/协议均匹配、尝试 24；不再出现电话或型号拒绝，但全部以 `VEHICLE_IDENTIFIER_MISMATCH` 拒绝，注册/ACTIVE/鉴权 0，Outbox 无积压或死信。
- 非目标未知身份拦截 210，并形成 17 条 `TEMPORARILY_BLOCKED_FOR_MAINTENANCE` 审计；未改变终端业务状态。
- `00:27:21Z` 已 fail-fast 恢复过期哨兵停止态，7611 监听/连接 0、API `UP`、观察器停止。冻结快照 SHA-256 `da3c05ceaa5f982c9dcda84e167b6407be4cdb5d9d63e366d72a2a66cc7e9313`。
- 结论为真实电话、型号合同通过，车辆标识合同失败。P6-2 及合并继续阻塞，等待准确设备车辆标识或经批准的私密取值诊断。
