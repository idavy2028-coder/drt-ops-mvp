# P4 三项阻断项与首日试运行准备 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan.

**Goal:** 在不污染当前试点业务数据的前提下，依次完成位置事件容量、数据库备份恢复、管理端与审计告警链路三项 P0 验证，并形成首日 5–10 笔真实订单可启动或不可启动的书面结论。

**Architecture:** 容量验证在独立 PostGIS 数据库中通过应用服务写入 4 车共 10,000 条事件，恢复演练在独立 PostGIS 容器中验证当前试点库的逻辑备份，告警验证只在当前试点环境通过正式 HTTP API 制造一条可逆的区外位置事件。三项严格串行，任一项不通过即停止后续业务启动；验证脚本和自动化测试只输出计数、耗时、哈希和非敏感标识，不输出联系方式、密码、令牌或订单乘客信息。

**Tech Stack:** Java 21、Spring Boot 3.5、JUnit 5、PostgreSQL/PostGIS 16-3.5、Docker、PowerShell、Vue 3、Maven、Markdown。

## Global Constraints

- 当前工作树存在 P2 遗留的未提交文件，所有提交必须使用精确路径暂存，不得夹带这些文件。
- 不停止、不重建、不覆盖 `drt-ops-login-dev-postgres`，也不向当前试点库直接执行 SQL 业务写入。
- 容量库、恢复库使用独立容器和独立端口；成功后删除临时容器，失败时保留现场并记录容器名。
- 备份写入 `D:\codex-projects\.pilot-backups\`，不得进入 Git；记录中只写文件名、字节数和 SHA-256。
- 告警验证使用调度员正式接口写入，使用 `SYSTEM_ADMIN` 验收审计页与运营看板；不扩大 `dispatcher02` 权限。
- 区外事件写入后必须立即用已启用站点坐标上报新的区内快照；两个事件均保留为审计轨迹。
- ETA 采用当前估算且仅作参考；本计划不改变路径算法、派单参数或权限模型。
- 本计划不自动创建真实订单，不接触真实乘客姓名、电话或行程信息。

---

## File Map

- 新增 `apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationCapacityIntegrationTest.java`
  - 显式门控的 PostGIS 容量测试；通过 `VehicleLocationRecorder` 和 `VehicleLocationSnapshotService` 写入 10,000 条事件。
  - 测量批量写入、单车历史查询、全量 CSV 导出和 4 车最新快照耗时。
- 新增 `scripts/pilot/Invoke-LocationCapacityValidation.ps1`
  - 创建/清理独立容量容器，调用 Maven 容量测试并返回进程退出码。
- 新增 `scripts/pilot/Invoke-BackupRestoreDrill.ps1`
  - 对当前试点库执行自定义格式备份、SHA-256、独立容器恢复和只读一致性核对。
- 新增 `scripts/pilot/Test-BackupRestoreResult.ps1`
  - 对备份演练 JSON 摘要执行结构、阈值和一致性断言，不读取备份内容。
- 新增 `docs/pilot/evidence/p4-capacity-validation-2026-07-30.md`
- 新增 `docs/pilot/evidence/p4-backup-restore-drill-2026-07-30.md`
- 新增 `docs/pilot/evidence/p4-alert-chain-validation-2026-07-30.md`
- 新增 `docs/pilot/evidence/p5-day-1-readiness-2026-07-30.md`
- 修改 `docs/release/vehicle-location-acceptance-2026-07.md`
- 修改 `docs/release/mvp-readiness-checklist.md`
- 修改 `progress.md`

## Interfaces and Test Gates

### 容量测试系统属性

```text
drt.integration.capacity=true
drt.integration.postgis-url=jdbc:postgresql://127.0.0.1:15434/drt_ops_capacity
```

未显式设置 `drt.integration.capacity=true` 时，容量测试必须跳过，避免普通测试误跑 10,000 条写入。

### 容量验收阈值

```text
事件总数 = 10,000
车辆数 = 4
每车事件数 = 2,500
10,000 条应用服务写入耗时 <= 10 分钟
单车 2,500 条历史查询耗时 <= 3 秒
10,000 条 CSV 导出耗时 <= 10 秒
4 车最新快照查询耗时 <= 1 秒
幂等编号重复数 = 0
车辆快照事件编号与各车最新事件一致
```

### 备份恢复摘要

`Invoke-BackupRestoreDrill.ps1` 在仓库外输出 `p4-backup-restore-summary-<timestamp>.json`，只允许以下字段：

```json
{
  "backupFileName": "drt_ops_pilot_bootstrap-<timestamp>.dump",
  "backupBytes": 1,
  "sha256": "<64 位十六进制>",
  "restoreContainer": "drt-ops-p4-restore-<timestamp>",
  "source": {"schemaMigrations": 1, "vehicles": 4, "drivers": 4, "virtualStops": 31},
  "restored": {"schemaMigrations": 1, "vehicles": 4, "drivers": 4, "virtualStops": 31},
  "orphanCounts": {"vehicleLocationVehicle": 0, "taskVehicle": 0, "taskDriver": 0},
  "queryChecksPassed": true,
  "passed": true
}
```

脚本不得把数据库口令、连接串、用户联系方式或业务行内容写入摘要。

---

### Task 1: 先用测试定义容量门禁

**Files:**
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationCapacityIntegrationTest.java`

