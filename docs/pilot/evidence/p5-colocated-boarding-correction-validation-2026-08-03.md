# P5 同站点多人上车修复与任务纠正验收（2026-08-03）

## 验收结论

通过。自动派单与人工复核现在按同站点插入上车节点；一次到站可联动相邻同站点上车节点，但每笔订单仍需单独确认上车并分别审计。试点任务 `1626d98d-d792-4a7d-91eb-40f6ccbba5be` 已安全纠正，原始位置事件和审计均保留。

## 代码与测试

- 设计提交：`adbaa6c docs: design co-located boarding insertion`
- 实施计划提交：`debedb9 docs: plan co-located boarding insertion fix`
- 插入策略提交：`78ecf5f fix: insert co-located boarding stops together`
- 双派单路径提交：`5bda4fa fix: share insertion policy across dispatch paths`
- 共享到站提交：`2c4a7df fix: share arrival across co-located boarding stops`
- 审查修复提交：`19d16ff fix: normalize inserted task stop sequences`

测试先行证据：

1. 新测试在旧实现上失败：第二个同站上车节点仍为 `PLANNED`，随后上车返回 `409 当前任务节点不能执行上车`。
2. 最小实现完成后执行：

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api "-Dtest=CoLocatedBoardingTaskExecutionTest,TaskExecutionApiTest,TaskLocationTransactionTest" test
```

退出码：`0`。

3. 完整 API 回归：

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test
```

退出码：`0`；耗时约 `280.7s`；无失败测试。

4. 审查修复回归：新增序号空洞、连续同站组三节点、非连续同站不联动、共享到站幂等重放测试。执行：

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api "-Dtest=TaskStopInsertionPolicyTest,CoLocatedBoardingTaskExecutionTest,TaskExecutionApiTest,TaskLocationTransactionTest" test
```

退出码：`0`。序号空洞测试在修复前得到 `1,3,4,4` 并失败；修复后所有追加路径均通过 `insertStop(size, stop)` 统一重编号为连续序列。

5. 审查修复后的完整 API 回归：再次执行完整 API 测试，退出码为 `0`，耗时 `326.5s`；确认序号连续性修复未引入回归。

## 部署

- 部署目标：`drt-ops-pilot-api-debug`，绑定当前 worktree。
- 操作：仅重启 API 容器；未重启 PostgreSQL，未重建数据卷。
- 健康检查：`http://127.0.0.1:18081/actuator/health`。
- 结果：`status = UP`，liveness/readiness 可用。
- 启动日志：`DrtOpsApplication` 于 `2026-08-03T04:58:39.332Z` 启动完成。
- 审查修复 `19d16ff` 二次部署后，`DrtOpsApplication` 于 `2026-08-03T05:28:49.906Z` 启动完成，健康状态仍为 `UP`。

## 纠正事务

- 目标任务：`1626d98d-d792-4a7d-91eb-40f6ccbba5be`。
- 目标车辆：`甘J00856D`。
- 操作者：`dispatcher02`，用户 ID `35e8db28-9cfb-4c3b-a107-4a7de7083f04`。
- 原因：`COLOCATED_BOARDING_INSERTION_ORDER_CORRECTION`。
- SQL：`p5-colocated-boarding-correction-2026-08-03.sql`。

### 回滚演练

脚本以 `ROLLBACK` 结尾执行，输出：

```text
BEGIN
DO
UPDATE 1
UPDATE 1
UPDATE 1
INSERT 0 1
INSERT 0 2
DO
ROLLBACK
```

回滚后复核：原 4 个节点顺序和状态完整恢复；固定纠正事件 ID 计数为 `0`；固定纠正审计 ID 计数为 `0`。

### 正式提交

回滚演练通过后，仅将脚本末尾改为 `COMMIT` 并执行一次。输出：

```text
BEGIN
DO
UPDATE 1
UPDATE 1
UPDATE 1
INSERT 0 1
INSERT 0 2
DO
COMMIT
```

提交时间：数据库新增位置事件记录时间 `2026-08-03 05:03:20.836592+00`。

## 纠正后节点

