# P6-2 最终审阅整改实施计划

> **供智能体执行者使用：** 必须使用子技能 superpowers:subagent-driven-development 按任务逐项执行本计划。步骤使用复选框（`- [ ]`）进行跟踪。

**目标：** 以严格 RED→GREEN 修复最终 whole-branch review 的 I-1～I-7，使协议档案、报警授权、当前会话、位置主备、配置运行态、审计脱敏和管理端可达性重新形成一条可独立复审的权威合同。

**架构：** 保留 V19/V20 和既有历史，只用 V21 增加 current session lease、位置时钟游标和 alarm onboard provenance。API 在统一锁序下重查 aggregate 权威事实，gateway 只消费版本化 session contract；readiness、自动/人工调度和 UI 共用当前 lease，位置仲裁严格分离 terminal time 与 gateway time。

**技术栈：** Java 21、Spring Boot 3.5、Spring Data JPA、Flyway、PostgreSQL/PostGIS 16、Netty、JUnit 5、AssertJ、MockMvc、Vue 3、TypeScript、Vitest、PowerShell 5.1、Docker。

**规格：** `docs/superpowers/specs/2026-08-29-p6-2-composite-onboard-system-design.md`

**补充审阅依据：** `D:\codex-projects\.worktrees\jt-gateway-deployment\.superpowers\sdd\2026-08-29-p6-2-composite-onboard-system\final-whole-branch-review.md`

**执行模式：** Subagent-Driven 连续执行。每个任务使用新的实现代理和独立复审代理；当前任务的 RED、GREEN、聚焦回归、复审和提交全部完成后，直接继续下一任务，不再请求执行方式选择。只有新的权限门禁、规格冲突或连续三轮无法消除的同一阻塞才暂停。

## 全局约束

- 在当前 `codex/p6-2-composite-onboard-system` 分支就地修复；不新建 worktree，不切换分支，不覆盖他人改动。
- 当前计划基线为 `39494f3e3e459a7c5ac842f73ad99967baf3119b`；开始 R1 前重新记录 HEAD 和 status。
- 本计划不授权 cloud、真实设备、真实流量、push、merge、PR 或外部账号操作。
- 附件、媒体、RTP、录像、转码和完整 GB/T 28787 `0x0Bxx/0x8Bxx` 业务消息保持 out of scope。
- 严格 TDD：每个行为先写最小失败测试，单独运行并保存真实失败原因，再写最小生产实现。
- Java/Maven 必须串行；任何时刻只允许一个 Maven 进程。
- Windows 上每次 Java 运行前必须把 `TEMP`、`TMP` 和 `-Djava.io.tmpdir` 指向当前 worktree 的任务专用目录。
- 每个任务独立聚焦回归、独立代码复审、独立提交；Critical/Important 未清零不得进入下一任务。
- `V19__add_composite_onboard_system_model.sql` 和 `V20__replace_single_terminal_vehicle_binding_constraint.sql` 只读，不得修改。
- V21 的全部 schema 在 R1 一次性冻结；R1 提交后任何任务不得编辑 V21 或改变 checksum。
- V21 只能 additive/forward-safe：不 DROP/RENAME 旧表列，不猜测历史 alarm provenance，不清洗历史 `audit_logs`。
- legacy `jt_terminal_vehicle_bindings` 只能用于明确标注的历史展示，不能参与 session、alarm、readiness 或 dispatch 授权。
- 终端手机号、terminal code、真实车牌、鉴权材料、摘要、原始帧、gateway 私密地址和 private 文件内容不得进入日志、审计 metadata、错误、测试报告或公开计划。
- 测试仅使用合成 UUID、合成终端和合成车辆标识。
- 所有内部 lease/profile DTO 只暴露运行所需字段；`gatewayInstance`、`connectionId` 和 lease generation 不进入管理 UI。
- 配置、鉴权、报警和位置共享固定锁序：`onboard_systems → jt_terminals(UUID 排序) → memberships(id) → roles(role,id) → profiles(terminal,id) → capabilities(terminal,capability,id) → session_leases(terminal) → runtime_state → vehicles → ingress-domain rows`。路径可跳过不需要的表，但不得反向取锁。
- 时间域固定：terminal time 只和同一物理 terminal 的 cursor 比；gateway time 只和 gateway time 比；alarm 授权边界使用关联位置事件的 API `recordedAt`；lease 生死只使用 API Clock。
- 完整回归必须是修复后的新鲜结果：Java 执行数不低于修复后实际发现的测试总数，failure/error 均为 0；frontend、external PostgreSQL、private scripts 分开计数。
- 不复用审阅前的 `963/304/57/41/43/6` 作为修复后通过证据。
- 本计划只修改下面列出的生产和测试文件；不顺手重构、不更新收口文档，文档状态留给最终独立复审后的单独 closeout。

## 执行环境

每个 Java RED/GREEN 前先在 worktree 根目录运行：

```powershell
$Repo = (Get-Location).Path
if ((git branch --show-current) -ne 'codex/p6-2-composite-onboard-system') {
    throw 'P6_2_REMEDIATION_BRANCH_INVALID'
}
$Maven = (Resolve-Path '..\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd').Path
$MavenBase = @('-q', '-Dsurefire.failIfNoSpecifiedTests=false')
$MavenTemp = Join-Path $Repo '.tmp\p6-2-final-review-remediation\maven'
New-Item -ItemType Directory -Force -Path $MavenTemp | Out-Null
$env:TEMP = $MavenTemp
$env:TMP = $MavenTemp
```

每次执行前确认没有其他 Maven：

```powershell
$mavenJava = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'maven|surefire' }
if ($mavenJava) {
    throw 'ANOTHER_MAVEN_PROCESS_IS_RUNNING'
}
```

命令中的 synthetic PostgreSQL 凭据只用于一次性 loopback 容器；不得替换成任何真实连接。

## 文件结构与归属

R1 创建并永久冻结：

- `apps/api/src/main/resources/db/migration/V21__repair_composite_onboard_runtime_authority.sql`：一次性增加 lease、runtime clock cursor 和 alarm provenance。

R3 创建：

- `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLease.java`：一台物理 terminal 的当前持久 lease。
- `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLeaseRepository.java`：terminal-keyed current lease 查询和锁。
- `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLeaseService.java`：acquire/renew/release/fencing/TTL。
- `apps/api/src/test/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLeaseServiceTest.java`：fake-clock 生命周期和并发测试。
- `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/SessionLeaseReporter.java`：在有界非 Netty executor 上合并 renew/release。

共享文件按任务顺序串行修改：

- R2→R3：`OnboardRegistrationResolver.java`、`GatewayRegistryController.java`、gateway session DTO/client/handler。
- R2→R3→R4：`OnboardReadinessService.java`、`OnboardSystemConfigurationService.java`。
- R2→R3→R5：`TerminalManagementService.java`、`TerminalController.java`。
- R2→R4：`CompositeOnboardEndToEndTest.java`。
- R3→R6：`OnboardSystemManagementPage.vue` 和其测试。

## 执行前计划工件门禁

本次“写计划”任务不 stage/commit。开始 R1 前，root/orchestrator 必须确认本计划已获采用，并用一个仅包含本计划的
docs-only commit 使 worktree 回到 clean；该 planning commit 不计入 6 个实施任务，也不包含任何实现文件。
如果计划尚未被提交，R1 实现代理必须停止，不能把本计划顺带混入 R1 schema commit。

---

### 任务 R1：冻结增量式 V21 数据库结构

**文件：**

- 新建： `apps/api/src/main/resources/db/migration/V21__repair_composite_onboard_runtime_authority.sql`
- 修改： `apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java`
- 修改： `apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java`

**接口：**

- 依赖： V20 schema、`vehicles.current_location_onboard_system_id`、`vehicle_location_events.onboard_system_id` 和冻结的 legacy/audit history。
- 产出： `jt_terminal_session_leases`；runtime columns `last_primary_valid_gateway_received_at`、`primary_terminal_cursor_at`、`backup_terminal_cursor_at`；nullable `vehicle_alarms.onboard_system_id`。
- 供 R2 使用的产出： nullable alarm aggregate provenance column。
- 供 R3 使用的产出： current lease table。
- 供 R4 使用的产出： three independent location runtime clocks。

- [ ] **步骤 1：编写 V21 迁移合同失败测试**

在 `P6CompositeOnboardSystemMigrationTest` 新增以下测试和 helper：

```java
@Test
void v21AddsLeaseRuntimeClocksAndAlarmProvenanceWithoutRewritingHistory()
        throws Exception {
    ExternalPostgres postgres = externalPostgres();
    String schema = schema("v21_additive_authority");
    flyway(postgres, schema, "20").migrate();

    UUID auditId = UUID.randomUUID();
    UUID auditEntityId = UUID.randomUUID();
    try (Connection connection = connection(postgres, schema)) {
        execute(connection, """
                insert into audit_logs(
                    id, entity_type, entity_id, action, actor_type, actor_id,
                    reason, metadata_json, created_at
                ) values (?, 'JT_TERMINAL', ?, 'JT_TERMINAL_REPLACED',
                    'SYSTEM', 'migration-test', 'sentinel',
                    '{"sentinel":"unchanged"}'::jsonb, now())
                """, auditId, auditEntityId);
    }

    flyway(postgres, schema, "21").migrate();

    try (Connection connection = connection(postgres, schema)) {
        assertThat(tableExists(connection, "jt_terminal_session_leases")).isTrue();
        assertThat(columnExists(connection, "onboard_system_runtime_state",
                "last_primary_valid_gateway_received_at")).isTrue();
        assertThat(columnExists(connection, "onboard_system_runtime_state",
                "primary_terminal_cursor_at")).isTrue();
        assertThat(columnExists(connection, "onboard_system_runtime_state",
                "backup_terminal_cursor_at")).isTrue();
        assertThat(columnExists(connection, "vehicle_alarms", "onboard_system_id")).isTrue();
        assertThat(queryText(connection,
                "select metadata_json::text from audit_logs where id = ?", auditId))
                .contains("\"sentinel\": \"unchanged\"");
    }
}

@Test
void v21BackfillsSnapshotProvenanceOnlyFromTheExactCurrentEvent()
        throws Exception {
    ExternalPostgres postgres = externalPostgres();
    String schema = schema("v21_exact_snapshot_provenance");
    flyway(postgres, schema, "20").migrate();
    ExactSnapshotFixture exact = insertExactCurrentSnapshotFixture(postgres, schema);
    MismatchedSnapshotFixture mismatch =
            insertMismatchedCurrentSnapshotFixture(postgres, schema);

    flyway(postgres, schema, "21").migrate();

    try (Connection connection = connection(postgres, schema)) {
        assertThat(queryUuid(connection, """
                select current_location_onboard_system_id
                from vehicles where id = ?
                """, exact.vehicleId())).isEqualTo(exact.onboardSystemId());
        assertThat(queryNullableUuid(connection, """
                select current_location_onboard_system_id
                from vehicles where id = ?
                """, mismatch.vehicleId())).isNull();
    }
}

private static boolean columnExists(
        Connection connection, String tableName, String columnName) throws Exception {
    return queryCount(connection, """
            select count(*)
            from information_schema.columns
            where table_schema = current_schema()
              and table_name = ?
              and column_name = ?
            """, tableName, columnName) == 1;
}

private static ExactSnapshotFixture insertExactCurrentSnapshotFixture(
        ExternalPostgres postgres, String schema) throws Exception {
    try (Connection connection = connection(postgres, schema)) {
        UUID vehicleId = insertVehicle(
                connection, "V21-EXACT-" + UUID.randomUUID());
        UUID terminalId = insertTerminal(
                connection, "013800000091", "T-V21-EXACT");
        UUID systemId = insertSystem(connection, vehicleId);
        insertMembership(connection, systemId, terminalId);
        UUID eventId = insertGpsSnapshotEvent(
                connection, vehicleId, terminalId, systemId);
        execute(connection, """
                update vehicles
                set current_location_event_id = ?,
                    current_location_terminal_id = ?,
                    current_location_source = 'GPS_DEVICE'
                where id = ?
                """, eventId, terminalId, vehicleId);
        return new ExactSnapshotFixture(vehicleId, systemId);
    }
}

private static MismatchedSnapshotFixture
        insertMismatchedCurrentSnapshotFixture(
                ExternalPostgres postgres, String schema) throws Exception {
    try (Connection connection = connection(postgres, schema)) {
        UUID vehicleId = insertVehicle(
                connection, "V21-MISMATCH-" + UUID.randomUUID());
        UUID eventTerminalId = insertTerminal(
                connection, "013800000092", "T-V21-EVENT");
        UUID currentTerminalId = insertTerminal(
                connection, "013800000093", "T-V21-CURRENT");
        UUID systemId = insertSystem(connection, vehicleId);
        insertMembership(connection, systemId, eventTerminalId);
        UUID eventId = insertGpsSnapshotEvent(
                connection, vehicleId, eventTerminalId, systemId);
        execute(connection, """
                update vehicles
                set current_location_event_id = ?,
                    current_location_terminal_id = ?,
                    current_location_source = 'GPS_DEVICE'
                where id = ?
                """, eventId, currentTerminalId, vehicleId);
        return new MismatchedSnapshotFixture(vehicleId);
    }
}

private static UUID insertGpsSnapshotEvent(
        Connection connection,
        UUID vehicleId,
        UUID terminalId,
        UUID onboardSystemId) throws Exception {
    UUID eventId = UUID.randomUUID();
    execute(connection, """
            insert into vehicle_location_events (
              id, vehicle_id, event_type, source, location,
              longitude, latitude, coordinate_system,
              driver_reported_at, idempotency_key,
              request_fingerprint, snapshot_applied,
              outside_service_area, terminal_id,
              protocol_version, message_serial_no,
              raw_longitude, raw_latitude,
              raw_coordinate_system, gateway_received_at,
              payload_digest, coordinate_transform_version,
              quality_status, quality_reasons,
              onboard_system_id, source_role
            ) values (
              ?, ?, 'GPS_REPORTED', 'GPS_DEVICE',
              ST_SetSRID(
                ST_MakePoint(121.4737, 31.2304), 4326
              )::geography,
              121.4737000, 31.2304000, 'GCJ02',
              now(), ?, repeat('f', 64), true, false, ?,
              'JT808_2019', 1,
              121.4737000, 31.2304000, 'GCJ02', now(),
              repeat('e', 64), 'V21_TEST',
              'GOOD', '[]'::jsonb, ?, 'LOCATION_PRIMARY'
            )
            """,
            eventId,
            vehicleId,
            UUID.randomUUID(),
            terminalId,
            onboardSystemId);
    return eventId;
}

private static String queryText(
        Connection connection, String sql, Object argument) throws Exception {
    try (PreparedStatement query = connection.prepareStatement(sql)) {
        query.setObject(1, argument);
        try (ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}

private static UUID queryUuid(
        Connection connection, String sql, UUID argument) throws Exception {
    return Objects.requireNonNull(
            queryNullableUuid(connection, sql, argument));
}

private static UUID queryNullableUuid(
        Connection connection, String sql, UUID argument) throws Exception {
    try (PreparedStatement query = connection.prepareStatement(sql)) {
        query.setObject(1, argument);
        try (ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getObject(1, UUID.class);
        }
    }
}

private record ExactSnapshotFixture(
        UUID vehicleId, UUID onboardSystemId) { }

private record MismatchedSnapshotFixture(UUID vehicleId) { }
```

