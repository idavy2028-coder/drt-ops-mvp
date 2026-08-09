# P4 数据库备份恢复演练记录（2026-07-30）

## 结论

通过。当前本地试点数据库已完成自定义格式逻辑备份，并在不挂载源数据卷、不发布数据库端口的独立 PostGIS 容器中从空库恢复。源库与恢复库关键计数一致，三类孤儿记录均为 0，PostGIS 查询通过，源数据库与 API 在演练后保持可用。

## 执行边界

- 源容器：`drt-ops-login-dev-postgres`
- 源数据库：`drt_ops_pilot_bootstrap`
- 备份目录：`D:\codex-projects\.pilot-backups\`（Git 仓库外）
- 恢复容器：`drt-ops-p4-restore-20260730-113756`
- 恢复目标：从 `template0` 创建的空数据库
- 源数据卷：未挂载、未停止、未重建
- 恢复容器端口：未发布
- 备份内容：未作为文本读取，未输出业务行、联系方式或凭据

## 成功演练产物

| 项目 | 结果 |
|---|---|
| 备份文件名 | `drt_ops_pilot_bootstrap-20260730-113756.dump` |
| 备份大小 | 91,039 字节 |
| SHA-256 | `9a71090198f2cab560dd066895421d14a355ce382efd52c68ca90832c3a45fb9` |
| 恢复摘要 | `p4-backup-restore-summary-20260730-113756.json` |
| 摘要验收 | 通过 |
| 恢复容器清理 | 已删除 |

执行命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/pilot/Invoke-BackupRestoreDrill.ps1
```

进程退出码：`0`

## 源库与恢复库一致性

| 核对项 | 源库 | 恢复库 | 判定 |
|---|---:|---:|---|
| Flyway 成功迁移 | 12 | 12 | 一致 |
| 车辆 | 4 | 4 | 一致 |
| 驾驶员 | 4 | 4 | 一致 |
| 虚拟站点 | 31 | 31 | 一致 |
| 位置事件找不到车辆的孤儿记录 | — | 0 | 通过 |
| 车辆任务找不到车辆的孤儿记录 | — | 0 | 通过 |
| 车辆任务找不到驾驶员的孤儿记录 | — | 0 | 通过 |
| PostGIS 与车辆点位查询 | — | 成功 | 通过 |

## 源服务连续性

- 源容器状态：`running`；该现有容器未配置 Docker healthcheck。
- `pg_isready`：`accepting connections`。
- API 健康：`UP`。
- 成功恢复容器：已确认不存在。

## 执行期问题与修正

1. 首次前置检查把“没有 Docker healthcheck”误判为“不健康”。只读核对确认源容器为 `running` 且 `pg_isready` 成功后，脚本改为“容器必须 running，并继续通过 pg_isready 验证真实数据库可用性”。
2. 首次恢复目标由 PostGIS 镜像预装了 `postgis_topology` 和 `postgis_tiger_geocoder`，导致 `pg_restore --clean` 无法删除其依赖的 `postgis`。修正为从 `template0` 创建空目标库后恢复，不使用 `CASCADE`。
3. 失败恢复容器完成诊断后已删除；失败演练备份 `drt_ops_pilot_bootstrap-20260730-113610.dump` 按失败留证策略保留在仓库外，不纳入成功门禁哈希。

## 后续

数据库备份恢复 P0 阻断项解除，可以进入第三项“管理端页面与审计告警链路验证”。成功备份及摘要继续保留，用于首日启动门禁复核。
