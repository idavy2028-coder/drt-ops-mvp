# 区域动态响应公交运营管理 MVP 实施计划

> **给后续执行代理的要求：** 实施本计划时必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，逐个任务执行。本文使用复选框跟踪步骤，当前步骤已全部标记完成。

**目标：** 建设第一版面向公交企业内部运营的区域动态响应公交 MVP，跑通资源配置、需求录入、实时调度评估、车辆任务生成、执行模拟、异常处理、审计记录和基础调度工作台。

**架构：** 使用 monorepo 管理三个核心应用：Spring Boot 业务后台、Python/FastAPI 调度算法服务、Vue 3/TypeScript 企业管理前端。Java 后台负责业务状态和调度编排，Python 算法服务只返回调度建议和解释，不直接修改订单、车辆任务或审计表。

**技术栈：** Java 21、Spring Boot 3.5.x、Maven、PostgreSQL 16+、PostGIS 3+、Redis 7+、Python 3.12、FastAPI、pytest、Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、MapLibre GL JS、Vitest、Playwright。

## 全局约束

- 第一版只做企业内部运营闭环，不做完整乘客端、完整司机端、监管端、支付、会员、营销和完整客服体系。
- 核心运营模式是“虚拟站点 + 区域动态响应公交”。
- 调度触发方式是“每个订单实时触发”，架构必须能兼容未来批量调度和混合调度。
- 订单承诺规则是“找到可行车辆或任务后才确认接单”。
- 第一版支持即时出行和未来 2 小时内短时预约。
- 派发策略是可配置的分级自动派发，默认采用中等人工兜底。
- 调度评分采用综合权重，默认偏向乘客体验和服务稳定。
- 执行中插单策略可配置，默认只允许同方向、低绕行插单。
- 算法服务每次调度都必须返回解释信息。
- Java 后台拥有订单、任务、审计和规则的最终业务状态；算法服务不能直接写业务表。
- 关键状态流转、人工操作、规则变更和算法决策必须写审计日志。
- 每个实施任务都必须有可运行的验证命令，并在通过后提交。

---

## 范围检查

设计文档覆盖了多个未来子系统。本实施计划只覆盖第一版可演示的企业运营闭环：

- 资源与规则配置。
- 需求录入与虚拟站点匹配。
- 实时调度算法评估。
- 调度编排与车辆任务生成。
- 简化执行模拟。
- 异常、审计和基础指标。
- 企业管理端页面。

以下内容另行拆计划：完整乘客端、完整司机端、监管端、支付结算、机器学习需求预测、Kafka 生产级事件流。

## 文件结构

创建以下 monorepo 结构：

```text
D:/codex-projects/
  apps/
    api/
      pom.xml
      src/main/java/com/idavy/drtops/
        DrtOpsApplication.java
        common/
          ApiResponse.java
          BadRequestException.java
          NotFoundException.java
          GlobalExceptionHandler.java
        config/
          ClockConfig.java
          WebClientConfig.java
        domain/
          area/
          audit/
          dispatch/
          fleet/
          order/
          task/
        integration/
          algorithm/
        metrics/
      src/main/resources/
        application.yml
        db/migration/
      src/test/java/com/idavy/drtops/
    algorithm/
      pyproject.toml
      src/drt_algorithm/
        main.py
        schemas.py
        matching.py
        insertion.py
        scoring.py
        explanations.py
      tests/
    admin-web/
      package.json
      index.html
      vite.config.ts
      tsconfig.json
      src/
        main.ts
        App.vue
        router/
        stores/
        api/
        layouts/
        pages/
        components/
  docs/
    api/
      dispatch-algorithm-contract.md
    superpowers/
      specs/
      plans/
  infra/
    docker-compose.yml
    postgres/
      init-postgis.sql
  pom.xml
  README.md
```

职责边界：

- `apps/api`：业务 API、领域服务、持久化、调度编排、指标和审计。
- `apps/algorithm`：无状态调度评估，不保存业务数据。
- `apps/admin-web`：页面、API 客户端、前端状态和路由。
- `infra`：本地开发依赖。
- `docs/api/dispatch-algorithm-contract.md`：Java 调度编排层和 Python 算法服务之间的接口契约。

---

### Task 1：创建 Monorepo 骨架与本地运行基础

**文件：**
- 新建：`pom.xml`
- 新建：`apps/api/pom.xml`
- 新建：`apps/api/src/main/java/com/idavy/drtops/DrtOpsApplication.java`
- 新建：`apps/api/src/main/resources/application.yml`
- 新建：`apps/algorithm/pyproject.toml`
- 新建：`apps/algorithm/src/drt_algorithm/main.py`
- 新建：`apps/admin-web/package.json`
- 新建：`apps/admin-web/index.html`
- 新建：`apps/admin-web/vite.config.ts`
- 新建：`apps/admin-web/tsconfig.json`
- 新建：`apps/admin-web/src/main.ts`
- 新建：`apps/admin-web/src/App.vue`
- 新建：`infra/docker-compose.yml`
- 新建：`infra/postgres/init-postgis.sql`
- 新建：`README.md`

**接口：**
- 产出：后端健康检查 `GET /actuator/health`
- 产出：算法健康检查 `GET /health`
- 产出：前端首页 `/`
- 产出：本地依赖服务 `postgres:5432`、`redis:6379`、`api:8080`、`algorithm:8090`、`admin-web:5173`

- [x] **步骤 1：创建后端骨架**

根目录 Maven 聚合项目包含 `apps/api` 模块。

```xml
<!-- D:/codex-projects/pom.xml -->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.idavy</groupId>
  <artifactId>dynamic-responsive-bus-ops</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <modules>
    <module>apps/api</module>
  </modules>
</project>
```

`apps/api/pom.xml` 使用 Spring Boot 3.5.x，并加入 Web、Validation、Data JPA、Flyway、PostgreSQL、Actuator、WebClient、Testcontainers 和测试依赖。

```java
// D:/codex-projects/apps/api/src/main/java/com/idavy/drtops/DrtOpsApplication.java
package com.idavy.drtops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DrtOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(DrtOpsApplication.class, args);
    }
}
```

- [x] **步骤 2：创建算法服务骨架**

```python
# D:/codex-projects/apps/algorithm/src/drt_algorithm/main.py
from fastapi import FastAPI

app = FastAPI(title="DRT Dispatch Algorithm Service", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}
```

- [x] **步骤 3：创建前端骨架**

```vue
<!-- D:/codex-projects/apps/admin-web/src/App.vue -->
<template>
  <main class="app-shell">
    <h1>区域动态响应公交运营管理</h1>
  </main>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  padding: 24px;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}
</style>
```

