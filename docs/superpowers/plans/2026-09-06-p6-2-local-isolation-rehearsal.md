# P6-2 本地复合车载系统隔离演练实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development。runner先测试和独立复核，再由控制器执行真实本地演练。

**Goal:** 在不使用Docker、真实private或云端的情况下，以PG17/PostGIS、最新API/gateway和四个真实loopback TCP连接完成2+1+1复合车载系统演练。
**Architecture:** 版本化PowerShell runner负责本轮资源所有权、迁移/服务生命周期和安全证据；两个外部Java helper负责Flyway分阶段迁移及注册→管理API激活→鉴权→lease读回。Task12仅在上游真实wire/API/DB断言后校验四项结构。
**Tech Stack:** Windows PowerShell 5.1、Java21、Maven3.9.11、PostgreSQL17.9/PostGIS、Spring Boot、Netty/JT808。
**Spec:** `.superpowers/sdd/2026-09-05-p6-2-ops-task10-exception-gates/isolated-rehearsal-preflight.md`（只读预检）及Task11/Task12安全手册。

## Global Constraints

- 工作树 `D:\codex-projects\.worktrees\p6-2-composite-onboard-system`，入口HEAD `f1d3b42dac7c945fc9b9f5aa7f989790909a1d2e`，分支 `codex/p6-2-ops-safety-gates`。
- Task1A安全基础提交为 `11ced71d581c652c3c111a3b4e025b9c40dc5167`；Task1B/1C从该提交继续，最终Plan按运行时当前干净HEAD动态绑定，不能硬编码已成为父节点的旧HEAD。
- 不修改业务API/gateway/simulator代码、V1–V21、Task10/11/12冻结工件或真实资料；仅新增演练runner/helper/测试/文档。
- Docker库存有bind，禁止调用Task12 ApplyCleanup、停止/修改现有容器或伪称Docker清理通过；只用本轮native PG。
- 所有服务只绑定127.0.0.1随机端口；所有数据为固定前缀合成身份。无附件消息、真实终端、云端、外部HTTP或真实凭据。
- 默认Mode=Plan且无副作用；Execute必须显式提供本轮确认token。任何检查失败立即停止，不继续业务步骤、不自动重试破坏性动作。
- secrets只放进本轮进程环境/受限临时文件，不输出到控制台、公开报告、命令摘要、JSON结果或完整子进程参数。安全报告仅别名、计数、状态、版本/hash。
- 原生进程调用必须有界；Java服务Start-Process/ProcessStartInfo隐藏窗口并记录PID/StartTime/ExecutablePath。本轮外进程不按name/port停止。
- runner只支持已验证的Windows PowerShell 5.1；其他宿主在读取receipt或创建资源前固定拒绝，不自动兼容PowerShell 7的JSON日期类型变化。
- 清理精确验证run root、owner marker、PID+StartTime+ExecutablePath、PG data/postmaster；无法证明停止则保留目录并报告，不递归删除。删除只在验证后的本轮run子目录。

### Task 1A: 建立资源安全库和固定Flyway helper

**Files:**
- Create: `tools/ops-safety/p6-composite-isolation-lib.ps1`
- Create: `tools/ops-safety/fixtures/P6CompositeFlywayTool.java`
- Create: `tools/ops-safety/tests/p6-composite-isolation-safety.tests.ps1`

**Interfaces:**
- 安全库生成和校验Plan receipt、run路径、四个不同loopback端口、随机nonce与进程身份；不接受现有目录、PID、Docker或远端地址。
- Flyway helper只从环境读JDBC/user/password/action，固定migration location和target19/20/21；V19后仅更新V2两个固定demo UUID且rowcount恰好2，再V20/V21 validate。没有任意SQL参数。

- [ ] **Step 1: 写Plan和安全生命周期RED**

测试真实执行runner副本与假native/Java进程，断言：默认Plan零创建；非法token/非目标HEAD/预存run目录/路径逃逸/非loopback/已有端口/启动失败/超时均固定安全码且无后续动作；只回收持有进程，失败后部分状态安全报告；输出/日志无合成secret sentinel或路径泄漏。辅助设施失败不能冒充产品RED。

- [ ] **Step 2: 写Flyway helper合同测试和GREEN**

helper action白名单固定为MIGRATE_19、PREPARE_V20、MIGRATE_20、MIGRATE_21、VALIDATE；PREPARE_V20事务检查空terminal/system、两个固定V2 demo为dispatchable，精确update2，其他形状拒绝/回滚。测试通过合成数据库适配层或package-private JDBC seam观察真实事务/SQL行为，不增加任意SQL接口；最终实际演练再由PG真实验证。

- [ ] **Step 3: 验证与Task 1A复核**

运行安全库及Flyway helper定向测试、PowerShell Parser、secret sentinel扫描、`git diff --check`。不创建真实PG、不执行任何Java服务；报告精确RED/GREEN/exit/计数/临时资源0。控制器独立复核后再进入1B。

