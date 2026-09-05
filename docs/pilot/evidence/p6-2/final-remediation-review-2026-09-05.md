# R1–R6 最终独立复核记录（2026-09-05）

最终结论：**APPROVED，仅限本地整改快照**。已验证代码/测试提交为 `cfd18b330b79b597ff73115305cdcb056a6f4a1a`。下文完整保留中间 NOT_APPROVED、真实失败与修复后最终裁决；最新结论及提交映射位于文末。

---

# R1–R6 全分支独立复核

> 最新结论：本文末尾“最终裁决”已追加 **APPROVED（仅本地 R1–R6 整改快照）**。下方 interim NOT_APPROVED 与真实失败历史完整保留，不代表最终状态；cloud/真实窗口仍未获准入。

## 当前裁决：NOT_APPROVED（interim）

日期：2026-09-05。此为真实中间复核点，不是最终通过报告。代码阶段发现已一次性交给控制器统一修复；后续仅复核修复差异及新鲜整体门禁，并在本文追加最终结论。

- BASE：`39494f3e3e459a7c5ac842f73ad99967baf3119b`。
- 本次广域源码审阅 HEAD：`4518c675a7db5b6a783ad74409fb51006bd8118c`。
- 工作树：`D:/codex-projects/.worktrees/p6-2-composite-onboard-system`。
- 本轮新增发现：Critical **0**、Important **2**、Minor **0**；两项 Important 尚未复核关闭。
- 整体门禁另有：完整 Java 未通过且 API 尚未执行；最终外部迁移尚无成功结果。
- 入口及静态核对时暂存区为空，仅根 `progress.md` 为控制器持续记录而 dirty。此边界不被当作生产未提交；它必须进入后续独立文档收口，不得混入测试整改提交。
- 未运行任何测试，未修改源码、测试、索引、HEAD 或迁移，未读取真实 private 资料，未使用子代理。唯一写入为本文。

依据为当前规格、2026-09-02 整改计划全局约束、SDD ledger 的明确 rulings，以及当前源码/测试/报告；没有用单任务 APPROVED 代替全分支审阅。审阅遵循 requesting-code-review/code-reviewer 的只读、分级和可复现场景要求。78 个变更文件按数据流分段检查，重点读取生产链及承载 I1–I7 的行为测试；不声称逐字阅读全部计划示例或完整测试日志。

## 优点与 I1–I7 数据流核对

下表的“代码阶段未确认新增缺陷”不等于整体门禁通过。