两个 fixture 只使用 synthetic UUID/车牌；helper 内用 PreparedStatement，不拼接业务值。

同时在 `DatabaseMigrationTest.migrationScriptsDeclareCoreTablesAndSeedData` 读取 V21，并断言只出现允许的
`CREATE TABLE/ALTER TABLE/UPDATE vehicles`；把 external migration 的最终 Flyway version 从 `20` 改为 `21`。

- [ ] **步骤 2：运行 V21 测试并保存 RED**

目的：证明现有 HEAD 没有 V21，而不是让测试因外部数据库不可用失败。

```powershell
docker run --rm --name drt-p6-2-remediation-pg `
  -e POSTGRES_USER=remediation `
  -e POSTGRES_PASSWORD=remediation-test-only `
  -e POSTGRES_DB=p6_2_remediation `
  -p 55441:5432 `
  -d postgis/postgis:16-3.5

& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=P6CompositeOnboardSystemMigrationTest,DatabaseMigrationTest' `
  '-Ddrt.integration.composite-onboard=true' `
  '-Ddrt.integration.composite-onboard.external-ephemeral=true' `
  '-Ddrt.integration.composite-onboard.jdbc-url=jdbc:postgresql://127.0.0.1:55441/p6_2_remediation' `
  '-Ddrt.integration.composite-onboard.username=remediation' `
  '-Ddrt.integration.composite-onboard.password=remediation-test-only' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：至少一个新断言失败，明确指出 `jt_terminal_session_leases` 或 V21 column 不存在，或最终 Flyway version
仍为 20。数据库连接、PostGIS extension 和 V20 必须先成功；基础设施失败不能算 RED。

- [ ] **步骤 3：按精确增量结构创建 V21**

写入：

```sql
CREATE TABLE jt_terminal_session_leases (
  terminal_id UUID PRIMARY KEY REFERENCES jt_terminals(id),
  gateway_instance VARCHAR(120) NOT NULL,
  connection_id UUID NOT NULL,
  token_version INTEGER NOT NULL,
  lease_generation BIGINT NOT NULL,
  authenticated_at TIMESTAMPTZ NOT NULL,
  last_valid_message_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  released_at TIMESTAMPTZ,
  release_reason VARCHAR(80),
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_jt_terminal_session_leases_token_version
    CHECK (token_version > 0),
  CONSTRAINT ck_jt_terminal_session_leases_generation
    CHECK (lease_generation > 0),
  CONSTRAINT ck_jt_terminal_session_leases_expiry
    CHECK (expires_at > last_valid_message_at
      AND last_valid_message_at >= authenticated_at),
  CONSTRAINT ck_jt_terminal_session_leases_release
    CHECK ((released_at IS NULL AND release_reason IS NULL)
      OR (released_at IS NOT NULL AND release_reason IS NOT NULL)),
  CONSTRAINT ck_jt_terminal_session_leases_release_reason
    CHECK (release_reason IS NULL
      OR release_reason ~ '^[A-Z][A-Z0-9_]{2,79}$')
);

CREATE INDEX idx_jt_terminal_session_leases_live_expiry
  ON jt_terminal_session_leases(expires_at)
  WHERE released_at IS NULL;

ALTER TABLE onboard_system_runtime_state
  ADD COLUMN last_primary_valid_gateway_received_at TIMESTAMPTZ,
  ADD COLUMN primary_terminal_cursor_at TIMESTAMPTZ,
  ADD COLUMN backup_terminal_cursor_at TIMESTAMPTZ;

ALTER TABLE vehicle_alarms
  ADD COLUMN onboard_system_id UUID REFERENCES onboard_systems(id);

CREATE INDEX idx_vehicle_alarms_onboard_system_received
  ON vehicle_alarms(onboard_system_id, gateway_received_at DESC)
  WHERE onboard_system_id IS NOT NULL;

UPDATE vehicles vehicle
SET current_location_onboard_system_id = event.onboard_system_id
FROM vehicle_location_events event
WHERE vehicle.current_location_onboard_system_id IS NULL
  AND vehicle.current_location_event_id = event.id
  AND event.vehicle_id = vehicle.id
  AND event.terminal_id = vehicle.current_location_terminal_id
  AND event.onboard_system_id IS NOT NULL;
```

禁止在此 migration 中修改 `audit_logs`、`jt_terminal_vehicle_bindings`、V19/V20、历史 alarm 行或 runtime cursor。

- [ ] **步骤 4：添加静态迁移安全断言**

`DatabaseMigrationTest` 必须断言 V21：

```java
assertThat(v21).contains(
        "CREATE TABLE jt_terminal_session_leases",
        "last_primary_valid_gateway_received_at TIMESTAMPTZ",
        "primary_terminal_cursor_at TIMESTAMPTZ",
        "backup_terminal_cursor_at TIMESTAMPTZ",
        "ADD COLUMN onboard_system_id UUID REFERENCES onboard_systems(id)",
        "vehicle.current_location_event_id = event.id",
        "event.terminal_id = vehicle.current_location_terminal_id");
assertThat(v21.toLowerCase()).doesNotContain(
        "drop table", "drop column", "truncate", "delete from audit_logs",
        "update audit_logs", "update vehicle_alarms");
```

- [ ] **步骤 5：运行聚焦 GREEN 与迁移回归**

重跑 步骤 2 的 Maven 命令。

预期 GREEN：两个 suite 全部通过，failure/error=0；Flyway 21；sentinel audit JSON 和 legacy row count 未变；
只有 exact current event 获得 snapshot provenance。

- [ ] **步骤 6：停止一次性数据库**

```powershell
docker stop drt-p6-2-remediation-pg
```

预期：容器因 `--rm` 被删除。只处理这个精确容器名。

- [ ] **步骤 7：执行独立迁移复审**

复审输入只包含本任务三个文件。复审必须确认：

- V19/V20 diff 为零；
- V21 没有 destructive SQL；
- constraint 支持 stale-owner fencing；
- deterministic UPDATE 不跨 vehicle/terminal；
- audit/legacy/alarm history 未被清洗。

预期：Critical=0、Important=0。任何 migration 改动在提交前重新运行 步骤 5。

- [ ] **步骤 8：提交 R1 并冻结 V21**

```powershell
git add apps/api/src/main/resources/db/migration/V21__repair_composite_onboard_runtime_authority.sql `
  apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java `
  apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java
git commit --only -m 'feat: add final review remediation schema' -- `
  apps/api/src/main/resources/db/migration/V21__repair_composite_onboard_runtime_authority.sql `
  apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java `
  apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java
```

提交后记录 V21 SHA-256。R2～R6 只能读取该文件；发现 schema 不足必须停止并升级设计，不能改写已提交 V21。

**V21 升级/回滚合同：**

- 本计划不执行部署。未来获批窗口必须先备份并停止 gateway，确认 7611 无监听，再由新版 API 启动应用 V21。
- API 验证 Flyway 21 和 additive invariants 后，才部署支持 contract v2/lease 的 gateway；设备重新鉴权前 readiness
  必须 fail closed。
- 数据库不做 down migration；代码回滚保留 V21 新表/列，旧代码可忽略 nullable additions。
- 新 gateway 已接入流量后不得直接回到以历史 lastAuthenticatedAt 判定在线的旧 API；必须先停止 gateway 和调度入口，
  再执行受控代码回滚或 forward fix。
- V21 不改变历史 audit/alarm/legacy rows，因此回滚不需要也不允许“恢复”这些行。

---

### 任务 R2：以单一“协议档案到报警授权”纵向合同闭环 I-1 与 I-2

**文件：**

- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolver.java:37-68,289-402,467-531`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationService.java:529-678,965-1131`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardReadinessService.java:225-250,344-380`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/GatewayRegistryController.java:31-126`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/location/GatewayIngressRouter.java:85-171,189-195`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/alarm/AlarmStore.java`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/alarm/JpaAlarmStore.java`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmIngressService.java:52-153,204-217`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarm.java:20-63,81-104`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java:86-108`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalController.java:254-296`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSessionContext.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSession.java:14-215`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/RegistrationDecision.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClient.java:79-220,253-334`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandler.java:109-178,345-415`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/CanonicalVehicleAlarm.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/ActiveSafetyAlarmRouter.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistry.java:75-140,250-328`
- 修改： `apps/admin-web/src/api/types.ts:367-410`
- 修改： `apps/admin-web/src/pages/TerminalManagementPage.vue:117-141`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardTestFixtures.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolverTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationServiceTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardReadinessServiceTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/CompositeOnboardEndToEndTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/alarm/VehicleAlarmIngressServiceTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/alarm/InMemoryAlarmStore.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/alarm/PostgisVehicleAlarmIngressIntegrationTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalApiTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalManagementServiceTest.java`
- 测试： `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClientTest.java`
- 测试： `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandlerTest.java`
- 测试： `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/ingress/ActiveSafetyAlarmRouterTest.java`
- 测试： `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistryActiveSafetyDispatchTest.java`
- 测试： `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/JtGatewayRuntimeIntegrationTest.java`
- 测试： `apps/admin-web/src/pages/terminal-management-page.test.ts`

**接口：**

- 依赖： R1 `vehicle_alarms.onboard_system_id`；V19 profile/capability/role history；V20 frozen legacy binding。
- 供 R3 使用的产出： `TerminalSessionContext(contractVersion=2, ..., protocolProfile, compatibility fields)` and stable authentication JSON。
- 供 R4 使用的产出： canonical lock-order helper conventions；no location runtime behavior yet。
- 产出： `CanonicalVehicleAlarm.onboardSystemId`、`AlarmFact.onboardSystemId`、
  `ActiveSafetyAuthorization lockAndAuthorizeActiveSafety(...)`。
- 不变量： I-1/I-2 只有在真实 aggregate → session → decoder → API alarm E2E 通过后一起关闭。

- [ ] **步骤 1：编写 API 会话权威 RED 测试**

在 `OnboardTestFixtures` 增加明确的 roleless fixture：

```java
public RolelessMemberFixture configureRolelessMember(
        String terminalCode, String vehicleIdentifier) {
    Vehicle vehicle = vehicleRepository.findByPlateNumber(vehicleIdentifier)
            .orElseGet(() -> createVehicle(
                    UUID.randomUUID(), vehicleIdentifier, false));
    OnboardSystem system = activeSystem(
            vehicle, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY);
    JtTerminal terminal = terminal(terminalCode);
    membershipRepository.saveAndFlush(OnboardDeviceMembership.join(
            system.getId(), terminal.getId(), NetworkMode.DIRECT_CELLULAR,
            "synthetic roleless member", ACTOR_ID, OffsetDateTime.now()));
    profileRepository.saveAndFlush(OnboardDeviceProtocolProfile.activate(
            terminal.getId(),
            TransportProfile.JT808_2019,
            BusinessProfile.NONE,
            SafetyProfile.NONE,
            MediaProfile.NONE,
            30,
            60,
            "synthetic roleless profile",
            ACTOR_ID,
            OffsetDateTime.now()));
    return new RolelessMemberFixture(
            terminal.getTerminalPhone(), terminal.getId(), vehicle.getId());
}

public record RolelessMemberFixture(
        String semanticPhone, UUID terminalId, UUID vehicleId) { }
```

在 `OnboardRegistrationResolverTest` 新增：

```java
@Test
void sessionContextComesFromActiveProfileAndVerifiedCapabilities() {
    fixtures.configureDualDeviceSystem(
            "dispatch-01", "recorder-01", "VEHICLE-A");

    RegistrationDecision recorder = resolver.verify(registration(
            "recorder-01", "PHONE-RECORDER", "VEHICLE-A"));

    assertThat(recorder.approved()).isTrue();
    assertThat(recorder.context().contractVersion()).isEqualTo(2);
    assertThat(recorder.context().onboardConfigurationVersion()).isPositive();
    assertThat(recorder.context().protocolProfile().transportProfile())
            .isEqualTo(TransportProfile.JT808_2019);
    assertThat(recorder.context().protocolProfile().safetyProfile())
            .isEqualTo(SafetyProfile.JSATL12_2017);
    assertThat(recorder.context().protocolProfile()
            .enabledActiveSafetyModules()).containsExactly("ADAS");
    assertThat(recorder.context().activeSafetyStandard())
            .isEqualTo("T/JSATL12-2017");
    assertThat(recorder.context().activeSafetyModules())
            .containsExactly("ADAS");
}

@Test
void memberWithoutBusinessRolesCanAuthenticateWithAnEmptyRoleSet() {
    RolelessMemberFixture member =
            fixtures.configureRolelessMember("roleless-01", "ROLELESS-A");

    RegistrationDecision decision = resolver.verify(registration(
            "roleless-01", member.semanticPhone(), "ROLELESS-A"));

    assertThat(decision.approved()).isTrue();
    assertThat(decision.context().roles()).isEmpty();
    assertThat(decision.context().protocolProfile()
            .enabledActiveSafetyModules()).isEmpty();
}
```

在 `OnboardSystemConfigurationServiceTest` 新增
`rejectsRoleProfileMismatchAndTransportIdentityMismatch`，精确覆盖：

- DISPATCH + businessProfile=NONE → `ROLE_PROTOCOL_PROFILE_MISMATCH:DISPATCH`；
- ACTIVE_SAFETY + safetyProfile=NONE → `ROLE_PROTOCOL_PROFILE_MISMATCH:ACTIVE_SAFETY`；
- VIDEO + mediaProfile=NONE → `ROLE_PROTOCOL_PROFILE_MISMATCH:VIDEO`；
- terminal protocol JT808_2019 + transportProfile JT808_2013 →
  `TRANSPORT_PROFILE_IDENTITY_MISMATCH`。

- [ ] **步骤 2：运行 API 合同测试并保存 RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardRegistrationResolverTest,OnboardSystemConfigurationServiceTest,OnboardReadinessServiceTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：测试编译失败，明确缺少 `contractVersion`、`protocolProfile` 或新 validation；现有 context 仍从
`JtTerminal.activeSafetyStandard/activeSafetyModules` 返回 null/legacy 值。既有测试必须先编译运行，不能用无关错误代替。

- [ ] **步骤 3：实现 API 会话合同 v2**

在 `OnboardRegistrationResolver` 定义：

```java
public static final int SESSION_CONTRACT_VERSION = 2;

