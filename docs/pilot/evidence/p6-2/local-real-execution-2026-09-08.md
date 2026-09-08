# P6-2 本地真实执行接线与环境阻挡

## 结果

用户已确认将本地隔离演练延期至环境就绪后；本分支按代码交付合并，不以完整演练通过作为本次合并声明。恢复时先使用非管理员宿主重新Plan，再使用新指纹执行。现有合成残留保持原状。

真实执行代码与独立复核完成；完整隔离演练未通过。当前 Windows 工具宿主为管理员，Windows PostgreSQL 拒绝该令牌。Plan 与 Execute 现于创建资源前明确拒绝，错误为 `REHEARSAL_NONADMIN_HOST_REQUIRED`，不再以 `NOT_IMPLEMENTED` 掩盖实现状态。

## 已实现

- 固定 Maven Java launcher 的真实 Process.Start、fresh build、原生 PG 前台进程、双库、外部59迁移入口、Flyway V19/V20/V21、真实API业务准备、真实Gateway/WireHarness与Task12消费者。
- 基于当前HEAD、源码清单、宿主身份与权限的Plan指纹；普通权限和有效token才允许执行。
- 超时使用原进程句柄有界停止；无法证明退出时记录私有恢复票据。readiness响应读取有大小和时间上限。结果逐阶段记录PASS/FAIL/SKIP并逐资源记录清理状态。
- 新建secrets目录显式设置当前用户owner，保留唯一用户DACL。Flyway helper已同步短根目录。

## 证据及限制

| 检查 | 结果 |
| --- | --- |
| 真实Process.Start、非零退出、输出消费者、超时停止与宿主拒绝 | 7/7（实现者最终运行） |
| PowerShell安全回归 | 125/125（主控运行，ACL修复后） |
| 业务helper配置及HTTP204合同 | 3/3（主控最终字节编译运行） |
| Flyway真实子JVM目录合同 | 3/3（新短根接受，任意/旧根拒绝） |
| 独立代码复核 | R1/R2/R3全部关闭，PASS |
| 真实initdb | 成功 |
| 真实PostgreSQL启动 | 失败：管理员令牌被拒绝 |
| external59、API、Gateway、四socket、Task12完整演练 | 未执行通过，不得称验收成功 |

当前账户无可用普通权限UAC关联令牌，受限宿主实验以0xC0000142退出；实验launcher没有进入正式runner。需在有依赖访问权限及CIM读权限的非管理员Windows PowerShell 5.1中重新Plan和执行；禁止通过取消PostgreSQL权限限制解决。

## 残留与恢复

两个已记录的本轮合成目录保留：`native-a43b8f93f06c4c37aad92e5fec5c5433`（ACL诊断未启动PG）、`native-f24cf232d4c0438fa51de0b3a0c227d5`（PG已STOPPED但删除证明未通过）。精确票据及日志在本轮SDD报告中；含合成数据/凭据，不作为下一轮运行目录复用。另两次PG烟测目录在证明停止后已删除，数据不可恢复。

未停止既有PostgreSQL，未操作Docker、云端或真实设备。下一次必须使用新的run目录和当前Plan指纹，不能复用本轮失败数据或旧token。
