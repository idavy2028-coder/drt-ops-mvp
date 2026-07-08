# Dynamic Responsive Bus Ops MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first enterprise-facing MVP for a dynamic responsive bus operations system that can configure resources, accept ride demand, run real-time dispatch evaluation, create vehicle tasks, simulate execution, handle exceptions, audit decisions, and show a basic dispatch workbench.

**Architecture:** Use a monorepo with a modular Spring Boot business backend, a Python/FastAPI dispatch algorithm service, a Vue 3/TypeScript admin web app, and PostgreSQL/PostGIS as the source of truth. The Java backend owns business state and dispatch orchestration; the Python service returns dispatch recommendations and explanations without mutating business records.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Maven, PostgreSQL 16+ with PostGIS 3+, Redis 7+, Python 3.12, FastAPI, pytest, Vue 3, TypeScript, Vite, Pinia, Vue Router, Element Plus, MapLibre GL JS, Vitest, Playwright.

## Global Constraints

- First MVP scope is enterprise internal operations only: complete passenger app, driver app, regulator portal, payment, membership, marketing, and full customer service are out of scope.
- Core operating mode is virtual-stop dynamic responsive bus service.
- Dispatch trigger is real-time per order; architecture must allow future batch or hybrid triggers.
- Order promise rule is "confirm only after feasible vehicle/task is found".
- Supported demand window is immediate travel plus short booking up to 2 hours ahead.
- Dispatch assignment uses configurable tiered auto-dispatch; default manual fallback is medium strictness.
- Dispatch scoring is weighted and defaults toward passenger experience plus service stability.
- In-service insertion is configurable; default allows only same-direction, low-detour insertion.
- Algorithm service must return explanations for every dispatch attempt.
- Java backend owns order, task, audit, and rule state; algorithm service must not directly write business tables.
- All key state transitions, manual actions, rule changes, and algorithm decisions must be audited.
- Every implementation task must end with a runnable verification command and a commit.

---

## Scope Check

The product design covers several future subsystems. This implementation plan deliberately builds only the first MVP vertical slice:

- Resource and rule configuration.
- Demand entry and virtual-stop matching.
- Real-time algorithm evaluation.
- Dispatch orchestration and vehicle task generation.
- Simplified execution simulation.
- Exceptions, audit, and basic metrics.
- Enterprise admin web pages for the above.

Separate future plans should cover complete passenger app, driver app, regulator portal, payment/settlement, machine-learning demand prediction, and production event streaming with Kafka.

## File Structure

Create this monorepo structure:

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

Boundary rules:

- `apps/api` contains business APIs, domain services, persistence, orchestration, metrics, and audit.
- `apps/algorithm` contains stateless dispatch evaluation and no business persistence.
- `apps/admin-web` contains only presentation, API clients, client-side state, and UI routing.
- `infra` contains local development dependencies only.
- `docs/api/dispatch-algorithm-contract.md` is the contract between Java orchestration and Python algorithm service.

---

### Task 1: Monorepo Skeleton And Local Runtime

**Files:**
- Create: `pom.xml`
- Create: `apps/api/pom.xml`
- Create: `apps/api/src/main/java/com/idavy/drtops/DrtOpsApplication.java`
- Create: `apps/api/src/main/resources/application.yml`
- Create: `apps/algorithm/pyproject.toml`
- Create: `apps/algorithm/src/drt_algorithm/main.py`
- Create: `apps/admin-web/package.json`
- Create: `apps/admin-web/index.html`
- Create: `apps/admin-web/vite.config.ts`
- Create: `apps/admin-web/tsconfig.json`
- Create: `apps/admin-web/src/main.ts`
- Create: `apps/admin-web/src/App.vue`
- Create: `infra/docker-compose.yml`
- Create: `infra/postgres/init-postgis.sql`
- Create: `README.md`

**Interfaces:**
- Produces: backend health endpoint `GET /actuator/health`
- Produces: algorithm health endpoint `GET /health`
- Produces: admin web route `/`
- Produces: local services `postgres:5432`, `redis:6379`, `api:8080`, `algorithm:8090`, `admin-web:5173`