| 不变量 | 本次核对的数据流与证据 | 代码阶段结论 |
| --- | --- | --- |
| I1 档案、能力与解码权威 | `OnboardRegistrationResolver.java:314` 从系统、terminal、membership、role、profile、capability 加锁重查；`:466`/`:490` 只由活动角色与 VERIFIED ADAS/DMS 形成 enabled modules。API v2 context 经 `GatewayRegistryController` 展平/嵌套输出，再由 `OperationsTerminalRegistryClient.java:315` 校验一致性、`TerminalSessionContext` 校验枚举/模块、handler 在 ACK/touch 前校验 transport，`ProtocolModuleRegistry` 再次门禁，`ActiveSafetyAlarmRouter` 使用 session profile 解码。 | 生产链未确认新增 C/I；测试夹具协议不一致见 I-F1。未把完整 GB 业务/媒体视为要求补做的功能。 |
| I2 报警位置 provenance 与生命周期 | `JpaAlarmStore.java:37` 同时核对 accepted position receipt、GPS 事件、terminal/system/vehicle；`:55` 起以关联位置事件 API recordedAt 核验成员/角色/profile/capability 历史有效区间，并先锁 system/terminal。`VehicleAlarmIngressService` 在授权后处理幂等 START/END；查询以 onboardSystemId 缩小生命周期，跨 system 全局未结束冲突返回稳定业务拒绝，outbox 与事实同事务。检查了 external 测试的并发 START/END、回滚、角色撤销、同车跨系统历史、缺 DMS 和全局 open 冲突。 | 未确认新增 C/I；不能将单次入库或同名测试替代外部 PostgreSQL 证据。 |
| I3 当前物理 terminal lease | `JtTerminalSessionLeaseService.java:42` 先锁 terminal，首次创建与 takeover 使用同一 terminal-keyed 行；renew/release 检查 token version 与完整 owner/generation，180s 时间由 API Clock 生成。`SessionLeaseReporter` 使用有界 executor、30s attempt throttle、owner match 和事件循环收敛；handler 的鉴权失败/身份不符/接管审计异常清理已检查。readiness、terminal detail、onboard device view 均读 dedicated lease，不用历史鉴权或 lastSeen 冒充在线。 | 生产链未确认新增 C/I；双会话测试固定过期 grant 见 I-F2。API 不可达时 release 失败依赖 TTL 是明确残余，不是“立即全局断开”承诺。 |
| I4 双时钟与恢复 | `LocationSourceArbitrator.java:27` 起先检查同物理 source terminal cursor 和 applied snapshot gateway 顺序，再处理质量；primary valid gateway 取同域 max，streak 只由严格递增 primary terminal time 推动，backup stale 比较 gateway 阈值 `max(30s,2×interval)`。GPS quality 和 implied speed 同源使用 terminal 时钟、跨源使用 gateway 时钟。检查 ±29s、30s/2 interval 边界、迟到坏质量保持状态、reload 和恢复 gateway 倒序修复测试。 | 未确认新增 C/I。冻结计划明确要求 in-order invalid 推进 source cursor；本次没有擅自更改该恢复合同。 |
| I5 配置/运行态/位置原子性 | `OnboardSystemConfigurationService.java:233` 获取 system 后统一 terminal(UUID 字符串排序)→membership→role→profile→capability，再获取 runtime/vehicle；apply、retire、replacement、legacy bind 都协调运行态。`:663` 只保留仍合法的 source；失效 source 重置 cursor/streak 并只把对应 system 的 GPS 快照标 stale。`Vehicle.java:262` 从事件整体写入坐标、时钟、terminal/system provenance；旧系统位置不被新系统认领。GPS 在首次 INSERT 前决定 snapshotApplied，避免 immutable-fact trigger 冲突。 | 未确认新增 C/I；检查了两方向 owner-after-vehicle-lock/contender-before-after-system-lock、历史有效区间与 PostgreSQL 外部 fixture 保护，真实最终门禁仍待完成。 |
| I6 新审计脱敏、历史不清洗 | `TerminalManagementService.java:567` replacement metadata 为固定 7 键：两设备安全别名、角色数量/角色名、两 token version、固定 reasonCode；不保留真实终端身份、原 UUID、凭据或摘要原值。当前范围无 audit_logs 历史清洗；V21 也不改历史 audit。 | 未确认新增 C/I。新写安全不意味着历史敏感审计已处置，历史清理未获授权。 |
| I7 分页、权限、异步隔离 | `OnboardSystemManagementPage.vue:69` 使用独立 list token；`:126` 翻页立即清除草稿并作废旧详情，详情另有 selection token；空页最多回退一次；apply 开始作废旧列表请求并锁定切换。使用后端 totalElements/totalPages，检查第 21 套、旧成功/失败、空集合归零、应用期间竞态和草稿版本失效测试。路由保持 TERMINAL_READ，管理控件仍要求 TERMINAL_MANAGE。 | 未确认新增 C/I；本轮 frontend 317/317、typecheck/build exit 0。 |

注：表中 Java 文件 basename 均指相应 `apps/api/src/main/java/com/idavy/drtops/domain/...` 或 `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/...` 源文件，不是测试替身。

## Important（本轮统一修复清单）

### I-F1：共享 E2E fixture 的 transportProfile 与实际报文协议不同

- 文件：`apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/e2e/GatewayTestRig.java:247`。
- 消费证据：`apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/e2e/GatewayOperationsFlowIntegrationTest.java:122` 明确创建 JT808_2013 终端，`:236` 的原始帧也为 JT808_2013；同类 DSL 场景沿用 2013 实际报文。
- 问题：新 v2 fixture 把权威 `transportProfile` 固定为 JT808_2019。真实 handler 按要求 fail-close，不发成功注册 ACK。现有 full-journey、无 VIDEO 角色拒绝、畸形 attachment metadata 拒绝和 control plane 共用 registry 的业务断言因此不再被执行。
- 可复现场景：在当前 HEAD 运行 `GatewayOperationsFlowIntegrationTest`；控制器首轮完整 Java 已实测该类 4 项失败，三个为注册无应答、另一个会话为空。审阅者未另跑测试。
- 被破坏的不变量：测试声明的合法协议连接必须真实走到被测业务路径；不能因为生产新增正确的 fail-closed 而让旧安全保护回归失效。
- 最小建议：使 fixture 档案显式匹配场景所发协议，或由构造参数显式指定协议；保留 v2、角色/模块限制和 lease owner。不得为了迁就测试在生产增加协议猜测、兼容默认值或绕过 transport gate。
- 验证要求：先保存当前 4 项 RED 原因，再定向 GREEN；断言实际 ACK/会话/安全拒绝与原有业务结果，不仅令测试不抛异常；最后完整 reactor。