- [x] **步骤 4：创建本地基础设施**

```yaml
# D:/codex-projects/infra/docker-compose.yml
services:
  postgres:
    image: postgis/postgis:16-3.5
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: drt_ops
      POSTGRES_USER: drt_ops
      POSTGRES_PASSWORD: drt_ops
    volumes:
      - ./postgres/init-postgis.sql:/docker-entrypoint-initdb.d/init-postgis.sql:ro
  redis:
    image: redis:7
    ports:
      - "6379:6379"
```

```sql
-- D:/codex-projects/infra/postgres/init-postgis.sql
CREATE EXTENSION IF NOT EXISTS postgis;
```

- [x] **步骤 5：验证骨架**

运行：

```powershell
mvn -q -pl apps/api test
cd apps/algorithm
python -m pytest
cd ../admin-web
npm run typecheck
```

预期：

```text
Maven exits 0.
pytest exits 0.
TypeScript typecheck exits 0.
```

- [x] **步骤 6：提交**

```bash
git add pom.xml apps infra README.md
git commit -m "chore: scaffold dynamic bus ops monorepo"
```

---

### Task 2：数据库结构与演示数据

**文件：**
- 新建：`apps/api/src/main/resources/db/migration/V1__create_core_schema.sql`
- 新建：`apps/api/src/main/resources/db/migration/V2__seed_demo_operations.sql`
- 新建：`apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java`

**接口：**
- 产出表：`service_areas`、`virtual_stops`、`dispatch_rule_sets`、`vehicles`、`drivers`、`ride_orders`、`vehicle_tasks`、`task_stops`、`dispatch_decisions`、`audit_logs`
- 产出 PostGIS 字段：`service_areas.boundary`、`virtual_stops.location`、`vehicles.current_location`

- [x] **步骤 1：先写失败的迁移测试**

```java
package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class DatabaseMigrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgis/postgis:16-3.5")
            .withDatabaseName("drt_ops")
            .withUsername("drt_ops")
            .withPassword("drt_ops");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void migrationsCreateCoreTablesAndSeedArea() {
        List<String> tableNames = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tableNames).contains(
                "service_areas",
                "virtual_stops",
                "dispatch_rule_sets",
                "vehicles",
                "drivers",
                "ride_orders",
                "vehicle_tasks",
                "task_stops",
                "dispatch_decisions",
                "audit_logs");

        Integer areaCount = jdbcTemplate.queryForObject("select count(*) from service_areas", Integer.class);
        assertThat(areaCount).isGreaterThanOrEqualTo(1);
    }
}
```

- [x] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -q -pl apps/api -Dtest=DatabaseMigrationTest test
```

预期：

```text
FAIL because migration files or tables do not exist.
```

- [x] **步骤 3：创建核心数据库迁移**

先创建规则、区域和虚拟站点：

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE dispatch_rule_sets (
  id UUID PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  max_wait_minutes INTEGER NOT NULL CHECK (max_wait_minutes > 0),
  max_detour_minutes INTEGER NOT NULL CHECK (max_detour_minutes >= 0),
  booking_window_minutes INTEGER NOT NULL CHECK (booking_window_minutes > 0),
  auto_dispatch_score_threshold NUMERIC(5,2) NOT NULL,
  manual_review_score_threshold NUMERIC(5,2) NOT NULL,
  wait_weight NUMERIC(5,2) NOT NULL,
  detour_weight NUMERIC(5,2) NOT NULL,
  stability_weight NUMERIC(5,2) NOT NULL,
  utilization_weight NUMERIC(5,2) NOT NULL,
  insertion_policy VARCHAR(40) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE service_areas (
  id UUID PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  boundary geography(POLYGON, 4326) NOT NULL,
  service_start TIME NOT NULL,
  service_end TIME NOT NULL,
  rule_set_id UUID NOT NULL REFERENCES dispatch_rule_sets(id),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE virtual_stops (
  id UUID PRIMARY KEY,
  service_area_id UUID NOT NULL REFERENCES service_areas(id),
  name VARCHAR(120) NOT NULL,
  location geography(POINT, 4326) NOT NULL,
  service_radius_meters INTEGER NOT NULL CHECK (service_radius_meters > 0),
  boarding_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  alighting_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  safety_note VARCHAR(300) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_virtual_stops_location ON virtual_stops USING GIST (location);
CREATE INDEX idx_service_areas_boundary ON service_areas USING GIST (boundary);
```

继续创建车辆、司机、订单、任务、决策和审计表：

```sql
CREATE TABLE vehicles (
  id UUID PRIMARY KEY,
  plate_number VARCHAR(30) NOT NULL UNIQUE,
  vehicle_type VARCHAR(60) NOT NULL,
  capacity INTEGER NOT NULL CHECK (capacity > 0),
  current_status VARCHAR(40) NOT NULL,
  current_location geography(POINT, 4326),
  fleet_name VARCHAR(100) NOT NULL,
  dispatchable BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vehicles_current_location ON vehicles USING GIST (current_location);

CREATE TABLE drivers (
  id UUID PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  phone VARCHAR(30) NOT NULL UNIQUE,
  qualification_status VARCHAR(40) NOT NULL,
  shift_start TIMESTAMPTZ,
  shift_end TIMESTAMPTZ,
  current_status VARCHAR(40) NOT NULL,
  fleet_name VARCHAR(100) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ride_orders (
  id UUID PRIMARY KEY,
  passenger_name VARCHAR(80) NOT NULL,
  passenger_phone VARCHAR(30) NOT NULL,
  passenger_count INTEGER NOT NULL CHECK (passenger_count > 0),
  request_type VARCHAR(40) NOT NULL,
  origin_lng NUMERIC(10,7) NOT NULL,
  origin_lat NUMERIC(10,7) NOT NULL,
  destination_lng NUMERIC(10,7) NOT NULL,
  destination_lat NUMERIC(10,7) NOT NULL,
  boarding_stop_id UUID REFERENCES virtual_stops(id),
  alighting_stop_id UUID REFERENCES virtual_stops(id),
  requested_departure_at TIMESTAMPTZ NOT NULL,
  estimated_boarding_at TIMESTAMPTZ,
  estimated_arrival_at TIMESTAMPTZ,
  status VARCHAR(40) NOT NULL,
  failure_reason VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE vehicle_tasks (
  id UUID PRIMARY KEY,
  vehicle_id UUID NOT NULL REFERENCES vehicles(id),
  driver_id UUID NOT NULL REFERENCES drivers(id),
  status VARCHAR(40) NOT NULL,
  planned_start_at TIMESTAMPTZ NOT NULL,
  planned_end_at TIMESTAMPTZ,
  current_stop_id UUID,
  source_type VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE task_stops (
  id UUID PRIMARY KEY,
  vehicle_task_id UUID NOT NULL REFERENCES vehicle_tasks(id) ON DELETE CASCADE,
  virtual_stop_id UUID NOT NULL REFERENCES virtual_stops(id),
  ride_order_id UUID REFERENCES ride_orders(id),
  sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
  stop_type VARCHAR(40) NOT NULL,
  planned_arrival_at TIMESTAMPTZ NOT NULL,
  actual_arrival_at TIMESTAMPTZ,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(vehicle_task_id, sequence_number)
);

CREATE TABLE dispatch_decisions (
  id UUID PRIMARY KEY,
  ride_order_id UUID NOT NULL REFERENCES ride_orders(id),
  decision_result VARCHAR(40) NOT NULL,
  candidate_count INTEGER NOT NULL CHECK (candidate_count >= 0),
  best_vehicle_id UUID REFERENCES vehicles(id),
  best_task_id UUID REFERENCES vehicle_tasks(id),
  score NUMERIC(6,2),
  estimated_wait_minutes INTEGER,
  estimated_detour_minutes INTEGER,
  rejected_reasons_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  explanation_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  algorithm_version VARCHAR(40) NOT NULL,
  actor_type VARCHAR(40) NOT NULL,
  actor_id VARCHAR(80) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dispatch_decisions_order ON dispatch_decisions(ride_order_id);

CREATE TABLE audit_logs (
  id UUID PRIMARY KEY,
  entity_type VARCHAR(60) NOT NULL,
  entity_id UUID NOT NULL,
  action VARCHAR(80) NOT NULL,
  actor_type VARCHAR(40) NOT NULL,
  actor_id VARCHAR(80) NOT NULL,
  reason VARCHAR(300),
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
```

