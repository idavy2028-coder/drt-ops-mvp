# 车辆位置管理模块详细实施计划

> **执行要求：** 实施时必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，严格逐任务执行并在任务间审阅；用复选框（`- [ ]`）跟踪步骤。

**目标：** 为通渭县单机试点实现可追溯的人工车辆位置上报、最新位置快照、任务动作原子提交、调度地图展示、历史查询导出和运行指标。

**架构：** 后端新增独立 `domain.location` 边界，以不可变位置事件作为事实源，以 `vehicles` 最新位置字段作为查询快照。任务动作通过位置记录服务在同一事务中推进任务、追加事件、更新快照和写入审计；前端通过位置确认面板提交一次业务命令，并通过查询接口轮询最新快照。

**技术栈：** Java 21、Spring Boot 3.5.3、Spring Security、Spring Data JPA、Flyway、PostgreSQL/PostGIS、H2、Micrometer、Vue 3、TypeScript、Vite、Vitest、Testing Library、Playwright。

## 执行环境前提

- 所有命令默认从仓库根目录 `D:\codex-projects` 执行。
- Java 21、Node.js、`npm.cmd` 和 PostgreSQL/PostGIS 必须可用。
- 当前仓库未提交 Maven Wrapper，且 `mvn` 不在本机 `PATH`。开始实施前设置 `$env:MAVEN_CMD` 指向 Maven 3.9.11 的 `mvn.cmd`；当前机器可使用 `D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd`。所有后端命令统一通过 `& $env:MAVEN_CMD ...` 执行。
- 真实 PostGIS 迁移测试需要可访问的 Docker/Testcontainers 或本机 `127.0.0.1:15432` 测试库；不能因环境不可用而把测试跳过视为通过。

## 全局约束

- 坐标体系固定为 `GCJ02`，来源固定为 `MANUAL_DISPATCHER`；道路距离和 ETA 不由本模块计算。
- `vehicle_location_events` 只允许追加，禁止更新和删除；修正通过新事件表达。
- 任务动作、位置事件、车辆快照和审计日志必须处于同一数据库事务。
- `driverReportedAt` 不得晚于服务器当前时间；历史补录使用独立位置上报接口。
- 较旧事件可以保存，但不得回退车辆快照。
- 服务区外位置保存成功并返回 `OUTSIDE_SERVICE_AREA` 警告。
- 任务动作必须携带位置请求和幂等编号；重复请求返回第一次结果。
- 前端必须显示“人工上报”和最后反馈时间，不得使用“GPS 在线”或“实时轨迹”表述。
- 地图每 15 秒轮询；执行中车辆 30 分钟无新位置时告警，阈值由配置项控制。
- 高德地址搜索、地图选点、电子围栏和虚拟站点管理是独立工作流。本计划定义 `LocationCandidate` 接口并交付经纬度/虚拟站点降级录入；真实高德适配器需在试点总体验收前由独立计划接入。
- 每个任务完成后运行聚焦测试、全量相关测试和代码审阅，再进入下一任务。

---

### Task 1: 建立位置事件表和车辆快照持久化模型

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V6__create_vehicle_location_events.sql`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationEventType.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationSource.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEvent.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEventRepository.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/fleet/Vehicle.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/fleet/VehicleRepository.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationEventRepositoryTest.java`

**Interfaces:**
- Consumes: `vehicles`、`vehicle_tasks`、`task_stops`、`virtual_stops` 现有主键。
- Produces: `VehicleLocationEvent`、`VehicleLocationEventRepository`、车辆快照更新方法和悲观锁查询。

- [ ] **Step 1: 写出失败的迁移结构测试**

在 `DatabaseMigrationTest` 中增加对以下结构的断言：

```java
assertColumns(connection, "vehicle_location_events",
        "id", "vehicle_id", "vehicle_task_id", "task_stop_id", "virtual_stop_id",
        "event_type", "source", "location", "longitude", "latitude",
        "coordinate_system", "standardized_address", "driver_reported_at",
        "recorded_at", "recorded_by", "note", "correction_reason",
        "corrects_event_id", "idempotency_key", "request_fingerprint", "snapshot_applied",
        "outside_service_area");
assertColumns(connection, "vehicles",
        "current_location_address", "current_location_source",
        "current_location_coordinate_system", "current_location_reported_at",
        "current_location_recorded_at", "current_location_event_id",
        "current_location_task_id");
```

并通过 JDBC 尝试更新和删除位置事件，断言数据库拒绝两种操作。