### I-F2：双物理会话集成测试的 lease 已过期，且与服务器使用不同时间基准

- 文件：`apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/netty/JtGatewayServerIntegrationTest.java:500`；同文件 `:495` 的 reporter 使用 `Clock.systemUTC()`。
- 问题：`approvedAuthentication` 用固定 `2026-08-21T10:00:00Z` 生成只有 180s 的 lease，而实际 server/reporter 使用运行时 UTC。当前日期已超过该窗口；handler 鉴权后 `renewIfDue` 正确安排关闭，测试可能短暂看到 registry claim，随后读取到 CLOSED。
- 可复现场景：当前 HEAD 运行 `keepsTwoPhysicalSessionsForOneVehicleAndTakesOverOnlyTheSameTerminal`，控制器本轮观察到期望 AUTHENTICATED、实际 CLOSED。该问题不是应通过延长 await 来处理的负载波动。
- 被破坏的不变量：一台物理终端重连只能替换自身，不能影响同车另一物理终端；测试必须先提供真实 live 的两条 lease 才能证明接管隔离。
- 最小建议：测试服务器、reporter 和 grant 使用同一显式可控 Clock；若现有服务器构造不支持注入，则 grant 使用与运行时一致的当前 Clock 构造，不能固定已过期日期。不得关闭生产 expiry 校验。
- 验证要求：保存当前 RED，修复后定向证明两会话保持 AUTHENTICATED、只替换同 terminal、另一 channel 仍 active，再做完整 reactor。

## Critical / Minor

- 本轮未确认 Critical。
- 本轮没有新增 Minor。历史 Task10 的 ByteBuf 释放时机/runner 首失败诊断属于既知 Minor，本次未收到新的针对性关闭证据，不能以总用例通过替代其关闭，也不把它们重复计为本轮新增发现。

## 迁移与范围门禁

- V19/V20 的 BASE..HEAD diff 实测为空。
- V21 当前 SHA-256：`EC6FEC3C8E38B9B4A48054E89220434B4B606F69147C01234E337E9758E33FA3`，与 R1 冻结值一致。
- V21 新增 dedicated lease 表、三个 runtime clock 列、nullable alarm system provenance 和索引；无 DROP/RENAME、无猜测历史 alarm system、无 audit_logs UPDATE。
- vehicle provenance 回填绑定 exact current event id、vehicle id、terminal id、非空 event system，且 system.vehicleId 必须相符；保留已有非空 provenance，不把另一旧事件当作当前快照来源。
- BASE..HEAD `git diff --check` exit 0。变更范围为 78 文件；没有新增 cloud/真实验收/private 数据文件、附件媒体或 full GB 业务实现。
- V20 后不允许回退至不理解多设备的旧 API/gateway；V21 保留追加 schema，不做破坏性 down migration。本次没有执行任何回滚。

## 新鲜验证证据（interim）

| 门禁 | 当前已读证据 | 裁决 |
| --- | --- | --- |
| Java 完整 reactor | `final-java-report.md`：2026-09-05 04:29:28Z 至 04:34:25Z，exit 1。协议48、模拟器24、gateway220，共292执行、287通过、5 failure、0 error/skip；API 未执行。 | **未通过**；不能用旧任务 API 计数补足。5 failures 收敛为上述两个 fixture 根因。 |
| 外部迁移首轮 | `final-external-report.md`：新 loopback PG 已 ready，但 pg_ctl 管道句柄造成 helper 等 EOF；Maven 未启动、无 fresh XML，实例与端口清理已记录。 | **未完成**；这是测试前基础设施失败，不是迁移代码失败。第二轮结果待补。 |
| 前端 | `final-frontend-report.md`：HEAD 4518c67，54 files、317/317、0 failed/pending；typecheck/build exit 0，191 build modules。 | 本轮已通过；既有大 chunk 提示不列阻断。 |
| private 既有入口 | 更正后的 `final-private-report.md`：迁移43/43、Task12 6/6，exit 0；安全输出扫描无命中。 | 仅这两个入口已通过，不等于后述历史门禁关闭。 |