- [x] **步骤 4：创建演示数据迁移**

`V2__seed_demo_operations.sql` 固定写入：

- 1 个服务区域。
- 6 个虚拟站点。
- 2 辆车。
- 2 名司机。
- 1 套默认规则组。

固定 UUID 供后续测试引用：

```text
rule_set_id = 11111111-1111-1111-1111-111111111111
service_area_id = 22222222-2222-2222-2222-222222222222
vehicle_1_id = 33333333-3333-3333-3333-333333333331
vehicle_2_id = 33333333-3333-3333-3333-333333333332
driver_1_id = 44444444-4444-4444-4444-444444444441
driver_2_id = 44444444-4444-4444-4444-444444444442
```

- [x] **步骤 5：运行迁移测试**

运行：

```powershell
mvn -q -pl apps/api -Dtest=DatabaseMigrationTest test
```

预期：

```text
BUILD SUCCESS
```

- [x] **步骤 6：提交**

```bash
git add apps/api/src/main/resources/db/migration apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java
git commit -m "feat: add core operations database schema"
```

---

### Task 3：Java 领域模型与状态规则

**文件：**
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/order/OrderStatus.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrder.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderRepository.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStatus.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTask.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecision.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionRepository.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderStateTest.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/task/VehicleTaskStateTest.java`

**接口：**
- 产出枚举 `OrderStatus`：`PENDING_DISPATCH`、`UNSERVICEABLE`、`PENDING_MANUAL_REVIEW`、`CONFIRMED`、`CANCELLED`、`IN_PROGRESS`、`COMPLETED`、`EXCEPTION_CLOSED`
- 产出枚举 `TaskStatus`：`PENDING_DEPARTURE`、`DISPATCHED`、`IN_PROGRESS`、`PAUSED`、`COMPLETED`、`CANCELLED`、`EXCEPTION`
- 产出方法：`RideOrder.confirm(...)`、`RideOrder.markUnserviceable(...)`、`RideOrder.markPendingManualReview(...)`、`RideOrder.cancel(...)`、`RideOrder.startExecution()`、`RideOrder.complete()`、`RideOrder.closeException(...)`

- [x] **步骤 1：先写失败的状态流转测试**

```java
@Test
void confirmedOrderCanStartAndComplete() {
    RideOrder order = RideOrder.pendingDispatch(sampleCreateOrder());
    order.confirm(samplePromise());
    order.startExecution();
    order.complete();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
}

@Test
void cancelledOrderCannotBeConfirmed() {
    RideOrder order = RideOrder.pendingDispatch(sampleCreateOrder());
    order.cancel("乘客取消");
    assertThatThrownBy(() -> order.confirm(samplePromise()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CANCELLED");
}
```

- [x] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -q -pl apps/api -Dtest=RideOrderStateTest,VehicleTaskStateTest test
```

预期：

```text
FAIL because domain classes do not exist.
```

- [x] **步骤 3：实现最小领域对象**

实现 JPA 实体，并把状态修改封装在领域方法里。控制器和编排服务不能直接赋值状态字符串。

- [x] **步骤 4：增加仓储冒烟测试**

保存并重新读取一个订单和一个车辆任务，验证基础持久化可用。

- [x] **步骤 5：运行领域测试**

运行：

```powershell
mvn -q -pl apps/api -Dtest=*StateTest,*RepositoryTest test
```

预期：

```text
BUILD SUCCESS
```

- [x] **步骤 6：提交**

```bash
git add apps/api/src/main/java/com/idavy/drtops/domain apps/api/src/test/java/com/idavy/drtops/domain
git commit -m "feat: add order task and dispatch domain model"
```

---

### Task 4：资源与规则配置 API

**文件：**
- 新建：`apps/api/src/main/java/com/idavy/drtops/common/ApiResponse.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/common/GlobalExceptionHandler.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaController.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopController.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/fleet/VehicleController.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/fleet/DriverController.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetController.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/area/ServiceAreaApiTest.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/fleet/FleetApiTest.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetApiTest.java`

**接口：**
- 产出 REST 接口：
  - `GET /api/service-areas`
  - `POST /api/service-areas`
  - `GET /api/virtual-stops?serviceAreaId={id}`
  - `POST /api/virtual-stops`
  - `GET /api/vehicles`
  - `POST /api/vehicles`
  - `GET /api/drivers`
  - `POST /api/drivers`
  - `GET /api/dispatch-rule-sets`
  - `PUT /api/dispatch-rule-sets/{id}`

- [x] **步骤 1：编写 API 测试**

```java
@Test
void listsSeededVirtualStopsForArea() throws Exception {
    mockMvc.perform(get("/api/virtual-stops").param("serviceAreaId", DEMO_AREA_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(6));
}

@Test
void rejectsRuleSetWithNegativeWaitTime() throws Exception {
    mockMvc.perform(put("/api/dispatch-rule-sets/" + DEMO_RULE_SET_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"maxWaitMinutes":-1,"maxDetourMinutes":8,"bookingWindowMinutes":120,
                 "autoDispatchScoreThreshold":80,"manualReviewScoreThreshold":60,
                 "waitWeight":0.35,"detourWeight":0.25,"stabilityWeight":0.30,"utilizationWeight":0.10,
                 "insertionPolicy":"SAME_DIRECTION_ONLY"}
                """))
            .andExpect(status().isBadRequest());
}
```

- [x] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -q -pl apps/api -Dtest=ServiceAreaApiTest,FleetApiTest,DispatchRuleSetApiTest test
```

预期：

```text
FAIL because controllers do not exist.
```

- [x] **步骤 3：实现控制器和服务**

统一返回结构：

```java
public record ApiResponse<T>(T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data);
    }
}
```

请求 DTO 必须校验名称、容量、坐标、等待时间、绕行时间、评分阈值和权重。

- [x] **步骤 4：运行 API 测试**

运行：

```powershell
mvn -q -pl apps/api -Dtest=ServiceAreaApiTest,FleetApiTest,DispatchRuleSetApiTest test
```

预期：

```text
BUILD SUCCESS
```

- [x] **步骤 5：提交**

```bash
git add apps/api/src/main/java/com/idavy/drtops apps/api/src/test/java/com/idavy/drtops/domain
git commit -m "feat: add resource and dispatch rule APIs"
```

---

### Task 5：需求录入与虚拟站点匹配

**文件：**
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderController.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderService.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopMatcher.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderApiTest.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/area/VirtualStopMatcherTest.java`