- [ ] **Step 1: Create backend skeleton**

Create root Maven aggregator with module `apps/api`.

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

Create Spring Boot API module with dependencies for web, validation, data JPA, Flyway, Postgres, actuator, test, Testcontainers, and WebClient.

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

- [ ] **Step 2: Create algorithm skeleton**

```python
# D:/codex-projects/apps/algorithm/src/drt_algorithm/main.py
from fastapi import FastAPI

app = FastAPI(title="DRT Dispatch Algorithm Service", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}
```

- [ ] **Step 3: Create admin web skeleton**

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

- [ ] **Step 4: Create local infrastructure**

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

- [ ] **Step 5: Verify skeleton**

Run:

```powershell
mvn -q -pl apps/api test
cd apps/algorithm
python -m pytest
cd ../admin-web
npm run typecheck
```

Expected:

```text
Maven exits 0.
pytest exits 0.
TypeScript typecheck exits 0.
```

- [ ] **Step 6: Commit**

```bash
git add pom.xml apps infra README.md
git commit -m "chore: scaffold dynamic bus ops monorepo"
```

---

### Task 2: Database Schema And Seed Data

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V1__create_core_schema.sql`
- Create: `apps/api/src/main/resources/db/migration/V2__seed_demo_operations.sql`
- Create: `apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java`

**Interfaces:**
- Produces tables: `service_areas`, `virtual_stops`, `dispatch_rule_sets`, `vehicles`, `drivers`, `ride_orders`, `vehicle_tasks`, `task_stops`, `dispatch_decisions`, `audit_logs`
- Produces PostGIS columns: `service_areas.boundary`, `virtual_stops.location`, `vehicles.current_location`

- [ ] **Step 1: Write failing migration test**

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

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
mvn -q -pl apps/api -Dtest=DatabaseMigrationTest test
```

Expected:

```text
FAIL because migration files or tables do not exist.
```

- [ ] **Step 3: Create schema migration**

Create tables with explicit constraints:

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

Continue the same migration with these exact business tables:

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

- [ ] **Step 4: Create seed migration**

Seed one area, six virtual stops, two vehicles, two drivers, and one default rule set. Use fixed UUIDs so tests can reference them.

- [ ] **Step 5: Run migration test**

Run:

```powershell
mvn -q -pl apps/api -Dtest=DatabaseMigrationTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/resources/db/migration apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java
git commit -m "feat: add core operations database schema"
```

---

### Task 3: Java Domain Model And State Rules

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/OrderStatus.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrder.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTask.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecision.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionRepository.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderStateTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/task/VehicleTaskStateTest.java`

**Interfaces:**
- Produces enum `OrderStatus` with values `PENDING_DISPATCH`, `UNSERVICEABLE`, `PENDING_MANUAL_REVIEW`, `CONFIRMED`, `CANCELLED`, `IN_PROGRESS`, `COMPLETED`, `EXCEPTION_CLOSED`
- Produces enum `TaskStatus` with values `PENDING_DEPARTURE`, `DISPATCHED`, `IN_PROGRESS`, `PAUSED`, `COMPLETED`, `CANCELLED`, `EXCEPTION`
- Produces domain methods `RideOrder.confirm(...)`, `RideOrder.markUnserviceable(...)`, `RideOrder.markPendingManualReview(...)`, `RideOrder.cancel(...)`, `RideOrder.startExecution()`, `RideOrder.complete()`, `RideOrder.closeException(...)`

- [ ] **Step 1: Write failing state transition tests**

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

- [ ] **Step 2: Run tests to verify they fail**

Run:

```powershell
mvn -q -pl apps/api -Dtest=RideOrderStateTest,VehicleTaskStateTest test
```

Expected:

```text
FAIL because domain classes do not exist.
```

- [ ] **Step 3: Implement minimal domain objects**

Implement JPA entities with private state and domain methods. Keep state changes inside domain methods; controllers and orchestration services must not assign status strings directly.

- [ ] **Step 4: Add repository smoke tests**

Add persistence tests that save and reload one order and one vehicle task.

- [ ] **Step 5: Run domain tests**

Run:

```powershell
mvn -q -pl apps/api -Dtest=*StateTest,*RepositoryTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/idavy/drtops/domain apps/api/src/test/java/com/idavy/drtops/domain
git commit -m "feat: add order task and dispatch domain model"
```

---

### Task 4: Resource And Rule Configuration APIs

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/fleet/VehicleController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/fleet/DriverController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetController.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/area/ServiceAreaApiTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/fleet/FleetApiTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetApiTest.java`