证据报告由本轮独立执行者/控制器生成；本审阅者只读取报告及对应源码，不声称亲自执行测试。private 迁移套件的准确范围是“合成测试 + 既有私有资料只读校验”，不是全合成；本审阅者没有读取其真实资料。

## 历史残余与不授权事项

1. Task11 runner 在 safe try/catch 外 dot-source library；缺失、ACL 不可读、语法错误三种子进程 stderr 安全收敛门禁没有关闭。既有 invalid JSON/HTTP 失败测试发生于成功 load 之后，不能替代。
2. Task12 未有实际验收结果 `count == 4` helper/测试；名称含 four-way 的 seed SQL 用例实际用 2 条 fixture 检查 2/2/2，不是四项验收计数证据。
3. Task12 resource cleanup selector 和 match-count 0/>1/越界 fail-closed 没有对应实现/测试；资源命名字符串检查、迁移 selector XOR 不等价于这个门禁。
4. cloud V20 的全库只读盘点与准入仍是 **NO-GO**。本地测试数据库成功不授予操作业务数据库、清洗历史审计或真实设备的权限。
5. 附件媒体/full GB/cloud 不属于此次整改交付，不要求补做。但 I-F1 涉及已有安全信令测试不得被回归削弱，所以应修复既有测试合同。
6. push、merge、PR、部署、真实流量、真实设备、外部账号与私密数据库操作均未授权。

## 下一步与最终批准条件

控制器已将 I-F1/I-F2 合并为一次修复波。修复只允许显式测试合同适配，不弱化 v2、transport、role、lease/expiry 或旧安全断言。最终需提交定向 RED/GREEN、修复 diff、fresh external migration 与完整 Java（包括 API）报告，并核验最终源码与报告对应版本。

在上述证据齐备前，结论持续为 **NOT_APPROVED**。即便最终本地整改获得 APPROVED，也必须保留上述 cloud/真实窗口未授权及历史准入限制，不把局部批准解释为部署批准。

---

## 追加：外部真实 RED 与四文件 scoped 复核（2026-09-05）

### 新增外部验证阻断 I-F3：当前 JPA 用例误用 V19-only schema

- 等级：Important。文件：`apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java:1029`、`:1633`、`:1698`（修复前位置）。
- 可复现场景：第二次新鲜 native PostgreSQL/PostGIS 外部门禁已真实进入 Maven，59 个 composite tests 中出现 3 errors，均为这三个当前 JPA round-trip/mapping/version-isolation 用例只迁 V19，却加载已包含 V21 cursor 列的当前 runtime entity。准确错误为 `column backup_terminal_cursor_at of relation onboard_system_runtime_state does not exist`。
- 不变量：历史 V19 schema/catalog 合同和“当前 JPA 在当前 schema 上运行”的合同必须分开；不得把当前实体的缺列错误当作环境故障，也不得改写冻结迁移或放宽 JPA mapping。
- 最小建议：只将这三个当前 JPA 用例的隔离 schema 准备切换为 V19→满足 V20 合同的测试 fixture→V20→V21，明确断言当前 Flyway head；保留全部原始行为断言与其他 V19 历史测试。
- 第二次外部运行 Maven exit 1、双 suite 62 total/3 errors/1 独立 Docker conditional skip；实例 stop 0、端口关闭、数据目录删除已记录。此为测试合同失败，不是首轮 pg_ctl 基础设施问题。

### 冻结修复包与代码裁定

- 当前 Git HEAD 仍为 `4518c675a7db5b6a783ad74409fb51006bd8118c`；审阅对象是该 HEAD 加四个未提交测试修复，不声称为纯净 HEAD。
- `final-fix-review-package.diff` SHA-256 已独立核对为 `D57027F0B4972F43D3CE0504D601673A33E03F0C1D2218B079B609D083925F87`。
- 四文件仅为 `P6CompositeOnboardSystemMigrationTest.java`、`GatewayOperationsFlowIntegrationTest.java`、`GatewayTestRig.java`、`JtGatewayServerIntegrationTest.java`；另有控制器 `progress.md` 持续记录。无 production/migration 修改，当前 diff-check exit 0。
- scoped 复核完整读取四文件差异及 `final-fix-report.md`，没有重跑测试或再次广域扫描。