public record SessionProtocolProfile(
        TransportProfile transportProfile,
        BusinessProfile businessProfile,
        SafetyProfile safetyProfile,
        MediaProfile mediaProfile,
        List<String> enabledActiveSafetyModules,
        int activePositionIntervalSeconds,
        int idlePositionIntervalSeconds) {
    public SessionProtocolProfile {
        Objects.requireNonNull(transportProfile, "transportProfile");
        Objects.requireNonNull(businessProfile, "businessProfile");
        Objects.requireNonNull(safetyProfile, "safetyProfile");
        Objects.requireNonNull(mediaProfile, "mediaProfile");
        enabledActiveSafetyModules =
                List.copyOf(enabledActiveSafetyModules);
        if (activePositionIntervalSeconds <= 0
                || idlePositionIntervalSeconds <= 0) {
            throw new IllegalArgumentException(
                    "position intervals must be positive");
        }
    }
}

public record TerminalSessionContext(
        int contractVersion,
        UUID terminalId,
        UUID onboardSystemId,
        UUID vehicleId,
        long onboardConfigurationVersion,
        Set<Role> roles,
        String sourceCoordinateSystem,
        SessionProtocolProfile protocolProfile,
        String activeSafetyStandard,
        List<String> activeSafetyModules,
        int tokenVersion) {
    public TerminalSessionContext {
        if (contractVersion != SESSION_CONTRACT_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported session contract version");
        }
        roles = roles == null || roles.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(roles));
        activeSafetyModules = List.copyOf(activeSafetyModules);
    }
}
```

注入 `OnboardDeviceProtocolProfileRepository` 和 `OnboardDeviceCapabilityRepository`。把
`context(terminal,membership,system)` 替换为
`loadLockedSessionAuthority(terminalId,onboardSystemId)`，在全局锁序下锁并重新读取：

- exactly one active membership；
- active role rows；
- exactly one active protocol profile；
- current VERIFIED capability rows；
- runtime（仅 registration warning 需要）。

`enabledActiveSafetyModules` 的唯一映射：

```java
private static List<String> enabledActiveSafetyModules(
        Set<Role> roles, Set<Capability> verifiedCapabilities) {
    if (!roles.contains(Role.ACTIVE_SAFETY)) {
        return List.of();
    }
    List<String> modules = new ArrayList<>(2);
    if (verifiedCapabilities.contains(Capability.ADAS)) {
        modules.add("ADAS");
    }
    if (verifiedCapabilities.contains(Capability.DMS)) {
        modules.add("DMS");
    }
    return List.copyOf(modules);
}

private static String compatibilitySafetyStandard(SafetyProfile profile) {
    return switch (profile) {
        case JSATL12_2017 -> "T/JSATL12-2017";
        case GBT28787_2023 -> "GB/T 28787-2023";
        case NONE -> null;
    };
}
```

compatibility 字段也必须来自这个 profile/capability 交集；删除 resolver 对
`terminal.getActiveSafetyStandard()`、`parseModules(terminal.getActiveSafetyModules())` 的读取。

`OnboardSystemConfigurationService.validateRoleProtocolCompatibility`：

```java
private static void validateRoleProtocolCompatibility(
        DesiredDevice device) {
    String terminalTransport =
            JtTerminalRepository.canonicalProtocolVersion(
                    device.terminal().getProtocolVersion());
    if (!device.profiles().transportProfile().name()
            .equals(terminalTransport)) {
        throw conflict("TRANSPORT_PROFILE_IDENTITY_MISMATCH");
    }
    if (device.roles().contains(Role.DISPATCH)
            && device.profiles().businessProfile()
                    == BusinessProfile.NONE) {
        throw conflict(
                "ROLE_PROTOCOL_PROFILE_MISMATCH:DISPATCH");
    }
    if (device.roles().contains(Role.ACTIVE_SAFETY)
            && device.profiles().safetyProfile()
                    == SafetyProfile.NONE) {
        throw conflict(
                "ROLE_PROTOCOL_PROFILE_MISMATCH:ACTIVE_SAFETY");
    }
    if (device.roles().contains(Role.VIDEO)
            && device.profiles().mediaProfile()
                    == MediaProfile.NONE) {
        throw conflict(
                "ROLE_PROTOCOL_PROFILE_MISMATCH:VIDEO");
    }
}
```

在 `evaluate` 中对所有 desired device 调用该方法，再计算 diff。

`OnboardReadinessService.activeSafety` 当前只把 `JSATL12_2017` 视为 decoder-ready；`GBT28787_2023` 保留配置能力但
返回 UNAVAILABLE，直到另一个明确阶段交付 decoder。

- [ ] **步骤 4：以增量兼容窗口扩展 API 响应**

`GatewayRegistryController.RegistrationVerificationResponse` 增加
`contractVersion`、`onboardConfigurationVersion`、`protocolProfile`，同时保留并返回由新事实派生的
`activeSafetyStandard/activeSafetyModules`。authentication response 继续直接包 v2 context。

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardRegistrationResolverTest,OnboardSystemConfigurationServiceTest,TerminalApiTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期：API 侧 GREEN；旧字段与 v2 nested fields 同源，无真实 identity 泄漏。

- [ ] **步骤 5：编写 gateway 合同与传输协议 RED 测试**

在 `OperationsTerminalRegistryClientTest` 把 synthetic response 改成 contract v2，并新增：

```java
@Test
void rejectsV2ProfileThatDiffersFromFlatCompatibilityFields() {
    stubRegistrationResponse(v2Response(
            "JSATL12_2017",
            List.of("ADAS"),
            "T/JSATL12-2017",
            List.of("DMS")));

    RegistrationDecision decision =
            registry.verifyRegistration(identity());

    assertThat(decision.approved()).isFalse();
}

@Test
void acceptsAuthenticatedMemberWithNoBusinessRoles() {
    stubAuthenticationResponse(v2AuthenticationResponse(Set.of()));

    AuthenticationDecision decision =
            registry.verifyAuthenticationByIdentity(
                    ProtocolVersion.JT808_2019,
                    "000000000001",
                    "a".repeat(64));

    assertThat(decision.approved()).isTrue();
    assertThat(decision.context().roles()).isEmpty();
}
```

在 `RegistrationAuthenticationHandlerTest` 新增
`authenticatedHeartbeatWithDifferentTransportProfileClosesBeforeAcknowledgement`：

```java
channel.writeInbound(authenticationFrameFor(ProtocolVersion.JT808_2019));
assertEquals(
        TerminalSessionState.AUTHENTICATED, handler.session().state());

channel.writeInbound(heartbeatFrameFor(ProtocolVersion.JT808_2013));

assertFalse(channel.isOpen());
assertEquals(
        "SESSION_TRANSPORT_PROFILE_MISMATCH",
        port.lastAudit().reasonCode());
assertNull(channel.readOutbound());
```

在 `ActiveSafetyAlarmRouterTest` 增加 `usesOnlyTheV2EnabledModuleIntersection`；session profile 仅 ADAS 时，含
ADAS+DMS 的 payload 只产生 ADAS。

- [ ] **步骤 6：运行 gateway RED**

```powershell
& $Maven @MavenBase -pl apps/jt-gateway -am `
  '-Dtest=OperationsTerminalRegistryClientTest,RegistrationAuthenticationHandlerTest,ActiveSafetyAlarmRouterTest,ProtocolModuleRegistryActiveSafetyDispatchTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：gateway record 缺少 v2 fields；空 roles 被 constructor 拒绝；已鉴权 heartbeat 协议错配仍被 ACK。

- [ ] **步骤 7：实现 gateway v2 消费端**

gateway 定义与 API JSON property 同名的：

```java
public record SessionProtocolProfile(
        String transportProfile,
        String businessProfile,
        String safetyProfile,
        String mediaProfile,
        List<String> enabledActiveSafetyModules,
        int activePositionIntervalSeconds,
        int idlePositionIntervalSeconds) { }

public record TerminalSessionContext(
        int contractVersion,
        UUID terminalId,
        UUID onboardSystemId,
        UUID vehicleId,
        long onboardConfigurationVersion,
        Set<String> roles,
        String sourceCoordinateSystem,
        SessionProtocolProfile protocolProfile,
        String activeSafetyStandard,
        List<String> activeSafetyModules,
        int tokenVersion) { }
```

constructor 接受空 roles，但拒绝未知 role、contractVersion!=2、null profile、非正 interval 和 nested/flat safety
不一致。`OperationsTerminalRegistryClient.registrationContextIsConsistent` 比较所有 v2/compatibility 字段。

`TerminalSession` 新增：

```java
public boolean acceptsTransport(ProtocolVersion version) {
    return context != null
            && context.protocolProfile().transportProfile()
                    .equals(version.name());
}

public ActiveSafetyCapabilityProfile activeSafetyCapabilityProfile() {
    String standard = switch (
            context.protocolProfile().safetyProfile()) {
        case "JSATL12_2017" -> "T/JSATL12-2017";
        case "GBT28787_2023" -> "GB/T 28787-2023";
        default -> null;
    };
    return new ActiveSafetyCapabilityProfile(
            standard,
            context.protocolProfile().enabledActiveSafetyModules());
}
```

`RegistrationAuthenticationHandler.handleAuthenticated` 在 `session.touch` 和 heartbeat ACK 之前调用
`acceptsTransport`；错配时 release frame、写安全 audit、从 registry 移除并只关闭当前 channel。
`ActiveSafetyAlarmRouter` 只调用 `session.activeSafetyCapabilityProfile()`。

- [ ] **步骤 8：编写报警车载系统授权 RED 测试**

`CanonicalVehicleAlarm`、`GatewayIngressRouter.AlarmPayload` 和 `AlarmFact` 的测试构造器先增加
`onboardSystemId`，然后在 `PostgisVehicleAlarmIngressIntegrationTest` 新增：

```java
@Test
void secondActiveSafetyMemberWithoutLegacyBindingPersistsAlarm() {
    OnboardAlarmFixture fixture = onboardAlarmFixture(
            false,
            Role.ACTIVE_SAFETY,
            Capability.ADAS,
            SafetyProfile.JSATL12_2017);

    VehicleAlarmIngressService.Result result =
            service.ingest(fixture.alarmKey(), fixture.alarmFact());

    assertThat(result.status()).isEqualTo("ACCEPTED");
    assertThat(alarmRepository.findAll()).singleElement()
            .satisfies(alarm -> {
                assertThat(alarm.getOnboardSystemId())
                        .isEqualTo(fixture.onboardSystemId());
                assertThat(alarm.getTerminalId())
                        .isEqualTo(fixture.terminalId());
                assertThat(alarm.getVehicleId())
                        .isEqualTo(fixture.vehicleId());
            });
}

@Test
void revokedRoleAndHistoricalLegacyBindingCannotAuthorizeAlarm() {
    OnboardAlarmFixture fixture = onboardAlarmFixture(
            true,
            Role.ACTIVE_SAFETY,
            Capability.ADAS,
            SafetyProfile.JSATL12_2017);
    fixture.revokeActiveSafetyBeforePositionRecordedAt();

    VehicleAlarmIngressService.Result result =
            service.ingest(fixture.alarmKey(), fixture.alarmFact());

    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.reasonCodes()).containsExactly(
            "ACTIVE_SAFETY_AUTHORITY_MISMATCH");
    assertThat(alarmRepository.findAll()).isEmpty();
}
```

`OnboardAlarmFixture` 必须完整创建：active system、recorder membership、ACTIVE_SAFETY role、JSATL profile、
VERIFIED ADAS、已接受且同 onboardSystemId 的 position event/receipt；boolean 参数只决定是否插入 legacy history。
另加：

- `alarmCannotCrossOnboardSystemThroughAReusedVehicle`；
- `dmsAlarmRequiresVerifiedDmsNotOnlyVerifiedAdas`；
- `configurationAndAlarmAuthorizationSerializeToOneWholeOutcome`。

并发用例用两个 transaction 和 latch；允许结果只有“旧配置完整授权并写一条 alarm”或“新配置完整生效并安全拒绝”，
禁止 partial row、deadlock 和 legacy fallback。

- [ ] **步骤 9：运行报警 RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=VehicleAlarmIngressServiceTest,PostgisVehicleAlarmIngressIntegrationTest,CompositeOnboardEndToEndTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：第二设备返回 `TERMINAL_BINDING_MISMATCH`；新 DTO/field 缺失；历史 legacy row 仍可影响结果。

- [ ] **步骤 10：实现基于发生时刻的车载报警授权**

精确更新 DTO：

```java
public record CanonicalVehicleAlarm(
        UUID terminalId,
        UUID onboardSystemId,
        UUID vehicleId,
        String standard,
        String module,
        long terminalAlarmId,
        int typeCode,
        String alarmType,
        String state,
        int level,
        String terminalAlarmIdentifier,
        Instant occurredAt,
        Instant gatewayReceivedAt,
        BigDecimal longitude,
        BigDecimal latitude,
        BigDecimal speedKph,
        int vehicleStatus,
        int alarmSequenceNumber,
        int attachmentCount,
        UUID positionIdempotencyKey,
        String locationQualityStatus,
        String extensionPayloadDigest) { }
```

API `AlarmFact` 使用相同 terminal/onboard/vehicle 前缀。`ProtocolModuleRegistry` 从 authenticated session 写
`onboardSystemId`；API 不接受缺失值。

`AlarmStore` 改为：

```java
interface AlarmStore {
    Optional<LocationReference> findLocation(
            UUID positionIdempotencyKey,
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId);

    ActiveSafetyAuthorization lockAndAuthorizeActiveSafety(
            VehicleAlarmIngressService.AlarmFact fact,
            LocationReference location);

    record LocationReference(
            UUID eventId,
            UUID onboardSystemId,
            Instant recordedAt,
            String qualityStatus,
            String qualityReasons) { }

    record ActiveSafetyAuthorization(
            boolean authorized, String reasonCode) {
        static ActiveSafetyAuthorization allowed() {
            return new ActiveSafetyAuthorization(true, null);
        }
        static ActiveSafetyAuthorization rejected() {
            return new ActiveSafetyAuthorization(
                    false, "ACTIVE_SAFETY_AUTHORITY_MISMATCH");
        }
    }
}
```

`JpaAlarmStore.lockAndAuthorizeActiveSafety` 以 `location.recordedAt` 为唯一授权时刻，按全局锁序分别执行
`SELECT ... FOR UPDATE`；每一步缺失都返回 rejected：

```sql
-- membership / role / profile 的共同有效区间
valid_from <= :authorizedAt
AND (valid_to IS NULL OR :authorizedAt < valid_to)

-- capability 的发生时刻 VERIFIED 语义
verified_at <= :authorizedAt
AND (
  status = 'VERIFIED'
  OR (status = 'DISABLED' AND updated_at > :authorizedAt)
)
```

顺序查询固定为：

```sql
SELECT id
FROM onboard_systems
WHERE id = :onboardSystemId
  AND vehicle_id = :vehicleId
  AND status = 'ACTIVE'
FOR UPDATE;

SELECT id
FROM jt_terminals
WHERE id = :terminalId
FOR UPDATE;

SELECT id
FROM onboard_device_memberships
WHERE onboard_system_id = :onboardSystemId
  AND terminal_id = :terminalId
  AND valid_from <= :authorizedAt
  AND (valid_to IS NULL OR :authorizedAt < valid_to)
ORDER BY id
FOR UPDATE;