- [ ] **Step 1: 写出门控、数据量和阈值断言**

测试类使用 `@EnabledIfSystemProperty(named = "drt.integration.capacity", matches = "true")` 和独立 PostGIS 数据源。用固定 UUID 创建 4 辆测试车和一个测试操作人；服务区覆盖测试坐标。业务事件必须调用：

```java
LocationReportResult result = recorder.append(command);
snapshotService.apply(result.event());
```

测试以 `System.nanoTime()` 记录四段耗时，最后断言总数、每车数、幂等唯一性、快照一致性和四项阈值。

- [ ] **Step 2: 先运行测试并确认容量环境尚未就绪时失败**

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api '-Dtest=VehicleLocationCapacityIntegrationTest' '-Ddrt.integration.capacity=true' '-Ddrt.integration.postgis-url=jdbc:postgresql://127.0.0.1:15434/drt_ops_capacity' test
```

Expected: 因 15434 尚无容量容器而连接失败；证明测试没有静默跳过。

- [ ] **Step 3: 运行普通单测并确认容量测试默认跳过**

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api '-Dtest=VehicleLocationCapacityIntegrationTest' test
```

Expected: `Tests run: 1, Skipped: 1`，退出码 0。

- [ ] **Step 4: 提交容量测试**

```powershell
git add apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationCapacityIntegrationTest.java
git commit -m "test: define vehicle location capacity gate"
```

---

### Task 2: 实现并执行隔离容量验证

**Files:**
- Create: `scripts/pilot/Invoke-LocationCapacityValidation.ps1`
- Create: `docs/pilot/evidence/p4-capacity-validation-2026-07-30.md`

- [ ] **Step 1: 编写容量容器执行脚本**

脚本固定使用容器名 `drt-ops-p4-location-capacity` 和宿主机端口 `15434`。执行顺序：

1. 若同名容器存在则拒绝覆盖并退出。
2. 启动 `postgis/postgis:16-3.5`，数据库名 `drt_ops_capacity`。
3. 等待 `pg_isready`，超时即失败并保留容器。
4. 运行门控 Maven 测试。
5. 成功时删除容量容器；失败时保留容器并返回非零退出码。

- [ ] **Step 2: 对脚本做语法和安全边界检查**

```powershell
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
  (Resolve-Path 'scripts/pilot/Invoke-LocationCapacityValidation.ps1'),
  [ref]$null,
  [ref]$errors
) | Out-Null
if ($errors.Count -ne 0) { $errors; exit 1 }
Select-String -Path scripts/pilot/Invoke-LocationCapacityValidation.ps1 -Pattern 'drt-ops-login-dev-postgres|docker compose down|Remove-Item.*Recurse'
```

Expected: PowerShell 语法错误为 0；危险模式无匹配。

