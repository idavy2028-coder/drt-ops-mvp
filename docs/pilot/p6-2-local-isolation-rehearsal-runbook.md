# P6-2 本地隔离演练运行手册

当前正在交接 Task1C2a 资源生命周期基础，尚未取得本轮独立审阅通过；**总 runner 的 Execute 仍固定拒绝，不能据此执行完整演练**。本手册不是生产上线批准，不授权现有数据库、Docker、云端、真实设备或外部 HTTP。

唯一支持宿主为 **Windows PowerShell 5.1（powershell.exe）**，不是 PowerShell 7（pwsh.exe）。runner 在加载库、解析 JSON 或任何资源动作前检查版本；不支持的宿主固定返回 `REHEARSAL_POWERSHELL_UNSUPPORTED ACTIONS=0`，不尝试跨版本兼容。WinPS 5.1 的 JSON 收据往返保留 CreatedAt 字符串，PS 7 的自动日期转换不在支持范围。

## 现在可以做什么

以下命令只读取当前工作树和工具字节，不启动服务、不建立演练目录；风险为只读 Git/文件访问。请先进入 `D:\codex-projects\.worktrees\p6-2-composite-onboard-system`。

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File tools/ops-safety/Invoke-P6CompositeIsolationRehearsal.ps1 -Mode Plan
```

Plan 绑定当前 HEAD、指定分支、规范化 root 摘要及固定 11 文件清单的 SHA-256；会显示 tracked 工作区和全部非 ignored 输入是否干净、工具是否已进入提交。`CLEAN_INPUTS` 来自固定 `git status --porcelain=v1 --untracked-files=all`，不人工过滤 `.tmp/.superpowers`；只有 Git 本身明确 ignored 的内容才不会出现。开发期允许输出 Plan，但当前始终 `EXECUTABLE=false`，阻挡码为 `REHEARSAL_EXECUTE_NOT_IMPLEMENTED`。提交后必须重新 Plan，不能沿用旧 token。继承 `GIT_*` 被清除，只给只读子进程设置确切目录的 safe.directory，不修改 Git 配置。

以下命令会创建独立测试产物并启动短命 PowerShell/javac/Java，以及合成回环 HTTP/TCP fixture；不启动真实 PostgreSQL/API/GW，不运行 Maven，不连接真实账号。

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File tools/ops-safety/tests/p6-composite-isolation-safety.tests.ps1 -Phase All
```

## C1 native 库合同

- 真实资源根由后续 runner 固定推导为 `<当前工作树>\.tmp\p6iso\native-<随机RunId>`；SDD 只放安全报告/测试证据。生成路径保守限制为 240 字符，同目录短随机 stage 使用 CREATE_NEW 和原子重命名。
- 后续 runner 负责生成新 RunId、nonce、互不相同的随机口令及四个空闲 loopback 端口，使用不存在的新目录，并先保护目录 ACL。`Write-P6OwnedStorage` 写入 owner.properties、初始 receipt.json、受保护的 secrets/pg-password.txt；任何既有目标都拒绝覆盖。
- `Start-P6NativeCluster` 串行执行固定 initdb、foreground postgres.exe、pidfile/监听/持有句柄的 ready 校验、创建 composite_onboard 与 composite_live 两库，以及固定 psql SELECT 1。返回 READY 后数据库仍由调用方持有，必须在总 deadline 内继续或停止；READY 不等于业务演练通过。
- native child 使用环境白名单、隐藏窗口、无 shell。口令只在本轮受控文件/子进程环境内。短命工具同时消费两个有界流，仅返回固定状态、退出码和字符数量，不返回原文；超时只尝试结束本函数亲自创建且仍持有的短命 Process。不得据此假定 initdb 的所有派生进程已消失。
- 长驻 PG 的输出由固定缓冲后台消费，只保存计数/失败标志；ready 与工具阶段核验读取状态。后续总 runner 必须维持整个调用周期的 deadline 及进程/监听/输出限额观测。
- receipt 原子追加本轮 PG 进程，保留 `receipt.previous.json`。启动证据保存原 Process 对象和 StartInfo 的 cwd；Win32_Process 独立提供 PID/时间/可执行路径/命令行，**不声称能读到运行时 cwd**。
- `Stop-P6NativeCluster` 的实际边界必须进入 Task1A `Stop-P6OwnedPostgres`。当前 owner、路径链、原持有句柄、PID/时间/可执行文件、pgdata、pidfile、唯一 127.0.0.1 监听全部证明后，才运行固定 pg_ctl fast stop；之后再证明进程与监听退出。公开短命 native action 明确拒绝 PG_STOP。
- 启动失败、ready 超时、pidfile 不完整、观测异常、停止超时、原句柄丢失、runner 崩溃后的恢复均保守保留。不能从 receipt 重新构造自动停止权限，不能按名称/端口杀进程。

