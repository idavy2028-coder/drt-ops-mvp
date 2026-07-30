# P4 位置事件容量验证记录（2026-07-30）

## 结论

通过。隔离 PostGIS 环境完成 4 辆车、每车 2,500 条、合计 10,000 条位置事件的应用服务写入；写入、历史查询、CSV 导出和最新快照四项耗时均低于 P4 设计门限。

## 执行边界

- 分支：`codex/p1-vehicle-location-calibration`
- 容量测试提交：`0e71db6`
- 测试类：`VehicleLocationCapacityIntegrationTest`
- 临时容器：`drt-ops-p4-location-capacity`
- 临时数据库端口：`127.0.0.1:15434`
- 当前试点数据库：未连接、未写入、未停止
- 测试事件写入路径：`VehicleLocationRecorder` + `VehicleLocationSnapshotService`
- 测试数据：固定测试 UUID 和非业务地址，不含乘客或运营联系方式

## 执行命令

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/pilot/Invoke-LocationCapacityValidation.ps1
```

执行日期：2026-07-30（Asia/Shanghai）
进程退出码：`0`

## 结果

| 验收项 | 实际结果 | 门限 | 判定 |
|---|---:|---:|---|
| 位置事件总数 | 10,000 | 10,000 | 通过 |
| 车辆数 | 4 | 4 | 通过 |
| 每车事件数 | 2,500 | 2,500 | 通过 |
| 应用服务写入耗时 | 210,830 ms | ≤ 600,000 ms | 通过 |
| 单车历史查询耗时 | 1,269 ms | ≤ 3,000 ms | 通过 |
| 全量 CSV 导出耗时 | 960 ms | ≤ 10,000 ms | 通过 |
| 最新快照查询耗时 | 34 ms | ≤ 1,000 ms | 通过 |
| 重复幂等编号 | 0 | 0 | 通过 |
| 车辆快照与各车最新事件一致 | 4/4 | 4/4 | 通过 |

测试输出摘要：

```text
P4_CAPACITY_RESULT events=10000 vehicles=4 perVehicle=2500 insertMs=210830 historyMs=1269 exportMs=960 snapshotMs=34
```

## 清理与后续

- 测试成功后已删除 `drt-ops-p4-location-capacity` 临时容器。
- 未创建或挂载持久卷。
- 容量 P0 阻断项解除，可以进入第二项“数据库备份恢复演练”。