- [ ] **Step 2: 运行迁移测试并确认失败**

Run:

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=DatabaseMigrationTest' '-Ddrt.integration.postgis=true' test
```

Expected: FAIL，提示 `vehicle_location_events` 不存在或缺少目标列。

- [ ] **Step 3: 新增 V6 迁移**

迁移必须创建事件表、索引、唯一约束、车辆快照字段和不可变触发器，核心 SQL 为：

```sql
CREATE TABLE vehicle_location_events (
  id UUID PRIMARY KEY,
  vehicle_id UUID NOT NULL REFERENCES vehicles(id),
  vehicle_task_id UUID REFERENCES vehicle_tasks(id),
  task_stop_id UUID REFERENCES task_stops(id),
  virtual_stop_id UUID REFERENCES virtual_stops(id),
  event_type VARCHAR(40) NOT NULL,
  source VARCHAR(40) NOT NULL CHECK (source IN ('MANUAL_DISPATCHER', 'GPS_DEVICE')),
  location geography(POINT, 4326) NOT NULL,
  longitude NUMERIC(10,7) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  latitude NUMERIC(10,7) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  coordinate_system VARCHAR(20) NOT NULL CHECK (coordinate_system = 'GCJ02'),
  standardized_address VARCHAR(300) NOT NULL,
  driver_reported_at TIMESTAMPTZ NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  recorded_by UUID NOT NULL REFERENCES user_accounts(id),
  note VARCHAR(500),
  correction_reason VARCHAR(500),
  corrects_event_id UUID REFERENCES vehicle_location_events(id),
  idempotency_key UUID NOT NULL UNIQUE,
  request_fingerprint VARCHAR(64) NOT NULL,
  snapshot_applied BOOLEAN NOT NULL,
  outside_service_area BOOLEAN NOT NULL
);

CREATE INDEX idx_vehicle_location_vehicle_time
  ON vehicle_location_events(vehicle_id, driver_reported_at DESC);
CREATE INDEX idx_vehicle_location_task_time
  ON vehicle_location_events(vehicle_task_id, driver_reported_at ASC);
CREATE INDEX idx_vehicle_location_recorded_at
  ON vehicle_location_events(recorded_at DESC);
CREATE INDEX idx_vehicle_location_point
  ON vehicle_location_events USING GIST(location);
```

触发器函数对 `UPDATE` 和 `DELETE` 统一抛出 `vehicle location events are immutable`。随后为 `vehicles` 增加设计文档中的七个快照字段及外键。

- [ ] **Step 4: 创建领域枚举和不可变实体**

枚举值必须与接口动作一一对应：

```java
public enum LocationEventType {
    TASK_STARTED,
    PICKUP_ARRIVED,
    PASSENGER_BOARDED,
    DROPOFF_ARRIVED,
    PASSENGER_ALIGHTED,
    TASK_COMPLETED,
    MANUAL_CORRECTION
}

public enum LocationSource {
    MANUAL_DISPATCHER,
    GPS_DEVICE
}
```

`VehicleLocationEvent` 只提供静态工厂和 getter，不提供修改方法。`Vehicle` 增加 `applyLocationSnapshot(...)`，仅当新反馈时间不早于当前快照时更新并返回 `true`。

- [ ] **Step 5: 增加悲观锁和仓储查询**

`VehicleRepository` 增加：

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select vehicle from Vehicle vehicle where vehicle.id = :id")
Optional<Vehicle> findByIdForLocationUpdate(@Param("id") UUID id);
```

`VehicleLocationEventRepository` 提供按幂等编号、车辆、任务和日期排序查询。幂等命中时必须比较服务端生成的 `request_fingerprint`；同一编号但请求语义不同返回 409，禁止静默复用旧结果。

- [ ] **Step 6: 运行持久化验证**

