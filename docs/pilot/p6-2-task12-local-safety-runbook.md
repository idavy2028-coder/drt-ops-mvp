# P6-2 Task12 本地四设备验收与安全清理手册

## 适用范围与安全边界

本手册只适用于 Task12 本地隔离验收。工具不会创建验收快照，不伪造来源签名，也不承诺数据实时性。四设备结果必须来自后续获准的可信采集过程。

以下命令需从当前工作树根目录执行。先把示例路径替换为本轮已核对文件；不要复制示例身份或 receipt 值作为真实输入。此手册提供执行方法，不表示本轮已执行真实资源清理。

清理仅接受以下本地 Docker endpoint：

- `npipe:////./pipe/dockerDesktopLinuxEngine`
- `npipe:////./pipe/docker_engine`
- `unix:///var/run/docker.sock`

禁止使用 `tcp://`、`ssh://`、远端 daemon 或云端 API。工具不会切换全局 Docker context，不调用 `force`、`prune` 或自动重试。Docker volume 没有原子的 compare-and-delete；执行 Apply 前必须取得本轮独占维护窗口，避免其他本机 Docker 管理员并发操作。该流程不能宣称绝对竞态安全。

脚本针对 Windows PowerShell 5.1 验证。执行脚本保留 UTF-8 BOM，不能用系统默认 ANSI 编码另存。JSON 使用严格 UTF-8，单个文件不超过 1 MiB；生成工具应明确编码，而不是依赖 PowerShell 版本的默认值。

## 四设备验收输入合同

Expected JSON 和 Results JSON 都必须是恰好 4 条记录的数组，别名集合严格为：

- `terminal-01`
- `terminal-02`
- `terminal-03`
- `terminal-04`

Expected 每条必须包含字符串字段 `SafeAlias`、`TerminalId`、`VehicleId`、`OnboardSystemId`。三个 ID 必须是非空 UUID；别名和物理 `TerminalId` 分别唯一。允许同一车辆的两台终端共享同一个 `VehicleId` 和 `OnboardSystemId`，但车辆与车载系统必须保持双向一一映射。

Results 每条还必须包含 `Status`，且值严格为 `PASS`。同别名的三个 ID 必须与 Expected 按 UUID 值逐项相等。缺失、重复、额外、空对象、畸形字段、错误映射或非 PASS 都会拒绝，不允许通过覆盖参数改变数量。

验收命令：

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\tools\ops-safety\Invoke-Task12SafetyGate.ps1 `
  -Mode VerifyAcceptance `
  -ExpectedPath '.private/task12-local-acceptance/本轮目录/expected.json' `
  -ResultsPath '.private/task12-local-acceptance/本轮目录/results.json'
```

成功时 stdout 只包含数量和状态：

```text
TASK12_SAFETY_STATUS=PASS MODE=VerifyAcceptance ACCEPTED=4
```

## 创建资源时保存 receipt

创建容器和 volume 的同一流程必须立即保存 receipt；清理时禁止根据当前资源名称临时认领。receipt 放在 Git 忽略的任务目录，例如 `.private/task12-local-acceptance/receipt.json`，不得包含凭据、车牌、终端鉴权材料或其他私密业务内容。

下面的值全部虚构，仅说明结构，**不可直接复用**。创建流程应先生成并保存本轮 RunId、随机 OwnerNonce 和固定 endpoint，再用这些值命名并标记新资源；保存 `docker create` 返回的完整容器 ID，并与同 endpoint 的 inspect 结果核对。卷创建完成时即保存它的创建时间，最后把实际字段写入 receipt。若创建流程中断且未留下这些证据，停止自动清理，另行人工盘点；不能从同名现有资源补造可信 receipt。

```json
{
  "SchemaVersion": 1,
  "RunId": "20260905-a1b2c3",
  "OwnerNonce": "0123456789abcdef0123456789abcdef",
  "DockerEndpoint": "npipe:////./pipe/dockerDesktopLinuxEngine",
  "ContainerId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "VolumeCreatedAt": "2026-09-05T01:02:03Z"
}
```

- `RunId` 格式为 `yyyyMMdd-六位小写十六进制`。
- `OwnerNonce` 必须使用密码学安全随机源生成 16 字节，再编码为 32 位小写十六进制；不得复用。
- `ContainerId` 必须记录创建后 inspect 得到的完整 64 位小写十六进制 ID。
- `VolumeCreatedAt` 必须记录创建后 inspect 得到的创建时间。
- 容器名为 `drt-p6-2-task12-pg-<RunId>`；卷名为 `drt-p6-2-task12-pgdata-<RunId>`。
- 容器和卷都必须在创建时写入 `com.drt.task=p6-2-task12`、`com.drt.run-id=<RunId>`、`com.drt.owner=<OwnerNonce>` 三个 label。