SELECT id
FROM onboard_device_role_assignments
WHERE onboard_system_id = :onboardSystemId
  AND terminal_id = :terminalId
  AND role = 'ACTIVE_SAFETY'
  AND valid_from <= :authorizedAt
  AND (valid_to IS NULL OR :authorizedAt < valid_to)
ORDER BY id
FOR UPDATE;

SELECT id, safety_profile
FROM onboard_device_protocol_profiles
WHERE terminal_id = :terminalId
  AND valid_from <= :authorizedAt
  AND (valid_to IS NULL OR :authorizedAt < valid_to)
ORDER BY id
FOR UPDATE;

SELECT id
FROM onboard_device_capabilities
WHERE terminal_id = :terminalId
  AND capability = :requiredCapability
  AND verified_at <= :authorizedAt
  AND (
    status = 'VERIFIED'
    OR (status = 'DISABLED' AND updated_at > :authorizedAt)
  )
ORDER BY id
FOR UPDATE;
```

每个 authority 层必须恰好一行；0 行或多行都返回同一个安全 rejection，不把内部表名暴露给 gateway。

module=ADAS 只匹配 capability=ADAS，module=DMS 只匹配 capability=DMS。standard 必须与 profile 显式对应。
删除 `matchesBindingAt` 和业务路径上的 `lockTerminal` 起始锁。

`VehicleAlarm` 映射 nullable historical/new-required `onboardSystemId`；新 constructor 从 `LocationReference` 写值，
并验证 fact 与 location 的 onboardSystemId 一致。

- [ ] **步骤 11：用当前车载系统成员关系替换终端“当前绑定”**

`TerminalManagementService.getDetail` 从 active membership → active system → vehicle 得到：

```java
public record CurrentOnboardMembershipSummary(
        UUID onboardSystemId,
        UUID vehicleId,
        String plateNumber,
        String status,
        OffsetDateTime validFrom) { }
```

`TerminalDetail`/`TerminalDetailView` 增加 `currentOnboardMembership` 和 `legacyBindingHistory`。兼容
`currentBinding` 固定返回 null；`bindingHistory` 暂时等于 legacy history。`TerminalManagementPage.vue` 改用新字段，
标题固定为“当前车载系统归属”和“历史 legacy 绑定”。

- [ ] **步骤 12：添加真实纵向 E2E**

在 `CompositeOnboardEndToEndTest` 新增
`realProfileCapabilitySessionDecodeAndAlarmAuthorizationStayOnOneAuthority`：

1. 用真实 `OnboardSystemConfigurationService` 配置 dual-device；
2. 用真实 `OnboardRegistrationResolver.verify` 取得 API v2 context；
3. 用 Spring ObjectMapper serialize API context，再 deserialize 为 gateway `TerminalSessionContext`；
4. 安装进 `TerminalSession`，用 test 内固定的 synthetic JSATL 0x64 hex fixture 调用真实
   `ActiveSafetyAlarmRouter`；
5. 先由真实 `GpsLocationIngressService` 接受对应 position；
6. 把 router 产生的 `CanonicalVehicleAlarm` 经 `GatewayIngressRouter` 送入真实 alarm service；
7. 断言新 alarm 的 terminal/system/vehicle/locationEvent 四元组一致；
8. 撤销 ACTIVE_SAFETY 后复用旧 session context，再送一条新 alarm，断言 API 二次授权拒绝。

该测试禁止使用手写 JSON profile stub；JSON 必须来自 resolver 返回对象。

- [ ] **步骤 13：运行聚焦 GREEN**

```powershell
& $Maven @MavenBase -pl apps/jt-gateway,apps/api -am `
  '-Dtest=OnboardRegistrationResolverTest,OnboardSystemConfigurationServiceTest,OnboardReadinessServiceTest,TerminalApiTest,TerminalManagementServiceTest,OperationsTerminalRegistryClientTest,RegistrationAuthenticationHandlerTest,ActiveSafetyAlarmRouterTest,ProtocolModuleRegistryActiveSafetyDispatchTest,VehicleAlarmIngressServiceTest,PostgisVehicleAlarmIngressIntegrationTest,CompositeOnboardEndToEndTest' `
  "-Djava.io.tmpdir=$MavenTemp" test

npm.cmd --prefix apps/admin-web test -- `
  src/pages/terminal-management-page.test.ts
```

预期：全部通过，failure/error=0；第二设备无 legacy row 成功；撤销/跨车/历史/并发失败安全；空 roles 可鉴权但
无业务事实；unsupported GBT profile 不显示 READY。

- [ ] **步骤 14：运行 R2 回归**

```powershell
& $Maven @MavenBase -pl libs/jt-protocol,apps/jt-gateway,apps/api -am `
  '-Dtest=TerminalApiTest,TerminalManagementServiceTest,GpsLocationIngressIntegrationTest,VehicleAlarmApiTest,VehicleAlarmActionServiceTest,JtGatewayRuntimeIntegrationTest,JtGatewayServerIntegrationTest' `
  "-Djava.io.tmpdir=$MavenTemp" test

npm.cmd --prefix apps/admin-web run typecheck
```

预期：0 failure/error；既有 attachment tests 不变且仍 out of scope。

- [ ] **步骤 15：独立复审 R2**

复审必须从 configuration command 开始，逐层跟踪到 alarm row；禁止只审 DTO。检查：

- resolver 不再读 legacy safety 字段；
- gateway 不猜 profile；
- empty roles fail closed；
- alarm 不查询 legacy binding；
- event-time predicate 和锁序正确；
- old session 在 role revoke 后被 API 二次授权拒绝；
- terminal detail 不再把 legacy ACTIVE row 显示为当前。

预期：Critical=0、Important=0。修复任何复审问题后重新运行 步骤 13-14。

- [ ] **步骤 16：将 R2 作为一个纵向单元提交**

```powershell
$R2Files = @(
  'apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolver.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardReadinessService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/GatewayRegistryController.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalController.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/location/GatewayIngressRouter.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/alarm/AlarmStore.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/alarm/JpaAlarmStore.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarmIngressService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/alarm/VehicleAlarm.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardTestFixtures.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolverTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationServiceTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardReadinessServiceTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/CompositeOnboardEndToEndTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/alarm/VehicleAlarmIngressServiceTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/alarm/InMemoryAlarmStore.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/alarm/PostgisVehicleAlarmIngressIntegrationTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalApiTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalManagementServiceTest.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSessionContext.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSession.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/RegistrationDecision.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClient.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandler.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/CanonicalVehicleAlarm.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/ActiveSafetyAlarmRouter.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistry.java',
  'apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClientTest.java',
  'apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandlerTest.java',
  'apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/ingress/ActiveSafetyAlarmRouterTest.java',
  'apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistryActiveSafetyDispatchTest.java',
  'apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/JtGatewayRuntimeIntegrationTest.java',
  'apps/admin-web/src/api/types.ts',
  'apps/admin-web/src/pages/TerminalManagementPage.vue',
  'apps/admin-web/src/pages/terminal-management-page.test.ts'
)
git add -- $R2Files
git commit --only -m 'fix: align onboard session and alarm authority' -- $R2Files
```

提交前用 `git diff --cached --name-only` 确认不含 V21、progress、acceptance docs 或 private 文件。

---

### 任务 R3：新增带防串扰的当前会话租约，并让所有就绪度消费者统一使用

**文件：**

- 新建： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLease.java`
- 新建： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLeaseRepository.java`
- 新建： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLeaseService.java`
- 新建： `apps/api/src/test/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLeaseServiceTest.java`
- 新建： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/SessionLeaseReporter.java`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolver.java`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/GatewayRegistryController.java`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardReadinessService.java:181-199,256-295,516-528`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationService.java:752-900,1486-1501`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java:86-108,759-766`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalController.java:254-296`
- 修改： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardTestFixtures.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalRegistryPort.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClient.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/AuthenticationDecision.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSession.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandler.java`
- 修改： `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/JtGatewayRuntimeConfiguration.java`
- 修改： `apps/admin-web/src/api/types.ts:299-320,367-410`
- 修改： `apps/admin-web/src/pages/OnboardSystemManagementPage.vue:341-367`
- 修改： `apps/admin-web/src/pages/TerminalManagementPage.vue`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolverTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardReadinessServiceTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardSystemApiTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalApiTest.java`
- 测试： `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClientTest.java`
- 测试： `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandlerTest.java`
- 测试： `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/JtGatewayRuntimeIntegrationTest.java`
- 测试： `apps/admin-web/src/pages/onboard-system-management-page.test.ts`
- 测试： `apps/admin-web/src/pages/terminal-management-page.test.ts`

**接口：**

- 依赖： R1 frozen `jt_terminal_session_leases`；R2 authentication context v2。
- 产出： API nested records
  `JtTerminalSessionLeaseService.SessionLeaseOwner`、`SessionLeaseGrant`、`SessionLeaseReleaseResult`；
  gateway 对称 records 定义在 `TerminalRegistryPort`。
- 产出： `AuthenticationDecision(approved,context,lease,reasonCode)`。
- 供 R4 使用的产出： readiness 中独立于 location freshness 的 `currentlyAuthenticated`。
- 不变量： lease 以 physical terminalId 为键；另一设备、历史 auth、audit 或 lastSeen 均不能替代。

- [ ] **步骤 1：编写租约生命周期 RED 测试**

创建 `JtTerminalSessionLeaseServiceTest`，使用 mutable Clock，新增：

```java
@Test
void acquireRenewReleaseAndExpiryUseOnlyApiClock() {
    SessionLeaseGrant first = service.acquire(
            TERMINAL_ID, "gateway-a", CONNECTION_A, 7);
    assertThat(first.owner().leaseGeneration()).isEqualTo(1);
    assertThat(first.expiresAt())
            .isEqualTo(NOW.plusSeconds(180));
    assertThat(service.isLiveAt(
            TERMINAL_ID, 7, NOW.plusSeconds(179))).isTrue();
    assertThat(service.isLiveAt(
            TERMINAL_ID, 7, NOW.plusSeconds(180))).isFalse();

    clock.set(NOW.plusSeconds(30));
    SessionLeaseGrant renewed =
            service.renew(first.owner()).orElseThrow();
    assertThat(renewed.lastValidMessageAt())
            .isEqualTo(NOW.plusSeconds(30));
    assertThat(renewed.expiresAt())
            .isEqualTo(NOW.plusSeconds(210));

    SessionLeaseReleaseResult released =
            service.release(renewed.owner(), "SESSION_OFFLINE");
    assertThat(released.status()).isEqualTo("RELEASED");
    assertThat(service.isLiveAt(
            TERMINAL_ID, 7, NOW.plusSeconds(31))).isFalse();
}

@Test
void staleReleaseCannotClearTheTakeoverLease() {
    SessionLeaseGrant oldOwner =
            service.acquire(TERMINAL_ID, "gateway-a", CONNECTION_A, 7);
    SessionLeaseGrant takeover =
            service.acquire(TERMINAL_ID, "gateway-b", CONNECTION_B, 7);

    assertThat(takeover.owner().leaseGeneration()).isEqualTo(2);
    assertThat(service.release(
            oldOwner.owner(), "SESSION_OFFLINE").status())
            .isEqualTo("STALE_OWNER_IGNORED");
    assertThat(service.isLiveAt(
            TERMINAL_ID, 7, clock.instant())).isTrue();
    assertThat(repository.findById(TERMINAL_ID)
            .orElseThrow().getConnectionId()).isEqualTo(CONNECTION_B);
}

@Test
void tokenRotationInvalidatesAnOtherwiseFreshLease() {
    SessionLeaseGrant lease =
            service.acquire(TERMINAL_ID, "gateway-a", CONNECTION_A, 7);

    assertThat(service.isLiveAt(
            TERMINAL_ID, 8, clock.instant())).isFalse();
    assertThat(service.renew(new SessionLeaseOwner(
            TERMINAL_ID,
            "gateway-a",
            CONNECTION_A,
            8,
            lease.owner().leaseGeneration()))).isEmpty();
}
```

- [ ] **步骤 2：运行生命周期 RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=JtTerminalSessionLeaseServiceTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：compile failure，因为 entity/service/records 尚不存在。R1/V21 schema 必须保持无 diff。

- [ ] **步骤 3：实现租约记录、实体、仓储和服务**

在 `JtTerminalSessionLeaseService` 定义：

```java
public record SessionLeaseOwner(
        UUID terminalId,
        String gatewayInstance,
        UUID connectionId,
        int tokenVersion,
        long leaseGeneration) { }

public record SessionLeaseGrant(
        SessionLeaseOwner owner,
        Instant authenticatedAt,
        Instant lastValidMessageAt,
        Instant expiresAt) { }

public record SessionLeaseReleaseResult(String status) {
    public SessionLeaseReleaseResult {
        if (!Set.of(
                "RELEASED",
                "ALREADY_RELEASED",
                "STALE_OWNER_IGNORED").contains(status)) {
            throw new IllegalArgumentException(
                    "unsupported release status");
        }
    }
}
```

`JtTerminalSessionLease` 一一映射 R1 字段，提供：

```java
static JtTerminalSessionLease acquire(
        UUID terminalId,
        String gatewayInstance,
        UUID connectionId,
        int tokenVersion,
        long generation,
        OffsetDateTime now,
        Duration ttl);

void takeover(
        String gatewayInstance,
        UUID connectionId,
        int tokenVersion,
        long generation,
        OffsetDateTime now,
        Duration ttl);

boolean ownedBy(SessionLeaseOwner owner);
void renew(OffsetDateTime now, Duration ttl);
void release(String reasonCode, OffsetDateTime now);
boolean isLiveAt(int currentTokenVersion, OffsetDateTime now);
SessionLeaseGrant toGrant(); // entity timestamps convert with toInstant()
```

repository：

```java
public interface JtTerminalSessionLeaseRepository
        extends JpaRepository<JtTerminalSessionLease, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lease from JtTerminalSessionLease lease "
            + "where lease.terminalId = :terminalId")
    Optional<JtTerminalSessionLease> findLockedByTerminalId(
            @Param("terminalId") UUID terminalId);

    List<JtTerminalSessionLease> findAllByTerminalIdIn(
            Collection<UUID> terminalIds);
}
```

service 使用固定 `Duration.ofSeconds(180)`，所有时间由注入 Clock 生成：

```java
@Transactional
public SessionLeaseGrant acquire(
        UUID terminalId,
        String gatewayInstance,
        UUID connectionId,
        int tokenVersion);

@Transactional
public Optional<SessionLeaseGrant> renew(SessionLeaseOwner owner);

@Transactional
public SessionLeaseReleaseResult release(
        SessionLeaseOwner owner, String safeReasonCode);

@Transactional(readOnly = true)
public boolean isLiveAt(
        UUID terminalId, int currentTokenVersion, Instant now);
```

`acquire` 在 resolver 已锁 terminal 后锁/创建 lease；existing row generation+1。`renew` 只有 owner 五元组匹配且
当前 lease 尚未过期时才延长；已过期 lease 不能复活。`release` 对 stale owner 幂等返回
`STALE_OWNER_IGNORED`。