Run:

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationEventRepositoryTest' test
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=DatabaseMigrationTest' '-Ddrt.integration.postgis=true' test
```

Expected: H2 仓储测试通过；Docker/PostGIS 可用时迁移和不可变触发器测试通过。若本机 Testcontainers 仍受 named pipe 限制，必须改用现有 `127.0.0.1:15432` PostGIS 持久化测试验证 V6，不能把跳过视为通过。

- [ ] **Step 7: 提交 Task 1**

```powershell
git add apps/api/src/main/resources/db/migration/V6__create_vehicle_location_events.sql apps/api/src/main/java/com/idavy/drtops/domain/location/LocationEventType.java apps/api/src/main/java/com/idavy/drtops/domain/location/LocationSource.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEvent.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEventRepository.java apps/api/src/main/java/com/idavy/drtops/domain/fleet/Vehicle.java apps/api/src/main/java/com/idavy/drtops/domain/fleet/VehicleRepository.java apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationEventRepositoryTest.java
git commit -m "feat: add vehicle location persistence"
```

### Task 2: 实现位置记录、快照和服务区警告服务

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationReportCommand.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationWarning.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationReportResult.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/ServiceAreaLocationChecker.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/PostgisServiceAreaLocationChecker.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationRecorder.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationSnapshotService.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationRecorderTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/PostgisServiceAreaLocationCheckerTest.java`

**Interfaces:**
- Consumes: Task 1 的事件仓储、车辆悲观锁和快照更新方法。
- Produces: `VehicleLocationRecorder.append(...)` 和 `VehicleLocationSnapshotService.apply(...)`，供独立补报接口和任务状态机按既定事务顺序共同调用。

- [ ] **Step 1: 定义命令和结果类型**

```java
public record LocationReportCommand(
        UUID vehicleId,
        UUID vehicleTaskId,
        UUID taskStopId,
        UUID virtualStopId,
        LocationEventType eventType,
        BigDecimal longitude,
        BigDecimal latitude,
        String standardizedAddress,
        OffsetDateTime driverReportedAt,
        UUID recordedBy,
        String note,
        String correctionReason,
        UUID correctsEventId,
        UUID idempotencyKey) {
}

public enum LocationWarning {
    OUTSIDE_SERVICE_AREA,
    HISTORICAL_EVENT_NOT_APPLIED_TO_SNAPSHOT,
    MAP_PROVIDER_DEGRADED
}

public record LocationReportResult(
        VehicleLocationEvent event,
        List<LocationWarning> warnings,
        boolean replayed) {
}
```

- [ ] **Step 2: 写出失败的领域服务测试**

覆盖以下独立场景：

- 新事件更新车辆快照。
- 较旧事件保存但 `snapshotApplied=false`。
- 重复幂等编号返回同一事件。
- 同一幂等编号携带不同请求指纹时返回 409。
- 未来反馈时间返回 400。
- 服务区外事件保存并返回警告。
- 修正事件缺少原事件或原因时返回 400。

- [ ] **Step 3: 运行聚焦测试并确认失败**

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationRecorderTest' test
```

Expected: FAIL，因为位置记录服务尚不存在。

- [ ] **Step 4: 实现服务区检查端口和 PostGIS 适配器**

端口只暴露：

```java
public interface ServiceAreaLocationChecker {
    boolean isInsideEnabledArea(BigDecimal longitude, BigDecimal latitude);
}
```

PostGIS 适配器使用 `JdbcTemplate` 查询任一启用服务区是否覆盖输入点。自动化领域测试注入内存实现，不依赖 PostGIS。

另建 PostGIS 集成测试，写入一个启用围栏和围栏内外两个 `GCJ02` 点，验证 `ST_Covers` 对边界内、边界上和边界外的判断。该测试使用与迁移测试相同的真实 PostGIS 启动策略。

- [ ] **Step 5: 实现位置记录服务**

`findReplay(idempotencyKey, requestFingerprint)` 先检查已成功请求；命中时返回原事件、原警告语义和 `replayed=true`。`append(command)` 再校验时间和修正关系、悲观锁读取车辆、计算服务区警告及快照是否应推进，并创建不可变事件。`VehicleLocationSnapshotService.apply(event)` 仅在 `snapshotApplied=true` 时更新已锁定车辆。

记录器和快照服务不直接写审计，避免任务动作产生两条审计。任务动作由 `TaskExecutionService` 写一条原动作审计；独立补报和修正由 Task 3 的命令服务写一条位置审计。两种调用方都在外层 `@Transactional` 中按“追加事件、推进业务状态、更新快照、写单条审计”的顺序提交。时间由构造函数注入 `Clock`，测试固定时钟，生产使用 `Clock.systemUTC()`。

- [ ] **Step 6: 验证 Task 2**

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationRecorderTest,VehicleLocationEventRepositoryTest' test
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=PostgisServiceAreaLocationCheckerTest' '-Ddrt.integration.postgis=true' test
```

Expected: 所有位置记录、快照、幂等和警告测试通过。

