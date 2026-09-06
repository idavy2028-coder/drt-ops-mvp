# P6-2 本地隔离演练运行手册

当前交付范围为 Task1C1 native 安全基础，**总 runner 的 Execute 仍固定拒绝，不能据此执行完整演练**。本手册不是生产上线批准，不授权现有数据库、Docker、云端、真实设备或外部 HTTP。

唯一支持宿主为 **Windows PowerShell 5.1（powershell.exe）**，不是 PowerShell 7（pwsh.exe）。runner 在加载库、解析 JSON 或任何资源动作前检查版本；不支持的宿主固定返回 `REHEARSAL_POWERSHELL_UNSUPPORTED ACTIONS=0`，不尝试跨版本兼容。WinPS 5.1 的 JSON 收据往返保留 CreatedAt 字符串，PS 7 的自动日期转换不在支持范围。

## 现在可以做什么

以下命令只读取当前工作树和工具字节，不启动服务、不建立演练目录；风险为只读 Git/文件访问。请先进入 `D:\codex-projects\.worktrees\p6-2-composite-onboard-system`。

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File tools/ops-safety/Invoke-P6CompositeIsolationRehearsal.ps1 -Mode Plan
```

Plan 绑定当前 HEAD、指定分支、规范化 root 摘要及固定工具清单的 SHA-256；会显示 tracked 工作区是否干净、工具是否已进入提交。开发期允许输出 Plan，但当前始终 `EXECUTABLE=false`，阻挡码为 `REHEARSAL_EXECUTE_NOT_IMPLEMENTED`。提交后必须重新 Plan，不能沿用旧 token。继承 `GIT_*` 被清除，只给只读子进程设置确切目录的 safe.directory，不修改 Git 配置。

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

1. 独立审阅 C1，并提交安全基础；在干净 tracked 工作树重新生成 Plan/token。Execute 必须检查同一 HEAD、branch、root、工具字节和 token。
2. Task1B WireHarness 目前仍限定旧 SDD run 布局。必须显式授权后测试先行同步 `.tmp\p6iso\native-*` 根验证，保持 owner/wire marker 字段合同；否则固定失败，不能绕过。
3. 串行 fresh Maven package；本轮两库 external59 必须实际 59 tests、0 failure/error/skip；live 库使用既有 Flyway helper V19→精确两行 demo fixture→V20→V21，不修改历史迁移。
4. 按预检精确环境/JSON 完成 API 换密、三车四终端、绑定/能力、preview 全表 hash 不变、3 systems / 2+1+1，随后真实 gateway/wire、四 live lease 与 Task12 ACCEPTED=4。任何实际合同漂移停止，不改业务代码掩盖。
5. finally 释放 wire/lease，按本轮收据和原句柄停止服务。只有完整系统观测证明所有本轮进程退出、相关端口关闭，且 Task1A removal proof 通过后，才精确删除本轮 data/secrets；安全最终报告必须位于删除范围之外。C1 不执行删除，不把保留目录称为清理成功。

上述业务步骤和真实 Execute 未由 C1 实施者运行。下行分片是 Task1B 已知 fail-closed 局限；实际 gateway 若触发，应停止并另行授权修复，不能伪造成功。