## C2 接通前仍须完成

1. C1 已完成限定复审；C2a 仍需独立审阅及完整回归。未来每次动作必须重新读取 HEAD、branch、root、全部非 ignored 状态、11 文件字节和 token，不能把旧 Plan 的布尔值当当前权限。
2. C2a 的 WireHarness 已通过测试先行同步 `.tmp\p6iso\native-*`，拒绝旧 SDD 布局；生成路径不超过 240，owner/wire marker 与 reparse 合同保留。
3. 串行 fresh Maven package；本轮两库 external59 必须实际 59 tests、0 failure/error/skip；live 库使用既有 Flyway helper V19→精确两行 demo fixture→V20→V21，不修改历史迁移。
4. 按预检精确环境/JSON 完成 API 换密、三车四终端、绑定/能力、preview 全表 hash 不变、3 systems / 2+1+1，随后真实 gateway/wire、四 live lease 与 Task12 ACCEPTED=4。任何实际合同漂移停止，不改业务代码掩盖。
5. finally 释放 wire/lease，按本轮收据和原句柄停止服务。只有完整系统观测证明所有本轮进程退出、相关端口关闭，且 Task1A removal proof 通过后，才精确删除本轮 data/secrets；安全最终报告必须位于删除范围之外。C1 不执行删除，不把保留目录称为清理成功。

上述业务步骤和真实 Execute 未由 C1 实施者运行。下行分片是 Task1B 已知 fail-closed 局限；实际 gateway 若触发，应停止并另行授权修复，不能伪造成功。

## C2a 与后续 C2b 的交接边界

`p6-composite-isolation-pipeline.ps1` 目前是资源阶段编排器，不是业务 runner。内部 `Invoke-P6ResourceStages` 必须获得明确 boundary；不提供自动默认执行链，也不由 CLI 加载。固定资源阶段只有 PG/API/GW 的 START、READY、TOOL；没有 Maven、external59、迁移编排、HTTP 初始化、wire 或 Task12 调用。其 `PASS` 仅指资源合同测试结果，不代表业务验收。

- 全局资源 deadline 固定为 30 分钟；单个短命工具最多 60000ms。从进入短命函数即启动同一个 Stopwatch，启动前后、工作 wait、kill 后 wait、drain 都使用它的绝对剩余量，不再追加固定 5 秒/1 秒。到期无法证明停止时保留原 Process（以及已经创建的 drain），不靠 Dispose 假装退出。任意同步 HealthProbe/StopProcess 回调接口已移除；等待循环只读取固定 typed quick guard 的 Stopwatch、原 Process.HasExited、Drain.Failed/Count 属性。完整 CIM/监听/receipt 证明由调用方在工具前后进行，并遵循各自已有的查询/HTTP时限。ProcessFactory 仅为测试 seam，不声称能抢占任意阻塞测试回调或不可中断的 OS 启动调用。
- C2a 新收据链从初始空进程收据按 PG→API→GW 追加；每代写 Sequence 与 PreviousSha256，同目录原子替换并保留 `receipt.000.json`、`.001.json`、`.002.json`。前驱内容、顺序、hash、已存在备份均验证。它与 C1 旧 `receipt.previous.json` 追加接口不可混用，后续 C2b 应使用新 held-resource 接口。
- Java 路径固定 JDK21，运行文件固定为本轮 `api.jar`/`gateway.jar`，要求与受控 artifact hash 一致；参数只有本轮 JVM marker、`-jar` 与对应文件。凭据只进入显式环境白名单，未设置附件控制/上传配置。
- C2a 启动收据的 `LaunchExecutablePath` 仅表示固定 spec 请求且原 Process.StartInfo 持有的启动路径；兼容字段 `ExecutablePath` 同样是启动请求，不是运行时观测。启动记录不读取 MainModule，提前退出或部分记录失败返回 FAILED/Retained 并保留原 ticket。STARTED 不代表健康，使用或停止前仍必须通过独立系统 `Observed.ExecutablePath`、原 Process.MainModule/句柄与固定 spec 的三方严格路径证明；不能用请求值伪造系统观测。任何后验读取失败/身份不符都拒绝停止和删除。
- executable 身份仅接受本机盘符绝对路径，先做完整路径规范化，再做 OrdinalIgnoreCase 比较；拒绝不同目录/文件、额外路径、相对路径、UNC、设备路径与 ADS，不解析链接。原 Process/MainModule 与独立 OS 观测仍须一致，收据原始内容及前驱链仍按字节保护。
- Wire 路径预算覆盖 `.wire-stage` 与 `acceptance` 下 expected/results 四个完整生成路径；最长 240 接受、241 拒绝。
- C2a 的健康证明是进程、输出及所有权/监听层；业务 HTTP 的 UP、登录、强制换密和真实业务状态仍由 C2b 接通，不以端口监听代替 HTTP readiness。
- 删除入口会内部无条件重做 Task1A removal proof，即使 pgdata/secrets/gateway-outbox 三目录都已不存在，仍要求每个收据 PID 恰有一次成功、明确不存在的独立观测；失败/未知/缺项/重复不等于空。typed inventory 必须明确 Succeeded=true 且 Items 为成功空数组，未登记但带本轮 marker 的进程也拒绝。原句柄退出、四端口空闲、无 retained 短命句柄、marker/ACL/root 与整个 run 无 reparse 均须成立；只删除准确 run 子目录，不接受旧 PROVEN 直接授权。Task1A proof 允许固定资源子路径缺失，但未放开将 run root 作为其 Target。
- 内部 Plan/context/held ticket 包含原始路径或进程对象，不可直接序列化至控制台/安全报告。安全结果只允许固定 Status/Phase/Code/Retained 和固定阶段 PASS/FAIL/SKIP。C2b 还必须把最终安全报告原子保存到 run 删除范围以外的固定 ignored SDD execution 目录。