- [ ] **Step 3: 执行容量验证**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/pilot/Invoke-LocationCapacityValidation.ps1
```

Expected: Maven 测试通过，四项耗时均低于阈值，脚本退出码 0，临时容量容器已删除。

- [ ] **Step 4: 写入容量证据**

证据记录提交号、开始/结束时间、10,000/4/2,500 数据规模、四项实际耗时、完整测试命令、退出码和容器清理结果。不得记录测试数据库口令。

- [ ] **Step 5: 提交容量验证产物**

```powershell
git add scripts/pilot/Invoke-LocationCapacityValidation.ps1 docs/pilot/evidence/p4-capacity-validation-2026-07-30.md
git commit -m "test: validate pilot location event capacity"
```

**Checkpoint:** 只有 Task 2 全部通过才进入备份恢复；否则将 P4 标记为阻断并停止。

---

### Task 3: 先定义备份恢复摘要验收器

**Files:**
- Create: `scripts/pilot/Test-BackupRestoreResult.ps1`

- [ ] **Step 1: 编写失败样例并确认验收器拒绝**

```powershell
$invalid = Join-Path $env:TEMP 'p4-invalid-restore-summary.json'
'{"backupBytes":0,"sha256":"bad","passed":false}' | Set-Content -LiteralPath $invalid -Encoding utf8
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/pilot/Test-BackupRestoreResult.ps1 -SummaryPath $invalid
```

Expected: 非零退出码，并明确指出备份大小、哈希、计数或 `passed` 不满足要求。

- [ ] **Step 2: 实现摘要结构与一致性断言**

验收器必须检查：

- 备份字节数大于 0，SHA-256 为 64 位十六进制。
- `source` 与 `restored` 的迁移、车辆、驾驶员、虚拟站点计数完全相同。
- 三类孤儿计数均为 0。
- `queryChecksPassed` 和 `passed` 均为 `true`。
- JSON 不含 `password`、`token`、`phone`、`contact`、`jdbc:` 字段或值。

- [ ] **Step 3: 用合格的最小样例确认验收器通过**

```powershell
$valid = Join-Path $env:TEMP 'p4-valid-restore-summary.json'
'{"backupFileName":"x.dump","backupBytes":1,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","restoreContainer":"x","source":{"schemaMigrations":1,"vehicles":4,"drivers":4,"virtualStops":31},"restored":{"schemaMigrations":1,"vehicles":4,"drivers":4,"virtualStops":31},"orphanCounts":{"vehicleLocationVehicle":0,"taskVehicle":0,"taskDriver":0},"queryChecksPassed":true,"passed":true}' | Set-Content -LiteralPath $valid -Encoding utf8
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/pilot/Test-BackupRestoreResult.ps1 -SummaryPath $valid
```

Expected: 退出码 0。

- [ ] **Step 4: 提交摘要验收器**

```powershell
git add scripts/pilot/Test-BackupRestoreResult.ps1
git commit -m "test: define backup restore acceptance"
```

---

### Task 4: 实现并执行数据库备份恢复演练

**Files:**
- Create: `scripts/pilot/Invoke-BackupRestoreDrill.ps1`
- Create: `docs/pilot/evidence/p4-backup-restore-drill-2026-07-30.md`

- [ ] **Step 1: 编写演练脚本**

脚本只接受以下显式参数，敏感值通过安全字符串或当前容器环境读取，不写日志：

```powershell
param(
  [string]$SourceContainer = 'drt-ops-login-dev-postgres',
  [string]$SourceDatabase = 'drt_ops_pilot_bootstrap',
  [string]$BackupDirectory = 'D:\codex-projects\.pilot-backups'
)
```

执行顺序：

1. 校验源容器正在运行且数据库可连接。
2. 通过 `pg_dump -Fc` 将备份流写到仓库外文件。
3. 计算 SHA-256，并再次确认文件非空。
4. 启动 `drt-ops-p4-restore-<timestamp>`，不挂载源卷，使用随机空闲宿主机端口。
5. 用 `pg_restore --clean --if-exists --no-owner --no-privileges` 恢复。
6. 对源库和恢复库执行相同的只读计数查询。
7. 在恢复库核对三类外键孤儿、Flyway 迁移表、PostGIS 点位查询和关键列表查询。
8. 生成最小 JSON 摘要并调用 `Test-BackupRestoreResult.ps1`。
9. 成功时删除恢复容器；失败时保留容器和备份并输出非敏感诊断。

- [ ] **Step 2: 语法与危险目标检查**

```powershell
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
  (Resolve-Path 'scripts/pilot/Invoke-BackupRestoreDrill.ps1'),
  [ref]$null,
  [ref]$errors
) | Out-Null
if ($errors.Count -ne 0) { $errors; exit 1 }
Select-String -Path scripts/pilot/Invoke-BackupRestoreDrill.ps1 -Pattern 'docker volume rm|docker compose down|-v'
```

Expected: 语法错误为 0；脚本不删除卷、不执行 Compose down、不挂载源数据卷。

- [ ] **Step 3: 执行备份恢复演练**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/pilot/Invoke-BackupRestoreDrill.ps1
```