三个 mutation 方法自身仍须先执行下面的 terminal lock，因此从 controller 单独调用也不会绕过顺序；auth 路径重复获取
同一事务已持有的行锁不会改变顺序：

```java
private JtTerminal lockTerminal(UUID terminalId) {
    entityManager.createNativeQuery("""
            select id from jt_terminals
            where id = :terminalId
            for update
            """)
            .setParameter("terminalId", terminalId)
            .getSingleResult();
    JtTerminal terminal = terminalRepository
            .findById(terminalId).orElseThrow();
    entityManager.refresh(terminal);
    return terminal;
}
```

`acquire/renew` 还要比较该 terminal 当前 `authTokenVersion`；不匹配时不得改 lease。

- [ ] **步骤 4：编写鉴权/租约原子性 RED 测试**

把 API 方法改为预期签名，并先更新测试：

```java
AuthenticationDecision authenticateByTerminalId(
        UUID terminalId,
        int tokenVersion,
        String tokenSha256,
        String gatewayInstance,
        UUID connectionId);

AuthenticationDecision authenticateByIdentity(
        String protocolVersion,
        String terminalPhone,
        String tokenSha256,
        String gatewayInstance,
        UUID connectionId);

public record AuthenticationDecision(
        boolean approved,
        TerminalSessionContext context,
        SessionLeaseGrant lease,
        String reasonCode) { }
```

新增
`successfulAuthenticationAtomicallyReturnsALeaseForThatPhysicalTerminal`，断言 auth 成功时 grant
terminal/token/gateway/connection 完全匹配；auth 失败时 lease=null 且表无 row。

`GatewayRegistryController.AuthenticationVerificationRequest` 和
`IdentityAuthenticationVerificationRequest` 增加 `@NotNull UUID connectionId`。

- [ ] **步骤 5：运行鉴权/租约 RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=JtTerminalSessionLeaseServiceTest,OnboardRegistrationResolverTest,TerminalApiTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：旧 authentication signature/response 没有 connectionId/lease，测试 compile 或 JSON assertion 失败。

- [ ] **步骤 6：完成 API 鉴权/租约获取 GREEN**

在 `authenticateLocked` 的 terminal/token/system/membership/profile/capability 校验全部成功、并仍持锁时调用
`leaseService.acquire`；和 `lastAuthenticatedAt` 更新处于同一事务。lease acquire 失败时整个 auth 回滚。

GREEN 断言：

- auth response context 与 grant 同 terminal/token；
- rejected auth 不创建/更新 lease；
- takeover generation 单调增加；
- raw token、phone、gateway address 不出现在 exception/toString。

`GatewayRegistryController` 增加：

```java
@PostMapping("/session-leases/renew")
ResponseEntity<ApiResponse<SessionLeaseGrant>> renewSessionLease(
        @Valid @RequestBody SessionLeaseOwner owner) {
    return leaseService.renew(owner)
            .map(grant -> ResponseEntity.ok(ApiResponse.ok(grant)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(null));
}

@PostMapping("/session-leases/release")
ApiResponse<SessionLeaseReleaseResult> releaseSessionLease(
        @Valid @RequestBody SessionLeaseReleaseRequest request) {
    return ApiResponse.ok(leaseService.release(
            request.owner(), request.reasonCode()));
}

public record SessionLeaseReleaseRequest(
        @NotNull SessionLeaseOwner owner,
        @NotBlank String reasonCode) { }
```

renew 的 409 没有 response body；gateway 只把它解释为本地 grant 不延长，不能记录服务原始响应。

- [ ] **步骤 7：编写 gateway 租约上报器 RED 测试**

`TerminalRegistryPort` 定义 JSON property 对称的 nested records：

```java
record SessionLeaseOwner(
        UUID terminalId,
        String gatewayInstance,
        UUID connectionId,
        int tokenVersion,
        long leaseGeneration) { }

record SessionLeaseGrant(
        SessionLeaseOwner owner,
        Instant authenticatedAt,
        Instant lastValidMessageAt,
        Instant expiresAt) { }

record SessionLeaseReleaseResult(String status) { }
```

精确签名：

```java
AuthenticationDecision verifyAuthentication(
        UUID terminalId,
        int tokenVersion,
        String presentedTokenSha256,
        UUID connectionId);

AuthenticationDecision verifyAuthenticationByIdentity(
        ProtocolVersion protocolVersion,
        String terminalPhone,
        String presentedTokenSha256,
        UUID connectionId);

Optional<SessionLeaseGrant> renewSessionLease(
        SessionLeaseOwner owner);

SessionLeaseReleaseResult releaseSessionLease(
        SessionLeaseOwner owner, String reasonCode);
```

在 gateway `AuthenticationDecision` 增加 `SessionLeaseGrant lease`。新增：

- `authenticationSendsThePhysicalConnectionIdAndInstallsTheLeaseGrant`；
- `validMessagesCoalesceRenewalsToAtMostOnePerThirtySeconds`；
- `renewalRunsOutsideTheNettyEventLoop`；
- `failedRenewalNeverExtendsLocalExpiryAndClosesAtExpiry`；
- `channelInactiveReleasesOnlyItsCurrentLeaseOwner`；
- `takeoverOldChannelReleaseCannotClearNewOwner`。

- [ ] **步骤 8：运行 gateway 租约上报器 RED**

```powershell
& $Maven @MavenBase -pl apps/jt-gateway -am `
  '-Dtest=OperationsTerminalRegistryClientTest,RegistrationAuthenticationHandlerTest,JtGatewayRuntimeIntegrationTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：client request 没有 connectionId；AuthenticationDecision 没有 grant；handler 只更新内存
`lastValidMessageAt`，没有 renew/release。

- [ ] **步骤 9：实现 SessionLeaseReporter 与 gateway 生命周期**

`TerminalSession` 增加 current owner/expiry 和原子 renew gate：

```java
public void installLease(SessionLeaseGrant grant);
public boolean leaseOwnerMatches(SessionLeaseOwner owner);
public boolean renewalDue(Instant now, Duration interval);
public void acceptRenewal(SessionLeaseGrant grant);
public boolean leaseExpired(Instant now);
public boolean beginRenewal();
public void endRenewal();
```

`SessionLeaseReporter`：

```java
public final class SessionLeaseReporter {
    private static final Duration RENEW_INTERVAL =
            Duration.ofSeconds(30);

    public void renewIfDue(TerminalSession session, Instant now);
    public void release(TerminalSession session, String reasonCode);
}
```

实现约束：

- executor 由 `JtGatewayRuntimeConfiguration` 创建，线程数 1、`ArrayBlockingQueue(1024)`、AbortPolicy，
  `@Bean(destroyMethod="shutdown")`；
- 同一 session 最多一个 in-flight renew；
- response 必须 owner 完全匹配才调用 `acceptRenewal`；
- success callback 和 close 都切回 `channel.eventLoop()`；
- 到本地 grant.expiresAt 仍未成功 renew 时关闭当前 channel；
- auth exception、audit exception、takeover、reader-idle、channelInactive、handlerRemoved 都通过同一 release path；
- release 失败由 API TTL 兜底，不阻塞 channel close。

`OperationsTerminalRegistryClient` 调用：

- POST `/internal/jt-gateway/session-leases/renew`；
- POST `/internal/jt-gateway/session-leases/release`。

请求不包含客户端决定的 expiry/lastValid time。

- [ ] **步骤 10：编写就绪度与调度 RED 测试**

`OnboardTestFixtures.clear` 先删除 leases；`makeAuthenticated` 用 lease service 为 synthetic terminal 创建 owner，
使既有 READY fixture 继续表达“当前在线”。

在 `OnboardReadinessServiceTest`：

```java
@Test
void historicalAuthenticationWithoutALiveLeaseIsNotDispatchReady() {
    UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
    UUID dispatchTerminalId =
            terminalForRole(vehicleId, Role.DISPATCH).getId();
    leaseRepository.deleteById(dispatchTerminalId);

    OnboardReadiness readiness = service.evaluate(vehicleId);

    assertThat(readiness.dispatch())
            .isEqualTo(ReadinessState.UNAVAILABLE);
    assertThat(readiness.dispatchEligible()).isFalse();
    assertThat(readiness.overallStatus()).isEqualTo("OFFLINE");
}

@Test
void anotherDeviceLeaseCannotAuthenticateTheDispatchRoleDevice() {
    DualDeviceReadinessFixture fixture =
            dualDeviceWithFreshBackupLocation();
    leaseService.acquire(
            fixture.recorderId(),
            "gateway-a",
            UUID.randomUUID(),
            1);

    assertThat(service.evaluate(
            fixture.vehicleId()).dispatchEligible()).isFalse();
}
```

在 `DispatchOrchestratorTest` 新增
`excludesHistoricallyAuthenticatedVehicleWhenDispatchLeaseExpiredButBackupLocationIsFresh`。

在 `ManualReviewApiTest` 新增
`rejectsApprovalWhenDispatchLeaseExpiresAfterCandidateWasCreated`，预期 HTTP 409 和
`DISPATCH_ONBOARD_SYSTEM_NOT_READY`。

- [ ] **步骤 11：运行就绪度 RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardReadinessServiceTest,DispatchOrchestratorTest,ManualReviewApiTest,OnboardSystemApiTest,TerminalApiTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：删除/过期 lease 后，旧 `lastAuthenticatedAt != null` 仍令 dispatch READY/eligible。

- [ ] **步骤 12：让就绪度与管理 DTO 使用同一租约**

`OnboardReadinessService.loadTerminals` 同时 bulk-load current leases；`TerminalFact` 改为：

```java
private record TerminalFact(
        JtTerminal.Status status,
        int tokenVersion,
        OffsetDateTime lastAuthenticatedAt,
        SessionLeaseFact lease) { }

private record SessionLeaseFact(
        int tokenVersion,
        OffsetDateTime lastValidMessageAt,
        OffsetDateTime expiresAt,
        OffsetDateTime releasedAt) {
    boolean liveAt(int terminalTokenVersion, Instant now) {
        return releasedAt == null
                && tokenVersion == terminalTokenVersion
                && now.isBefore(expiresAt.toInstant());
    }
}
```

`terminalAuthenticated` 只调用 `lease.liveAt`；`lastAuthenticatedAt` 不参与 current decision。

`OnboardSystemConfigurationService.DeviceView` 新增：

```java
boolean currentlyAuthenticated,
OffsetDateTime sessionLastValidMessageAt,
OffsetDateTime sessionExpiresAt
```

保留 `lastAuthenticatedAt` 作为历史显示；`authenticationPresent` 标 deprecated 并固定等于
`lastAuthenticatedAt != null`，UI 禁止使用。

`TerminalManagementService.getDetail` 的 `onlineStatus`、`lastValidMessageAt` 和 `offlineAt` 也从同一个 lease
projection 计算，不再从 `JtTerminal.lastSeenAt` 推导。

- [ ] **步骤 13：编写 UI 当前会话 RED 测试**

API/TS `OnboardDeviceView` 增加：

```typescript
currentlyAuthenticated: boolean;
sessionLastValidMessageAt: IsoDateTime | null;
sessionExpiresAt: IsoDateTime | null;
```

新增：

- `showsCurrentOfflineWhileKeepingHistoricalAuthenticationTime`；
- `neverRendersGatewayInstanceConnectionIdOrLeaseGeneration`；
- `terminalDetailUsesTheSameCurrentSessionStateAsOnboardDetail`。

第一项 fixture 设置 `authenticationPresent=true`、`lastAuthenticatedAt` 非空，但
`currentlyAuthenticated=false`，预期显示“当前：离线”和“最近成功鉴权（历史）”，不能出现“当前已鉴权”。

- [ ] **步骤 14：实现 UI 文案并运行聚焦 GREEN**

`OnboardSystemManagementPage.vue` 使用 `currentlyAuthenticated`：

```html
<div>
  <dt>当前会话</dt>
  <dd>{{ device.currentlyAuthenticated ? "当前：在线且已鉴权" : "当前：离线" }}</dd>
</div>
<div>
  <dt>最近成功鉴权（历史）</dt>
  <dd>{{ time(device.lastAuthenticatedAt) }}</dd>
</div>
<div>
  <dt>会话最近有效消息</dt>
  <dd>{{ time(device.sessionLastValidMessageAt) }}</dd>
</div>
```

```powershell
& $Maven @MavenBase -pl apps/api,apps/jt-gateway -am `
  '-Dtest=JtTerminalSessionLeaseServiceTest,OnboardRegistrationResolverTest,OnboardReadinessServiceTest,DispatchOrchestratorTest,ManualReviewApiTest,OnboardSystemApiTest,TerminalApiTest,OperationsTerminalRegistryClientTest,RegistrationAuthenticationHandlerTest,JtGatewayRuntimeIntegrationTest' `
  "-Djava.io.tmpdir=$MavenTemp" test

npm.cmd --prefix apps/admin-web test -- `
  src/pages/onboard-system-management-page.test.ts `
  src/pages/terminal-management-page.test.ts
npm.cmd --prefix apps/admin-web run typecheck
```

预期 GREEN：0 failure/error；lease expiry 同时关闭 readiness、自动、人工和两个 UI 的 current 状态。

- [ ] **步骤 15：独立复审 R3**

复审必须检查：

- API Clock 是 TTL 唯一时间源；
- stale generation release/renew 不改变新 owner；
- renew 不能复活 expired lease；
- Netty event loop 不执行阻塞 HTTP；
- readiness/automatic/manual/UI 使用同一 live predicate；
- 另一 terminal lease 不冒充；
- lease internals 不进管理 DTO/日志；
- V21 diff 为零。

预期：Critical=0、Important=0。修复后重跑 步骤 14。

- [ ] **步骤 16：提交 R3**

```powershell
$R3Files = @(
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLease.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLeaseRepository.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLeaseService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolver.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/GatewayRegistryController.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardReadinessService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalController.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/terminal/JtTerminalSessionLeaseServiceTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardTestFixtures.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolverTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardReadinessServiceTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardSystemApiTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalApiTest.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/SessionLeaseReporter.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalRegistryPort.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClient.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/AuthenticationDecision.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSession.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandler.java',
  'apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/JtGatewayRuntimeConfiguration.java',
  'apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClientTest.java',
  'apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandlerTest.java',
  'apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/JtGatewayRuntimeIntegrationTest.java',
  'apps/admin-web/src/api/types.ts',
  'apps/admin-web/src/pages/OnboardSystemManagementPage.vue',
  'apps/admin-web/src/pages/TerminalManagementPage.vue',
  'apps/admin-web/src/pages/onboard-system-management-page.test.ts',
  'apps/admin-web/src/pages/terminal-management-page.test.ts'
)
git add -- $R3Files
git commit --only -m 'fix: require live terminal session leases' -- $R3Files
```

确认 staged 列表不含 V21。提交完成后直接进入 R4。

---

### 任务 R4：修复位置时钟域并协调配置、运行态与快照来源

**文件：**

- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeState.java`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationSourceDecision.java`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationSourceArbitrator.java`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationQualityEvaluator.java`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/location/GpsLocationIngressService.java:145-215,261-465,577-603`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/fleet/Vehicle.java:51-64,248-263`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationService.java:201-229,286-313,330-412,529-678,1149-1241`
- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardReadinessService.java:298-341`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/location/LocationSourceArbitratorTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/location/LocationQualityEvaluatorTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/location/GpsLocationIngressIntegrationTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationServiceTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardReadinessServiceTest.java`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/onboard/CompositeOnboardEndToEndTest.java`

**接口：**

- 依赖： R1 frozen runtime columns；R3 live lease readiness。
- 产出： `ArbitrationState`/`LocationSourceDecision` with three separated cursors；
  `Vehicle.currentLocationOnboardSystemId` mapping；
  `reconcileLocationRuntimeAfterConfiguration(...)`。
- 不变量： I-4/I-5 同一任务；纯仲裁、持久化和 configuration concurrency 全部通过才关闭。
- 不修改： V21、V19、V20、gateway DTO、alarm authorization。

- [ ] **步骤 1：编写纯仲裁时钟域 RED 测试**

把 `LocationSourceArbitratorTest.state(...)` 替换为接收四个时钟字段的 builder，并新增：

```java
@Test
void primaryReceivedOneSecondAgoIsNotStaleWhenTerminalClockIsTwentyNineSecondsBehind() {
    ArbitrationState state = state(
            BACKUP,
            PRIMARY,
            BASE,
            BASE.minusSeconds(29),
            null,
            BASE,
            Duration.ofSeconds(15),
            true,
            0);

    LocationSourceDecision decision = arbitrator.decide(
            state,
            backup(
                    LocationQualityStatus.GOOD,
                    BASE.plusSeconds(1),
                    BASE.plusSeconds(1)));

    assertThat(decision.applySnapshot()).isFalse();
    assertThat(decision.reasonCode())
            .isEqualTo("NON_ACTIVE_SOURCE_IGNORED");
}

@Test
void primaryIsStaleAtPlatformBoundaryEvenWhenTerminalClockIsTwentyNineSecondsAhead() {
    ArbitrationState state = state(
            BACKUP,
            PRIMARY,
            BASE,
            BASE.plusSeconds(29),
            null,
            BASE,
            Duration.ofSeconds(15),
            true,
            0);

    LocationSourceDecision decision = arbitrator.decide(
            state,
            backup(
                    LocationQualityStatus.GOOD,
                    BASE.plusSeconds(1),
                    BASE.plusSeconds(30)));

    assertThat(decision.applySnapshot()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo("PRIMARY_STALE");
}

@Test
void lateRejectedPrimaryCannotRevokeEligibilityOrResetRecovery() {
    ArbitrationState state = state(
            BACKUP,
            BACKUP,
            BASE.plusSeconds(21),
            BASE.plusSeconds(20),
            BASE.plusSeconds(20),
            BASE.plusSeconds(21),
            Duration.ofSeconds(15),
            true,
            2);

    LocationSourceDecision decision = arbitrator.decide(
            state,
            primary(
                    LocationQualityStatus.REJECTED,
                    BASE.plusSeconds(19),
                    BASE.plusSeconds(22)));

    assertThat(decision.primaryEligible()).isTrue();
    assertThat(decision.primaryRecoveryStreak()).isEqualTo(2);
    assertThat(decision.primaryTerminalCursorAt())
            .isEqualTo(BASE.plusSeconds(20));
    assertThat(decision.reasonCode())
            .isEqualTo("POSITION_NOT_ELIGIBLE");
}
```

新 `state` 参数顺序固定为：

```java
state(
    UUID backupTerminalId,
    UUID activeTerminalId,
    Instant lastPrimaryValidGatewayReceivedAt,
    Instant primaryTerminalCursorAt,
    Instant backupTerminalCursorAt,
    Instant lastSnapshotGatewayReceivedAt,
    Duration expectedPrimaryInterval,
    boolean primaryEligible,
    int primaryRecoveryStreak)
```

另加：

- `takesOverAtExactGatewayBoundaryButNotOneNanosecondBefore`；
- `lateQuarantinedPrimaryLeavesEveryRuntimeFieldUnchanged`；
- `lateBackupLeavesPrimaryAndBackupCursorsUnchanged`；
- `thirdStrictlyIncreasingPrimaryTerminalTimeCompletesRecovery`。

- [ ] **步骤 2：运行纯仲裁 RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=LocationSourceArbitratorTest,LocationQualityEvaluatorTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：`ArbitrationState` 缺新字段；当前 backup 用 gateway time 对 terminal-derived
`lastPrimaryValidAt`，且 late REJECTED 在时序门禁前把 eligibility 置 false。

- [ ] **步骤 3：实现精确仲裁类型与处理顺序**

`ArbitrationState`：

```java
public record ArbitrationState(
        UUID primaryTerminalId,
        UUID backupTerminalId,
        UUID activeTerminalId,
        Instant lastPrimaryValidGatewayReceivedAt,
        Instant primaryTerminalCursorAt,
        Instant backupTerminalCursorAt,
        Instant lastSnapshotGatewayReceivedAt,
        Duration expectedPrimaryInterval,
        boolean primaryEligible,
        int primaryRecoveryStreak) { }
```

`LocationSourceDecision`：

```java
public record LocationSourceDecision(
        boolean applySnapshot,
        boolean switchSource,
        UUID selectedTerminalId,
        boolean primaryEligible,
        int primaryRecoveryStreak,
        Instant lastPrimaryValidGatewayReceivedAt,
        Instant primaryTerminalCursorAt,
        Instant backupTerminalCursorAt,
        String reasonCode) { }
```

`LocationSourceArbitrator.decide` 严格按下面顺序实现：

1. terminalId/sourceRole 必须对应当前 primary 或 backup；
2. candidate.terminalLocatedAt 必须严格晚于对应 source cursor；否则返回所有 state 原值；
3. candidate.gatewayReceivedAt 必须严格晚于 lastSnapshotGatewayReceivedAt 才能应用 snapshot；
4. 再判断 quality；
5. in-order invalid primary 推进 primary cursor、置 eligible=false、streak=0，但不推进 primary valid gateway time；
6. in-order valid primary 同时推进 primary cursor 与 primary valid gateway time；
7. backup staleness 只比较 gateway times；
8. primary recovery streak 只由严格递增的 primary terminal time 推进。

所有 unchanged 分支必须把三个 cursor 原样放进 decision，不能默认为 null。

`LocationQualityEvaluator.Input.latestTrustedReportedAt` 改名
`latestSourceTerminalLocatedAt`，OUT_OF_ORDER 只比较同 source cursor。

- [ ] **步骤 4：编写持久化与推算速度 RED 测试**

在 `GpsLocationIngressIntegrationTest` 新增：

- `persistsSeparatedPrimaryGatewayAndTerminalCursorsAcrossEntityManagerClear`；
- `lateRejectedPrimaryDoesNotMutateRuntimeOrEnableBackupTakeover`；
- `terminalClockSkewDoesNotChangeThePlatformStalenessBoundary`；
- `crossDeviceImpliedSpeedUsesGatewayIntervalNotTwoTerminalClocks`。

第一个测试在 ingest 后调用 `entityManager.flush(); entityManager.clear();`，重新读取并断言：

```java
assertThat(runtime.getLastPrimaryValidGatewayReceivedAt())
        .isEqualTo(primaryGatewayAt.atOffset(ZoneOffset.UTC));
assertThat(runtime.getPrimaryTerminalCursorAt())
        .isEqualTo(primaryTerminalAt.atOffset(ZoneOffset.UTC));
assertThat(runtime.getBackupTerminalCursorAt())
        .isEqualTo(backupTerminalAt.atOffset(ZoneOffset.UTC));
```

cross-device implied-speed 测试构造 primary terminal time 快 29 秒、backup terminal time 慢 29 秒，但 gateway
interval/距离合理；预期不出现 `IMPLIED_SPEED_EXCEEDED` 或 `OUT_OF_ORDER`。

- [ ] **步骤 5：映射并写入已冻结的 V21 运行态字段**

`OnboardSystemRuntimeState` 新增：

```java
private OffsetDateTime lastPrimaryValidGatewayReceivedAt;
private OffsetDateTime primaryTerminalCursorAt;
private OffsetDateTime backupTerminalCursorAt;

public void applyLocationArbitration(
        UUID selectedTerminalId,
        boolean nextPrimaryEligible,
        int nextPrimaryRecoveryStreak,
        OffsetDateTime nextLastPrimaryValidGatewayReceivedAt,
        OffsetDateTime nextPrimaryTerminalCursorAt,
        OffsetDateTime nextBackupTerminalCursorAt,
        boolean sourceChanged,
        OffsetDateTime processedAt);

public void resetLocationAuthority(OffsetDateTime changedAt);
```

保留 `lastPrimaryValidAt` 映射并双写为 primary terminal cursor 的 compatibility mirror；任何 staleness 代码禁止读取它。

`GpsLocationIngressService.prepareArbitration` 从 runtime/vehicle 构造新 state，直接应用 decision 返回的三个 next cursor。
`impliedSpeedKph` 固定规则：

- 同 terminal 连续事件用 terminal times；
- source 切换用 vehicle.currentLocationGatewayReceivedAt 与 candidate.gatewayReceivedAt；
- 非正 duration 返回 null，不产生跨时钟速度。

- [ ] **步骤 6：运行持久化 RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=GpsLocationIngressIntegrationTest,LocationSourceArbitratorTest,LocationQualityEvaluatorTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：entity 没有 frozen V21 fields；reload 断言失败；current implied-speed 仍比较不同 terminal 的
`driverReportedAt`。

- [ ] **步骤 7：完成位置持久化 GREEN**

按 步骤 3/5 的 signature 更新 `GpsLocationIngressService`。在 quality evaluation 前选择 source cursor：

```java
Instant sourceCursor = ingress.terminalId().equals(
        authority.primaryTerminalId())
        ? instant(runtime.getPrimaryTerminalCursorAt())
        : instant(runtime.getBackupTerminalCursorAt());
```

把 `sourceCursor` 传给 `LocationQualityEvaluator.Input.latestSourceTerminalLocatedAt`。raw invalid coordinate 和
QUARANTINED/REJECTED 分支也必须经过同一个 arbitrator order gate 后才改变 runtime。

重跑步骤 6；预期 GREEN：0 failure/error。

- [ ] **步骤 8：编写配置/运行态/来源 RED 测试**

在 `OnboardSystemConfigurationServiceTest` 新增 test helper：

```java
private record ActiveLocationFixture(
        UUID vehicleId,
        UUID systemId,
        UUID activeTerminalId,
        ConfigurationCommand commandWithoutActiveTerminal,
        ConfigurationCommand commandMovingOnlyWanUplink) { }
```

helper 必须创建两设备、真实 location event、Vehicle snapshot、runtime source 和两个可复用 command。新增：

```java
@Test
void removingActiveLocationMemberClearsRuntimeAndMarksSnapshotStale() {
    ActiveLocationFixture fixture = activeDualDeviceLocationFixture();

    service.apply(
            fixture.vehicleId(),
            fixture.commandWithoutActiveTerminal(),
            OnboardTestFixtures.ACTOR_ID);

    OnboardSystemRuntimeState runtime = runtimeStateRepository
            .findById(fixture.systemId()).orElseThrow();
    Vehicle vehicle = vehicleRepository
            .findById(fixture.vehicleId()).orElseThrow();
    assertThat(runtime.getActiveLocationTerminalId()).isNull();
    assertThat(runtime.getPrimaryRecoveryStreak()).isZero();
    assertThat(runtime.getPrimaryTerminalCursorAt()).isNull();
    assertThat(runtime.getBackupTerminalCursorAt()).isNull();
    assertThat(vehicle.isCurrentLocationStale()).isTrue();
}

@Test
void unrelatedWanChangePreservesStillLegalActiveLocationSource() {
    ActiveLocationFixture fixture = activeDualDeviceLocationFixture();

    service.apply(
            fixture.vehicleId(),
            fixture.commandMovingOnlyWanUplink(),
            OnboardTestFixtures.ACTOR_ID);

    assertThat(runtimeStateRepository.findById(fixture.systemId())
            .orElseThrow().getActiveLocationTerminalId())
            .isEqualTo(fixture.activeTerminalId());
    assertThat(vehicleRepository.findById(fixture.vehicleId())
            .orElseThrow().isCurrentLocationStale()).isFalse();
}
```

另加：

- `movingPrimaryRoleLetsTheNextLegalPrimarySelectWithoutProvenanceMismatch`；
- `replacementNeverClaimsTheOldSnapshotForTheNewTerminal`；
- `newSystemCannotUseThePreviousSystemsVehicleSnapshot`；
- `configurationAndIngressObserveOnlyWholeOldOrWholeNewAuthority`。

并发测试必须使用两个 transaction/latch，并在 5 秒 bounded wait 内结束；允许旧整态或新整态，禁止
`ONBOARD_PROVENANCE_MISMATCH` 永久卡死、partial history 或 deadlock。

- [ ] **步骤 9：运行配置/来源 RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardSystemConfigurationServiceTest,OnboardReadinessServiceTest,CompositeOnboardEndToEndTest,GpsLocationIngressIntegrationTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：ordinary apply 后 runtime 仍指旧 terminal；replacement 直接选择未上报位置的新 terminal；
Vehicle 没有 currentLocationOnboardSystemId mapping。

- [ ] **步骤 10：映射车辆来源并集中协调配置**

`Vehicle` 增加：

```java
private UUID currentLocationOnboardSystemId;

public UUID getCurrentLocationOnboardSystemId() {
    return currentLocationOnboardSystemId;
}

public void invalidateGpsSnapshotForOnboardSystem(UUID onboardSystemId) {
    if (currentLocationSource == LocationSource.GPS_DEVICE
            && Objects.equals(
                    currentLocationOnboardSystemId, onboardSystemId)) {
        currentLocationStale = true;
    }
}
```

在现有 `applyGpsLocationSnapshot` 末尾精确增加：

```java
currentLocationOnboardSystemId =
        Objects.requireNonNull(
                event.getOnboardSystemId(), "onboardSystemId");
currentLocationTerminalId =
        Objects.requireNonNull(event.getTerminalId(), "terminalId");
currentLocationStale = false;
```

`OnboardSystemConfigurationService` 新增：

```java
private void reconcileLocationRuntimeAfterConfiguration(
        OnboardSystem system,
        CurrentLocationAssignment before,
        CurrentLocationAssignment after,
        OnboardSystemRuntimeState runtime,
        Vehicle vehicle,
        OffsetDateTime changedAt);

private record CurrentLocationAssignment(
        UUID primaryTerminalId,
        UUID backupTerminalId,
        Set<UUID> eligibleTerminalIds) { }
```

在关闭任何 membership/role/profile 前先按全局锁序锁齐 system、terminals、membership、roles、profiles、capabilities、
runtime、vehicle。`apply`、`retireTerminal`、`replaceTerminal` 和 legacy bind adapter 全部调用同一 helper：