**接口：**
- 消费：任务 4 的 `VirtualStop` 和 `ServiceArea`
- 产出：`POST /api/orders`
- 产出：`GET /api/orders`
- 产出方法：`VirtualStopMatch matchStops(BigDecimal originLng, BigDecimal originLat, BigDecimal destinationLng, BigDecimal destinationLat, Instant requestedDepartureAt)`

- [x] **步骤 1：编写虚拟站点匹配测试**

```java
@Test
void matchesNearestBoardingAndAlightingStopsInsideServiceArea() {
    VirtualStopMatch match = matcher.matchStops(
            new BigDecimal("120.1550"),
            new BigDecimal("30.2741"),
            new BigDecimal("120.1688"),
            new BigDecimal("30.2799"),
            Instant.parse("2026-07-08T02:30:00Z"));

    assertThat(match.boardingStopId()).isNotNull();
    assertThat(match.alightingStopId()).isNotNull();
    assertThat(match.boardingDistanceMeters()).isLessThanOrEqualTo(600);
}
```

- [x] **步骤 2：编写订单 API 测试**

```java
@Test
void createsPendingDispatchOrderWhenStopsMatch() throws Exception {
    mockMvc.perform(post("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"passengerName":"张三","passengerPhone":"13800000000","passengerCount":1,
                 "originLng":120.1550,"originLat":30.2741,
                 "destinationLng":120.1688,"destinationLat":30.2799,
                 "requestType":"IMMEDIATE","requestedDepartureAt":"2026-07-08T02:30:00Z"}
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PENDING_DISPATCH"))
            .andExpect(jsonPath("$.data.boardingStopId").exists())
            .andExpect(jsonPath("$.data.alightingStopId").exists());
}
```

- [x] **步骤 3：运行测试确认失败**

运行：

```powershell
mvn -q -pl apps/api -Dtest=VirtualStopMatcherTest,RideOrderApiTest test
```

预期：

```text
FAIL because matcher and order API do not exist.
```

- [x] **步骤 4：实现匹配与订单创建**

用 `JdbcTemplate` 调用 PostGIS 距离查询：

```sql
SELECT id, ST_Distance(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_meters
FROM virtual_stops
WHERE enabled = true
  AND boarding_enabled = true
  AND ST_DWithin(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, service_radius_meters)
ORDER BY distance_meters ASC
LIMIT 1
```

- [x] **步骤 5：运行测试**

运行：

```powershell
mvn -q -pl apps/api -Dtest=VirtualStopMatcherTest,RideOrderApiTest test
```

预期：

```text
BUILD SUCCESS
```

- [x] **步骤 6：提交**

```bash
git add apps/api/src/main/java/com/idavy/drtops/domain/order apps/api/src/main/java/com/idavy/drtops/domain/area apps/api/src/test/java/com/idavy/drtops/domain
git commit -m "feat: add ride demand entry and virtual stop matching"
```

---

### Task 6：算法服务契约与评估引擎

**文件：**
- 新建：`docs/api/dispatch-algorithm-contract.md`
- 新建：`apps/algorithm/src/drt_algorithm/schemas.py`
- 新建：`apps/algorithm/src/drt_algorithm/matching.py`
- 新建：`apps/algorithm/src/drt_algorithm/insertion.py`
- 新建：`apps/algorithm/src/drt_algorithm/scoring.py`
- 新建：`apps/algorithm/src/drt_algorithm/explanations.py`
- 修改：`apps/algorithm/src/drt_algorithm/main.py`
- 新建：`apps/algorithm/tests/test_dispatch_evaluation.py`

**接口：**
- 产出算法接口：`POST /dispatch/evaluate`
- 产出请求模型：`DispatchEvaluateRequest`
- 产出响应模型：`DispatchEvaluateResponse`
- 产出决策值：`AUTO_DISPATCH`、`MANUAL_REVIEW`、`NO_FEASIBLE_PLAN`

- [x] **步骤 1：编写算法契约文档**

请求 JSON 格式：

```json
{
  "order": {
    "orderId": "uuid",
    "passengerCount": 1,
    "requestType": "IMMEDIATE",
    "requestedDepartureAt": "2026-07-08T02:30:00Z",
    "boardingStopId": "uuid",
    "alightingStopId": "uuid"
  },
  "ruleSet": {
    "maxWaitMinutes": 12,
    "maxDetourMinutes": 8,
    "autoDispatchScoreThreshold": 80,
    "manualReviewScoreThreshold": 60,
    "weights": {
      "wait": 0.35,
      "detour": 0.25,
      "stability": 0.30,
      "utilization": 0.10
    },
    "insertionPolicy": "SAME_DIRECTION_ONLY"
  },
  "candidateTasks": []
}
```

- [x] **步骤 2：编写失败的 pytest 用例**

