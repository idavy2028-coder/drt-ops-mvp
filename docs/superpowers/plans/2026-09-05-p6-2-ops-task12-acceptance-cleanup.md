# Task12：验收数量与资源清理保护

基线：`codex/p6-2-ops-safety-gates@16e174214ff87bc5a90736a34eb3bdc1818a3f04`。
依据：已批准的上线前安全门禁方向，以及 `docs/pilot/evidence/p6-2/final-remediation-review-2026-09-05.md` 中 Task12 未关闭项。

## 全局约束

- 仅改运维工具、合成测试和文档；不改业务代码、数据库迁移、Task11 已冻结入口或真实私密资料。
- 不部署、不访问云端、不使用真实设备；不停止、删除或重启任何现有业务资源。不得使用 prune、通配删除、docker compose down -v 或 force。
- Windows PowerShell 5.1 可运行；输出固定安全错误码、计数和步骤，不输出数据字段原值、Docker 原始错误或凭据。
- RED 必须是行为断言失败；辅助设施失败不能当作 RED。GREEN 后独立规格与质量复核，修复 Important 以上问题再本地提交；不推送。
- 本任务证明本地门禁行为，不把合成四条记录称为四台真实设备验收，不把输入快照校验当作来源真实性证明。

### Task 12: 实现四设备结果门禁及默认只读清理入口

工作目录：`D:\codex-projects\.worktrees\p6-2-composite-onboard-system`。

#### 背景与范围

现有 `.private/task12-local-acceptance/task12-local-acceptance-lib.ps1` 只有 RunId 命名、loopback 校验和通用 legacy seed builder；旧6项测试没有验收结果或清理执行器。保留它们不变，不将通用 seed 的1..100约束改成4。新建通用工具直接执行门禁，后续隔离验收必须经它们收口。

新增文件可按职责分为：

- `tools/ops-safety/task12-safety-lib.ps1`：纯验收校验、receipt/inventory校验、清理编排。
- `tools/ops-safety/task12-docker-adapter.ps1`：本地Docker有界进程调用与库存规范化，禁止任意命令接口。
- `tools/ops-safety/Invoke-Task12SafetyGate.ps1`：安全加载库、读取受控JSON，提供 `VerifyAcceptance`、`DryRunCleanup`（默认）、`ApplyCleanup` 模式。
- `tools/ops-safety/tests/task12-safety.tests.ps1`：合成数据、可变库存适配器、实际CLI路径测试。
- `docs/pilot/p6-2-task12-local-safety-runbook.md`：输入合同、创建时receipt记录方式、运行和失败恢复步骤。

如测试需要本地假Docker可执行进程，放独立fixture helper；所有新文件必须职责清晰。不要变更旧private库或同步生成真实资料。

#### 验收合同

1. Expected 与 Results 均必须恰好4条，别名集合严格为 `terminal-01` 至 `terminal-04`；不提供覆盖数量参数。
2. 每条包含 SafeAlias、TerminalId、VehicleId、OnboardSystemId；ID为非空UUID，物理终端ID及别名在各自集合唯一。
3. Results 每条必须 Status=`PASS`，并与同别名预期的三个ID逐项一致；拒绝缺失、重复、额外项、错误映射、失败状态、空对象及畸形字段。
4. 同车双设备允许共享VehicleId及OnboardSystemId；同一VehicleId不能映射不同系统，同一系统也不能映射不同车辆。不可只统计 PASS 数。
5. 成功只输出验收数量/状态，不输出ID或车牌。验收快照由后续可信采集器生成，当前不伪造来源签名或实时性保证。

#### 清理合同