- active source 仍在 after.eligibleTerminalIds：保留；
- active source 不再合法：`resetLocationAuthority` + `invalidateGpsSnapshotForOnboardSystem`；
- primary terminal 改变：清 primary gateway/cursor/streak；
- backup terminal 改变：清 backup cursor；
- replacement 没有新 event 时绝不直接 `selectLocationSource(replacementId)`。

`OnboardReadinessService.location` 增加：

```java
if (!snapshot.system().getId().equals(
        snapshot.vehicle().getCurrentLocationOnboardSystemId())) {
    return ReadinessState.UNAVAILABLE;
}
```

- [ ] **步骤 11：运行 R4 聚焦 GREEN**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=LocationSourceArbitratorTest,LocationQualityEvaluatorTest,GpsLocationIngressIntegrationTest,OnboardSystemConfigurationServiceTest,OnboardReadinessServiceTest,CompositeOnboardEndToEndTest,PostgisVehicleLocationConcurrencyTest,VehicleLocationRecorderTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期：0 failure/error；±29 秒、阈值两侧、late bad quality、reload、role/member migration、replacement、
new system reuse 和 concurrency 全通过。

- [ ] **步骤 12：运行 R4 回归**

```powershell
& $Maven @MavenBase -pl apps/api,apps/jt-gateway -am `
  '-Dtest=GpsLocationIngressIntegrationTest,LocationSourceArbitratorTest,OnboardReadinessServiceTest,OnboardSystemConfigurationServiceTest,DispatchOrchestratorTest,ManualReviewApiTest,ProtocolModuleRegistryActiveSafetyDispatchTest,CompositeOnboardEndToEndTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期：0 failure/error；location provenance change 不破坏 alarm position dependency 或 lease readiness。

- [ ] **步骤 13：独立复审 R4**

复审必须构造时间表逐步验证：

- 不存在 terminal time 与 gateway time 直接比较；
- 两个 terminal 的 terminal time 不互比；
- late event 在 quality 改变 runtime 之前被 order gate 挡住；
- source switch implied speed 用平台时间；
- configuration 锁齐后才关闭历史 row；
- active source 合法保留、非法重置；
- Vehicle system/terminal/event/runtime provenance 一致；
- V21 diff 为零。

预期：Critical=0、Important=0。修复后重跑 步骤 11-12。

- [ ] **步骤 14：提交 R4**

```powershell
$R4Files = @(
  'apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeState.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/location/LocationSourceDecision.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/location/LocationSourceArbitrator.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/location/LocationQualityEvaluator.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/location/GpsLocationIngressService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/fleet/Vehicle.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationService.java',
  'apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardReadinessService.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/location/LocationSourceArbitratorTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/location/LocationQualityEvaluatorTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/location/GpsLocationIngressIntegrationTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationServiceTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardReadinessServiceTest.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/onboard/CompositeOnboardEndToEndTest.java'
)
git add -- $R4Files
git commit --only -m 'fix: coordinate location runtime provenance' -- $R4Files
```

确认 staged 列表不含 V21。

---

### 任务 R5：停止在换机审计元数据中持久化原始终端与车牌

**文件：**

- 修改： `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java:407-448,523-534`
- 测试： `apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalManagementServiceTest.java:500-558`

**接口：**

- 依赖： R2 `OnboardReplacementResult(onboardSystemId,vehicleId,transferredRoles)`；R3 terminal management shape。
- 产出： replacement audit metadata with exact safe key allowlist。
- 不依赖且不修改： V21、historical `audit_logs`、private databases。

- [ ] **步骤 1：将不安全的正向断言替换为失败的白名单测试**

把现有“metadata 必须包含 code/plate”的断言改为：

```java
@Test
void replacementAuditContainsOnlySafeAliasesVersionsRolesAndReasonCode()
        throws Exception {
    ReplacementFixture fixture = replacementFixture(true);
    JtTerminal oldTerminal = fixture.oldTerminal();
    JtTerminal replacement = fixture.replacement();
    int oldVersionBefore = oldTerminal.getAuthTokenVersion();
    int replacementVersionBefore =
            replacement.getAuthTokenVersion();

    service.replace(
            oldTerminal.getTerminalCode(),
            replacement.getTerminalCode(),
            oldTerminal.getVersion(),
            replacement.getVersion(),
            "synthetic replacement",
            ACTOR_ID);

    AuditLog audit = auditLogRepository
            .findAllByOrderByCreatedAtAsc().stream()
            .filter(item -> "JT_TERMINAL_REPLACED"
                    .equals(item.getAction()))
            .findFirst()
            .orElseThrow();
    JsonNode metadata =
            objectMapper.readTree(audit.getMetadataJson());
    assertThat(fieldNames(metadata)).containsExactlyInAnyOrder(
            "oldDeviceAlias",
            "replacementDeviceAlias",
            "transferredRoleCount",
            "transferredRoles",
            "oldTokenVersion",
            "replacementTokenVersion",
            "reasonCode");
    assertThat(metadata.path("reasonCode").asText())
            .isEqualTo("TERMINAL_REPLACED");
    assertThat(metadata.path("oldTokenVersion").asInt())
            .isEqualTo(oldVersionBefore + 1);
    assertThat(metadata.path("replacementTokenVersion").asInt())
            .isEqualTo(replacementVersionBefore + 1);
    assertThat(audit.getMetadataJson()).doesNotContain(
            oldTerminal.getTerminalCode(),
            replacement.getTerminalCode(),
            oldTerminal.getTerminalPhone(),
            replacement.getTerminalPhone(),
            oldTerminal.getId().toString(),
            replacement.getId().toString(),
            "浙A10001");
}

private static List<String> fieldNames(JsonNode value) {
    List<String> names = new ArrayList<>();
    value.fieldNames().forEachRemaining(names::add);
    return List.copyOf(names);
}
```

- [ ] **步骤 2：运行 R5 RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=TerminalManagementServiceTest#replacementAuditContainsOnlySafeAliasesVersionsRolesAndReasonCode' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期 RED：当前 metadata 仍包含 `oldTerminalCode`、`replacementTerminalCode`、`vehiclePlate`，allowlist 断言失败。

- [ ] **步骤 3：实现精确安全元数据**

删除 replace 路径为 metadata 读取 `Vehicle.plateNumber` 的代码。`replacementMetadata` 改为：

```java
private String replacementMetadata(
        JtTerminal oldTerminal,
        JtTerminal replacement,
        OnboardReplacementResult onboardReplacement) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    List<String> roles =
            onboardReplacement.transferredRoles().stream()
                    .map(Enum::name)
                    .sorted()
                    .toList();
    metadata.put(
            "oldDeviceAlias", safeDeviceAlias(oldTerminal.getId()));
    metadata.put(
            "replacementDeviceAlias",
            safeDeviceAlias(replacement.getId()));
    metadata.put("transferredRoleCount", roles.size());
    metadata.put("transferredRoles", roles);
    metadata.put(
            "oldTokenVersion", oldTerminal.getAuthTokenVersion());
    metadata.put(
            "replacementTokenVersion",
            replacement.getAuthTokenVersion());
    metadata.put("reasonCode", "TERMINAL_REPLACED");
    try {
        return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException exception) {
        throw new IllegalStateException(
                "failed to encode safe replacement audit metadata",
                exception);
    }
}

private static String safeDeviceAlias(UUID terminalId) {
    try {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(terminalId.toString()
                        .getBytes(StandardCharsets.US_ASCII));
        return "device-"
                + HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(
                "SHA-256 is unavailable", impossible);
    }
}
```

不要把 UUID、plate 或 code 换成另一种可逆编码。gateway audit 保持现状。

- [ ] **步骤 4：运行 R5 GREEN 与回归**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=TerminalManagementServiceTest,TerminalApiTest,OnboardSystemConfigurationServiceTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

预期：0 failure/error；换机、token invalidation、disconnect、onboard role transfer 均不变。

- [ ] **步骤 5：保留历史数据权限边界**

本任务不访问任何真实数据库。未来另获只读授权后，唯一允许的初始盘点 SQL 是：

```sql
SELECT
  count(*) AS replacement_audit_count,
  count(*) FILTER (
    WHERE metadata_json ?| ARRAY[
      'oldTerminalCode',
      'replacementTerminalCode',
      'vehiclePlate'
    ]
  ) AS affected_count,
  min(created_at) AS first_created_at,
  max(created_at) AS last_created_at
FROM audit_logs
WHERE action = 'JT_TERMINAL_REPLACED';
```

输出只允许 count/timestamp/action，不读取或打印 metadata value。任何 UPDATE/DELETE/历史替换需要新的安全与业务批准，
不属于本计划。

- [ ] **步骤 6：独立复审 R5**

复审检查生产 metadata allowlist、测试 forbidden values、异常消息、gateway audit 和 staged diff。

预期：Critical=0、Important=0，且历史 audit 零修改。

- [ ] **步骤 7：提交 R5**

```powershell
$R5Files = @(
  'apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java',
  'apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalManagementServiceTest.java'
)
git add -- $R5Files
git commit --only -m 'fix: redact terminal replacement audit metadata' -- $R5Files
```

---

### 任务 R6：仅用现有分页 API 让每套车载系统可访问

**文件：**

- 修改： `apps/admin-web/src/pages/OnboardSystemManagementPage.vue:40-119,292-325,418-420`
- 测试： `apps/admin-web/src/pages/onboard-system-management-page.test.ts`

**接口：**

- 依赖： unchanged
  `listOnboardSystems(page:number,size:number): Promise<OnboardSystemPage>` and
  `OnboardSystemPage{items,page,size,totalElements,totalPages}`。
- 产出： accessible previous/next pagination, server total, request fencing and draft invalidation。
- 不修改： backend controller/service、`onboardSystems.ts`、`types.ts`、search endpoints。

- [ ] **步骤 1：编写分页、竞态与权限 RED 测试**

扩展 test helpers：

```typescript
function pageFixture(
  items: ReturnType<typeof summaryFixture>[],
  page = 0,
  totalElements = items.length,
  totalPages = totalElements === 0
    ? 0
    : Math.ceil(totalElements / 20)
) {
  return {
    items,
    page,
    size: 20,
    totalElements,
    totalPages
  };
}

function vehicleId(index: number): string {
  return "33333333-3333-3333-3333-"
    + String(index).padStart(12, "0");
}
```

新增：

```typescript
it("exposes the twenty-first system through the existing page API", async () => {
  const firstTwenty = Array.from({ length: 20 }, (_, index) =>
    summaryFixture(vehicleId(index + 1))
  );
  const twentyFirst = summaryFixture(vehicleId(21));
  onboardApi.listOnboardSystems
    .mockResolvedValueOnce(pageFixture(firstTwenty, 0, 21, 2))
    .mockResolvedValueOnce(pageFixture([twentyFirst], 1, 21, 2));
  onboardApi.getOnboardSystem.mockImplementation(
    async (id: string) => detailFixture(id)
  );

  render(OnboardSystemManagementPage);
  expect(await screen.findByText("共 21 套系统"))
    .toBeInTheDocument();
  await fireEvent.click(
    screen.getByRole("button", { name: "下一页" })
  );

  expect(onboardApi.listOnboardSystems)
    .toHaveBeenLastCalledWith(1, 20);
  expect(await screen.findByRole("button", {
    name: new RegExp(vehicleId(21))
  })).toBeInTheDocument();
  expect(screen.getByText("第 2 / 2 页"))
    .toBeInTheDocument();
});

it("ignores an older refresh response after a newer page response", async () => {
  const firstTwenty = Array.from({ length: 20 }, (_, index) =>
    summaryFixture(vehicleId(index + 1))
  );
  const lateRefresh = deferred<ReturnType<typeof pageFixture>>();
  onboardApi.listOnboardSystems
    .mockResolvedValueOnce(pageFixture(firstTwenty, 0, 21, 2))
    .mockReturnValueOnce(lateRefresh.promise)
    .mockResolvedValueOnce(pageFixture(
      [summaryFixture(vehicleId(21))], 1, 21, 2
    ));

  render(OnboardSystemManagementPage);
  await screen.findByText("共 21 套系统");
  const refresh = screen.getByRole("button", { name: "刷新" });
  const next = screen.getByRole("button", { name: "下一页" });
  refresh.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  next.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  lateRefresh.resolve(pageFixture(firstTwenty, 0, 21, 2));

  expect(await screen.findByText("第 2 / 2 页"))
    .toBeInTheDocument();
  expect(screen.queryByRole("button", {
    name: new RegExp(vehicleId(1))
  })).not.toBeInTheDocument();
});
```

另加：

- `pageChangeClearsAnOpenConfigurationDraft`；
- `readOnlyOperatorCanPageButNeverSeesManageControls`；
- `previousAndNextHaveCorrectFirstAndLastPageDisabledStates`。

- [ ] **步骤 2：运行 R6 RED**

```powershell
npm.cmd --prefix apps/admin-web test -- `
  src/pages/onboard-system-management-page.test.ts
```

预期 RED：页面没有“上一页/下一页”，固定调用 `listOnboardSystems(0,20)`，并把 `systems.length` 显示成总数。

- [ ] **步骤 3：实现带请求隔离的分页状态**

script state：

```typescript
const currentPage = ref(0);
const pageSize = 20;
const totalElements = ref(0);
const totalPages = ref(0);
let listRequestToken = 0;

async function loadSystems(
  targetPage = currentPage.value
): Promise<void> {
  if (applying.value) return;
  const request = ++listRequestToken;
  loading.value = true;
  pageError.value = "";
  try {
    const page = await listOnboardSystems(targetPage, pageSize);
    if (request !== listRequestToken) return;
    if (page.items.length === 0
        && page.totalPages > 0
        && page.page >= page.totalPages) {
      await loadSystems(page.totalPages - 1);
      return;
    }
    currentPage.value = page.page;
    totalElements.value = page.totalElements;
    totalPages.value = page.totalPages;
    systems.value = page.items;
    const previous = page.items.find(
      (system) => system.vehicleId === selectedVehicleId.value
    );
    const next = previous ?? page.items[0];
    if (next) {
      void selectSystem(next.vehicleId);
    } else {
      selectedVehicleId.value = "";
      detail.value = null;
      draft.value = null;
    }
  } catch {
    if (request !== listRequestToken) return;
    systems.value = [];
    detail.value = null;
    draft.value = null;
    pageError.value =
      "车载系统数据暂时不可用，请稍后重试";
    feedbackStore.error(pageError.value);
  } finally {
    if (request === listRequestToken) {
      loading.value = false;
    }
  }
}

async function changePage(nextPage: number): Promise<void> {
  if (applying.value
      || nextPage < 0
      || nextPage >= totalPages.value
      || nextPage === currentPage.value) {
    return;
  }
  if (draft.value) {
    draft.value = null;
    notice.value = "草稿已失效，请重新开始编辑";
  }
  selectedVehicleId.value = "";
  detail.value = null;
  selectionRequest += 1;
  await loadSystems(nextPage);
}
```

按钮在 loading 时 disabled；`changePage` 仍由 request token 防御同一 tick 的竞态。空页最多回退请求一次。

- [ ] **步骤 4：添加可访问控件与真实总数**

template：

```html
<div class="list-heading">
  <strong>车辆</strong>
  <span>共 {{ totalElements }} 套系统</span>