```python
def test_no_vehicle_returns_no_feasible_plan(client):
    response = client.post("/dispatch/evaluate", json=sample_request(candidate_tasks=[]))
    assert response.status_code == 200
    body = response.json()
    assert body["decision"] == "NO_FEASIBLE_PLAN"
    assert body["explanation"]["reason"] == "NO_CANDIDATE_TASK"


def test_same_direction_low_detour_returns_auto_dispatch(client):
    response = client.post("/dispatch/evaluate", json=sample_request(candidate_tasks=[same_direction_task()]))
    assert response.status_code == 200
    body = response.json()
    assert body["decision"] == "AUTO_DISPATCH"
    assert body["bestPlan"]["score"] >= 80
    assert body["bestPlan"]["estimatedWaitMinutes"] <= 12
```

- [x] **步骤 3：运行测试确认失败**

运行：

```powershell
cd apps/algorithm
python -m pytest tests/test_dispatch_evaluation.py -v
```

预期：

```text
FAIL because /dispatch/evaluate and schemas do not exist.
```

- [x] **步骤 4：实现 Pydantic 模型**

模型使用 `uuid.UUID`、`datetime`、整型乘客数、小数权重和字符串枚举。对乘客数、阈值和权重做边界校验。

- [x] **步骤 5：实现第一版评估逻辑**

确定性规则：

1. 候选为空返回 `NO_FEASIBLE_PLAN`。
2. 候选容量小于乘客数时剔除。
3. 候选等待时间超过最大等待时剔除。
4. 候选绕行时间超过最大绕行时剔除。
5. 插单策略是 `SAME_DIRECTION_ONLY` 时，执行中任务必须同方向。
6. 对剩余候选评分：
   - 等待时间越接近上限，等待得分越低。
   - 绕行时间越接近上限，绕行得分越低。
   - 已有乘客受影响越大，稳定性得分越低。
   - 在不超容量前提下，座位利用越合理，利用率得分越高。
7. 最高分达到自动派发阈值时返回 `AUTO_DISPATCH`。
8. 最高分达到人工确认阈值且低于自动派发阈值时返回 `MANUAL_REVIEW`。
9. 全部候选被剔除时返回 `NO_FEASIBLE_PLAN`。

- [x] **步骤 6：运行算法测试**

运行：

```powershell
cd apps/algorithm
python -m pytest -v
```

预期：

```text
all tests pass
```

- [x] **步骤 7：提交**

```bash
git add docs/api/dispatch-algorithm-contract.md apps/algorithm
git commit -m "feat: add dispatch algorithm evaluation service"
```

---

### Task 7：Java 调度编排

**文件：**
- 新建：`apps/api/src/main/java/com/idavy/drtops/integration/algorithm/AlgorithmClient.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/integration/algorithm/DispatchEvaluateRequest.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/integration/algorithm/DispatchEvaluateResponse.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java`

**接口：**
- 消费：任务 6 的 `POST /dispatch/evaluate`
- 产出方法：`DispatchResult dispatchOrder(UUID orderId)`
- 产出接口：`POST /api/orders/{orderId}/dispatch`

- [x] **步骤 1：编写编排测试**

```java
@Test
void autoDispatchConfirmsOrderAndCreatesVehicleTask() {
    UUID orderId = createPendingOrder();
    algorithmServer.stubAutoDispatch();

    DispatchResult result = orchestrator.dispatchOrder(orderId);

    assertThat(result.decision()).isEqualTo(DispatchDecisionType.AUTO_DISPATCH);
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(vehicleTaskRepository.findAll()).hasSize(1);
    assertThat(dispatchDecisionRepository.findByOrderId(orderId)).hasSize(1);
}

@Test
void manualReviewKeepsOrderPendingManualReview() {
    UUID orderId = createPendingOrder();
    algorithmServer.stubManualReview();

    DispatchResult result = orchestrator.dispatchOrder(orderId);

    assertThat(result.decision()).isEqualTo(DispatchDecisionType.MANUAL_REVIEW);
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_MANUAL_REVIEW);
}
```

- [x] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -q -pl apps/api -Dtest=DispatchOrchestratorTest test
```

预期：

```text
FAIL because orchestration classes do not exist.
```

- [x] **步骤 3：实现算法客户端**

使用 `WebClient`，基础地址来自配置项 `dispatch.algorithm.base-url`，本地默认值为 `http://localhost:8090`。

- [x] **步骤 4：实现编排事务**

单个事务内完成：

1. 加载订单，非 `PENDING_DISPATCH` 订单拒绝调度。
2. 加载规则组、可用车辆、司机和进行中任务。
3. 组装候选任务请求。
4. 调用算法服务。
5. 保存 `DispatchDecision`。
6. `AUTO_DISPATCH` 时确认订单并创建或更新 `VehicleTask`。
7. `MANUAL_REVIEW` 时把订单标记为待人工确认。
8. `NO_FEASIBLE_PLAN` 时把订单标记为不可服务。
9. 写审计日志。

- [x] **步骤 5：运行编排测试**

运行：

```powershell
mvn -q -pl apps/api -Dtest=DispatchOrchestratorTest test
```

预期：

```text
BUILD SUCCESS
```

- [x] **步骤 6：提交**

```bash
git add apps/api/src/main/java/com/idavy/drtops/integration apps/api/src/main/java/com/idavy/drtops/domain/dispatch apps/api/src/test/java/com/idavy/drtops/domain/dispatch
git commit -m "feat: add dispatch orchestration"
```

---

### Task 8：人工确认、任务执行与异常处理