**Interfaces:**
- Produces REST endpoints:
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

- [ ] **Step 1: Write API tests**

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

- [ ] **Step 2: Run tests to verify they fail**

Run:

```powershell
mvn -q -pl apps/api -Dtest=ServiceAreaApiTest,FleetApiTest,DispatchRuleSetApiTest test
```

Expected:

```text
FAIL because controllers do not exist.
```

- [ ] **Step 3: Implement controllers and services**

Return a consistent envelope:

```java
public record ApiResponse<T>(T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data);
    }
}
```

Use request DTO validation annotations for numeric thresholds, required names, capacities, and coordinates.

- [ ] **Step 4: Run API tests**

Run:

```powershell
mvn -q -pl apps/api -Dtest=ServiceAreaApiTest,FleetApiTest,DispatchRuleSetApiTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/idavy/drtops/domain/area apps/api/src/main/java/com/idavy/drtops/domain/fleet apps/api/src/main/java/com/idavy/drtops/domain/dispatch apps/api/src/test/java/com/idavy/drtops/domain
git commit -m "feat: add resource and dispatch rule APIs"
```

---

### Task 5: Demand Entry And Virtual Stop Matching

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopMatcher.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderApiTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/area/VirtualStopMatcherTest.java`

**Interfaces:**
- Consumes: `VirtualStop` and `ServiceArea` from Task 4
- Produces endpoint `POST /api/orders`
- Produces endpoint `GET /api/orders`
- Produces method `VirtualStopMatch matchStops(BigDecimal originLng, BigDecimal originLat, BigDecimal destinationLng, BigDecimal destinationLat, Instant requestedDepartureAt)`

- [ ] **Step 1: Write virtual-stop matching tests**

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

- [ ] **Step 2: Write order API tests**

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

- [ ] **Step 3: Run tests to verify they fail**

Run:

```powershell
mvn -q -pl apps/api -Dtest=VirtualStopMatcherTest,RideOrderApiTest test
```

Expected:

```text
FAIL because matcher and order API do not exist.
```

- [ ] **Step 4: Implement matching and order creation**

Use PostGIS distance queries through `JdbcTemplate` for matching:

```sql
SELECT id, ST_Distance(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_meters
FROM virtual_stops
WHERE enabled = true
  AND boarding_enabled = true
  AND ST_DWithin(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, service_radius_meters)
ORDER BY distance_meters ASC
LIMIT 1
```

- [ ] **Step 5: Run tests**

Run:

```powershell
mvn -q -pl apps/api -Dtest=VirtualStopMatcherTest,RideOrderApiTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/idavy/drtops/domain/order apps/api/src/main/java/com/idavy/drtops/domain/area apps/api/src/test/java/com/idavy/drtops/domain
git commit -m "feat: add ride demand entry and virtual stop matching"
```

---

### Task 6: Algorithm Service Contract And Evaluation Engine

**Files:**
- Create: `docs/api/dispatch-algorithm-contract.md`
- Create: `apps/algorithm/src/drt_algorithm/schemas.py`
- Create: `apps/algorithm/src/drt_algorithm/matching.py`
- Create: `apps/algorithm/src/drt_algorithm/insertion.py`
- Create: `apps/algorithm/src/drt_algorithm/scoring.py`
- Create: `apps/algorithm/src/drt_algorithm/explanations.py`
- Modify: `apps/algorithm/src/drt_algorithm/main.py`
- Create: `apps/algorithm/tests/test_dispatch_evaluation.py`

**Interfaces:**
- Produces algorithm endpoint `POST /dispatch/evaluate`
- Request model `DispatchEvaluateRequest`
- Response model `DispatchEvaluateResponse`
- Response decisions: `AUTO_DISPATCH`, `MANUAL_REVIEW`, `NO_FEASIBLE_PLAN`

- [ ] **Step 1: Write algorithm contract**

Document this JSON contract:

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

- [ ] **Step 2: Write failing pytest cases**

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

- [ ] **Step 3: Run tests to verify they fail**

Run:

```powershell
cd apps/algorithm
python -m pytest tests/test_dispatch_evaluation.py -v
```

Expected:

```text
FAIL because /dispatch/evaluate and schemas do not exist.
```

- [ ] **Step 4: Implement Pydantic schemas**

Define strict models with `uuid.UUID`, `datetime`, integer passenger counts, decimal weights, and string enums.

- [ ] **Step 5: Implement evaluation**

Implement deterministic first-version logic:

1. Reject empty candidates with `NO_FEASIBLE_PLAN`.
2. Reject candidates with capacity below passenger count.
3. Reject candidates exceeding max wait or max detour.
4. Reject in-service candidates that are not same-direction when insertion policy is `SAME_DIRECTION_ONLY`.
5. Score remaining candidates:
   - wait score decreases as wait approaches max wait.
   - detour score decreases as detour approaches max detour.
   - stability score decreases when existing passengers are affected.
   - utilization score increases when free seats are used without exceeding capacity.
6. Return highest score as `AUTO_DISPATCH` when score is at or above auto threshold.
7. Return `MANUAL_REVIEW` when score is at or above manual threshold and below auto threshold.
8. Return `NO_FEASIBLE_PLAN` when all candidates are rejected.

- [ ] **Step 6: Run algorithm tests**

Run:

```powershell
cd apps/algorithm
python -m pytest -v
```

Expected:

```text
all tests pass
```

- [ ] **Step 7: Commit**

```bash
git add docs/api/dispatch-algorithm-contract.md apps/algorithm
git commit -m "feat: add dispatch algorithm evaluation service"
```

---

### Task 7: Java Dispatch Orchestration

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/algorithm/AlgorithmClient.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/algorithm/DispatchEvaluateRequest.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/algorithm/DispatchEvaluateResponse.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java`

**Interfaces:**
- Consumes: `POST /dispatch/evaluate` from Task 6
- Produces method `DispatchResult dispatchOrder(UUID orderId)`
- Produces endpoint `POST /api/orders/{orderId}/dispatch`

- [ ] **Step 1: Write orchestration tests**

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

- [ ] **Step 2: Run tests to verify they fail**

Run:

```powershell
mvn -q -pl apps/api -Dtest=DispatchOrchestratorTest test
```

Expected:

```text
FAIL because orchestration classes do not exist.
```

- [ ] **Step 3: Implement algorithm client**

Use `WebClient` with base URL from `dispatch.algorithm.base-url`. Set local default `http://localhost:8090`.

- [ ] **Step 4: Implement orchestration transaction**

Inside one transaction:

1. Load order and reject non-`PENDING_DISPATCH` orders.
2. Load rule set, available vehicles, drivers, and active tasks.
3. Build candidate task request.
4. Call algorithm service.
5. Persist `DispatchDecision`.
6. For `AUTO_DISPATCH`, confirm order and create or update `VehicleTask`.
7. For `MANUAL_REVIEW`, mark order pending manual review.
8. For `NO_FEASIBLE_PLAN`, mark order unserviceable.
9. Write audit log.

- [ ] **Step 5: Run orchestration tests**

Run:

```powershell
mvn -q -pl apps/api -Dtest=DispatchOrchestratorTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/idavy/drtops/integration apps/api/src/main/java/com/idavy/drtops/domain/dispatch apps/api/src/test/java/com/idavy/drtops/domain/dispatch
git commit -m "feat: add dispatch orchestration"
```

---

### Task 8: Manual Review, Task Execution, And Exceptions

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskExecutionService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/OrderExceptionService.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java`

**Interfaces:**
- Produces endpoint `POST /api/dispatch-decisions/{decisionId}/approve`
- Produces endpoint `POST /api/dispatch-decisions/{decisionId}/reject`
- Produces endpoint `POST /api/vehicle-tasks/{taskId}/start`
- Produces endpoint `POST /api/vehicle-tasks/{taskId}/stops/{stopId}/arrive`
- Produces endpoint `POST /api/vehicle-tasks/{taskId}/stops/{stopId}/board`
- Produces endpoint `POST /api/vehicle-tasks/{taskId}/stops/{stopId}/alight`
- Produces endpoint `POST /api/vehicle-tasks/{taskId}/complete`
- Produces endpoint `POST /api/orders/{orderId}/cancel`
- Produces endpoint `POST /api/orders/{orderId}/no-show`
- Produces endpoint `POST /api/vehicle-tasks/{taskId}/exception`

- [ ] **Step 1: Write execution tests**

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

- [ ] **Step 2: Write exception tests**

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

- [ ] **Step 3: Run tests to verify they fail**

Run:

```powershell
mvn -q -pl apps/api -Dtest=TaskExecutionApiTest,OrderExceptionApiTest test
```

Expected:

```text
FAIL because execution APIs do not exist.
```

- [ ] **Step 4: Implement execution services**

Enforce status transitions:

- `PENDING_DEPARTURE` -> `IN_PROGRESS` on start.
- A task stop can be arrived once.
- Boarding is allowed only at boarding stops after arrival.
- Alighting is allowed only at alighting stops after arrival.
- Task can complete only when all required stops are done.
- Vehicle failure sets task to `EXCEPTION` and affected orders to `EXCEPTION_CLOSED`.

- [ ] **Step 5: Run tests**

Run:

```powershell
mvn -q -pl apps/api -Dtest=TaskExecutionApiTest,OrderExceptionApiTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/idavy/drtops/domain/dispatch apps/api/src/main/java/com/idavy/drtops/domain/task apps/api/src/main/java/com/idavy/drtops/domain/order apps/api/src/test/java/com/idavy/drtops/domain
git commit -m "feat: add manual review task execution and exceptions"
```

---

### Task 9: Audit Logs And Operational Metrics

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/audit/AuditLog.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/audit/AuditLogRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/audit/AuditLogService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/audit/AuditLogController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsController.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/audit/AuditLogApiTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsServiceTest.java`

**Interfaces:**
- Produces endpoint `GET /api/audit-logs`
- Produces endpoint `GET /api/metrics/operations-summary`
- Produces metrics fields: `orderCount`, `confirmationRate`, `autoDispatchRate`, `manualReviewRate`, `averageWaitMinutes`, `averageDetourMinutes`, `taskCompletionRate`, `exceptionCloseRate`, `vehicleUtilizationRate`

- [ ] **Step 1: Write audit test**

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

- [ ] **Step 2: Write metrics test**

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

- [ ] **Step 3: Run tests to verify they fail**

Run:

```powershell
mvn -q -pl apps/api -Dtest=AuditLogApiTest,OperationsMetricsServiceTest test
```

Expected:

```text
FAIL because audit and metrics services do not exist.
```

- [ ] **Step 4: Implement audit service**

Every audited event must record:

- `entityType`
- `entityId`
- `action`
- `actorType`
- `actorId`
- `reason`
- `metadataJson`
- `createdAt`

- [ ] **Step 5: Implement metrics service**

Use repository queries or SQL views. Use decimal division with zero-denominator handling returning `0.0000`.

- [ ] **Step 6: Run tests**

Run:

```powershell
mvn -q -pl apps/api -Dtest=AuditLogApiTest,OperationsMetricsServiceTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/java/com/idavy/drtops/domain/audit apps/api/src/main/java/com/idavy/drtops/metrics apps/api/src/test/java/com/idavy/drtops/domain/audit apps/api/src/test/java/com/idavy/drtops/metrics
git commit -m "feat: add audit logs and operations metrics"
```

---

### Task 10: Admin Web Foundation And API Client

**Files:**
- Create: `apps/admin-web/src/router/index.ts`
- Create: `apps/admin-web/src/api/http.ts`
- Create: `apps/admin-web/src/api/types.ts`
- Create: `apps/admin-web/src/api/resources.ts`
- Create: `apps/admin-web/src/api/orders.ts`
- Create: `apps/admin-web/src/api/tasks.ts`
- Create: `apps/admin-web/src/api/metrics.ts`
- Create: `apps/admin-web/src/layouts/AppLayout.vue`
- Create: `apps/admin-web/src/stores/operationsStore.ts`
- Create: `apps/admin-web/src/pages/DashboardPage.vue`
- Create: `apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- Create: `apps/admin-web/src/pages/ResourcesPage.vue`
- Create: `apps/admin-web/src/pages/RulesPage.vue`
- Create: `apps/admin-web/src/pages/OrdersPage.vue`
- Create: `apps/admin-web/src/pages/TasksPage.vue`
- Create: `apps/admin-web/src/pages/AuditLogsPage.vue`
- Create: `apps/admin-web/src/api/http.test.ts`

**Interfaces:**
- Consumes backend endpoints from Tasks 4, 5, 8, and 9
- Produces routes `/`, `/dispatch`, `/resources`, `/rules`, `/orders`, `/tasks`, `/audit-logs`

- [ ] **Step 1: Write API client tests**

```ts
import { describe, expect, it } from "vitest";
import { unwrapApiResponse } from "./http";

describe("unwrapApiResponse", () => {
  it("returns data from backend response envelope", () => {
    expect(unwrapApiResponse({ data: { id: "1", name: "demo" } })).toEqual({ id: "1", name: "demo" });
  });
});
```

- [ ] **Step 2: Run frontend test to verify it fails**

Run:

```powershell
cd apps/admin-web
npm run test -- http.test.ts
```

Expected:

```text
FAIL because API client does not exist.
```

- [ ] **Step 3: Implement router and layout**

Use a left navigation shell for enterprise operations:

- Dispatch
- Orders
- Tasks
- Resources
- Rules
- Metrics
- Audit Logs

- [ ] **Step 4: Implement typed API client**

Use one `request<T>()` function backed by `fetch`, `VITE_API_BASE_URL`, and the backend envelope shape `{ data: T }`.

- [ ] **Step 5: Run frontend checks**

Run:

```powershell
cd apps/admin-web
npm run typecheck
npm run test
```

Expected:

```text
typecheck exits 0
tests exit 0
```

- [ ] **Step 6: Commit**

```bash
git add apps/admin-web
git commit -m "feat: add admin web foundation and API client"
```

---

### Task 11: Resource, Rule, Order, And Task Pages

**Files:**
- Modify: `apps/admin-web/src/pages/ResourcesPage.vue`
- Modify: `apps/admin-web/src/pages/RulesPage.vue`
- Modify: `apps/admin-web/src/pages/OrdersPage.vue`
- Modify: `apps/admin-web/src/pages/TasksPage.vue`
- Create: `apps/admin-web/src/components/VirtualStopTable.vue`
- Create: `apps/admin-web/src/components/VehicleTable.vue`
- Create: `apps/admin-web/src/components/DriverTable.vue`
- Create: `apps/admin-web/src/components/RuleSetForm.vue`
- Create: `apps/admin-web/src/components/OrderCreateDialog.vue`
- Create: `apps/admin-web/src/components/TaskStopTimeline.vue`
- Create: `apps/admin-web/src/pages/orders-page.test.ts`
- Create: `apps/admin-web/src/pages/tasks-page.test.ts`

**Interfaces:**
- Consumes API client from Task 10
- Produces order creation UI for immediate and short booking orders
- Produces task execution controls for start, arrive, board, alight, complete, exception

- [ ] **Step 1: Write page tests**

```ts
it("shows create order action and order status columns", async () => {
  render(OrdersPage);
  expect(await screen.findByRole("button", { name: "录入需求" })).toBeInTheDocument();
  expect(screen.getByText("订单状态")).toBeInTheDocument();
  expect(screen.getByText("预计上车时间")).toBeInTheDocument();
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```powershell
cd apps/admin-web
npm run test -- orders-page.test.ts tasks-page.test.ts
```

Expected:

```text
FAIL because page components are incomplete.
```

- [ ] **Step 3: Implement resources page**

Show service area, virtual stops, vehicles, drivers, and capacity. Use tables with compact filters.

- [ ] **Step 4: Implement rules page**

Expose:

- maximum wait minutes
- maximum detour minutes
- booking window minutes
- insertion policy
- auto dispatch threshold
- manual review threshold
- wait, detour, stability, utilization weights

- [ ] **Step 5: Implement orders page**

Support:

- creating demand
- listing status
- dispatch trigger button
- cancel button
- exception close button
- dispatch decision drawer

- [ ] **Step 6: Implement tasks page**

Support:

- task list
- task stop timeline
- execution simulation buttons
- vehicle failure exception action

- [ ] **Step 7: Run frontend checks**

Run:

```powershell
cd apps/admin-web
npm run typecheck
npm run test
```

Expected:

```text
typecheck exits 0
tests exit 0
```

- [ ] **Step 8: Commit**

```bash
git add apps/admin-web/src/pages apps/admin-web/src/components
git commit -m "feat: add resource rule order and task pages"
```

---

### Task 12: Dispatch Workbench And Basic Operations Dashboard

**Files:**
- Modify: `apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- Modify: `apps/admin-web/src/pages/DashboardPage.vue`
- Modify: `apps/admin-web/src/pages/AuditLogsPage.vue`
- Create: `apps/admin-web/src/components/DispatchMap.vue`
- Create: `apps/admin-web/src/components/DispatchDecisionPanel.vue`
- Create: `apps/admin-web/src/components/RealtimeOrderList.vue`
- Create: `apps/admin-web/src/components/VehicleTaskList.vue`
- Create: `apps/admin-web/src/components/MetricTileGrid.vue`
- Create: `apps/admin-web/src/pages/dispatch-workbench.test.ts`
- Create: `apps/admin-web/src/pages/dashboard-page.test.ts`

**Interfaces:**
- Consumes orders, tasks, dispatch decisions, metrics, audit logs
- Produces dispatch workbench with map, realtime orders, vehicle tasks, decision explanation, and manual actions

- [ ] **Step 1: Write workbench tests**

```ts
it("renders dispatch workbench operational regions", async () => {
  render(DispatchWorkbenchPage);
  expect(await screen.findByText("实时订单")).toBeInTheDocument();
  expect(screen.getByText("车辆任务")).toBeInTheDocument();
  expect(screen.getByText("算法解释")).toBeInTheDocument();
  expect(screen.getByLabelText("调度地图")).toBeInTheDocument();
});
```

- [ ] **Step 2: Write dashboard tests**

```ts
it("renders first-version operations metrics", async () => {
  render(DashboardPage);
  expect(await screen.findByText("订单确认率")).toBeInTheDocument();
  expect(screen.getByText("自动派发率")).toBeInTheDocument();
  expect(screen.getByText("平均等待时间")).toBeInTheDocument();
  expect(screen.getByText("车辆利用率")).toBeInTheDocument();
});
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```powershell
cd apps/admin-web
npm run test -- dispatch-workbench.test.ts dashboard-page.test.ts
```

Expected:

```text
FAIL because components are incomplete.
```

- [ ] **Step 4: Implement dispatch map**

Use MapLibre GL JS. Render:

- service area polygon
- virtual stops
- vehicle markers
- task route polyline

Use stable container sizing so map controls do not shift layout.

- [ ] **Step 5: Implement workbench panels**

Use dense operations layout:

- left panel: realtime orders
- center: map
- right panel: selected order/task and algorithm explanation
- bottom panel: manual review queue and exceptions

- [ ] **Step 6: Implement dashboard and audit pages**

Dashboard shows metric tiles and basic trend sections backed by current API data. Audit page shows filters by entity type, action, and date.

- [ ] **Step 7: Run frontend checks**

Run:

```powershell
cd apps/admin-web
npm run typecheck
npm run test
```

Expected:

```text
typecheck exits 0
tests exit 0
```

- [ ] **Step 8: Commit**

```bash
git add apps/admin-web/src/pages apps/admin-web/src/components
git commit -m "feat: add dispatch workbench and operations dashboard"
```

---

### Task 13: End-To-End Demo Flow

**Files:**
- Create: `apps/admin-web/e2e/dispatch-flow.spec.ts`
- Create: `apps/api/src/test/java/com/idavy/drtops/e2e/DispatchFlowIntegrationTest.java`
- Create: `README.md`

**Interfaces:**
- Verifies full flow from demand entry to dispatch decision to vehicle task execution

- [ ] **Step 1: Write backend integration flow test**

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

- [ ] **Step 2: Write Playwright flow**

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

- [ ] **Step 3: Run tests to verify they fail**

Run:

```powershell
mvn -q -pl apps/api -Dtest=DispatchFlowIntegrationTest test
cd apps/admin-web
npm run e2e -- dispatch-flow.spec.ts
```

Expected:

```text
FAIL until all previous tasks are complete and local services are running.
```

- [ ] **Step 4: Implement missing flow wiring**

Connect UI actions to backend endpoints and ensure demo seed data supports at least one auto-dispatchable order.

- [ ] **Step 5: Update README runbook**

Document:

```powershell
docker compose -f infra/docker-compose.yml up -d
mvn -pl apps/api spring-boot:run
cd apps/algorithm
uvicorn drt_algorithm.main:app --port 8090 --reload
cd ../admin-web
npm run dev
```

- [ ] **Step 6: Run full verification**

Run:

```powershell
mvn -q -pl apps/api test
cd apps/algorithm
python -m pytest -v
cd ../admin-web
npm run typecheck
npm run test
npm run e2e -- dispatch-flow.spec.ts
```

Expected:

```text
backend tests pass
algorithm tests pass
frontend typecheck passes
frontend unit tests pass
Playwright dispatch flow passes
```

- [ ] **Step 7: Commit**

```bash
git add apps README.md
git commit -m "test: add end-to-end dispatch demo flow"
```

---

### Task 14: Final MVP Readiness Review

**Files:**
- Create: `docs/release/mvp-readiness-checklist.md`
- Modify: `README.md`

**Interfaces:**
- Produces release checklist mapped to the approved design document

- [ ] **Step 1: Create readiness checklist**

Include these checked sections:

- Resource setup: service area, virtual stops, vehicles, drivers, rule set.
- Demand entry: immediate and short booking orders.
- Dispatch: no feasible plan, auto-dispatch, manual review.
- Vehicle tasks: create, insert, execute, complete.
- Exceptions: cancellation, no-show, vehicle failure, severe delay.
- Audit: decision, manual action, rule change, task status change.
- Metrics: confirmation rate, auto-dispatch rate, manual review rate, waiting time, detour time, completion rate, exception rate, utilization.
- UX: workbench, map, decision explanation, resource pages, task simulation.

- [ ] **Step 2: Run final verification**

Run:

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

Expected:

```text
git status shows only intended readiness documentation before commit
all backend tests pass
all algorithm tests pass
all frontend checks pass
e2e dispatch flow passes
```

- [ ] **Step 3: Commit**

```bash
git add README.md docs/release/mvp-readiness-checklist.md
git commit -m "docs: add MVP readiness checklist"
```

---

## Self-Review

**Spec coverage:** This plan covers product boundary, order and task state flow, algorithm service, module boundaries, core data objects, architecture/data flow, exceptions, audit, metrics, admin pages, and MVP acceptance scenarios from the approved design document.

**Known scope exclusions:** Complete passenger app, complete driver app, regulator portal, payment/settlement, machine-learning prediction, and Kafka production event streaming are excluded from this MVP plan and require separate plans.

**Unresolved-token scan:** The plan avoids unresolved planning tokens and gives concrete file paths, interfaces, commands, and expected outcomes for every task.

**Type consistency:** Core terms are consistent across tasks: `RideOrder`, `VehicleTask`, `TaskStop`, `DispatchDecision`, `DispatchRuleSet`, `OrderStatus`, `TaskStatus`, `DispatchEvaluateRequest`, and `DispatchEvaluateResponse`.