</div>
<nav class="pagination" aria-label="车载系统分页">
  <button
    type="button"
    aria-label="上一页"
    :disabled="loading || applying || currentPage === 0"
    @click="changePage(currentPage - 1)"
  >上一页</button>
  <span aria-live="polite">
    第 {{ totalPages === 0 ? 0 : currentPage + 1 }}
    / {{ totalPages }} 页
  </span>
  <button
    type="button"
    aria-label="下一页"
    :disabled="loading || applying
      || currentPage + 1 >= totalPages"
    @click="changePage(currentPage + 1)"
  >下一页</button>
</nav>
```

分页属于 TERMINAL_READ；只有既有编辑按钮继续受 TERMINAL_MANAGE 控制。

- [ ] **步骤 5：运行 R6 GREEN、typecheck 与 build**

```powershell
npm.cmd --prefix apps/admin-web test -- `
  src/pages/onboard-system-management-page.test.ts `
  src/api/onboard-systems.test.ts `
  src/pages/terminal-management-page.test.ts
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
```

预期：全部 exit 0；21+ item、首尾 disabled、总数、draft invalidation、late response 和 read-only permission 通过。

- [ ] **步骤 6：独立复审 R6**

复审确认：

- 没有后端/search 改动；
- 每次只读取一页 20；
- total 使用 server metadata；
- list/detail 两套 request token 都能挡迟到响应；
- apply 中导航保持锁定；
- read-only 可分页但不可配置；
- 键盘和 aria label 可用。

预期：Critical=0、Important=0。修复后重跑 步骤 5。

- [ ] **步骤 7：提交 R6**

```powershell
$R6Files = @(
  'apps/admin-web/src/pages/OnboardSystemManagementPage.vue',
  'apps/admin-web/src/pages/onboard-system-management-page.test.ts'
)
git add -- $R6Files
git commit --only -m 'fix: paginate onboard system management' -- $R6Files
```

## 完整回归与全分支复审门禁

这不是第 7 个实施任务；它是 R1～R6 全部提交后的发布前证明。不得在任一任务尚有未提交生产改动时开始。

- [ ] **门禁 1：核对分支、提交、不可变迁移与干净暂存区**

```powershell
git branch --show-current
git status --short
git diff 39494f3e3e459a7c5ac842f73ad99967baf3119b..HEAD -- `
  apps/api/src/main/resources/db/migration/V19__add_composite_onboard_system_model.sql `
  apps/api/src/main/resources/db/migration/V20__replace_single_terminal_vehicle_binding_constraint.sql
git log --oneline -6
```

预期：正确分支；status clean；V19/V20 diff 空；最近 6 个提交依次对应 R1～R6。

- [ ] **门禁 2：运行新鲜的外部 PostgreSQL 迁移套件**

```powershell
docker run --rm --name drt-p6-2-remediation-final-pg `
  -e POSTGRES_USER=remediation_final `
  -e POSTGRES_PASSWORD=remediation-final-test-only `
  -e POSTGRES_DB=p6_2_remediation_final `
  -p 55442:5432 `
  -d postgis/postgis:16-3.5

$ExternalStartedAt = [DateTimeOffset]::UtcNow
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=P6CompositeOnboardSystemMigrationTest,DatabaseMigrationTest' `
  '-Ddrt.integration.composite-onboard=true' `
  '-Ddrt.integration.composite-onboard.external-ephemeral=true' `
  '-Ddrt.integration.composite-onboard.jdbc-url=jdbc:postgresql://127.0.0.1:55442/p6_2_remediation_final' `
  '-Ddrt.integration.composite-onboard.username=remediation_final' `
  '-Ddrt.integration.composite-onboard.password=remediation-final-test-only' `
  "-Djava.io.tmpdir=$MavenTemp" test
if ($LASTEXITCODE -ne 0) {
    throw "EXTERNAL_MIGRATION_REGRESSION_FAILED=$LASTEXITCODE"
}
```

预期：exit 0，两个 suite 的新鲜 report failure/error=0，V18→V19→V20→V21 实际执行。记录修复后实际 test 数，
不复用旧 57。

- [ ] **门禁 3：串行运行完整 Java reactor**

```powershell
$JavaStartedAt = [DateTimeOffset]::UtcNow
& $Maven @MavenBase `
  -pl libs/jt-protocol,apps/jt-gateway,apps/api,tools/jt-terminal-simulator `
  "-Djava.io.tmpdir=$MavenTemp" test
if ($LASTEXITCODE -ne 0) {
    throw "JAVA_FULL_REGRESSION_FAILED=$LASTEXITCODE"
}
```

机械汇总只接受本轮之后写入的 XML：

```powershell
$ReportRoots = @(
  'libs\jt-protocol\target\surefire-reports',
  'apps\jt-gateway\target\surefire-reports',
  'apps\api\target\surefire-reports',
  'tools\jt-terminal-simulator\target\surefire-reports'
)
$Reports = $ReportRoots |
  ForEach-Object {
    Get-ChildItem -LiteralPath $_ -Filter 'TEST-*.xml'
  } |
  Where-Object {
    $_.LastWriteTimeUtc -ge $JavaStartedAt.UtcDateTime
  }
if (-not $Reports) {
    throw 'NO_FRESH_JAVA_REPORTS'
}
$Suites = $Reports | ForEach-Object {
  [xml](Get-Content -Raw -LiteralPath $_.FullName)
}
$JavaTests = ($Suites | ForEach-Object {
  [int]$_.testsuite.tests
} | Measure-Object -Sum).Sum
$JavaFailures = ($Suites | ForEach-Object {
  [int]$_.testsuite.failures
} | Measure-Object -Sum).Sum
$JavaErrors = ($Suites | ForEach-Object {
  [int]$_.testsuite.errors
} | Measure-Object -Sum).Sum
if ($JavaFailures -ne 0 -or $JavaErrors -ne 0) {
    throw "JAVA_RESULT_INVALID=$JavaTests/$JavaFailures/$JavaErrors"
}
Write-Output (
  "JAVA_FRESH_TESTS=$JavaTests "
  + "FAILURES=$JavaFailures ERRORS=$JavaErrors"
)
```

预期：修复后所有发现的 Java tests 均有本轮 report，failure/error=0；实际总数写入复审包。

- [ ] **门禁 4：运行完整前端门禁**

```powershell
npm.cmd --prefix apps/admin-web test
if ($LASTEXITCODE -ne 0) {
    throw 'FRONTEND_TEST_FAILED'
}
npm.cmd --prefix apps/admin-web run typecheck
if ($LASTEXITCODE -ne 0) {
    throw 'FRONTEND_TYPECHECK_FAILED'
}
npm.cmd --prefix apps/admin-web run build
if ($LASTEXITCODE -ne 0) {
    throw 'FRONTEND_BUILD_FAILED'
}
```

预期：全部 exit 0；记录修复后实际 files/tests/modules，旧 304 不能替代。

- [ ] **门禁 5：单独运行既有 private 脚本测试**

只运行既有测试入口，不运行 cloud Apply、真实连接或历史 audit 处置：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\cloud-deployment\p6-2-cloud-7fa38d0\cloud-onboard-system-migration-tests.ps1'
if ($LASTEXITCODE -ne 0) {
    throw 'PRIVATE_MIGRATION_TESTS_FAILED'
}

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\task12-local-acceptance\task12-local-acceptance-tests.ps1'
if ($LASTEXITCODE -ne 0) {
    throw 'PRIVATE_ACCEPTANCE_TESTS_FAILED'
}
```

预期：两组都以本轮实际数 exit 0；输出扫描无真实 identity、credential、private absolute path 或 metadata value。

- [ ] **门禁 6：运行最终静态与范围检查**

```powershell
git diff --check 39494f3e3e459a7c5ac842f73ad99967baf3119b..HEAD
git status --short
git diff --name-only 39494f3e3e459a7c5ac842f73ad99967baf3119b..HEAD
```

预期：diff check 0；status clean；只有 R1～R6 文件清单内的实现/测试文件和本计划，不含 private、V19/V20、
cloud/real acceptance 或附件/媒体/full GB 实现。

- [ ] **门禁 7：独立执行全分支复审**

复审 BASE=`39494f3e3e459a7c5ac842f73ad99967baf3119b`，HEAD=R6 commit。审阅者必须：

1. 逐条重跑 I-1～I-7 的数据流，而不是只看测试名；
2. 核对 V21 additive、V19/V20 checksum 和 rollback 边界；
3. 核对 lock order、lease fencing、API Clock、event-time alarm auth；
4. 核对 location ±29 秒、late bad quality、configuration concurrency；
5. 核对 new audit allowlist 和历史零清洗；
6. 核对 UI 21+、竞态和 TERMINAL_READ；
7. 读取本轮 external/Java/frontend/private 新鲜 report；
8. 明确输出 verdict、Critical/Important/Minor、残余风险和不授权事项。

通过条件：`APPROVED`，Critical=0、Important=0；任何新 Important 返回对应任务 RED→GREEN，不用文档声明代替代码闭环。

- [ ] **门禁 8：停止最终一次性数据库**

```powershell
docker stop drt-p6-2-remediation-final-pg
```

预期：精确测试容器被删除；不触碰其他容器、volume 或网络。若 门禁 2～7 中途失败，也必须在保存失败证据后执行本
门禁。

## 计划自检结果

### 规格覆盖

| 规格/审阅要求 | 任务 | 结果 |
| --- | --- | --- |
| 协议档案是 decoder 唯一选择依据；不按 payload 猜测 | R2 | v2 协议档案合同 + 传输协议门禁 + decoder 映射 |
| capability 与 role 分离，只允许 VERIFIED 业务范围 | R2 | session 交集 + API 报警二次授权 |
| 同车独立 physical session，不互相冒充在线 | R3 | 按 terminal 键控且带 fencing 的 lease |
| 调度准入要求当前鉴权与当前权威位置 | R3、R4 | 当前 lease + system/terminal/event 来源 |
| 主源 staleness=max(30s,2×interval) | R4 | 仅使用 gateway time 的阈值测试 |
| 三条恢复、迟到事件不能改变权威状态 | R4 | 每个主源的 terminal cursor + 先时序后质量 |
| configuration expectedVersion 与 runtime version 分离 | R4 | 同事务协调；runtime event 不推进 config version |
| 角色/成员改变后 runtime 可自愈且合法源不抖动 | R4 | 集中协调 |
| audit 不保存 terminal/phone/plate/auth 原文 | R5 | 精确 metadata 白名单 + 禁止值测试 |
| 管理端聚合和全部车辆可达 | R6 | 既有分页 API + 可访问控件 |
| V19/V20 历史和回滚安全 | R1、最终门禁 | additive V21 + 不可变迁移检查 |
| 附件、媒体、full GB 消息保持非目标 | 全局约束、最终门禁 | 无相关生产文件 |
| cloud/真实设备/真实流量需要另行授权 | 全局约束、最终门禁 | 本计划不执行 |

覆盖裁决：I-1～I-7 全部映射到一个且仅一个主实施任务；I-1/I-2 和 I-4/I-5 分别以纵向门禁合并。没有把
deferred attachment/media/cloud 项混入。

### 共享文件与接口冲突复核

| 共享文件/接口 | 首次归属任务 | 后续归属任务 | 强制顺序 |
| --- | --- | --- | --- |
| V21 migration | R1 | 无 | R1 提交后 checksum 冻结 |
| TerminalSessionContext v2 | R2 | R3 仅向 AuthenticationDecision 添加 lease | R2→R3 |
| OnboardRegistrationResolver | R2 协议档案权威 | R3 获取 lease | R2→R3 |
| OnboardReadinessService | R2 可解码协议档案 | R3 当前 lease；R4 快照来源 | R2→R3→R4 |
| OnboardSystemConfigurationService | R2 协议档案不变量 | R3 DeviceView lease；R4 运行态协调 | R2→R3→R4 |
| TerminalManagementService/Controller | R2 车载系统成员视图 | R3 lease 状态；R5 安全审计 | R2→R3→R5 |
| gateway handler/client/session | R2 合同/传输协议 | R3 lease 生命周期 | R2→R3 |
| CompositeOnboardEndToEndTest | R2 协议档案→报警 | R4 位置/配置扩展 | R2→R4 |
| OnboardSystemManagementPage | R3 当前鉴权文案 | R6 分页 | R3→R6 |

冲突裁决：没有两个可并行任务修改同一共享文件；Subagent-Driven 连续执行顺序已固定。

### 类型与签名一致性

| 类型/方法 | 定义任务 | 已核对消费者 |
| --- | --- | --- |
| SessionProtocolProfile | R2 API 与 gateway 对称定义 | resolver、HTTP、client、session、router |
| TerminalSessionContext contractVersion=2 | R2 | RegistrationDecision、AuthenticationDecision、R3 auth |
| CanonicalVehicleAlarm.onboardSystemId | R2 | ProtocolModuleRegistry、GatewayIngressRouter、AlarmFact |
| AlarmFact.onboardSystemId | R2 | VehicleAlarmIngressService、AlarmStore、VehicleAlarm |
| LocationReference(eventId,onboardSystemId,recordedAt,quality...) | R2 | JpaAlarmStore、VehicleAlarm |
| SessionLeaseOwner/Grant/ReleaseResult | R3 API/gateway 对称嵌套 records | service、controller、client、reporter、session |
| AuthenticationDecision(approved,context,lease,reasonCode) | R3 | API controller、gateway client、handler |
| ArbitrationState 分离时钟 | R4 | GpsLocationIngressService、arbitrator tests |
| LocationSourceDecision 分离后的 next cursor | R4 | runtime apply、integration tests |
| CurrentOnboardMembershipSummary | R2 | TerminalDetail/View、TerminalManagementPage |
| OnboardSystemPage | 既有 | 仅 R6；后端/API client 不变 |

类型裁决：字段名、时钟类型和 owner tuple 在生产者/消费者间一致；R3 不重定义 R2 context，R4 不重定义 lease。

### 占位符扫描

结果：通过，0 个占位或泛化实现步骤。每个 RED 都有测试名和真实失败原因；每个生产步骤都有具体类型、字段、方法、
SQL/DTO 或状态转换；每个任务都有聚焦命令、回归、独立复审和提交命令。

### 路径存在性复核

结果：

- 所有 Modify/Test 路径在基线 worktree 中存在；
- R1/R3 标记为 Create 的 6 个生产/测试路径在基线中不存在，且父目录存在；
- Spec 和 supplemental review authority 可读；
- 计划目标在创建前不存在；
- 唯一允许的 filesystem absolute path 只出现在计划前言的 supplemental review authority；
- 本计划没有 private value、外部 host、真实 identity、credential 或数据库目标。

### 最终计划裁决

通过。计划满足 6 个任务、固定依赖、严格 TDD、V21 一次冻结、逐任务 review/commit、完整新鲜回归和独立
whole-branch 复审。执行模式固定为 Subagent-Driven 连续执行，不在计划末尾再次请求选择。