- [ ] **Step 7: 提交 Task 2**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/location/LocationReportCommand.java apps/api/src/main/java/com/idavy/drtops/domain/location/LocationWarning.java apps/api/src/main/java/com/idavy/drtops/domain/location/LocationReportResult.java apps/api/src/main/java/com/idavy/drtops/domain/location/ServiceAreaLocationChecker.java apps/api/src/main/java/com/idavy/drtops/domain/location/PostgisServiceAreaLocationChecker.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationRecorder.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationSnapshotService.java apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationRecorderTest.java apps/api/src/test/java/com/idavy/drtops/domain/location/PostgisServiceAreaLocationCheckerTest.java
git commit -m "feat: record vehicle location events"
```

### Task 3: 增加位置查询、补报、修正、导出和 RBAC

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationReportRequest.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationView.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationSnapshotView.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationReportResponse.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationCommandService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationExportService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/fleet/VehicleController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/fleet/VehicleView.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/auth/Permission.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationApiTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/auth/AuthorizationApiTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/fleet/FleetApiTest.java`

**Interfaces:**
- Consumes: `VehicleLocationRecorder`、`VehicleLocationSnapshotService` 和位置事件仓储。
- Produces: 已确认的四个位置接口、CSV 导出和不暴露 WKT 的车辆 DTO。

- [ ] **Step 1: 扩展权限模型并先写失败测试**

新增权限：

```java
LOCATION_READ,
LOCATION_REPORT,
LOCATION_CORRECT,
LOCATION_EXPORT
```

角色映射锁定为：调度员拥有 `LOCATION_READ`、`LOCATION_REPORT`；系统管理员拥有四项权限；其他角色不自动获得位置权限。前端权限表在 Task 5 同步。

授权测试必须验证调度员可补报和查询、不能修正或导出；系统管理员可修正和导出；未授权角色返回 403。

- [ ] **Step 2: 定义 API DTO**

`LocationReportRequest` 使用 Bean Validation 校验坐标、地址、反馈时间、幂等编号和可选关联对象。`LocationReportResponse` 返回事件视图、`snapshotApplied` 和警告数组。`VehicleLocationView` 只返回经纬度、地址、来源、业务时间、录入时间、操作人和关联编号。

- [ ] **Step 3: 写出失败的 API 测试**

覆盖：普通补报、管理员修正、重复幂等、旧事件提示、服务区外警告、车辆历史筛选、任务事件链、最新快照列表和 CSV 导出审计。日期筛选增加上海自然日跨 UTC 日界用例。

- [ ] **Step 4: 运行 API 测试并确认失败**

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationApiTest,AuthorizationApiTest,FleetApiTest' test
```

Expected: FAIL，接口、权限和车辆 DTO 尚不存在。

- [ ] **Step 5: 实现查询和补报控制器**

路由固定为：

```text
POST /api/vehicles/{vehicleId}/location-reports
GET  /api/vehicles/locations/latest
GET  /api/vehicles/{vehicleId}/location-events
GET  /api/vehicle-tasks/{taskId}/location-events
GET  /api/vehicle-locations/export.csv
```

同一个补报接口在 `correctsEventId` 非空时要求 `LOCATION_CORRECT`，否则要求 `LOCATION_REPORT`。控制器调用 `VehicleLocationCommandService`，由该服务在一个事务中依次追加事件、更新快照并写入唯一一条 `VEHICLE_LOCATION_REPORTED` 或 `VEHICLE_LOCATION_CORRECTED` 审计；幂等重放不重复更新快照或写审计。查询按含时区的 `from`、`to`、`taskId`、`eventType` 筛选并按 `driverReportedAt` 排序；上海日期使用 `ZoneId.of("Asia/Shanghai")` 生成左闭右开区间，禁止在后端按 UTC 字符串截取日期。

- [ ] **Step 6: 实现车辆 DTO 和 CSV 导出审计**

`VehicleController.list()` 和 `create()` 改为返回 `VehicleView`，包含可空 `latestLocation`，不返回 WKT。导出固定使用 UTF-8 BOM CSV，并在成功后写入 `VEHICLE_LOCATION_EXPORT` 审计，元数据包含筛选条件和记录数。

- [ ] **Step 7: 验证 Task 3**

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationApiTest,AuthorizationApiTest,FleetApiTest' test
& $env:MAVEN_CMD -q -pl apps/api test
```

Expected: 聚焦测试与后端全量测试通过。