| Finding | scoped 裁定 | 依据 |
| --- | --- | --- |
| I-F1 协议夹具不匹配 | **ADDRESSED** | rig 档案改为实际使用的 JT808_2013；真实鉴权 session 新增接受 2013、拒绝 2019 的正反断言。v2/profile/roles/lease 与生产 fail-closed 均未放宽，原有安全信令和完整旅程断言保留。 |
| I-F2 固定历史 lease | **ADDRESSED** | helper/reporter 使用 runtime UTC 即时 grant；保留精确 180s 有限 TTL、正 generation、新 connection owner，以及原两终端保持在线/仅同 terminal 被替换断言。未关闭 expiry 或用无限 lease 掩盖失败。 |
| I-F3 当前 JPA / V19 schema 不一致 | **ADDRESSED** | 仅三个当前 JPA 用例使用 `migrateCurrentJpaSchema`，复用既有 contract-ready V20 fixture，再迁 V21 并断言 head=21。两个误导性 v19 测试名改为 current，原 warning/mapping/version 独立行为与其他历史迁移用例未削弱。 |

scoped 复核新增 Critical/Important/Minor = **0/0/0**。原三项已在代码层关闭；整体 APPROVED 仍等待完整 Java 结果。

### 已核对 post-fix 新鲜证据

- Gateway 定向：两类 16/16、0 failure/error/skip，Maven exit 0（见 `final-fix-report.md`）。
- 外部完整双 suite：2026-09-05T04:53:27.8702214Z 至 04:57:56.4412202Z；PostgreSQL 17.9 / PostGIS 3.6.1，Maven exit 0。Composite 59/59、DatabaseMigration 3 total/2 passed/1 conditional skip，合计 62 total/61 实际通过/1 skip、0 failure/error。
- 唯一外部 skip 为独立 Docker/Testcontainers core smoke 的显式条件未开启；同类 external composite path 在本轮 native PostgreSQL 上实际执行并通过。不得写成 62 个全部实际执行。
- post-fix 实例 stop 0、端口关闭、自有进程不存在、精确数据目录/合成凭据清理完成，外部执行者未触碰真实数据库。
- 此时完整 Java 仍运行；之前 frontend317/317、typecheck/build0 与 private43+6 证据保留，private 三项历史残余仍未关闭。

---

## 最终裁决：APPROVED（仅本地 R1–R6 整改快照）

最终证据复核日期：2026-09-05。本结论覆盖 BASE `39494f3e3e459a7c5ac842f73ad99967baf3119b` 至 HEAD `4518c675a7db5b6a783ad74409fb51006bd8118c` 的 R1–R6 变更，加四文件统一测试修复包 `D57027F0B4972F43D3CE0504D601673A33E03F0C1D2218B079B609D083925F87`。四文件在审阅时尚未提交；最终提交 SHA 需由控制器提交后补充映射，不能把上述 HEAD 单独写作已修复版本。

### Findings 最终状态

- I-F1、I-F2、I-F3 全部 **ADDRESSED**。
- 当前整改范围未关闭 Critical **0**、Important **0**；本轮新增 Minor **0**。
- 原 interim 的 2 Important 和后来外部真实验证发现的第 3 项均已保存，未覆盖 RED 历史。
- 完整广域审阅加本轮 scoped 修复复核，未确认新的生产安全、原子性或测试合同缺陷。四文件修复不变更生产代码，不削弱 transport/role/v2/lease 检查或冻结迁移。

### 最终新鲜门禁