**文件：**
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewController.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskController.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/task/TaskExecutionService.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/order/OrderExceptionService.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java`

**接口：**
- `POST /api/dispatch-decisions/{decisionId}/approve`
- `POST /api/dispatch-decisions/{decisionId}/reject`
- `POST /api/vehicle-tasks/{taskId}/start`
- `POST /api/vehicle-tasks/{taskId}/stops/{stopId}/arrive`
- `POST /api/vehicle-tasks/{taskId}/stops/{stopId}/board`
- `POST /api/vehicle-tasks/{taskId}/stops/{stopId}/alight`
- `POST /api/vehicle-tasks/{taskId}/complete`
- `POST /api/orders/{orderId}/cancel`
- `POST /api/orders/{orderId}/no-show`
- `POST /api/vehicle-tasks/{taskId}/exception`

- [x] **步骤 1：编写执行流程测试**

```java
@Test
void taskCanMoveThroughStartArriveBoardAlightComplete() throws Exception {
    UUID taskId = createConfirmedTaskWithOneOrder();

    mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/start")).andExpect(status().isOk());
    UUID boardingStopId = firstBoardingStop(taskId);
    mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + boardingStopId + "/arrive")).andExpect(status().isOk());
    mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + boardingStopId + "/board")).andExpect(status().isOk());
    UUID alightingStopId = firstAlightingStop(taskId);
    mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + alightingStopId + "/arrive")).andExpect(status().isOk());
    mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + alightingStopId + "/alight")).andExpect(status().isOk());
    mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/complete")).andExpect(status().isOk());

    assertThat(vehicleTaskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
}
```

- [x] **步骤 2：编写异常测试**

```java
@Test
void vehicleFailureClosesTaskAsExceptionAndAuditsReason() throws Exception {
    UUID taskId = createConfirmedTaskWithOneOrder();

    mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/exception")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"车辆故障\"}"))
            .andExpect(status().isOk());

    assertThat(vehicleTaskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.EXCEPTION);
    assertThat(auditLogRepository.findByEntityId(taskId)).anyMatch(log -> log.getAction().equals("TASK_EXCEPTION"));
}
```

- [x] **步骤 3：运行测试确认失败**

运行：

```powershell
mvn -q -pl apps/api -Dtest=TaskExecutionApiTest,OrderExceptionApiTest test
```

预期：

```text
FAIL because execution APIs do not exist.
```

- [x] **步骤 4：实现执行服务**

必须限制状态流转：

- `PENDING_DEPARTURE` 通过 start 进入 `IN_PROGRESS`。
- 一个任务节点只能到达一次。
- 上车只能发生在已到达的上车节点。
- 下车只能发生在已到达的下车节点。
- 所有必要节点完成后，车辆任务才能完成。
- 车辆故障时，任务进入 `EXCEPTION`，受影响订单进入 `EXCEPTION_CLOSED`。

- [x] **步骤 5：运行测试**

运行：

```powershell
mvn -q -pl apps/api -Dtest=TaskExecutionApiTest,OrderExceptionApiTest test
```

预期：

```text
BUILD SUCCESS
```

- [x] **步骤 6：提交**

```bash
git add apps/api/src/main/java/com/idavy/drtops/domain/dispatch apps/api/src/main/java/com/idavy/drtops/domain/task apps/api/src/main/java/com/idavy/drtops/domain/order apps/api/src/test/java/com/idavy/drtops/domain
git commit -m "feat: add manual review task execution and exceptions"
```

---

### Task 9：审计日志与运营指标

**文件：**
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/audit/AuditLog.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/audit/AuditLogRepository.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/audit/AuditLogService.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/audit/AuditLogController.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsService.java`
- 新建：`apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsController.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/audit/AuditLogApiTest.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsServiceTest.java`

**接口：**
- 产出：`GET /api/audit-logs`
- 产出：`GET /api/metrics/operations-summary`
- 指标字段：`orderCount`、`confirmationRate`、`autoDispatchRate`、`manualReviewRate`、`averageWaitMinutes`、`averageDetourMinutes`、`taskCompletionRate`、`exceptionCloseRate`、`vehicleUtilizationRate`

- [x] **步骤 1：编写审计测试**

```java
@Test
void auditLogsContainDispatchDecisionAndManualAction() throws Exception {
    UUID orderId = createOrderThroughManualReview();
    approveManualReview(orderId);

    mockMvc.perform(get("/api/audit-logs").param("entityId", orderId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].action").value(hasItems("DISPATCH_DECISION", "MANUAL_REVIEW_APPROVED")));
}
```

- [x] **步骤 2：编写指标测试**

```java
@Test
void computesOperationsSummaryFromOrdersTasksAndDecisions() {
    seedCompletedAutoDispatchOrder();
    seedManualReviewOrder();
    seedExceptionClosedOrder();

    OperationsSummary summary = service.calculateSummary(LocalDate.parse("2026-07-08"));

    assertThat(summary.orderCount()).isEqualTo(3);
    assertThat(summary.confirmationRate()).isEqualByComparingTo("0.6667");
    assertThat(summary.autoDispatchRate()).isEqualByComparingTo("0.3333");
    assertThat(summary.manualReviewRate()).isEqualByComparingTo("0.3333");
}
```

- [x] **步骤 3：运行测试确认失败**

运行：

```powershell
mvn -q -pl apps/api -Dtest=AuditLogApiTest,OperationsMetricsServiceTest test
```

预期：

```text
FAIL because audit and metrics services do not exist.
```

- [x] **步骤 4：实现审计服务**

每条审计记录必须包含：

- `entityType`
- `entityId`
- `action`
- `actorType`
- `actorId`
- `reason`
- `metadataJson`
- `createdAt`

- [x] **步骤 5：实现指标服务**

可使用 repository 查询或 SQL。所有比例字段使用小数计算，分母为 0 时返回 `0.0000`。

- [x] **步骤 6：运行测试**

运行：

```powershell
mvn -q -pl apps/api -Dtest=AuditLogApiTest,OperationsMetricsServiceTest test
```

预期：

```text
BUILD SUCCESS
```

- [x] **步骤 7：提交**

```bash
git add apps/api/src/main/java/com/idavy/drtops/domain/audit apps/api/src/main/java/com/idavy/drtops/metrics apps/api/src/test/java/com/idavy/drtops/domain/audit apps/api/src/test/java/com/idavy/drtops/metrics
git commit -m "feat: add audit logs and operations metrics"
```

---

### Task 10：前端基础框架与 API 客户端

**文件：**
- 新建：`apps/admin-web/src/router/index.ts`
- 新建：`apps/admin-web/src/api/http.ts`
- 新建：`apps/admin-web/src/api/types.ts`
- 新建：`apps/admin-web/src/api/resources.ts`
- 新建：`apps/admin-web/src/api/orders.ts`
- 新建：`apps/admin-web/src/api/tasks.ts`
- 新建：`apps/admin-web/src/api/metrics.ts`
- 新建：`apps/admin-web/src/layouts/AppLayout.vue`
- 新建：`apps/admin-web/src/stores/operationsStore.ts`
- 新建：`apps/admin-web/src/pages/DashboardPage.vue`
- 新建：`apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- 新建：`apps/admin-web/src/pages/ResourcesPage.vue`
- 新建：`apps/admin-web/src/pages/RulesPage.vue`
- 新建：`apps/admin-web/src/pages/OrdersPage.vue`
- 新建：`apps/admin-web/src/pages/TasksPage.vue`
- 新建：`apps/admin-web/src/pages/AuditLogsPage.vue`
- 新建：`apps/admin-web/src/api/http.test.ts`

**接口：**
- 消费任务 4、5、8、9 的后端接口
- 产出路由：`/`、`/dispatch`、`/resources`、`/rules`、`/orders`、`/tasks`、`/audit-logs`

- [x] **步骤 1：编写 API 客户端测试**

```ts
import { describe, expect, it } from "vitest";
import { unwrapApiResponse } from "./http";