资源测试会运行自己编译的合成 Java JAR（只有有界输出和等待，不是 Spring API/GW），并删除测试自己创建的一个合成 run 来验证窄清理；同级测试文件必须保留。测试不会启动真实 PostgreSQL/Maven/API/GW，也不会执行实际 Execute。

资源测试入口使用固定 **54 个串行 exact per-case 宿主**，每个 worker 只运行一个完整名称；Held/Core/Resources 阶段只作标签和父级筛选，不再组合运行。固定唯一集合为 Held 3、身份/生命周期 15、收据/清理 16、正常/阶段失败 11、清理/保留 9，共 54 项。`Resources` 只选择最后 20 个单项宿主。每个原宿主句柄保持 55000ms 工作预算，停止和捕获统一使用 Stopwatch 至 60000ms 的绝对剩余预算；父级总监督预算为 1800000ms，超过 1740000ms 后不再启动新宿主，为每项保留完整 60000ms。父级每不超过 60 秒可轮询，逐项核对退出码、stderr、停止状态、唯一 TOTAL=1/PASSED=1/FAILED=0 summary、exact 名称以及唯一 CHILDREN=0/ARTIFACTS=0 标记，全部成立才累计成果和启动下一项。超时固定 `TEST_HOST_TIMEOUT`、`Retained=true`、停止后续；仅能证明原宿主退出，不证明后代已清，不删除本轮文件。正常 worker 只清理自己创建的随机目录。最新结构和 test-only 修复证据见 `task-1c2a-percase-report.md` 的追加段；生产 Fix1/Fix2 结果见 `task-1c2a-report.md`。该限时保护不解除 Execute 门禁。

失败历史：旧 23 宿主方案曾把 Held3、CoreIdentity15、CoreReceipt16 分别合并运行，另有20个资源单项；其组合在不同负载下仍有超时，因此已被全部54项逐项方案替代。此前更大的资源组合与23宿主失败记录保留在 SDD 报告中，不作为当前执行说明或本轮通过证据。

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File tools/ops-safety/tests/p6-composite-isolation-pipeline.tests.ps1 -Phase All
```

历史记录中存在 GW 合成启动的间歇性失败，且旧 Diagnose 动态改写可能自身抛错。现 Diagnose 仅输出固定非侵入类别，不再改写产品函数或读取诊断路径。启动记录架构变更及其限定验证以 `task-1c2a-launchfix-report.md` 为准；此前失败不抹除，也不把后续绿色倒推为历史根因已查明。独立审阅通过前禁止接通 Execute。