Expected: 备份非空、哈希生成、恢复成功、源/恢复计数一致、孤儿为 0、关键查询成功、摘要验收器退出码 0。

- [ ] **Step 4: 验证源服务未被中断**

```powershell
docker inspect --format '{{.State.Status}}|{{.State.Health.Status}}' drt-ops-login-dev-postgres
Invoke-RestMethod -Uri 'http://127.0.0.1:18081/actuator/health' -TimeoutSec 10 | ConvertTo-Json -Depth 4
```

Expected: 数据库 `running|healthy`，API 返回 `UP`。

- [ ] **Step 5: 写入并提交恢复证据**

证据只记录备份文件名、大小、SHA-256、计数对比、孤儿计数、查询结果、源服务健康和临时容器清理状态。

```powershell
git add scripts/pilot/Invoke-BackupRestoreDrill.ps1 docs/pilot/evidence/p4-backup-restore-drill-2026-07-30.md
git commit -m "ops: verify pilot database recovery"
```

**Checkpoint:** 只有 Task 4 全部通过才进入当前试点环境的告警写入；否则停止。

---

### Task 5: 执行只读告警基线检查

**Files:**
- Create: `docs/pilot/evidence/p4-alert-chain-validation-2026-07-30.md`

- [ ] **Step 1: 检查服务、账号权限和告警自然样本**

通过健康接口和只读 API 确认：

- API、管理端、数据库均健康。
- `dispatcher02` 仍仅有 `DISPATCHER` 权限。
- 审计页和运营看板由 `SYSTEM_ADMIN` 账号访问。
- 当前至少有一个“活动车辆且位置超过 30 分钟”的自然样本；若没有，则只记录“无自然样本”，不得伪造历史时间。

- [ ] **Step 2: 在管理端验收过期位置提示**

使用 `SYSTEM_ADMIN` 登录调度工作台，刷新后检查：

- 存在自然样本时，页面出现“位置较久未更新”区域、车牌和“超过 30 分钟”文案。
- 无自然样本时，记录该分支不适用，不将其误判为链路失败。

保留非敏感截图或文字证据，不捕获令牌、密码、联系方式。

- [ ] **Step 3: 记录告警前审计基线**

通过 `/api/audit-logs` 获取 `VEHICLE_LOCATION_REPORTED` 当前条数和最新记录时间。只记录条数、动作名和非敏感实体编号。

---

### Task 6: 执行区外告警写入、审计验收和区内纠正

**Files:**
- Modify: `docs/pilot/evidence/p4-alert-chain-validation-2026-07-30.md`

- [ ] **Step 1: 选定可逆测试车辆和坐标**

从正式 API 读取：

- 一辆 `IDLE` 且可上报的车辆。
- 当前启用服务区边界。
- 一个已启用、位于服务区内的虚拟站点作为恢复坐标。

区外测试坐标必须基于已发布边界计算并在提交前用服务区包含接口确认 `inside=false`；不得凭空填写运营坐标。

- [ ] **Step 2: 通过正式 API 写入区外位置**

以 `dispatcher02` 调用：

```text
POST /api/vehicles/{vehicleId}/location-reports
eventType = MANUAL_REPORT
driverReportedAt = 当前时间
idempotencyKey = 新 UUID
note = P4 告警链路验证：区外位置
```

Expected: HTTP 201，`warnings` 精确包含 `OUTSIDE_SERVICE_AREA`，返回事件编号且 `replayed=false`。

- [ ] **Step 3: 立即通过正式 API恢复区内快照**

使用同一车辆和已启用站点坐标，新的 `driverReportedAt` 与新的幂等编号再次上报。

Expected: HTTP 201，`warnings` 不含 `OUTSIDE_SERVICE_AREA`；最新车辆快照指向区内事件。

- [ ] **Step 4: 用管理员验收管理端与审计页**

用 `SYSTEM_ADMIN`：

- 在位置历史页查到区外事件和随后发生的区内恢复事件。
- 在审计页查到两条新增 `VEHICLE_LOCATION_REPORTED`，实体编号均为目标车辆。
- 审计新增数量相对基线恰好增加 2。
- 运营看板刷新后目标车辆显示恢复后的区内最新位置。