receipt 是清理授权依据之一，必须来自可信创建流程并受本地访问控制保护。它不能防止拥有本机 Docker 权限和文件写权限的人员篡改；发现 receipt 来源、权限或内容可疑时不得继续清理。

## 默认 DryRunCleanup

先执行只读清理门禁：

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\tools\ops-safety\Invoke-Task12SafetyGate.ps1 `
  -Mode DryRunCleanup `
  -ReceiptPath '.private/task12-local-acceptance/本轮目录/receipt.json'
```

DryRun 只枚举并校验，不执行删除。成功输出固定为：

省略 `-Mode` 也使用 `DryRunCleanup`；模式值大小写必须精确，非法值直接拒绝，不会进入删除分支。

```text
TASK12_SAFETY_STATUS=PASS MODE=DryRunCleanup CONTAINERS=1 VOLUMES=1 ACTIONS=0
```

它会要求 RunId、label、名称和 ID 共同证明恰好一个本轮容器及一个本轮卷；三个 label 的键名和值均严格区分大小写。检查本地卷 driver/scope/options、创建时间、容器状态、唯一 volume 挂载，并查看所有容器中是否存在其他消费者。零匹配、多匹配、名字复用、ID 变化、少 label、错 owner、运行中、额外挂载或共享消费者都会失败关闭。

**保守限制：全库存出现任何 bind mount，自动清理均拒绝。** Docker inspect 仅提供路径，无法可靠排除符号链接等目录别名共享；不能因为 Source 看起来与本轮卷 Mountpoint 不同就认定安全。适配器会正常解析和保留这些字段，但清理门禁要求人工核对，不会把解析成功当作允许删除。这包括运行中或停止的非 Task12 容器，也包括确实无关的 bind。

不要为了让脚本通过而停删现有容器、改标签、改挂载或伪造库存。出现此保守拒绝时，本工具保持拒绝；另行制定并批准人工只读核对及精确清理流程。本轮没有提供绕过开关，也没有执行任何 daemon 文件系统探查。

这里的“停止态”限定为 `created` 或 `exited`。没有 receipt 的历史资源不得通过猜测 RunId 进入此流程。每个 Docker 调用默认最多等待 10 秒，可用 `-TimeoutMilliseconds` 在 100–60000 毫秒间指定；超时仍须人工核对，不能调成无限等待。

## 经复核后执行 ApplyCleanup

Apply 会删除容器和卷，属于破坏性操作。只有 DryRun 已通过、receipt 已人工核对、独占维护窗口已确认时才可执行：

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\tools\ops-safety\Invoke-Task12SafetyGate.ps1 `
  -Mode ApplyCleanup `
  -ReceiptPath '.private/task12-local-acceptance/本轮目录/receipt.json'
```

工具会再次执行完整门禁，删除前重新读取一次库存并拒绝漂移；随后按完整容器 ID 执行不带 force/volumes 的容器删除。容器删除后再次检查卷身份、label、创建时间和零消费者，最后按精确卷名执行不带 force 的卷删除。任一步异常立即停止，receipt 不会自动删除。

## 失败与部分成功恢复

失败输出只给固定安全码和已完成步骤，不回显输入 ID、业务内容、原始异常或文件路径。例如：

```text
TASK12_SAFETY_STATUS=FAIL MODE=ApplyCleanup CODE=CLEANUP_REJECTED COMPLETED=CONTAINER_REMOVED
```

处理原则：

1. 立即停止，不重试，不使用 `--force`、`prune` 或全局 context 变化绕过门禁。
2. 保存固定输出，人工查看本机 Docker 状态；不要把原始敏感输出复制到公开证据。
3. `COMPLETED=CONTAINER_REMOVED` 表示容器删除已完成而卷未确认删除。下一次默认运行会因容器缺失而拒绝，不能把零匹配当成功，也不能自动接管同名资源。
4. 重新取得独占维护窗口，使用 receipt 中的完整信息人工核对卷身份、创建时间、label 和全部消费者，再申请单独的恢复授权。
5. 容器删除失败、卷删除失败、两次读取间漂移、卷被替换或新增消费者时均不得自动重试。

`COMPLETED` 只表示脚本已确认的进度，不是 Docker daemon 最终状态的替代品。特别是命令超时或连接中断时，`NONE` 不证明什么都没删；必须先只读核对实际状态。成功删除的容器和卷数据不能由此工具恢复，需要保留的验收证据或数据必须在批准清理前归档。

本工具测试只使用合成库存和本地假 Docker 子进程；没有执行真实 Docker 删除，也没有完成真实四设备验收。Task10 异常路径、独立隔离演练、云端全库盘点、部署及真实窗口仍需各自授权和证据。
