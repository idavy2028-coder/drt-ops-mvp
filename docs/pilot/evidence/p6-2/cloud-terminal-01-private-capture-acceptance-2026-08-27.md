# P6-2 terminal-01 私密捕获真实流量验收记录（2026-08-27）

## 结论

本窗口真实流量已到达新版 gateway，消息头身份与协议版本均通过维护白名单，私密车辆标识捕获成功，并与本地最新私密资料完全一致；但 API 仍以 `VEHICLE_IDENTIFIER_MISMATCH` 拒绝注册。terminal-01 未完成注册、鉴权、位置和报警基础链路，本窗口判定为**未通过，阻断在云端车辆标识/绑定快照**。

本窗口没有启用附件链路，没有修改终端、车辆、绑定或数据库数据。gateway 已按 fail-fast 停止并恢复安全停止态。

## 时间线

| 事件 | 时间 |
| --- | --- |
| 私密维护/捕获工件生成 | 2026-08-27 13:10:06 UTC |
| 维护配置到期 | 2026-08-27 13:25:06 UTC |
| 观察器 READY | gateway 启动前已确认 |
| gateway 健康启动 | 2026-08-27 13:14:38 UTC |
| 现场执行 terminal-01 网络重连 | 2026-08-27 21:16:00 Asia/Shanghai |
| fail-fast 后停止态容器重建 | 2026-08-27 13:23:15 UTC |

## 启动门禁

- 观察器输出 `OBSERVER_READY=YES`，状态进入 `RUNNING`；基线为注册 0、ACTIVE 0、鉴权 0，附件端点 0。
- gateway 使用 `p6-2-exception-boundary-44b8703` 镜像，启动后为 running/healthy、restart count 0，liveness `UP`。
- TCP 7611 为单监听，启动时连接数 0；API 健康状态 `UP`。
- 维护配置只允许 terminal-01，terminal-02～04 由白名单隔离；捕获使用本窗口唯一输出路径。

## 真实流量观察结果

- 维护白名单快照：允许 25、拦截 0；唯一观察为 `terminal-01`、`identityMatch=true`、`protocolMatch=true`。
- 私密捕获状态：enabled=true、captured=true、安全别名 terminal-01；字符数 8、GBK 字节数 9。公开记录不保存捕获内容。
- gateway H2、磁盘、TCP、Operations API、Outbox 和 readiness 均为 `UP`；pending、delivering、dead-letter 均为 0；ERROR/FATAL 计数为 0。
- 云端终端聚合始终为总数 4、PENDING 4、注册 0、鉴权 0。
- 最近窗口安全原因码聚合为 `VEHICLE_IDENTIFIER_MISMATCH` 56 条，以及注册失败后产生的 `MESSAGE_NOT_ALLOWED_BEFORE_AUTHENTICATION` 56 条。

## 私密捕获比较

- 捕获文件只保存在服务器和本地 `.private` 证据目录，均未写入 Git 或公开报告。
- 本地比较使用 `cloud-terminal-preload-lib.ps1` 的正式 `New-CloudTerminalProvisioningPlan` 映射 terminal-01，不使用 CSV 字面别名或行号猜测。
- 计划共 4 台，terminal-01 映射唯一；捕获值与当前私密资料的字符数、UTF-8 字节数及固定时间字节比较全部一致，结果为 `VehicleIdentifierMatch=YES`。
- 由此排除设备上报值和本地最新私密资料不一致；剩余阻断为云端预录入车辆标识或活动绑定仍保留旧快照。

## Fail-fast 与证据完整性

- 私密证据复制首次遇到 SSH 会话关闭，使用不覆盖完整文件的幂等流程恢复；本地首次 SCP 命令因 PowerShell 参数空格错误未执行，随后在严格错误模式下成功下载。两次异常均未修改业务数据。
- 观察器状态在主动结束前冻结，状态 SHA-256 为 `c2bbdd00…b2dc`；冻结状态仍标记 `RUNNING`，不得误记为观察器自然完成或验收通过。
- 服务器窗口私密目录共 6 个文件，全部 mode 600；私密校验清单 SHA-256 为 `81d6e5d2…cade`。公开记录不披露捕获文件自身哈希。
- gateway 已停止并按无维护配置重建：状态 `created`、Running=false、restart count 0；TCP 7611 监听 0、连接 0；API 继续 `UP`。

## 下一门禁

不得重复开启相同配置的真实窗口。下一步需测试先行实现或复用受控车辆标识纠正流程，只允许在 PENDING、未注册、未鉴权、活动唯一绑定条件下，把云端车辆标识与已确认私密资料对齐；必须先 dry-run 显示 terminal-01 唯一字段差异，再备份、单台修正、只读注册验证。未获得该修正授权前，不直接操作数据库、不删除重建车辆或终端。

附件链路继续禁用；`0x9208`、`0x1210`、`0x1206` 不属于本窗口验收范围。