- [ ] **Step 8: 提交 Task 3**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/location/LocationReportRequest.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationView.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationSnapshotView.java apps/api/src/main/java/com/idavy/drtops/domain/location/LocationReportResponse.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationCommandService.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationController.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationExportService.java apps/api/src/main/java/com/idavy/drtops/domain/fleet/VehicleController.java apps/api/src/main/java/com/idavy/drtops/domain/fleet/VehicleView.java apps/api/src/main/java/com/idavy/drtops/auth/Permission.java apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationApiTest.java apps/api/src/test/java/com/idavy/drtops/auth/AuthorizationApiTest.java apps/api/src/test/java/com/idavy/drtops/domain/fleet/FleetApiTest.java
git commit -m "feat: expose vehicle location operations"
```

### Task 4: 将任务状态机与位置上报原子集成

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskActionRequest.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskActionResponse.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskController.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskExecutionService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/task/TaskLocationTransactionTest.java`

**Interfaces:**
- Consumes: `VehicleLocationRecorder.findReplay(...)`、`VehicleLocationRecorder.append(...)` 和 `VehicleLocationSnapshotService.apply(...)`。
- Produces: 所有任务动作返回 `TaskActionResponse(task, locationEvent, warnings)`。

- [ ] **Step 1: 定义任务动作请求和响应**

```java
public record TaskActionRequest(
        @NotNull @Valid LocationReportRequest locationReport) {
}

public record TaskActionResponse(
        VehicleTask task,
        VehicleLocationView locationEvent,
        boolean snapshotApplied,
        List<LocationWarning> warnings,
        boolean replayed) {
}
```

- [ ] **Step 2: 改写现有 API 测试使其先失败**

为发车、两次到站、上车、下车和完成请求分别提交唯一幂等编号和位置。断言每个首次成功动作恰好生成一条事件和一条任务审计，重复请求不增加事件或审计，并验证事件类型序列：

```text
TASK_STARTED -> PICKUP_ARRIVED -> PASSENGER_BOARDED ->
DROPOFF_ARRIVED -> PASSENGER_ALIGHTED -> TASK_COMPLETED
```

- [ ] **Step 3: 增加事务失败测试**

覆盖四条原子性和并发路径：未来位置时间导致任务状态不变且无事件；非法任务状态导致位置事件和快照均不写入；状态已推进后的同幂等请求仍返回原事件；两个并发相同请求通过任务悲观锁只推进一次状态、生成一条事件和一条审计。

- [ ] **Step 4: 运行测试并确认失败**

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=TaskExecutionApiTest,TaskLocationTransactionTest' test
```

Expected: FAIL，现有接口不接收位置请求且不返回位置结果。

- [ ] **Step 5: 集成任务动作与位置记录**

`VehicleTaskRepository` 增加执行用悲观锁查询。`TaskExecutionService` 的 `start`、`arrive`、`board`、`alight`、`complete` 增加位置请求参数。事件类型由服务端根据动作和站点类型推导；车辆编号从任务读取；任务、节点和操作人由后端注入。控制器不接受客户端传入这些字段。车辆故障和严重延误接口不属于已确认的六类节点事件，继续保留原因请求体；如需同时补位置，调用独立补报接口。

每个方法保持 `@Transactional`，固定顺序为：悲观锁任务；构造服务端请求指纹；在状态机校验前检查幂等重放；校验状态、任务车辆和位置；追加事件；推进任务或节点；应用车辆快照；写入原有任务动作审计。任务审计的 `metadataJson` 增加 `locationEventId`，但不再额外写位置审计。任何一步失败全部回滚。

- [ ] **Step 6: 验证 Task 4**

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=TaskExecutionApiTest,TaskLocationTransactionTest,AuthorizationApiTest' test
& $env:MAVEN_CMD -q -pl apps/api test
```

Expected: 完整任务链、事务回滚、幂等和 RBAC 测试通过。

- [ ] **Step 7: 提交 Task 4**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/task/TaskActionRequest.java apps/api/src/main/java/com/idavy/drtops/domain/task/TaskActionResponse.java apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskController.java apps/api/src/main/java/com/idavy/drtops/domain/task/TaskExecutionService.java apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java apps/api/src/test/java/com/idavy/drtops/domain/task/TaskLocationTransactionTest.java
git commit -m "feat: record locations with task actions"
```

### Task 5: 实现前端位置契约和任务位置确认面板

**Files:**
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/auth/permissions.ts`
- Create: `apps/admin-web/src/api/vehicleLocations.ts`
- Modify: `apps/admin-web/src/api/tasks.ts`
- Create: `apps/admin-web/src/maps/locationProvider.ts`
- Create: `apps/admin-web/src/components/LocationReportPanel.vue`
- Create: `apps/admin-web/src/components/location-report-panel.test.ts`
- Modify: `apps/admin-web/src/pages/TasksPage.vue`
- Modify: `apps/admin-web/src/pages/tasks-page.test.ts`