| 门禁 | 核对结果 | 限制 |
| --- | --- | --- |
| 完整 Java reactor | 2026-09-05T04:59:40.4612861Z → 05:11:48.7105541Z；Maven exit 0。126 source 测试类对应126 fresh XML，missingSourceReports=0；1042 total = **948实际通过 + 94条件跳过**，failure/error=0/0。 | 不是1042项全部实际通过；没有排除首轮失败用例或开启 failure-ignore。 |
| Java 分模块 | jt-protocol48/48、gateway220/220、simulator24/24；API750 total/656 passed/94 skipped。 | API之前未执行的缺口已补齐；首轮gateway5项失败在完整运行中真实通过。 |
| 独立外部迁移 | PostgreSQL17.9/PostGIS3.6.1；双suite62 total/**61实际通过+1独立Docker条件跳过**，failure/error=0/0，Maven exit0。 | 独立计数；与reactor有重叠，不相加宣称唯一用例总数。三个currentJPA用例在真实PG上通过，migration59/59零skip。 |
| 前端 | 54 files、317/317、0failed/pending；typecheck/build exit0。 | 四文件修复均属Java测试，未改变已验证前端源码。 |
| private既有入口 | 迁移43/43、Task12 6/6，均exit0。 | 仅既有入口通过；Task11/12历史三项门禁不因此关闭。 |
| 静态与快照 | 最终读取的修复包hash仍为D570…F87；diff-check exit0；生产目录未提交diff为空；V21 hash仍为EC6F…FA3，V19/V20不可变边界未变化。status仅四文件修复+控制器progress。 | 审阅者未修改源码、测试、索引或HEAD。精确测试提交、独立文档提交由控制器执行。 |
| 清理 | 外部最终实例stop0、端口关闭、精确目录与合成凭据清理；控制器最终静态记录Java/taskNode/taskPGdata=0。 | 不对其他进程、数据库或资源作清理授权。 |

已读取 `final-java-report.md` 最终追加节、`.tmp/final-regression-20260905/java-postfix-summary.json` 的全部安全计数/失败/跳过条目、post-fix external 报告以及前端/private报告。机器摘要 `failedCases=[]`、`missingSourceReports=[]`。本审阅者严格遵守只读不跑测试分工：结论基于本轮新鲜独立执行证据与源码复核，不声称亲自重跑。

94 条 Java skip 来自显式外部数据库、Docker/Testcontainers、容量等条件：P6Composite43、DatabaseMigration2、PostgisVehicleAlarmIngress11、PostgisTaskLocationTransaction9，余29分布于13个专用类。机器摘要中部分 assumption reason 文本为空，并不意味着这些用例执行过；其条件和专用suite身份必须与已保存源码证据一并解释。外部62项只补足该外部迁移门禁，**不能外推全部可选PostGIS/报警并发/位置并发/容量测试本轮都已执行**。R2/R4原有独立外部行为证据维持原本版本/执行条件边界，不冒充此次全量运行结果。

### 批准范围与持续限制

**APPROVED** 的含义仅是：本次约定的 R1–R6 本地整改、统一测试合同修复、完整默认Java回归、独立外部迁移、前端与既有private入口门禁已满足，可由控制器继续精确本地提交和独立文档收口。

以下限制保持有效，不影响本地整改审查结论，也绝非已关闭项：

- Task10两项既知Minor：异常路径ByteBuf释放时机；instance runner首个失败步骤/原因诊断。本轮未精确复验，均标为“未重新关闭，真实窗口前仍需验证”，不得从R1–R6局部整改结论中消失。
- Task11 library 在safe catch外加载，missing/ACL/syntax三类安全stderr门禁仍未关闭。
- Task12验收`count==4`、资源cleanup selector及match-count fail-closed仍未关闭。
- 历史敏感audit未清洗；无历史处置授权。
- cloud V20全库盘点及cloud/真实设备/真实流量准入仍 **NO-GO**。不授权部署、push、merge、PR、真实账号/数据库操作。
- 附件媒体及完整GB业务不属于此次交付；已有安全信令保护不能因范围外实现未开展而被移除或放宽。

最终评估：**本地整改审查通过；真实环境准入未通过且未授权。** 后续若改变任何生产文件、迁移或超出四文件修复包的测试合同，应重新提供相应差异与新鲜证据，不能沿用本次快照批准。

## 控制器提交映射（复核后）

- 已审核的四文件修复现已精确提交为 `cfd18b330b79b597ff73115305cdcb056a6f4a1a`，parent为R6提交 `4518c675a7db5b6a783ad74409fb51006bd8118c`，4文件+65/-13。
- 提交后重新生成`git diff -U12 HEAD^ HEAD`，SHA-256仍为 `D57027F0B4972F43D3CE0504D601673A33E03F0C1D2218B079B609D083925F87`，与测试/独立复核包完全一致；commit diff-check=0。
- 因此最终被批准的代码与测试版本可精确引用 `cfd18b330b79b597ff73115305cdcb056a6f4a1a`，无需将旧R6单独SHA误作完整修复版本。后续仅为独立文档提交；不改变本复核范围与真实环境NO-GO边界。