| 序号 | 节点 ID | 类型 | 订单短号 | 站点 | 状态 | 实际到站时间 |
|---:|---|---|---|---|---|---|
| 1 | `fb044aac-d48a-4e36-a8f8-26660f528bda` | BOARDING | `a9c1c313` | 高铁站 | BOARDED | `2026-08-03 03:57:05.599807+00` |
| 2 | `9ffda872-4d30-4a06-89fb-8dae41fe995c` | BOARDING | `0440ec69` | 高铁站 | BOARDED | `2026-08-03 03:57:05.599807+00` |
| 3 | `8f4ffcc7-48e2-492f-89fc-844580445913` | ALIGHTING | `a9c1c313` | 安川 | PLANNED | — |
| 4 | `4b7f1206-4fb9-480f-84fd-d0a3eb9cf6c1` | ALIGHTING | `0440ec69` | 陇阳镇 | PLANNED | — |

任务仍为 `IN_PROGRESS`；两笔订单仍为 `IN_PROGRESS`；车辆仍为 `IN_SERVICE`，未释放。

## 新增位置事件与审计

### 位置事件

- ID：`b995ae6c-de46-40fa-8a68-0c0f48a8e5bc`
- 类型：`PASSENGER_BOARDED`
- 节点：`9ffda872-4d30-4a06-89fb-8dae41fe995c`
- 虚拟站点：`f4d512d5-0eac-44ef-9b9e-d8d94c33282b`（高铁站）
- 驾驶员上报时间：`2026-08-03 03:57:00+00`
- 备注：`COLOCATED_BOARDING_INSERTION_ORDER_CORRECTION`
- `snapshot_applied = false`，因此不会用历史时间覆盖车辆当前待命/运行位置快照。

### 审计

| 审计 ID | 动作 | 原因 | 引用位置事件 |
|---|---|---|---|
| `fabe29aa-c5e5-42aa-b6e8-57d0c07427c7` | PASSENGER_BOARDED | 第二笔上车节点 ID | `b995ae6c-de46-40fa-8a68-0c0f48a8e5bc` |
| `fe010501-f2c1-4ef3-b5cd-de167a21a17f` | TASK_COLOCATED_BOARDING_CORRECTED | COLOCATED_BOARDING_INSERTION_ORDER_CORRECTION | `b995ae6c-de46-40fa-8a68-0c0f48a8e5bc` |

首轮纠正后，三条原始审计仍存在，总审计数由 `3` 增至 `5`，没有更新或删除历史记录。

## 独立审查补救

独立代码审查指出：首个纠正审计缺少原/新序列与现场确认依据，且正式纠正 SQL 对被复制位置事实的精确字段断言不足。处理方式是不修改或删除已有记录，而是执行独立补充脚本：

- 脚本：`p5-colocated-boarding-correction-audit-supplement-2026-08-03.sql`
- 固定审计 ID：`1078b27e-5700-49c4-bbf5-34c7070e383b`
- 动作：`TASK_COLOCATED_BOARDING_CORRECTED`
- 依据代码：`DISPATCHER_CONFIRMED_COLOCATED_BOARDING`
- 依据原文：`现场确认两名乘客在高铁站同时上车`
- 元数据包含：原 4 节点序列、新 4 节点序列、原纠正执行时间、两个订单 ID、两个上车节点 ID、共享到站事件、源上车事件和纠正上车事件。

补充脚本在插入前逐项断言：任务/车辆/订单/账号状态；共享到站事件和第一笔上车事件的车辆、任务、节点、虚拟站点、来源、经纬度、坐标系、地址、驾驶员上报时间、记录时间和操作者；两节点共享的历史实际到站时间；已提交纠正事件与源事件的位置、时间及状态一致性。

第一次回滚演练发现 geography 需要显式转 geometry，事务自动回滚且未新增审计。修正后回滚演练输出：

```text
BEGIN
DO
INSERT 0 1
DO
ROLLBACK
supplement_count=0
```

正式执行输出：

```text
BEGIN
DO
INSERT 0 1
DO
COMMIT
```

补充后任务级审计总数为 `6`：3 条原审计、2 条首轮追加审计、1 条上下文补充审计。所有记录均为追加式保存。

## 浏览器验收

- 页面：`http://127.0.0.1:5174/tasks?taskId=1626d98d-d792-4a7d-91eb-40f6ccbba5be`
- 只读验收账号会话：`dispatcher01`。
- 目标行：`1626d98d / 甘J00856D / 执行中`。
- 时间线：
  1. 上车站 · 已上车
  2. 上车站 · 已上车
  3. 下车站 · 计划中
  4. 下车站 · 计划中
- 操作状态：仅“到站”可用；“发车、上车、下车、完成”均禁用。
- 数据库确认第 3 节点为安川，因此下一正确动作是安川下车站“到站”。
- 验收过程中未点击任何任务动作按钮，未产生新的业务事件。