**Interfaces:**
- Consumes: Task 3/4 的 DTO、位置查询接口和任务动作响应。
- Produces: 可复用 `LocationCandidate`、位置确认面板和携带幂等编号的任务动作客户端。

- [ ] **Step 1: 定义前端位置类型和依赖端口**

```ts
export interface LocationCandidate {
  longitude: number;
  latitude: number;
  standardizedAddress: string;
  virtualStopId?: UUID;
  providerDegraded?: boolean;
}

export interface LocationReportInput extends LocationCandidate {
  driverReportedAt: IsoDateTime;
  note?: string;
  idempotencyKey: UUID;
}

export interface LocationPickerProvider {
  search(keyword: string): Promise<LocationCandidate[]>;
  pickOnMap(container: HTMLElement, initial?: LocationCandidate): Promise<LocationCandidate>;
}
```

本计划只交付该端口和经纬度/虚拟站点降级输入。高德实现由独立地图计划提供并注入，不在此任务中直接调用第三方全局对象。

- [ ] **Step 2: 写出失败的位置面板测试**

覆盖：默认位置、反馈时间必填、经纬度校验、虚拟站点选择、失败后保留输入、服务区外二次确认、同一面板重试复用幂等编号、地图提供者不可用时显示降级提示。

- [ ] **Step 3: 运行聚焦测试并确认失败**

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
```

Expected: FAIL，位置面板和新任务 API 尚不存在。

- [ ] **Step 4: 实现 API 客户端和位置确认面板**

`startTask`、`arriveStop`、`boardStop`、`alightStop`、`completeTask` 全部接收 `LocationReportInput` 并提交：

```ts
body: JSON.stringify({ locationReport })
```

面板打开时生成一次 `crypto.randomUUID()`，只有关闭并重新发起动作时才生成新编号。提交失败保持表单和编号不变。地图不可用时，经纬度降级录入仍必须填写人工确认的地址文本；选择虚拟站点时可带出其标准化地址。

- [ ] **Step 5: 接入车辆任务页**

动作按钮不再直接请求接口，而是设置待执行动作并打开面板。默认位置规则：发车取车辆快照；到站、上车和下车取目标虚拟站点；完成取最后任务节点。提交成功后用 `TaskActionResponse.task` 更新页面，关闭面板并刷新位置。

- [ ] **Step 6: 验证 Task 5**

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run test
```

Expected: 位置面板、任务页面、类型检查和前端全量测试通过。

- [ ] **Step 7: 提交 Task 5**

```powershell
git add apps/admin-web/src/api/types.ts apps/admin-web/src/api/vehicleLocations.ts apps/admin-web/src/api/tasks.ts apps/admin-web/src/auth/permissions.ts apps/admin-web/src/maps/locationProvider.ts apps/admin-web/src/components/LocationReportPanel.vue apps/admin-web/src/components/location-report-panel.test.ts apps/admin-web/src/pages/TasksPage.vue apps/admin-web/src/pages/tasks-page.test.ts
git commit -m "feat: confirm locations during task actions"
```

### Task 6: 实现工作台最新位置和车辆位置历史页

**Files:**
- Modify: `apps/admin-web/src/components/DispatchMap.vue`
- Create: `apps/admin-web/src/components/dispatch-map.test.ts`
- Modify: `apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- Modify: `apps/admin-web/src/pages/dispatch-workbench.test.ts`
- Create: `apps/admin-web/src/pages/VehicleLocationHistoryPage.vue`
- Create: `apps/admin-web/src/pages/vehicle-location-history-page.test.ts`
- Modify: `apps/admin-web/src/router/index.ts`
- Modify: `apps/admin-web/src/layouts/AppLayout.vue`
- Modify: `apps/admin-web/src/layouts/app-layout.test.ts`

**Interfaces:**
- Consumes: `GET /api/vehicles/locations/latest`、历史查询和导出接口。
- Produces: 15 秒位置轮询、人工节点链、过期告警和受权限控制的历史页面。

- [ ] **Step 1: 写出失败的工作台与历史页测试**

使用 Vitest fake timers 验证首次加载和每 15 秒轮询；执行中车辆 30 分钟无事件时显示告警；组件卸载后清除定时器。验证标记文本包含车牌、任务、反馈时间和“人工上报”，虚线节点链明确标注非实际轨迹。

历史页测试覆盖车辆、任务、日期、事件类型筛选，反馈时间与录入延迟展示，修正关系和管理员导出按钮。日期控件必须按 `Asia/Shanghai` 将自然日转换为带时区的左闭右开区间，并覆盖跨 UTC 日界用例。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
npm.cmd --prefix apps/admin-web run test -- dispatch-map.test.ts dispatch-workbench.test.ts vehicle-location-history-page.test.ts app-layout.test.ts
```