describe("unwrapApiResponse", () => {
  it("returns data from backend response envelope", () => {
    expect(unwrapApiResponse({ data: { id: "1", name: "demo" } })).toEqual({ id: "1", name: "demo" });
  });
});
```

- [x] **步骤 2：运行前端测试确认失败**

运行：

```powershell
cd apps/admin-web
npm run test -- http.test.ts
```

预期：

```text
FAIL because API client does not exist.
```

- [x] **步骤 3：实现路由和布局**

使用左侧导航的企业后台布局，菜单包括：

- 调度工作台
- 订单中心
- 车辆任务
- 资源配置
- 规则配置
- 运营看板
- 审计日志

- [x] **步骤 4：实现类型化 API 客户端**

使用一个 `request<T>()` 函数封装 `fetch`、`VITE_API_BASE_URL` 和后端返回结构 `{ data: T }`。

- [x] **步骤 5：运行前端检查**

运行：

```powershell
cd apps/admin-web
npm run typecheck
npm run test
```

预期：

```text
typecheck exits 0
tests exit 0
```

- [x] **步骤 6：提交**

```bash
git add apps/admin-web
git commit -m "feat: add admin web foundation and API client"
```

---

### Task 11：资源、规则、订单和任务页面

**文件：**
- 修改：`apps/admin-web/src/pages/ResourcesPage.vue`
- 修改：`apps/admin-web/src/pages/RulesPage.vue`
- 修改：`apps/admin-web/src/pages/OrdersPage.vue`
- 修改：`apps/admin-web/src/pages/TasksPage.vue`
- 新建：`apps/admin-web/src/components/VirtualStopTable.vue`
- 新建：`apps/admin-web/src/components/VehicleTable.vue`
- 新建：`apps/admin-web/src/components/DriverTable.vue`
- 新建：`apps/admin-web/src/components/RuleSetForm.vue`
- 新建：`apps/admin-web/src/components/OrderCreateDialog.vue`
- 新建：`apps/admin-web/src/components/TaskStopTimeline.vue`
- 新建：`apps/admin-web/src/pages/orders-page.test.ts`
- 新建：`apps/admin-web/src/pages/tasks-page.test.ts`

**接口：**
- 消费任务 10 的 API 客户端
- 产出即时订单和短时预约订单录入 UI
- 产出任务执行控制：发车、到站、上车、下车、完成、异常

- [x] **步骤 1：编写页面测试**

```ts
it("shows create order action and order status columns", async () => {
  render(OrdersPage);
  expect(await screen.findByRole("button", { name: "录入需求" })).toBeInTheDocument();
  expect(screen.getByText("订单状态")).toBeInTheDocument();
  expect(screen.getByText("预计上车时间")).toBeInTheDocument();
});
```

- [x] **步骤 2：运行测试确认失败**

运行：

```powershell
cd apps/admin-web
npm run test -- orders-page.test.ts tasks-page.test.ts
```

预期：

```text
FAIL because page components are incomplete.
```

- [x] **步骤 3：实现资源页面**

展示服务区域、虚拟站点、车辆、司机和容量信息。使用适合调度场景的紧凑表格和筛选控件。

- [x] **步骤 4：实现规则页面**

暴露这些配置：

- 最大等待时间。
- 最大绕行时间。
- 预约窗口。
- 执行中插单策略。
- 自动派发阈值。
- 人工确认阈值。
- 等待、绕行、稳定性、利用率权重。

- [x] **步骤 5：实现订单页面**

支持：

- 录入需求。
- 查看订单列表。
- 触发调度。
- 取消订单。
- 异常关闭。
- 查看调度决策抽屉。

- [x] **步骤 6：实现任务页面**

支持：

- 任务列表。
- 任务站点时间线。
- 执行模拟按钮。
- 车辆故障异常操作。

- [x] **步骤 7：运行前端检查**

运行：

```powershell
cd apps/admin-web
npm run typecheck
npm run test
```

预期：

```text
typecheck exits 0
tests exit 0
```

- [x] **步骤 8：提交**

```bash
git add apps/admin-web/src/pages apps/admin-web/src/components
git commit -m "feat: add resource rule order and task pages"
```

---

### Task 12：调度工作台与基础运营看板

**文件：**
- 修改：`apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- 修改：`apps/admin-web/src/pages/DashboardPage.vue`
- 修改：`apps/admin-web/src/pages/AuditLogsPage.vue`
- 新建：`apps/admin-web/src/components/DispatchMap.vue`
- 新建：`apps/admin-web/src/components/DispatchDecisionPanel.vue`
- 新建：`apps/admin-web/src/components/RealtimeOrderList.vue`
- 新建：`apps/admin-web/src/components/VehicleTaskList.vue`
- 新建：`apps/admin-web/src/components/MetricTileGrid.vue`
- 新建：`apps/admin-web/src/pages/dispatch-workbench.test.ts`
- 新建：`apps/admin-web/src/pages/dashboard-page.test.ts`

**接口：**
- 消费订单、任务、调度决策、指标和审计日志接口
- 产出调度工作台：地图、实时订单、车辆任务、算法解释、人工操作

- [x] **步骤 1：编写工作台测试**

```ts
it("renders dispatch workbench operational regions", async () => {
  render(DispatchWorkbenchPage);
  expect(await screen.findByText("实时订单")).toBeInTheDocument();
  expect(screen.getByText("车辆任务")).toBeInTheDocument();
  expect(screen.getByText("算法解释")).toBeInTheDocument();
  expect(screen.getByLabelText("调度地图")).toBeInTheDocument();
});
```

- [x] **步骤 2：编写看板测试**

```ts
it("renders first-version operations metrics", async () => {
  render(DashboardPage);
  expect(await screen.findByText("订单确认率")).toBeInTheDocument();
  expect(screen.getByText("自动派发率")).toBeInTheDocument();
  expect(screen.getByText("平均等待时间")).toBeInTheDocument();
  expect(screen.getByText("车辆利用率")).toBeInTheDocument();
});
```

- [x] **步骤 3：运行测试确认失败**

运行：

```powershell
cd apps/admin-web
npm run test -- dispatch-workbench.test.ts dashboard-page.test.ts
```

预期：

```text
FAIL because components are incomplete.
```

- [x] **步骤 4：实现调度地图**