- [ ] **Step 5: 做告警后只读一致性检查**

确认目标车辆最新事件为区内恢复事件，4 辆试点车辆仍存在，车辆与驾驶员状态未被告警验证改变，活动重复任务仍为 0。

- [ ] **Step 6: 完成并提交告警证据**

证据记录账号角色、接口状态码、告警枚举、事件编号、审计动作与数量变化、恢复结果和看板结果；不得记录密码、令牌或完整请求头。

```powershell
git add docs/pilot/evidence/p4-alert-chain-validation-2026-07-30.md
git commit -m "test: verify pilot alert notification chain"
```

**Checkpoint:** 区外写入成功但区内恢复失败时，P4 必须标记为阻断，并在继续任何订单前完成恢复。

---

### Task 7: 执行首日启动门禁

**Files:**
- Create: `docs/pilot/evidence/p5-day-1-readiness-2026-07-30.md`
- Modify: `docs/release/mvp-readiness-checklist.md`
- Modify: `docs/release/vehicle-location-acceptance-2026-07.md`
- Modify: `progress.md`

- [ ] **Step 1: 只读核对首日资源**

逐项确认：

- API、管理端、数据库、算法、本地模拟路由均健康。
- 已启用站点 31 个，均在已发布服务区内。
- 4 辆车为 `IDLE`，4 名驾驶员为 `AVAILABLE`，无活动重复任务。
- 规则仍为 5/8/60 分钟、阈值 82/62、权重 0.35/0.20/0.30/0.15、`REALTIME_INSERTION`。
- `dispatcher02` 已完成首次改密；若仍为 `must_change_password=true`，首日启动结论必须为阻断。
- 管理员账号可访问审计页和运营看板。
- 三项 P0 证据均为通过，数据库备份可定位且哈希可复核。

- [ ] **Step 2: 写入首日操作边界**

首日记录必须明确：

- 计划量为 5–10 笔真实订单。
- 订单必须由授权操作员录入，不由脚本生成。
- 只录入获得授权的真实乘客数据。
- ETA 使用虚拟站点/人工坐标和当前估算，仅作参考。
- 出现容量、数据恢复、告警、派单或位置恢复异常时立即停止新增订单。

- [ ] **Step 3: 更新阶段文档**

`progress.md` 和两份 release 文档只写已取得的事实证据。若任一门禁未通过，使用“阻断”而不是“完成”。

- [ ] **Step 4: 运行最终回归**

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api test
npm.cmd --prefix apps/admin-web run test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
python -m unittest apps/route-simulator/test_server.py -v
```

Expected: 后端、前端、类型检查、构建和路由模拟器测试均为 0 失败。

- [ ] **Step 5: 提交 P4 与首日准备结论**

```powershell
git add docs/pilot/evidence/p5-day-1-readiness-2026-07-30.md docs/release/mvp-readiness-checklist.md docs/release/vehicle-location-acceptance-2026-07.md progress.md
git commit -m "docs: close P4 first-day readiness gates"
```

---

### Task 8: 最终提交范围与安全复核

- [ ] **Step 1: 检查提交范围**

```powershell
git status --short
git log --oneline 8c1b049..HEAD
git diff --check 8c1b049..HEAD
```

Expected: 只包含本计划列出的 P4/P5 文件；P2 遗留文件仍保持原状态，`git diff --check` 无错误。

- [ ] **Step 2: 扫描敏感信息和占位符**

```powershell
rg -n -i "password|bearer |authorization:|jdbc:postgresql:|TODO|TBD|待补|占位" `
  scripts/pilot `
  docs/pilot/evidence/p4-capacity-validation-2026-07-30.md `
  docs/pilot/evidence/p4-backup-restore-drill-2026-07-30.md `
  docs/pilot/evidence/p4-alert-chain-validation-2026-07-30.md `
  docs/pilot/evidence/p5-day-1-readiness-2026-07-30.md
```

Expected: 脚本参数名之外无凭据，证据文件无密码、令牌、连接串或未完成占位符。

- [ ] **Step 3: 给出启动结论**

仅当三个 P0 验证、首日资源门禁和最终回归全部通过时，结论为：

```text
P4 三项 P0 阻断项已通过；当前本地试点环境已具备首日 5–10 笔真实订单的启动条件。
```

否则列出精确阻断项，不创建、不提交任何真实订单。