Expected: FAIL，位置轮询和历史页面尚不存在。

- [ ] **Step 3: 实现工作台轮询和地图状态**

`DispatchWorkbenchPage` 在挂载时加载最新快照，使用 `setInterval(..., 15_000)` 轮询，并在卸载时清理。30 分钟阈值来自 `VITE_MANUAL_LOCATION_STALE_MINUTES`，未配置时使用 `30`。

`DispatchMap` 接收快照和事件链 props。第三方地图不可用时仍渲染可访问的车辆列表、更新时间和告警，不把地图失败变成工作台整体失败。

- [ ] **Step 4: 实现历史页、路由和导航**

新增 `/vehicle-locations` 路由，要求 `LOCATION_READ`。页面支持筛选和时间线；只有 `LOCATION_EXPORT` 权限显示导出按钮，只有 `LOCATION_CORRECT` 权限显示修正入口。

- [ ] **Step 5: 验证 Task 6**

```powershell
npm.cmd --prefix apps/admin-web run test -- dispatch-map.test.ts dispatch-workbench.test.ts vehicle-location-history-page.test.ts app-layout.test.ts
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run test
npm.cmd --prefix apps/admin-web run build
```

Expected: 页面测试、前端全量测试、类型检查和生产构建通过；仅允许保留已知的地图包体积告警。

- [ ] **Step 6: 提交 Task 6**

```powershell
git add apps/admin-web/src/components/DispatchMap.vue apps/admin-web/src/components/dispatch-map.test.ts apps/admin-web/src/pages/DispatchWorkbenchPage.vue apps/admin-web/src/pages/dispatch-workbench.test.ts apps/admin-web/src/pages/VehicleLocationHistoryPage.vue apps/admin-web/src/pages/vehicle-location-history-page.test.ts apps/admin-web/src/router/index.ts apps/admin-web/src/layouts/AppLayout.vue apps/admin-web/src/layouts/app-layout.test.ts
git commit -m "feat: show vehicle location operations"
```

### Task 7: 增加位置运行指标和全链路验收测试

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationMetrics.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationRecorder.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationMetricsTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/e2e/VehicleLocationFlowIntegrationTest.java`
- Create: `apps/admin-web/e2e/vehicle-location-flow.spec.ts`
- Modify: `apps/api/src/main/resources/application.yml`

**Interfaces:**
- Consumes: 已完成的位置写入、查询、前端任务面板和工作台。
- Produces: Micrometer 指标、4 辆车完整位置链和试点验收证据。

- [ ] **Step 1: 定义并测试指标名称**

固定采集：

```text
drt.vehicle.location.report.total{result,source}
drt.vehicle.location.recording.delay
drt.vehicle.location.stale.count
drt.vehicle.location.outside_area.total
drt.vehicle.location.correction.total
drt.vehicle.location.missing_task_nodes
drt.vehicle.location.query.duration
drt.map.provider.request.duration{operation,result}
```

测试验证成功、失败、服务区外、修正和查询路径分别更新对应指标。`drt.map.provider.request.duration` 是真实高德适配器必须实现的指标契约，本模块只在运行手册和就绪清单中核验，不伪造第三方调用数据。

- [ ] **Step 2: 运行指标测试并确认失败**

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationMetricsTest' test
```

Expected: FAIL，指标组件尚不存在。

- [ ] **Step 3: 实现 Micrometer 指标和 Actuator 暴露**

使用 `MeterRegistry` 注入位置服务。`application.yml` 暴露 `health,info,metrics`；Prometheus 抓取和自建监控部署由试点基础设施计划负责，本任务不新增监控平台依赖。