使用 MapLibre GL JS，渲染：

- 服务区域多边形。
- 虚拟站点。
- 车辆标记。
- 任务路线折线。

地图容器使用稳定尺寸，避免地图控件导致布局抖动。

- [x] **步骤 5：实现工作台面板**

使用高密度运营布局：

- 左侧：实时订单。
- 中间：地图。
- 右侧：选中订单或任务的详情与算法解释。
- 底部：人工确认队列和异常列表。

- [x] **步骤 6：实现看板和审计页面**

看板展示指标卡片和基础趋势区，数据来自当前 API。审计页面支持按实体类型、操作和日期筛选。

- [x] **步骤 7：运行前端检查**

运行：

```powershell
cd apps/admin-web
npm run typecheck
npm run test
```

预期：

```text
typecheck exits 0
tests exit 0
```

- [x] **步骤 8：提交**

```bash
git add apps/admin-web/src/pages apps/admin-web/src/components
git commit -m "feat: add dispatch workbench and operations dashboard"
```

---

### Task 13：端到端演示链路

**文件：**
- 新建：`apps/admin-web/e2e/dispatch-flow.spec.ts`
- 新建：`apps/api/src/test/java/com/idavy/drtops/e2e/DispatchFlowIntegrationTest.java`
- 修改：`README.md`

**接口：**
- 验证从需求录入、调度决策到车辆任务执行的完整链路

- [x] **步骤 1：编写后端集成流测试**

```java
@Test
void demandToDispatchToTaskCompletionFlow() {
    UUID orderId = orderApi.createImmediateOrder(sampleDemand());
    dispatchApi.dispatchOrder(orderId);
    RideOrder confirmed = orderRepository.findById(orderId).orElseThrow();
    assertThat(confirmed.getStatus()).isIn(OrderStatus.CONFIRMED, OrderStatus.PENDING_MANUAL_REVIEW);

    if (confirmed.getStatus() == OrderStatus.PENDING_MANUAL_REVIEW) {
        manualReviewApi.approveLatestDecision(orderId);
    }

    VehicleTask task = vehicleTaskRepository.findByOrderId(orderId).orElseThrow();
    taskExecutionApi.start(task.getId());
    taskExecutionApi.completeAllStops(task.getId());
    taskExecutionApi.complete(task.getId());

    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
}
```

- [x] **步骤 2：编写 Playwright 流程**

```ts
test("operator can create demand dispatch it and complete the task", async ({ page }) => {
  await page.goto("/orders");
  await page.getByRole("button", { name: "录入需求" }).click();
  await page.getByLabel("乘客姓名").fill("张三");
  await page.getByLabel("乘客手机号").fill("13800000000");
  await page.getByLabel("乘客人数").fill("1");
  await page.getByRole("button", { name: "提交需求" }).click();
  await page.getByRole("button", { name: "调度" }).click();
  await expect(page.getByText(/已确认|待人工确认/)).toBeVisible();
});
```

- [x] **步骤 3：运行测试确认失败**

运行：

```powershell
mvn -q -pl apps/api -Dtest=DispatchFlowIntegrationTest test
cd apps/admin-web
npm run e2e -- dispatch-flow.spec.ts
```

预期：

```text
FAIL until all previous tasks are complete and local services are running.
```

- [x] **步骤 4：补齐演示链路连线**

把页面动作连到后端接口，并确保演示种子数据至少支持一个可以自动派发的订单。

- [x] **步骤 5：更新 README 运行手册**

写入：

```powershell
docker compose -f infra/docker-compose.yml up -d
mvn -pl apps/api spring-boot:run
cd apps/algorithm
uvicorn drt_algorithm.main:app --port 8090 --reload
cd ../admin-web
npm run dev
```

- [x] **步骤 6：运行完整验证**

运行：

```powershell
mvn -q -pl apps/api test
cd apps/algorithm
python -m pytest -v
cd ../admin-web
npm run typecheck
npm run test
npm run e2e -- dispatch-flow.spec.ts
```

预期：

```text
backend tests pass
algorithm tests pass
frontend typecheck passes
frontend unit tests pass
Playwright dispatch flow passes
```

- [x] **步骤 7：提交**

```bash
git add apps README.md
git commit -m "test: add end-to-end dispatch demo flow"
```

---

### Task 14：MVP 就绪检查

**文件：**
- 新建：`docs/release/mvp-readiness-checklist.md`
- 修改：`README.md`

**接口：**
- 产出与已确认设计文档对应的发布检查清单

- [x] **步骤 1：创建就绪检查清单**

清单必须覆盖：

- 资源配置：服务区域、虚拟站点、车辆、司机、规则组。
- 需求录入：即时订单和短时预约订单。
- 调度：无可行方案、自动派发、人工确认。
- 车辆任务：创建、插单、执行、完成。
- 异常：取消、未到、车辆故障、严重延误。
- 审计：调度决策、人工操作、规则变更、任务状态变化。
- 指标：确认率、自动派发率、人工确认率、等待时间、绕行时间、完成率、异常率、车辆利用率。
- 页面：调度工作台、地图、决策解释、资源页面、任务模拟。

- [x] **步骤 2：运行最终验证**

运行：

```powershell
git status --short
mvn -q -pl apps/api test
cd apps/algorithm
python -m pytest -v
cd ../admin-web
npm run typecheck
npm run test
npm run e2e -- dispatch-flow.spec.ts
```

预期：

```text
git status shows only intended readiness documentation before commit
all backend tests pass
all algorithm tests pass
all frontend checks pass
e2e dispatch flow passes
```

- [x] **步骤 3：提交**

```bash
git add README.md docs/release/mvp-readiness-checklist.md
git commit -m "docs: add MVP readiness checklist"
```

---

## 自查结果

**设计覆盖：** 本计划覆盖已确认设计文档中的产品边界、订单和任务状态流、算法服务、模块边界、核心数据对象、系统架构与数据流、异常处理、审计、指标、企业管理页面和 MVP 验收场景。

**已排除范围：** 完整乘客端、完整司机端、监管端、支付结算、机器学习预测、Kafka 生产级事件流不进入本 MVP 实施计划，需要单独拆计划。

**未解决标记扫描：** 本计划给出了明确文件路径、接口、命令和预期结果，没有保留未确定的实施项。

**类型一致性：** 核心命名在各任务中保持一致：`RideOrder`、`VehicleTask`、`TaskStop`、`DispatchDecision`、`DispatchRuleSet`、`OrderStatus`、`TaskStatus`、`DispatchEvaluateRequest`、`DispatchEvaluateResponse`。