### Task 1B: 建立wire helper及合同测试

**Files:**
- Create: `tools/ops-safety/fixtures/P6CompositeWireHarness.java`
- Create: `tools/ops-safety/fixtures/P6CompositeWireHarnessContractTest.java`
- Modify: `tools/ops-safety/tests/p6-composite-isolation-safety.tests.ps1`

**Interfaces:**
- Wire helper从环境读API/gateway/DB本轮参数；创建4个SimulatedTerminal连接，真实0x0100后GET版本/POST activate，再0x0102和heartbeat；保持四socket直到SQL/API/lease断言和expected/results生成完成。绝不输出ReplyRecord/body/token或原始异常。

- [ ] **Step 1: 写wire和总runner RED**

用真实loopback fixture或窄适配器覆盖四connect→逐一register→GET PENDING/version→activate→auth→heartbeat→四ACTIVE/3systems/2+1+1/四live leases/不同gateway connection IDs→生成文件。任一步失败后后续项SKIP、连接关闭、无PASS文件。Expected/Results只含规定字段，UUID不得输出stdout。

runner测试覆盖默认Plan零创建；非法token/非目标HEAD/预存run目录/路径逃逸/非loopback/已有端口/启动失败/超时固定安全码且无后续动作；失败后部分状态与清理证据。假进程证明调用参数/次序，不只断言mock存在。

- [ ] **Step 2: 最小GREEN wire helper**

实际adapter固定使用SimulatedTerminal、java.net.http和JDBC；不能接受任意URL/SQL/终端清单。所有env先与owner marker、固定四个合成terminal/vehicle映射交叉核对。合同测试用真实编排状态机与窄fake外部边界，不能只断言mock调用存在。

- [ ] **Step 3: 验证与独立复核**

运行Java合同和PowerShell包装测试、secret扫描、diff-check；不执行真实PG/API/gateway。实施报告独立复核后再进入1C。

### Task 1C: 接通总runner、手册并执行本地演练

Task1C实现基线：`a4e0a3a489810dbc59af836c759ad3fb0b470808`。Plan可在开发期报告当前HEAD与工具字节，但Execute必须在runner代码提交后的干净工作树、同一branch/root/工具字节和匹配token上运行；不得硬编码父提交造成提交后不可执行。

**Files:**
- Modify: `tools/ops-safety/Invoke-P6CompositeIsolationRehearsal.ps1`
- Modify: `tools/ops-safety/p6-composite-isolation-lib.ps1`
- Modify: `tools/ops-safety/fixtures/P6CompositeWireHarness.java`
- Modify: `tools/ops-safety/fixtures/P6CompositeWireHarnessContractTest.java`
- Modify: `tools/ops-safety/tests/p6-composite-isolation-safety.tests.ps1`
- Create: `docs/pilot/p6-2-local-isolation-rehearsal-runbook.md`

**Interfaces:**
- `Invoke-P6CompositeIsolationRehearsal.ps1 -Mode Plan|Execute -ConfirmationToken <token>`；Plan只读，Execute要求同一干净HEAD与工具字节生成的指纹。
- runner自选新run目录、新PG data、四个不同loopback端口和随机nonce/secrets；不接受调用者路径、PID、Docker或远端地址。
- 真实运行目录固定为短路径 `<repo>\.tmp\p6iso\native-<RunId>`，最长生成路径不超过240字符；SDD目录只保留安全证据。原子临时文件在目标同目录用短随机叶名，不使用长嵌套stage。
- Task1A强制停止/删除合同、Flyway helper和Task1B wire helper是唯一执行依赖；不复制或弱化安全判断。

- [ ] **Step 1: 写总runner生命周期RED**

测试假native/Java进程的真实参数与状态文件：默认Plan零动作；token/HEAD/dirty tree/路径/marker/端口/启动/超时/部分失败均fail-closed；真实adapter读取失败不伪造空观测；只停止本轮进程。验证秘密sentinel不进stdout/stderr/安全报告。

- [ ] **Step 2: 最小GREEN真实编排**

runner串行执行：依赖/HEAD/hash预检→receipt→构建→新PG及两数据库→external59→live Flyway19/精确fixture/V20/V21→API登录换密并创建/配置3车4终端→preview全表hash不变→gateway→wire helper→Task12→释放/停止/安全清理。若实际合同与预检不符停止，不改业务代码绕过。

- [ ] **Step 3: 测试、复核与Execute**

运行新增PowerShell测试、Parser、secret扫描、diff-check；实施报告独立复核后，控制器运行Plan并在无漂移代码上Execute。成功标准：external59零skip、preview hash不变、四socket真实注册/激活/鉴权/heartbeat、3system/2+1+1、四live lease、Task12 ACCEPTED4、服务和端口关闭、数据/secrets精确清理。

最终结果独立复核后提交；不push/deploy。任何部分失败按阶段报告，不称演练成功。