1. 调用者必须提供创建时保存的receipt，而不是按现存名字临时认领。receipt包含SchemaVersion=1、RunId（`yyyyMMdd-六位小写十六进制`）、随机OwnerNonce（32位小写十六进制）、本地DockerEndpoint、完整ContainerId（64位小写hex）、VolumeCreatedAt。容器/卷名从RunId派生，与旧 New-Task12ResourcePlan 一致。
2. 三个资源label为 `com.drt.task=p6-2-task12`、`com.drt.run-id=<RunId>`、`com.drt.owner=<OwnerNonce>`；容器和卷全部匹配。receipt保存在ignored任务目录；不得记录凭据或私密业务内容。说明receipt本身必须受信任，不声称能防止有本机Docker权限者篡改。
3. 只接受明确的本地endpoint：Windows命名管道 `npipe:////./pipe/dockerDesktopLinuxEngine` 或 `npipe:////./pipe/docker_engine`，以及 `unix:///var/run/docker.sock`。每次原生命令显式固定endpoint，并清除子进程Docker路由/TLS环境覆盖；绝不修改全局context，拒绝tcp/ssh远端。
4. 枚举库存后，RunId/label/name/ID必须共同证明恰好一个本轮容器、一个本轮卷；零匹配、多匹配、少标签、错owner、名字复用/ID变化均fail-closed。卷固定driver=local、Scope=local、无额外driver选项，并比较创建时间。容器须为created或exited状态；只挂载本轮一个volume，不允许bind/匿名/其他volume。所有容器中的卷消费者只能是此容器。
5. DryRunCleanup只枚举/校验并输出计划，不执行rm。ApplyCleanup先执行同样完整门禁，删除前再读取和校验，按精确完整ID删除容器（不带force/volumes），随后重新核对卷身份、标签、创建时间及零消费者，再删除精确卷名（不force）。任何命令失败或状态漂移立即停；不自动重试、不删除receipt、不把零匹配当作成功。
6. 所有相关本轮候选都纳入歧义检查，不能用first/select-one掩盖冲突。清理调用只接受固定操作，禁止拼接shell命令。部分成功必须报告已完成步骤，方便人工核对；下一次默认拒绝缺失资源而不是自动接管。
7. Docker卷删除没有原子compare-and-delete，本机并发管理员仍可造成最后一刻竞态；文档明确要求本轮独占维护操作，不能宣称绝对竞态安全。不增加全局锁或后台守护进程。

独立复核后的安全收紧：三个label的键名和值均要求Ordinal精确；保留bind Source与卷Mountpoint，但不以路径字符串不同推定无共享。由于本轮不具备可信daemon文件系统canonical/inode证明，全库存任何bind都会保守拒绝自动清理。无关bind的代价是需要人工核对，不提供绕过；适配器仍必须能正常解析它。此收紧只影响本地清理门禁，不触及业务资源。

#### RED → GREEN 与证据

1. 先保留旧6项测试基线，新增行为测试并运行真实RED，再实现最小代码；缺新函数可作为初始RED，但主要失败合同须由可观测行为覆盖。
2. 验收测试：正确4条、同车双设备、0/3/5、重复别名/物理ID、空UUID、错映射/共享系统冲突、非PASS、缺字段/畸形输入。
3. 清理测试：只读无删除、Apply精确调用顺序和参数、0/多匹配、错名称/标签/ID/时间/endpoint、运行中、额外挂载/共享消费者、两次读取间漂移、容器rm失败、卷rm失败、删除容器后卷被替换/新增消费者、部分状态与无重试。
4. 测试通过可变库存/操作日志验证真实编排行为；另通过安全子进程fixture验证原生Docker适配器JSON解析、exit/超时/参数和入口安全输出。不得仅测试helper或mock返回true。禁止调用真实Docker删除和云端API。
5. 固定stdout安全合同；输入中的合成敏感sentinel、原始异常、文件路径不得泄露到stdout/stderr。库missing/parse失败和畸形JSON入口也必须安全失败。
6. 定向全套、旧6项兼容基线、PowerShell parse与git diff --check；不运行无关Java/Maven回归。报告精确命令/退出码/执行数，不把skip算pass。
7. 报告记录可信边界、未执行真实删除/真实四设备验收；控制器完成独立审查、公开证据、progress.md及本地提交。

## 交接

本地门禁通过后，Task10异常路径及独立隔离演练仍待执行；云端全库盘点、部署、真实窗口继续保留单独授权边界。