- [ ] **Step 4: 编写后端全链路集成测试**

使用 4 辆车分别创建任务，至少一条任务完整执行六个节点；同时覆盖重复幂等、服务区外、旧事件不回退和管理员修正。断言任务状态、事件历史、车辆快照、审计和指标一致。

- [ ] **Step 5: 编写浏览器端到端测试**

Playwright 测试以调度员登录，选择非首条任务，逐次填写位置确认面板并完成任务；管理员登录后查询历史并导出。测试不得依赖真实高德网络，使用位置提供者测试替身。

- [ ] **Step 6: 运行全量验证**

```powershell
& $env:MAVEN_CMD -q -pl apps/api test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run test
npm.cmd --prefix apps/admin-web run build
npm.cmd --prefix apps/admin-web run e2e -- vehicle-location-flow.spec.ts
```

Expected: 后端、前端、构建和车辆位置 E2E 全部通过。

- [ ] **Step 7: 提交 Task 7**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationMetrics.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationRecorder.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationMetricsTest.java apps/api/src/test/java/com/idavy/drtops/e2e/VehicleLocationFlowIntegrationTest.java apps/api/src/main/resources/application.yml apps/admin-web/e2e/vehicle-location-flow.spec.ts
git commit -m "test: verify vehicle location operations"
```

### Task 8: 完成试点运行手册和真实本机验收

**Files:**
- Create: `docs/release/tongwei-vehicle-location-runbook.md`
- Create: `docs/release/vehicle-location-acceptance-2026-07.md`
- Modify: `docs/release/mvp-readiness-checklist.md`
- Modify: `README.md`
- Modify: `.superpowers/sdd/progress.md`

**Interfaces:**
- Consumes: Task 1-7 的验证结果和本机 PostgreSQL/PostGIS 环境。
- Produces: 可执行运行手册、真实验收记录和试点前风险清单。

- [ ] **Step 1: 编写运行手册**

手册必须包含：本机启动顺序、数据库每日备份、恢复演练、调度员电话/微信上报 SOP、反馈时间填写规则、服务区外告警处理、位置修正流程、地图降级操作、30 分钟未更新处置和故障联系人记录方式。

- [ ] **Step 2: 准备真实试点数据**

在本机 PostGIS 中配置通渭县试点服务区、4 辆车、4 名驾驶员、2 名调度员以及已经人工核验的虚拟站点。不得把演示 UUID、杭州坐标或演示账号密码写入生产配置。

- [ ] **Step 3: 执行真实业务验收**

用不同调度员账号完成：普通补报、六节点任务链、服务区外告警、重复提交、旧事件、管理员修正、15 秒轮询、30 分钟告警和 CSV 导出。逐项记录车辆短号、任务短号、事件数量、审计数量和页面结果。

- [ ] **Step 4: 执行约 1 万条事件容量验证**

生成接近试点上限的数据，验证最新快照查询、单车辆历史筛选和导出均可完成，并记录耗时而不设置脆弱的单机绝对阈值。确认数据库备份包含全部事件，并完成一次恢复核对。

- [ ] **Step 5: 更新就绪清单和验收结论**

明确车辆位置模块通过或未通过；高德真实适配器、电子围栏、虚拟站点导入、生产监控和后续 GPS 仍按独立工作流跟踪，不能因本模块通过而自动视为试点整体通过。

- [ ] **Step 6: 最终验证和格式检查**

```powershell
git diff --check
& $env:MAVEN_CMD -q -pl apps/api test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run test
npm.cmd --prefix apps/admin-web run build
```

Expected: 所有命令退出码为 0；验收记录与实际证据一致。

- [ ] **Step 7: 提交 Task 8**

```powershell
git add docs/release/tongwei-vehicle-location-runbook.md docs/release/vehicle-location-acceptance-2026-07.md docs/release/mvp-readiness-checklist.md README.md .superpowers/sdd/progress.md
git commit -m "docs: record vehicle location readiness"
```

## 执行顺序与审阅门槛

任务严格按 `1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8` 执行。Task 1-4 可在高德适配器完成前独立交付；Task 5-7 使用 `LocationPickerProvider` 测试替身和经纬度/虚拟站点降级路径。真实高德地址搜索、地图点击和电子围栏验收必须由独立高德地图计划完成，并在 Task 8 的试点总体验收前合入。

每个 Task 的提交均需单独审阅。任何数据库迁移、权限边界、事务原子性或事件不可变性问题未关闭时，不得进入前端任务。
