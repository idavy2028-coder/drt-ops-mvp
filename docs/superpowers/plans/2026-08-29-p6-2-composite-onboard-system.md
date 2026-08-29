# P6-2 Composite Onboard System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有“一车一终端”模型升级为“一车一个逻辑车载系统、系统下多个独立物理设备”，支持能力角色、双 TCP 会话、调度终端主定位、行车记录仪备定位及单设备降级。

**Architecture:** 保留 `jt_terminals` 作为物理设备身份，新增 `OnboardSystem` 聚合、设备成员、能力、协议档案和角色历史。API 负责聚合与角色约束，gateway 继续按物理设备鉴权并携带车载系统上下文，位置域保存双源不可变事件并通过有状态仲裁器更新唯一车辆快照。

**Tech Stack:** Java 21、Spring Boot、Spring Data JPA、Flyway、PostgreSQL/PostGIS 16、Netty、JUnit 5、AssertJ、MockMvc、Vue 3、TypeScript、Vitest、Playwright、PowerShell 5.1、Docker Compose。

**Spec:** `docs/superpowers/specs/2026-08-29-p6-2-composite-onboard-system-design.md`

## Global Constraints

- 实施前必须完整阅读本计划和关联规格；如冲突，以规格为准。
- 执行时必须先使用 `superpowers:using-git-worktrees` 创建隔离 worktree。
- 当前 `feat/jt-gateway-deployment` worktree 已有 51 项既有改动；未形成已审阅提交前禁止开始 Task 1。
- 不得 stash、reset、checkout 或覆盖既有未提交改动。
- 每台物理设备保留独立终端手机号语义身份、终端 ID、鉴权码、令牌版本和 TCP 会话。
- 同车设备允许共享 `vehicle_identifier`；终端手机号语义身份和终端 ID 继续全局唯一。
- 来源 IP、端口、NAT 和 SIM 所在设备不得参与身份判断。
- 双设备默认调度终端为 `LOCATION_PRIMARY`，行车记录仪为 `LOCATION_BACKUP`。
- 主源失效阈值固定为 `max(30 秒, 2 × 预期上报间隔)`；主源恢复连续 3 条有效位置后才切回。
- `GOOD/WARNING` 可更新快照；`QUARANTINED/REJECTED` 不得成为权威位置。
- 无已验证 `DISPATCH` 能力的车辆不得进入自动调度车池。
- 附件、媒体服务和完整 GB/T 28787 `0x0Bxx/0x8Bxx` 业务消息不在本计划实现。
- 不输出真实终端手机号、终端 ID、车牌、鉴权码、服务凭证或摘要。
- Java 测试串行运行；不得并行执行 Maven。
- Windows 测试必须同时设置 `TEMP`、`TMP` 和 `-Djava.io.tmpdir` 到当前 worktree 的 `.tmp/maven`。
- 每个任务独立 RED→GREEN→回归→复核→提交；复核未通过不得开始下一任务。
- 提交前确认暂存区只包含当前任务文件；禁止把历史脏改动带入提交。
- Java 完整回归总数不得低于 701，失败和错误均为 0；前端和私有脚本另行计数。
- gateway 在迁移、dry-run、Apply 和 V20 收口期间保持停止，`7611` 无监听。

## Execution Environment

在每个 Java 任务开始前执行：

```powershell
$Repo = (Get-Location).Path
$Maven = 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd'
$MavenBase = @('-q', '-Dsurefire.failIfNoSpecifiedTests=false')
$MavenTemp = Join-Path $Repo '.tmp\maven'
New-Item -ItemType Directory -Force -Path $MavenTemp | Out-Null
$env:TEMP = $MavenTemp
$env:TMP = $MavenTemp
if (-not (Test-Path -LiteralPath (Join-Path $Repo 'pom.xml'))) {
    throw 'EXECUTION_WORKTREE_ROOT_INVALID'
}
```

先进入 `using-git-worktrees` 实际创建并验证的 worktree 根目录，再执行上述代码；不要在当前脏 worktree 中运行实施任务。

## File Structure

新增 API 包 `com.idavy.drtops.domain.onboard`：

- `OnboardSystem.java`：配置聚合状态、运营模式和管理员配置版本。
- `OnboardSystemRuntimeState.java`：权威位置源、恢复计数和活动警告，不推进管理员配置版本。
- `OnboardDeviceMembership.java`：物理终端加入/移出车载系统的历史。
- `OnboardDeviceCapability.java`：声明、验证和禁用能力事实。
- `OnboardDeviceProtocolProfile.java`：四类协议档案和位置上报间隔。
- `OnboardDeviceRoleAssignment.java`：排他角色历史。
- `OnboardSystemRepository.java` 及其余仓储：聚合锁、活动关系和角色查询。
- `OnboardSystemConfigurationService.java`：desired-state preview/apply、角色和能力约束。
- `OnboardSystemController.java`：管理端聚合 API。
- `OnboardRegistrationResolver.java`：注册与鉴权时解析设备、系统、车辆、角色和警告。
- `OnboardReadinessService.java`：调度准入和管理端分维度状态。

位置域新增：

- `LocationSourceArbitrator.java`：纯业务仲裁规则。
- `LocationSourceDecision.java`：是否应用快照、是否切换及原因。

管理端新增：

- `src/api/onboardSystems.ts`：聚合查询和 desired-state 管理请求。
- `src/pages/OnboardSystemManagementPage.vue`：聚合卡、设备明细和受控操作。

---

### Task 0: Existing P6-2 Baseline Gate

**Files:**
- Read: `progress.md`
- Read: `docs/superpowers/specs/2026-08-29-p6-2-composite-onboard-system-design.md`
- Read: current `git status` and existing P6-2 diffs

**Interfaces:**
- Consumes: current `feat/jt-gateway-deployment` branch and all previously approved P6-2 work.
- Produces: a clean, committed baseline SHA from which the isolated implementation worktree can be created.

- [ ] **Step 1: Verify the current baseline state**

```powershell
git worktree list --porcelain
git branch --show-current
git rev-parse --short HEAD
git status --short
git diff --cached --name-only
Get-Content -LiteralPath 'progress.md' -Tail 260
```

Expected: branch is `feat/jt-gateway-deployment`; staged count is zero; all 51 existing entries are identified as pre-plan work.

- [ ] **Step 2: Stop if the baseline is still dirty**

```powershell
$dirty = @(git status --porcelain)
if ($dirty.Count -ne 0) {
    throw "COMPOSITE_ONBOARD_BASELINE_NOT_COMMITTED=$($dirty.Count)"
}
```

Expected today: FAIL with `COMPOSITE_ONBOARD_BASELINE_NOT_COMMITTED=51`. This is an intentional hard gate, not a defect in the new plan.

- [ ] **Step 3: Obtain review and a committed baseline**

Do not create a catch-all commit automatically. Complete the outstanding P6-2 review/real-validation decision, then commit the existing changes under their original approved scopes. Re-run Step 2 until it prints no exception.

- [ ] **Step 4: Create and verify an isolated worktree**

Invoke `superpowers:using-git-worktrees`. In the resulting worktree run:

```powershell
git branch --show-current
git status --short
git merge-base --is-ancestor 5da8486 HEAD
```

Expected: a non-detached feature branch, empty status, and merge-base exit code 0. No new commit is created by Task 0.

---

### Task 1: V19 Expand Schema and JPA Aggregate Foundation

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V19__add_composite_onboard_system_model.sql`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystem.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeState.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardDeviceMembership.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardDeviceCapability.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardDeviceProtocolProfile.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardDeviceRoleAssignment.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeStateRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardDeviceMembershipRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardDeviceCapabilityRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardDeviceProtocolProfileRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardDeviceRoleAssignmentRepository.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java`

**Interfaces:**
- Consumes: V18 schema, `vehicles.id`, `jt_terminals.id`, and `jt_terminal_vehicle_bindings` history.
- Produces: entities/repositories used by Tasks 2–8.

- [ ] **Step 1: Write failing PostgreSQL migration tests**

```java
@Test
void v19BackfillsOneActiveSystemAndMembershipPerLegacyBinding() throws Exception {
    ExternalPostgres postgres = externalPostgres();
    String schema = schema("v19_backfill");
    flyway(postgres, schema, "18").migrate();
    LegacyIds ids = insertVehicleTerminalAndActiveBinding(postgres, schema);
    flyway(postgres, schema, "19").migrate();
    try (Connection connection = connection(postgres, schema)) {
        assertThat(queryCount(connection,
                "select count(*) from onboard_systems where vehicle_id = '" + ids.vehicleId() + "'"))
                .isEqualTo(1);
        assertThat(queryCount(connection,
                "select count(*) from onboard_device_memberships where terminal_id = '" + ids.terminalId()
                        + "' and status = 'ACTIVE'"))
                .isEqualTo(1);
        assertThat(queryCount(connection,
                "select count(*) from jt_terminal_vehicle_bindings where id = '" + ids.bindingId() + "'"))
                .isEqualTo(1);
    }
}

@Test
void v19AllowsTwoDifferentTerminalsInOneSystemButRejectsOneTerminalInTwoSystems() throws Exception {
    ExternalPostgres postgres = externalPostgres();
    String schema = schema("v19_membership");
    flyway(postgres, schema, "19").migrate();
    MembershipFixture fixture = insertTwoVehiclesAndTwoTerminals(postgres, schema);
    insertMembership(postgres, schema, fixture.systemOne(), fixture.terminalOne());
    insertMembership(postgres, schema, fixture.systemOne(), fixture.terminalTwo());
    assertThatThrownBy(() -> insertMembership(
            postgres, schema, fixture.systemTwo(), fixture.terminalTwo()))
            .hasMessageContaining("uq_onboard_device_memberships_active_terminal");
}

private record LegacyIds(UUID vehicleId, UUID terminalId, UUID bindingId) { }
private record MembershipFixture(
        UUID systemOne, UUID systemTwo, UUID terminalOne, UUID terminalTwo) { }
```

Use integration property prefix `drt.integration.composite-onboard`, loopback database `composite_onboard`, and username `composite`. Preserve the exact loopback and empty-database gates used by `P6TerminalPhoneIdentityMigrationTest`.

- [ ] **Step 2: Run RED against disposable PostgreSQL**

```powershell
docker run --rm --name drt-composite-onboard-pg `
  -e POSTGRES_USER=composite `
  -e POSTGRES_PASSWORD=composite-test-only `
  -e POSTGRES_DB=composite_onboard `
  -p 55439:5432 `
  -d postgis/postgis:16-3.5
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=P6CompositeOnboardSystemMigrationTest' `
  '-Ddrt.integration.composite-onboard=true' `
  '-Ddrt.integration.composite-onboard.external-ephemeral=true' `
  '-Ddrt.integration.composite-onboard.jdbc-url=jdbc:postgresql://127.0.0.1:55439/composite_onboard' `
  '-Ddrt.integration.composite-onboard.username=composite' `
  '-Ddrt.integration.composite-onboard.password=composite-test-only' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: FAIL because migration V19 and onboard tables do not exist.

- [ ] **Step 3: Add V19 schema**

```sql
CREATE TABLE onboard_systems (
  id UUID PRIMARY KEY,
  vehicle_id UUID NOT NULL REFERENCES vehicles(id),
  status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','RETIRED')),
  operating_mode VARCHAR(30) NOT NULL CHECK (operating_mode IN ('DISPATCH_SERVICE','SAFETY_MONITOR_ONLY')),
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_onboard_systems_active_vehicle
  ON onboard_systems(vehicle_id)
  WHERE status = 'ACTIVE';

CREATE TABLE onboard_system_runtime_state (
  onboard_system_id UUID PRIMARY KEY REFERENCES onboard_systems(id),
  active_location_terminal_id UUID REFERENCES jt_terminals(id),
  primary_recovery_streak INTEGER NOT NULL DEFAULT 0 CHECK (primary_recovery_streak >= 0),
  primary_eligible BOOLEAN NOT NULL DEFAULT TRUE,
  last_primary_valid_at TIMESTAMPTZ,
  last_location_switch_at TIMESTAMPTZ,
  warning_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
  runtime_version BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Create the child tables with these exact business columns:

```text
onboard_device_memberships:
  id, onboard_system_id, terminal_id, network_mode, status,
  valid_from, valid_to, added_reason, removed_reason,
  added_by, removed_by, created_at, updated_at, version

onboard_device_capabilities:
  id, terminal_id, capability, status, evidence_ref,
  verified_at, verified_by, reason, created_at, updated_at, version

onboard_device_protocol_profiles:
  id, terminal_id, transport_profile, business_profile,
  safety_profile, media_profile, active_position_interval_seconds,
  idle_position_interval_seconds, status, valid_from, valid_to,
  reason, actor_id, created_at, updated_at, version

onboard_device_role_assignments:
  id, onboard_system_id, terminal_id, role, status,
  valid_from, valid_to, assigned_reason, revoked_reason,
  assigned_by, revoked_by, created_at, updated_at, version
```

Use checks for every enum, positive reporting intervals, active-row `valid_to IS NULL`, and closed-row `valid_to IS NOT NULL`. Add partial unique indexes for active terminal membership, active protocol profile per terminal, active capability per `(terminal_id, capability)`, and active `(onboard_system_id, role)`. Add `onboard_system_id` plus `source_role` to `vehicle_location_events`, and `current_location_onboard_system_id` to `vehicles`. Backfill one system/membership for every legacy active binding using `gen_random_uuid()`; retain every legacy binding row.

- [ ] **Step 4: Add focused JPA entities and repositories**

```java
public enum Status { ACTIVE, SUSPENDED, RETIRED }
public enum OperatingMode { DISPATCH_SERVICE, SAFETY_MONITOR_ONLY }
public enum Role { DISPATCH, LOCATION_PRIMARY, LOCATION_BACKUP, ACTIVE_SAFETY, VIDEO, WAN_UPLINK }
public enum Capability { JT808_LOCATION, GBT28787_DISPATCH, VENDOR_DISPATCH, ADAS, DMS, VIDEO, JT1078_MEDIA }
public enum CapabilityStatus { DECLARED, VERIFIED, DISABLED }
public enum NetworkMode { DIRECT_CELLULAR, SHARED_LAN_CLIENT }
```

`OnboardSystemRuntimeState` exposes runtime methods rather than public setters:

```java
public void selectLocationSource(UUID terminalId, OffsetDateTime switchedAt) {
    this.activeLocationTerminalId = Objects.requireNonNull(terminalId, "terminalId");
    this.primaryRecoveryStreak = 0;
    this.lastLocationSwitchAt = Objects.requireNonNull(switchedAt, "switchedAt");
    this.updatedAt = switchedAt;
}

public int recordPrimaryRecovery() {
    return ++primaryRecoveryStreak;
}
```

The configuration `OnboardSystem.version` changes only for status/mode/member/profile/role configuration. Runtime source switches and warning changes use `runtimeVersion`, preventing operational events from invalidating an administrator's `expectedVersion`.

- [ ] **Step 5: Run GREEN migration and JPA smoke tests**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=P6CompositeOnboardSystemMigrationTest,DatabaseMigrationTest' `
  '-Ddrt.integration.composite-onboard=true' `
  '-Ddrt.integration.composite-onboard.external-ephemeral=true' `
  '-Ddrt.integration.composite-onboard.jdbc-url=jdbc:postgresql://127.0.0.1:55439/composite_onboard' `
  '-Ddrt.integration.composite-onboard.username=composite' `
  '-Ddrt.integration.composite-onboard.password=composite-test-only' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```powershell
git add apps/api/src/main/resources/db/migration/V19__add_composite_onboard_system_model.sql `
  apps/api/src/main/java/com/idavy/drtops/domain/onboard `
  apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java `
  apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java
git commit --only -m 'feat: add composite onboard system schema' -- `
  apps/api/src/main/resources/db/migration/V19__add_composite_onboard_system_model.sql `
  apps/api/src/main/java/com/idavy/drtops/domain/onboard `
  apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java `
  apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java
```

---

### Task 2: Desired-State Configuration and Management API

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardConfigurationConflictException.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardTestFixtures.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardSystemConfigurationServiceTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardSystemApiTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java`

**Interfaces:**
- Consumes: Task 1 entities/repositories and existing `JtTerminal` identities.
- Produces: atomic preview/apply and capability verification APIs consumed by Tasks 3, 7, 9 and 11.

`OnboardTestFixtures` is the shared test-only builder for Tasks 2, 3 and 7. It exposes exact methods `activeSystem(OperatingMode)`, `configureDualDeviceSystem(String,String,String)`, `configureRecorderSystem(String,String)`, `verifyDispatchAndLocation(String)`, `verifySafetyVideoAndLocation(String)`, `recorderOnlyVehicleId()`, `dispatchSystemWithoutAuthenticationVehicleId()`, `dispatchSystemWithoutLocationVehicleId()` and `readyDispatchSystemVehicleId()`.

- [ ] **Step 1: Write failing role-invariant tests**

```java
@Test
void rejectsSecondExclusiveRoleAndRoleWithoutVerifiedCapability() {
    OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
    fixtures.addVerifiedCapability(system, "dispatch-01", Capability.GBT28787_DISPATCH);
    ConfigurationCommand command = command(system.getVersion(), List.of(
            device("dispatch-01", Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY)),
            device("recorder-01", Set.of(Role.DISPATCH, Role.LOCATION_BACKUP))));
    assertThatThrownBy(() -> service.preview(system.getVehicleId(), command))
            .isInstanceOf(OnboardConfigurationConflictException.class)
            .hasMessageContaining("EXCLUSIVE_ROLE_CONFLICT:DISPATCH");
}

@Test
void acceptsDispatchPrimaryAndRecorderBackupInOneSystem() {
    OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
    fixtures.verifyDispatchAndLocation("dispatch-01");
    fixtures.verifySafetyVideoAndLocation("recorder-01");
    ConfigurationPreview preview = service.preview(system.getVehicleId(), command(
            system.getVersion(), List.of(
                    device("dispatch-01", Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY, Role.WAN_UPLINK)),
                    device("recorder-01", Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO)))));
    assertThat(preview.changedFields()).containsExactly(
            "devices", "protocolProfiles", "roles", "wanUplink");
}
```

- [ ] **Step 2: Run RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardSystemConfigurationServiceTest,OnboardSystemApiTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: FAIL because service/controller do not exist.

- [ ] **Step 3: Define desired-state contracts**

```java
public record ConfigurationCommand(
        long expectedVersion,
        OnboardSystem.OperatingMode operatingMode,
        List<DeviceConfiguration> devices,
        String reason) { }

public record DeviceConfiguration(
        String terminalCode,
        OnboardDeviceMembership.NetworkMode networkMode,
        Set<OnboardDeviceRoleAssignment.Role> roles,
        ProtocolProfiles protocolProfiles) { }

public record ProtocolProfiles(
        String transportProfile,
        String businessProfile,
        String safetyProfile,
        String mediaProfile,
        int activePositionIntervalSeconds,
        int idlePositionIntervalSeconds) { }

public record ConfigurationPreview(
        UUID onboardSystemId,
        UUID vehicleId,
        long currentVersion,
        List<String> changedFields,
        List<String> warnings) { }
```

Expose:

```text
GET  /api/onboard-systems
GET  /api/onboard-systems/{vehicleId}
POST /api/onboard-systems/{vehicleId}/configuration/preview
POST /api/onboard-systems/{vehicleId}/configuration
POST /api/terminals/{terminalCode}/capability-verifications
```

- [ ] **Step 4: Implement transactional preview/apply**

Lock `OnboardSystem`, compare `expectedVersion`, validate all device capabilities and roles as one desired state, close removed history rows, append new rows, update profiles/network mode, and write safe audits. `preview` runs identical validation read-only.

Keep `/api/terminals/{terminalCode}/bind` as a V19 compatibility adapter that delegates to this service; it must not implement a second set of role rules.

- [ ] **Step 5: Add security and API tests**

```java
.requestMatchers(HttpMethod.GET, "/api/onboard-systems/**")
    .hasAuthority("TERMINAL_READ")
.requestMatchers("/api/onboard-systems/**")
    .hasAuthority("TERMINAL_MANAGE")
```

Assert 403 without manage authority, 409 for stale version, masked identity, and no evidence body in responses/audit metadata.

- [ ] **Step 6: Run GREEN**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardSystemConfigurationServiceTest,OnboardSystemApiTest,TerminalManagementServiceTest,TerminalApiTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 2**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/onboard `
  apps/api/src/test/java/com/idavy/drtops/domain/onboard `
  apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java
git commit --only -m 'feat: manage onboard systems by verified device roles' -- `
  apps/api/src/main/java/com/idavy/drtops/domain/onboard `
  apps/api/src/test/java/com/idavy/drtops/domain/onboard `
  apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java
```

---

### Task 3: Registration and Cross-Connection Authentication Context

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolver.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolverTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/GatewayRegistryController.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalRepository.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeState.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalApiTest.java`

**Interfaces:**
- Consumes: Task 2 aggregate configuration and V18 phone identity.
- Produces: physical-device registration decision and `TerminalSessionContext` for Task 4.

- [ ] **Step 1: Write failing identity tests**

```java
@Test
void allowsTwoDeviceIdentitiesToShareTheBoundVehicleIdentifier() {
    fixtures.configureDualDeviceSystem("dispatch-01", "recorder-01", "VEHICLE-A");
    RegistrationDecision dispatch = resolver.verify(registration(
            "dispatch-01", "PHONE-DISPATCH", "VEHICLE-A"));
    RegistrationDecision recorder = resolver.verify(registration(
            "recorder-01", "PHONE-RECORDER", "VEHICLE-A"));
    assertThat(dispatch.approved()).isTrue();
    assertThat(recorder.approved()).isTrue();
    assertThat(dispatch.context().onboardSystemId())
            .isEqualTo(recorder.context().onboardSystemId());
    assertThat(dispatch.context().terminalId())
            .isNotEqualTo(recorder.context().terminalId());
}

@Test
void warnsOnUnknownPlateMismatchAndRejectsAnotherKnownVehiclePlate() {
    fixtures.configureRecorderSystem("recorder-01", "VEHICLE-A");
    fixtures.createVehicle("VEHICLE-B");
    RegistrationDecision warning = resolver.verify(registration(
            "recorder-01", "PHONE-RECORDER", "UNASSIGNED-PLATE"));
    RegistrationDecision conflict = resolver.verify(registration(
            "recorder-01", "PHONE-RECORDER", "VEHICLE-B"));
    assertThat(warning.approved()).isTrue();
    assertThat(warning.warnings()).containsExactly("VEHICLE_IDENTIFIER_MISMATCH");
    assertThat(conflict.approved()).isFalse();
    assertThat(conflict.reasonCode()).isEqualTo("VEHICLE_IDENTIFIER_CONFLICT");
}
```

- [ ] **Step 2: Run RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardRegistrationResolverTest,TerminalApiTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: FAIL because registration still assumes one terminal↔vehicle binding.

- [ ] **Step 3: Implement shared context records**

```java
public record TerminalSessionContext(
        UUID terminalId,
        UUID onboardSystemId,
        UUID vehicleId,
        Set<OnboardDeviceRoleAssignment.Role> roles,
        String sourceCoordinateSystem,
        String activeSafetyStandard,
        List<String> activeSafetyModules,
        int tokenVersion) { }

public record RegistrationDecision(
        boolean approved,
        TerminalSessionContext context,
        List<String> warnings,
        String reasonCode) { }

public record AuthenticationDecision(
        boolean approved,
        TerminalSessionContext context,
        String reasonCode) { }
```

Resolve the device by normalized phone identity and terminal code. Resolve vehicle only through active membership, then apply auxiliary `vehicle_identifier` rules.

On `APPROVED_WITH_WARNING`, add `VEHICLE_IDENTIFIER_MISMATCH` to runtime `warningCodes` without changing administrator configuration version. A later matching registration removes that warning. A cross-vehicle conflict never changes runtime warnings because registration is rejected.

- [ ] **Step 4: Add identity-based authentication endpoint**

```text
POST /internal/jt-gateway/authentications/verify-by-identity
```

Request: `protocolVersion`, `terminalPhone`, `tokenSha256`, `gatewayInstance`. Success returns `TerminalSessionContext`; rejection returns no UUID. Reuse the locked auth core and update `last_authenticated_at` only on success.

- [ ] **Step 5: Run GREEN**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardRegistrationResolverTest,TerminalApiTest,TerminalManagementServiceTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: PASS; responses contain no raw token or complete phone.

- [ ] **Step 6: Commit Task 3**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolver.java `
  apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolverTest.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/GatewayRegistryController.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalRepository.java `
  apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeState.java `
  apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalApiTest.java
git commit --only -m 'feat: resolve terminal sessions through onboard membership' -- `
  apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolver.java `
  apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardRegistrationResolverTest.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/GatewayRegistryController.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalRepository.java `
  apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeState.java `
  apps/api/src/test/java/com/idavy/drtops/domain/terminal/TerminalApiTest.java
```

---

### Task 4: Gateway Session Context and Two Independent Connections Per Vehicle

**Files:**
- Create: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSessionContext.java`
- Modify: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalRegistryPort.java`
- Modify: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/RegistrationDecision.java`
- Modify: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/AuthenticationDecision.java`
- Modify: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClient.java`
- Modify: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/TerminalSession.java`
- Modify: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandler.java`
- Modify: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/OperationsTerminalRegistryClientTest.java`
- Modify: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session/RegistrationAuthenticationHandlerTest.java`
- Modify: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/netty/JtGatewayServerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 JSON contract.
- Produces: authenticated `TerminalSession` with terminal, onboard system, vehicle and immutable role set.

- [ ] **Step 1: Write failing dual-session and cross-connection tests**

```java
@Test
void twoDifferentTerminalsForOneVehicleRemainAuthenticatedTogether() {
    UUID vehicleId = UUID.randomUUID();
    UUID systemId = UUID.randomUUID();
    TerminalSession dispatch = authenticatedSession(
            UUID.randomUUID(), systemId, vehicleId, Set.of("DISPATCH", "LOCATION_PRIMARY"));
    TerminalSession recorder = authenticatedSession(
            UUID.randomUUID(), systemId, vehicleId, Set.of("LOCATION_BACKUP", "ACTIVE_SAFETY"));
    assertThat(registry.claim(dispatch)).isEmpty();
    assertThat(registry.claim(recorder)).isEmpty();
    assertThat(registry.current(dispatch.terminalId())).contains(dispatch);
    assertThat(registry.current(recorder.terminalId())).contains(recorder);
}

@Test
void authenticationOnASecondConnectionRestoresThePhysicalDeviceContext() {
    EmbeddedChannel channel = unauthenticatedChannel(registryPortApprovingByIdentity());
    assertThat(channel.writeInbound(authenticationFrame("TOKEN-22-CHARACTERS"))).isFalse();
    TerminalSession session = handler(channel).session();
    assertThat(session.state()).isEqualTo(TerminalSessionState.AUTHENTICATED);
    assertThat(session.onboardSystemId()).isEqualTo(ONBOARD_SYSTEM_ID);
    assertThat(session.roles()).containsExactlyInAnyOrder(
            "LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO");
}
```

- [ ] **Step 2: Run RED**

```powershell
& $Maven @MavenBase -pl apps/jt-gateway -am `
  '-Dtest=OperationsTerminalRegistryClientTest,RegistrationAuthenticationHandlerTest,JtGatewayServerIntegrationTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: FAIL because decisions/sessions lack onboard context and second-connection auth returns `REGISTRATION_REQUIRED`.

- [ ] **Step 3: Implement gateway context and client contract**

```java
public record TerminalSessionContext(
        UUID terminalId,
        UUID onboardSystemId,
        UUID vehicleId,
        Set<String> roles,
        String sourceCoordinateSystem,
        String activeSafetyStandard,
        List<String> activeSafetyModules,
        int tokenVersion) {
    public TerminalSessionContext {
        roles = Set.copyOf(roles);
        activeSafetyModules = List.copyOf(activeSafetyModules);
    }
}
```

Add:

```java
AuthenticationDecision verifyAuthenticationByIdentity(
        ProtocolVersion protocolVersion,
        String terminalPhone,
        String presentedTokenSha256);
```

Use it only when `session.terminalId()==null`. Clear token bytes in `finally`, call `restoreAuthenticatedIdentity(context, terminalIdentity)`, and claim the session by physical terminal ID.

- [ ] **Step 4: Verify failure cleanup**

Mock/Spy tests assert API failure releases the `Jt808Frame`, clears token bytes, removes the session from `TerminalSessionRegistry`, and closes only the failing channel.

- [ ] **Step 5: Run GREEN**

```powershell
& $Maven @MavenBase -pl apps/jt-gateway -am `
  '-Dtest=OperationsTerminalRegistryClientTest,RegistrationAuthenticationHandlerTest,JtGatewayServerIntegrationTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```powershell
git add apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session `
  apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session `
  apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/netty/JtGatewayServerIntegrationTest.java
git commit --only -m 'feat: keep independent sessions for onboard devices' -- `
  apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/session `
  apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/session `
  apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/netty/JtGatewayServerIntegrationTest.java
```

---

### Task 5: Preserve Onboard Provenance on Every Position Event

**Files:**
- Modify: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/CanonicalPositionIngress.java`
- Modify: `apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistry.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/CanonicalPositionIngress.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEvent.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEventRepository.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/GpsLocationIngressService.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/location/GpsLocationIngressIntegrationTest.java`
- Modify: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistryActiveSafetyDispatchTest.java`

**Interfaces:**
- Consumes: Task 4 session context.
- Produces: immutable position events with onboard provenance; Task 6 consumes these fields.

- [ ] **Step 1: Write failing provenance tests**

```java
@Test
void storesBothDeviceEventsWithTheirOnboardSourceRoles() {
    ingest(position(DISPATCH_TERMINAL_ID, SYSTEM_ID, VEHICLE_ID,
            "LOCATION_PRIMARY", "2026-08-29T01:00:00Z"));
    ingest(position(RECORDER_TERMINAL_ID, SYSTEM_ID, VEHICLE_ID,
            "LOCATION_BACKUP", "2026-08-29T01:00:01Z"));
    List<VehicleLocationEvent> events = repository.findAllByOrderByDriverReportedAtAsc();
    assertThat(events).extracting(VehicleLocationEvent::getTerminalId)
            .containsExactly(DISPATCH_TERMINAL_ID, RECORDER_TERMINAL_ID);
    assertThat(events).extracting(VehicleLocationEvent::getSourceRole)
            .containsExactly("LOCATION_PRIMARY", "LOCATION_BACKUP");
    assertThat(events).extracting(VehicleLocationEvent::getOnboardSystemId)
            .containsOnly(SYSTEM_ID);
}
```

- [ ] **Step 2: Run RED**

```powershell
& $Maven @MavenBase -pl apps/api,apps/jt-gateway -am `
  '-Dtest=GpsLocationIngressIntegrationTest,ProtocolModuleRegistryActiveSafetyDispatchTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: FAIL because ingress/events lack onboard provenance.

- [ ] **Step 3: Extend canonical DTOs and persistence**

Use this field order in gateway and API:

```java
public record CanonicalPositionIngress(
        UUID terminalId,
        UUID onboardSystemId,
        UUID vehicleId,
        String sourceRole,
        String protocolVersion,
        int messageSerialNo,
        BigDecimal rawLongitude,
        BigDecimal rawLatitude,
        String rawCoordinateSystem,
        Instant terminalLocatedAt,
        Instant gatewayReceivedAt,
        Long alarmBits,
        Long statusBits,
        BigDecimal speedKph,
        Integer directionDegrees,
        Integer altitudeMeters,
        Integer satelliteCount,
        String payloadDigest) { }
```

Gateway rejects `0x0200` unless session roles contain `LOCATION_PRIMARY` or `LOCATION_BACKUP`. API re-resolves membership/role before persistence.

For a valid location frame, base position may still be stored when no safety role is present, but `appendActiveSafetyAlarms` must require `ACTIVE_SAFETY`; otherwise append `DEVICE_ROLE_VIOLATION` protocol audit and skip alarm facts. `dispatchAttachmentMetadata` must require `VIDEO`; otherwise audit and skip attachment metadata. The ordinary location response must reflect whether the durable base position write succeeded, not whether an unauthorized optional extension was ignored.

- [ ] **Step 4: Run GREEN**

```powershell
& $Maven @MavenBase -pl apps/api,apps/jt-gateway -am `
  '-Dtest=GpsLocationIngressIntegrationTest,ProtocolModuleRegistryActiveSafetyDispatchTest,GatewayIngressRouterTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: PASS; both raw events survive and role violations become protocol-audit facts.

- [ ] **Step 5: Commit Task 5**

```powershell
git add apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/CanonicalPositionIngress.java `
  apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistry.java `
  apps/api/src/main/java/com/idavy/drtops/domain/location `
  apps/api/src/test/java/com/idavy/drtops/domain/location `
  apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistryActiveSafetyDispatchTest.java
git commit --only -m 'feat: preserve onboard source on position facts' -- `
  apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/ingress/CanonicalPositionIngress.java `
  apps/jt-gateway/src/main/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistry.java `
  apps/api/src/main/java/com/idavy/drtops/domain/location `
  apps/api/src/test/java/com/idavy/drtops/domain/location `
  apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/dispatch/ProtocolModuleRegistryActiveSafetyDispatchTest.java
```

---

### Task 6: Stateful Primary/Backup Location Arbitration

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationSourceDecision.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/location/LocationSourceArbitrator.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/location/LocationSourceArbitratorTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/GpsLocationIngressService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEvent.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeState.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/location/GpsLocationIngressIntegrationTest.java`

**Interfaces:**
- Consumes: Task 5 provenance and Task 1 protocol intervals/state.
- Produces: deterministic snapshot decision and `LOCATION_SOURCE_SWITCHED` audit.

- [ ] **Step 1: Write failing arbitration tests**

```java
@Test
void keepsFreshPrimaryAndSwitchesToFreshBackupAfterDynamicThreshold() {
    Instant now = Instant.parse("2026-08-29T02:02:01Z");
    ArbitrationState state = stateUsingPrimary(
            Instant.parse("2026-08-29T02:00:00Z"), Duration.ofSeconds(60));
    LocationSourceDecision decision = arbitrator.decide(
            state, backupReport(now, LocationQualityStatus.GOOD));
    assertThat(decision.applySnapshot()).isTrue();
    assertThat(decision.switchSource()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo("PRIMARY_STALE");
}

@Test
void requiresThreeConsecutiveValidPrimaryReportsBeforeFailback() {
    ArbitrationState state = stateUsingBackup();
    assertThat(arbitrator.decide(state, validPrimary(1)).switchSource()).isFalse();
    assertThat(arbitrator.decide(state, validPrimary(2)).switchSource()).isFalse();
    assertThat(arbitrator.decide(state, validPrimary(3)).reasonCode())
            .isEqualTo("PRIMARY_RECOVERED");
}

@Test
void neverAppliesQuarantinedOrOlderEventsToTheVehicleSnapshot() {
    assertThat(arbitrator.decide(
            stateUsingPrimary(), quarantinedPrimary()).applySnapshot()).isFalse();
    assertThat(arbitrator.decide(
            stateUsingPrimary(), olderBackup()).applySnapshot()).isFalse();
}

@Test
void letsFreshBackupTakeOverImmediatelyAfterPrimaryQualityRejection() {
    ArbitrationState state = stateUsingPrimaryWithEligibility(false);
    LocationSourceDecision decision = arbitrator.decide(
            state, freshBackup(LocationQualityStatus.GOOD));
    assertThat(decision.switchSource()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo("PRIMARY_QUALITY_REJECTED");
}
```

- [ ] **Step 2: Run RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=LocationSourceArbitratorTest,GpsLocationIngressIntegrationTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: FAIL because no arbitrator exists and backup events can update the snapshot.

- [ ] **Step 3: Implement pure decisions**

```java
public record ArbitrationState(
        UUID primaryTerminalId,
        UUID backupTerminalId,
        UUID activeTerminalId,
        Instant lastPrimaryValidAt,
        Instant lastSnapshotAt,
        Duration expectedPrimaryInterval,
        boolean primaryEligible,
        int primaryRecoveryStreak) { }

public record PositionCandidate(
        UUID terminalId,
        String sourceRole,
        LocationQualityStatus qualityStatus,
        Instant terminalLocatedAt,
        Instant gatewayReceivedAt) { }

public record LocationSourceDecision(
        boolean applySnapshot,
        boolean switchSource,
        UUID selectedTerminalId,
        boolean primaryEligible,
        int primaryRecoveryStreak,
        String reasonCode) { }

public LocationSourceDecision decide(
        ArbitrationState state,
        PositionCandidate candidate) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(candidate, "candidate");
    boolean eligibleQuality = candidate.qualityStatus() == LocationQualityStatus.GOOD
            || candidate.qualityStatus() == LocationQualityStatus.WARNING;
    boolean newer = state.lastSnapshotAt() == null
            || candidate.terminalLocatedAt().isAfter(state.lastSnapshotAt());
    if (!eligibleQuality || !newer) {
        boolean primaryEligible = candidate.terminalId().equals(state.primaryTerminalId())
                ? false : state.primaryEligible();
        return new LocationSourceDecision(
                false, false, state.activeTerminalId(), primaryEligible, 0,
                "POSITION_NOT_ELIGIBLE");
    }
    if (candidate.terminalId().equals(state.activeTerminalId())) {
        boolean primaryEligible = candidate.terminalId().equals(state.primaryTerminalId())
                || state.primaryEligible();
        return new LocationSourceDecision(
                true, false, state.activeTerminalId(), primaryEligible, 0,
                "ACTIVE_SOURCE_ACCEPTED");
    }
    Duration staleAfter = state.expectedPrimaryInterval().multipliedBy(2);
    if (staleAfter.compareTo(Duration.ofSeconds(30)) < 0) {
        staleAfter = Duration.ofSeconds(30);
    }
    boolean primaryUnavailable = !state.primaryEligible()
            || state.lastPrimaryValidAt() == null
            || !candidate.gatewayReceivedAt().isBefore(state.lastPrimaryValidAt().plus(staleAfter));
    if (candidate.terminalId().equals(state.backupTerminalId()) && primaryUnavailable) {
        return new LocationSourceDecision(
                true, true, state.backupTerminalId(), false, 0,
                state.primaryEligible() ? "PRIMARY_STALE" : "PRIMARY_QUALITY_REJECTED");
    }
    if (candidate.terminalId().equals(state.primaryTerminalId())
            && state.activeTerminalId().equals(state.backupTerminalId())) {
        int streak = state.primaryRecoveryStreak() + 1;
        return streak >= 3
                ? new LocationSourceDecision(
                        true, true, state.primaryTerminalId(), true, 0, "PRIMARY_RECOVERED")
                : new LocationSourceDecision(
                        false, false, state.backupTerminalId(), true, streak, "PRIMARY_RECOVERING");
    }
    return new LocationSourceDecision(
            false, false, state.activeTerminalId(), state.primaryEligible(), 0,
            "NON_ACTIVE_SOURCE_IGNORED");
}
```

Compute threshold exactly:

```java
Duration staleAfter = expectedInterval.multipliedBy(2);
if (staleAfter.compareTo(Duration.ofSeconds(30)) < 0) {
    staleAfter = Duration.ofSeconds(30);
}
```

Only `GOOD/WARNING` are eligible. Invalid primary resets recovery streak to zero.

- [ ] **Step 4: Integrate with GPS transaction**

Lock `OnboardSystemRuntimeState`, persist every decoded event, call arbitrator, set `snapshotApplied`, update vehicle snapshot only when allowed, and append switch audit when source changes. Do not increment the administrator configuration version.

- [ ] **Step 5: Run GREEN and concurrency regression**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=LocationSourceArbitratorTest,GpsLocationIngressIntegrationTest,PostgisVehicleLocationConcurrencyTest,VehicleLocationRecorderTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: PASS; concurrent primary/backup reports produce one monotonic snapshot.

- [ ] **Step 6: Commit Task 6**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/location `
  apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeState.java `
  apps/api/src/test/java/com/idavy/drtops/domain/location
git commit --only -m 'feat: arbitrate primary and backup vehicle locations' -- `
  apps/api/src/main/java/com/idavy/drtops/domain/location `
  apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardSystemRuntimeState.java `
  apps/api/src/test/java/com/idavy/drtops/domain/location
```

---

### Task 7: Capability-Driven Dispatch Readiness

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardReadinessService.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardReadinessServiceTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewService.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java`

**Interfaces:**
- Consumes: aggregate roles, device status and location state from Tasks 2–6.
- Produces: `OnboardReadiness` and dispatch candidate guard.

- [ ] **Step 1: Write failing readiness tests**

```java
@Test
void recorderOnlySystemIsSafetyReadyButNotDispatchReady() {
    OnboardReadiness readiness = service.evaluate(fixtures.recorderOnlyVehicleId());
    assertThat(readiness.location()).isEqualTo(ReadinessState.READY);
    assertThat(readiness.activeSafety()).isEqualTo(ReadinessState.READY);
    assertThat(readiness.video()).isEqualTo(ReadinessState.READY);
    assertThat(readiness.dispatch()).isEqualTo(ReadinessState.NOT_INSTALLED);
    assertThat(readiness.dispatchEligible()).isFalse();
}

@Test
void dispatchSystemRequiresAuthenticationAndCurrentLocation() {
    assertThat(service.evaluate(
            fixtures.dispatchSystemWithoutAuthenticationVehicleId()).dispatchEligible()).isFalse();
    assertThat(service.evaluate(
            fixtures.dispatchSystemWithoutLocationVehicleId()).dispatchEligible()).isFalse();
    assertThat(service.evaluate(fixtures.readyDispatchSystemVehicleId()).dispatchEligible()).isTrue();
}
```

- [ ] **Step 2: Run RED**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardReadinessServiceTest,DispatchOrchestratorTest,ManualReviewApiTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: FAIL because dispatch filtering checks only `Vehicle.dispatchable`.

- [ ] **Step 3: Implement readiness contract**

```java
public enum ReadinessState { READY, DEGRADED, UNAVAILABLE, NOT_INSTALLED }

public record OnboardReadiness(
        ReadinessState connectivity,
        ReadinessState dispatch,
        ReadinessState location,
        ReadinessState activeSafety,
        ReadinessState video,
        boolean dispatchEligible,
        String overallStatus) { }
```

`dispatchEligible` requires vehicle dispatchable, active `DISPATCH_SERVICE` system, active `DISPATCH` device with verified capability/authentication, required business registration where configured, and current authoritative location.

- [ ] **Step 4: Filter automatic and manual dispatch**

```java
List<Vehicle> dispatchableVehicles = vehicleRepository.findAll().stream()
        .filter(Vehicle::isDispatchable)
        .filter(vehicle -> onboardReadinessService.evaluate(vehicle.getId()).dispatchEligible())
        .toList();
```

`ManualReviewService` rechecks readiness before task mutation and returns `DISPATCH_ONBOARD_SYSTEM_NOT_READY` on stale state.

- [ ] **Step 5: Run GREEN**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=OnboardReadinessServiceTest,DispatchOrchestratorTest,DispatchOrchestratorMapEstimateTest,ManualReviewApiTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 7**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardReadinessService.java `
  apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardReadinessServiceTest.java `
  apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java `
  apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewService.java `
  apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java `
  apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java
git commit --only -m 'feat: require onboard readiness for dispatch' -- `
  apps/api/src/main/java/com/idavy/drtops/domain/onboard/OnboardReadinessService.java `
  apps/api/src/test/java/com/idavy/drtops/domain/onboard/OnboardReadinessServiceTest.java `
  apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java `
  apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewService.java `
  apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java `
  apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java
```

---

### Task 8: V20 Contract Migration and Legacy Binding Freeze

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V20__replace_single_terminal_vehicle_binding_constraint.sql`
- Modify: `apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalVehicleBindingRepository.java`

**Interfaces:**
- Consumes: fully populated V19 desired state and Tasks 2–7 runtime code.
- Produces: contract schema that permits multi-device vehicles and freezes legacy binding history.

- [ ] **Step 1: Write failing V20 gates**

```java
@Test
void v20RefusesContractWhenDispatchableVehicleHasNoDispatchRole() throws Exception {
    ExternalPostgres postgres = externalPostgres();
    String schema = schema("v20_missing_dispatch");
    flyway(postgres, schema, "19").migrate();
    insertDispatchableVehicleWithoutDispatchRole(postgres, schema);
    assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
            .hasStackTraceContaining("ONBOARD_CONTRACT_DISPATCH_ROLE_MISSING");
}

@Test
void v20DropsSingleVehicleBindingIndexAndFreezesLegacyHistory() throws Exception {
    ExternalPostgres postgres = externalPostgres();
    String schema = schema("v20_contract");
    flyway(postgres, schema, "19").migrate();
    insertContractReadyDualDeviceSystem(postgres, schema);
    flyway(postgres, schema, "20").migrate();
    try (Connection connection = connection(postgres, schema)) {
        assertThat(indexExists(connection,
                "uq_jt_terminal_vehicle_bindings_active_vehicle")).isFalse();
        assertThatThrownBy(() -> insertLegacyBinding(connection))
                .hasMessageContaining("LEGACY_TERMINAL_BINDINGS_READ_ONLY");
    }
}
```

- [ ] **Step 2: Run RED**

Use Task 1 PostgreSQL command with Flyway target 20. Expected: FAIL because V20 does not exist.

- [ ] **Step 3: Implement contract migration**

V20 must fail if any active terminal lacks membership, any dispatchable `DISPATCH_SERVICE` vehicle lacks verified `DISPATCH`/`LOCATION_PRIMARY`, any role lacks verified capability, or primary equals backup. Only then drop the old active-vehicle unique index and add a trigger raising `LEGACY_TERMINAL_BINDINGS_READ_ONLY` on future legacy table writes.

Remove remaining legacy repository writes before V20.

- [ ] **Step 4: Run GREEN V18→V19→V20 paths**

```powershell
& $Maven @MavenBase -pl apps/api -am `
  '-Dtest=P6CompositeOnboardSystemMigrationTest,DatabaseMigrationTest,TerminalManagementServiceTest' `
  '-Ddrt.integration.composite-onboard=true' `
  '-Ddrt.integration.composite-onboard.external-ephemeral=true' `
  '-Ddrt.integration.composite-onboard.jdbc-url=jdbc:postgresql://127.0.0.1:55439/composite_onboard' `
  '-Ddrt.integration.composite-onboard.username=composite' `
  '-Ddrt.integration.composite-onboard.password=composite-test-only' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: PASS.

```powershell
docker stop drt-composite-onboard-pg
```

Expected: container stops and is removed because it was created with `--rm`.

- [ ] **Step 5: Commit Task 8**

```powershell
git add apps/api/src/main/resources/db/migration/V20__replace_single_terminal_vehicle_binding_constraint.sql `
  apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java `
  apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalVehicleBindingRepository.java
git commit --only -m 'feat: enforce composite onboard binding contract' -- `
  apps/api/src/main/resources/db/migration/V20__replace_single_terminal_vehicle_binding_constraint.sql `
  apps/api/src/test/java/com/idavy/drtops/P6CompositeOnboardSystemMigrationTest.java `
  apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/TerminalManagementService.java `
  apps/api/src/main/java/com/idavy/drtops/domain/terminal/JtTerminalVehicleBindingRepository.java
```

---

### Task 9: Aggregate Management API Client and Admin UI

**Files:**
- Create: `apps/admin-web/src/api/onboardSystems.ts`
- Create: `apps/admin-web/src/api/onboard-systems.test.ts`
- Create: `apps/admin-web/src/pages/OnboardSystemManagementPage.vue`
- Create: `apps/admin-web/src/pages/onboard-system-management-page.test.ts`
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/router/index.ts`
- Modify: `apps/admin-web/src/pages/TerminalManagementPage.vue`
- Modify: `apps/admin-web/src/pages/terminal-management-page.test.ts`

**Interfaces:**
- Consumes: Task 2 aggregate API and Task 7 readiness view.
- Produces: vehicle-level card, device drill-down and controlled desired-state operations.

- [ ] **Step 1: Write failing API-client tests**

```typescript
it("reads an onboard system and submits a versioned desired configuration", async () => {
  await getOnboardSystem("33333333-3333-3333-3333-333333333331");
  await applyOnboardConfiguration("33333333-3333-3333-3333-333333333331", {
    expectedVersion: 7,
    operatingMode: "DISPATCH_SERVICE",
    devices: [],
    reason: "双设备角色核对"
  });
  expect(request).toHaveBeenNthCalledWith(
    1,
    "/api/onboard-systems/33333333-3333-3333-3333-333333333331"
  );
  expect(request).toHaveBeenNthCalledWith(
    2,
    "/api/onboard-systems/33333333-3333-3333-3333-333333333331/configuration",
    { method: "POST", body: JSON.stringify({
      expectedVersion: 7,
      operatingMode: "DISPATCH_SERVICE",
      devices: [],
      reason: "双设备角色核对"
    }) }
  );
});
```

- [ ] **Step 2: Write failing page tests**

```typescript
it("shows aggregate readiness and independent device failures", async () => {
  renderPage(dualDeviceFixture({ recorderOnline: false }));
  expect(await screen.findByText("整体：DEGRADED")).toBeInTheDocument();
  expect(screen.getByText("调度：READY")).toBeInTheDocument();
  expect(screen.getByText("主动安全：UNAVAILABLE")).toBeInTheDocument();
  expect(screen.getByText("位置主源：调度终端")).toBeInTheDocument();
  expect(screen.getByText("共享网络客户端")).toBeInTheDocument();
});
```

- [ ] **Step 3: Run RED**

```powershell
npm.cmd --prefix apps/admin-web test -- `
  src/api/onboard-systems.test.ts `
  src/pages/onboard-system-management-page.test.ts
```

Expected: FAIL because module/types/page do not exist.

- [ ] **Step 4: Add exact UI contracts and page**

Define `OnboardSystemSummary`, `OnboardSystemDetail`, `OnboardDeviceView`, `OnboardReadiness`, `OnboardRole`, `OnboardCapability`, `ProtocolProfiles`, and `OnboardConfigurationInput`. Use only masked device identity fields.

Route `/terminals` to `OnboardSystemManagementPage`; retain the existing terminal page as device detail or compatibility route. Show overall/readiness dimensions, active source, WAN device, installed count and expandable physical devices.

- [ ] **Step 5: Implement controlled forms**

Role/profile/WAN changes require fresh detail reload, `expectedVersion`, nonblank reason and confirmation checkbox. Stale selection or failed detail load disables submit.

- [ ] **Step 6: Run GREEN, typecheck and build**

```powershell
npm.cmd --prefix apps/admin-web test -- `
  src/api/onboard-systems.test.ts `
  src/pages/onboard-system-management-page.test.ts `
  src/pages/terminal-management-page.test.ts
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
```

Expected: all exit 0.

- [ ] **Step 7: Commit Task 9**

```powershell
git add apps/admin-web/src/api/onboardSystems.ts `
  apps/admin-web/src/api/onboard-systems.test.ts `
  apps/admin-web/src/api/types.ts `
  apps/admin-web/src/pages/OnboardSystemManagementPage.vue `
  apps/admin-web/src/pages/onboard-system-management-page.test.ts `
  apps/admin-web/src/pages/TerminalManagementPage.vue `
  apps/admin-web/src/pages/terminal-management-page.test.ts `
  apps/admin-web/src/router/index.ts
git commit --only -m 'feat: manage composite onboard systems in admin web' -- `
  apps/admin-web/src/api/onboardSystems.ts `
  apps/admin-web/src/api/onboard-systems.test.ts `
  apps/admin-web/src/api/types.ts `
  apps/admin-web/src/pages/OnboardSystemManagementPage.vue `
  apps/admin-web/src/pages/onboard-system-management-page.test.ts `
  apps/admin-web/src/pages/TerminalManagementPage.vue `
  apps/admin-web/src/pages/terminal-management-page.test.ts `
  apps/admin-web/src/router/index.ts
```

---

### Task 10: Dual-Device Simulator Scenarios and Full Regression

**Files:**
- Modify: `tools/jt-terminal-simulator/src/main/java/com/idavy/drtops/jtsimulator/Scenario.java`
- Modify: `tools/jt-terminal-simulator/src/main/java/com/idavy/drtops/jtsimulator/SimulatedTerminal.java`
- Modify: `tools/jt-terminal-simulator/src/main/java/com/idavy/drtops/jtsimulator/ScenarioRunner.java`
- Modify: `tools/jt-terminal-simulator/src/test/java/com/idavy/drtops/jtsimulator/ScenarioRunnerTest.java`
- Modify: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/JtGatewayRuntimeIntegrationTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/onboard/CompositeOnboardEndToEndTest.java`

**Interfaces:**
- Consumes: Tasks 1–9.
- Produces: reproducible dual-connection, shared-vehicle, failover/failback and role-violation scenarios.

- [ ] **Step 1: Write failing simulator tests**

```java
@Test
void runsTwoIndependentConnectionsForOneVehicleIdentifier() {
    Scenario scenario = Scenario.dualDevice(
            terminal("dispatch-01", "VEHICLE-A", "LOCATION_PRIMARY"),
            terminal("recorder-01", "VEHICLE-A", "LOCATION_BACKUP"));
    ScenarioResult result = runner.run(scenario);
    assertThat(result.connectionCount()).isEqualTo(2);
    assertThat(result.authenticatedAliases())
            .containsExactlyInAnyOrder("dispatch-01", "recorder-01");
    assertThat(result.vehicleIdentifiers()).containsOnly("VEHICLE-A");
}
```

Add scenarios for primary timeout, backup takeover, three-report failback, SIM role change without identity change, recorder-only non-dispatchable, and dispatch-only `NOT_INSTALLED` safety/video.

- [ ] **Step 2: Run RED**

```powershell
& $Maven @MavenBase -pl tools/jt-terminal-simulator,apps/jt-gateway,apps/api -am `
  '-Dtest=ScenarioRunnerTest,JtGatewayRuntimeIntegrationTest,CompositeOnboardEndToEndTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: FAIL because simulator has no multi-terminal scenario or onboard assertions.

- [ ] **Step 3: Implement multi-device scenario model**

Use these exact scenario records:

```java
public record TerminalDefinition(
        String alias,
        String terminalIdentity,
        String terminalCode,
        String vehicleIdentifier,
        ProtocolVersion protocolVersion,
        Set<String> capabilities,
        Set<String> roles) {
    public TerminalDefinition {
        capabilities = Set.copyOf(capabilities);
        roles = Set.copyOf(roles);
    }
}

public record Scenario(
        String name,
        List<TerminalDefinition> terminals,
        List<ScenarioStep> steps) {
    public Scenario {
        terminals = List.copyOf(terminals);
        steps = List.copyOf(steps);
    }
}

public record ScenarioResult(
        int connectionCount,
        Set<String> authenticatedAliases,
        Set<String> vehicleIdentifiers,
        List<String> completedSteps) { }
```

`SimulatedTerminal` exposes `UUID connectionId()` and keeps its registration token in the instance. Define explicit steps `CONNECT`, `REGISTER`, `AUTHENTICATE`, `LOCATION`, `DISCONNECT`, `ADVANCE_CLOCK`, `EXPECT_ACTIVE_SOURCE`, and `CHANGE_WAN_UPLINK`; every step identifies a terminal alias where applicable.

- [ ] **Step 4: Run targeted GREEN**

```powershell
& $Maven @MavenBase -pl tools/jt-terminal-simulator,apps/jt-gateway,apps/api -am `
  '-Dtest=ScenarioRunnerTest,JtGatewayRuntimeIntegrationTest,CompositeOnboardEndToEndTest' `
  "-Djava.io.tmpdir=$MavenTemp" test
```

Expected: PASS.

- [ ] **Step 5: Run complete regression serially**

```powershell
& $Maven @MavenBase -pl libs/jt-protocol,apps/jt-gateway,apps/api,tools/jt-terminal-simulator `
  "-Djava.io.tmpdir=$MavenTemp" test
npm.cmd --prefix apps/admin-web test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
git diff --check
```

Expected: Java tests ≥701, failures 0, errors 0; frontend commands and diff check exit 0. Record fresh report counts.

- [ ] **Step 6: Commit Task 10**

```powershell
git add tools/jt-terminal-simulator/src `
  apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/JtGatewayRuntimeIntegrationTest.java `
  apps/api/src/test/java/com/idavy/drtops/domain/onboard/CompositeOnboardEndToEndTest.java
git commit --only -m 'test: cover dual-device onboard system scenarios' -- `
  tools/jt-terminal-simulator/src `
  apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/JtGatewayRuntimeIntegrationTest.java `
  apps/api/src/test/java/com/idavy/drtops/domain/onboard/CompositeOnboardEndToEndTest.java
```

---

### Task 11: Private Migration Runner and Operations Documentation

**Files:**
- Create (private, never commit): `.private/cloud-deployment/p6-2-cloud-7fa38d0/cloud-onboard-system-migration-lib.ps1`
- Create (private, never commit): `.private/cloud-deployment/p6-2-cloud-7fa38d0/cloud-onboard-system-migration-tests.ps1`
- Create (private, never commit): `.private/cloud-deployment/p6-2-cloud-7fa38d0/Invoke-CloudOnboardSystemMigration.ps1`
- Create (private, never commit): `.private/cloud-deployment/p6-2-cloud-7fa38d0/onboard-system-migration-manifest.json`
- Create (private, never commit): `.private/cloud-deployment/p6-2-cloud-7fa38d0/cloud-onboard-system-deployment.private.json`
- Create: `docs/pilot/evidence/p6-2/onboard-system-intake-template.csv`
- Modify: `docs/pilot/jt-gateway-operations.md`

**Interfaces:**
- Consumes: Task 2 preview/apply API and Task 8 V20 contract gates.
- Produces: strict UTF-8 private DryRun/ApplyV19/ContractCheck workflow and public runbook.

- [ ] **Step 1: Write failing private tests**

```powershell
$Failures = 0

try {
  $plan = New-TestMigrationPlan -DuplicateRole 'LOCATION_PRIMARY'
  Test-OnboardMigrationPlan -Plan $plan
  Write-Output 'FAIL=EXCLUSIVE_ROLE_CONFLICT_NOT_THROWN'
  $Failures++
} catch {
  if ($_.Exception.Message -notmatch 'EXCLUSIVE_ROLE_CONFLICT') {
    Write-Output 'FAIL=EXCLUSIVE_ROLE_WRONG_ERROR'
    $Failures++
  }
}

$safe = Format-OnboardMigrationResult -Result (New-TestSafeResult)
if ($safe -match 'terminalPhone|terminalId|vehicleIdentifier|tokenSha256') {
  Write-Output 'FAIL=SENSITIVE_OUTPUT'
  $Failures++
}

try {
  $state = New-TestResumeState -Version 4
  Confirm-OnboardResume -State $state -CurrentVersion 5
  Write-Output 'FAIL=RESUME_DRIFT_NOT_THROWN'
  $Failures++
} catch {
  if ($_.Exception.Message -notmatch 'ONBOARD_RESUME_VERSION_DRIFT') {
    Write-Output 'FAIL=RESUME_DRIFT_WRONG_ERROR'
    $Failures++
  }
}

if ($Failures -gt 0) { exit 1 }
Write-Output 'PRIVATE_ONBOARD_MIGRATION_TESTS=PASS'
```

- [ ] **Step 2: Run private RED**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\cloud-deployment\p6-2-cloud-7fa38d0\cloud-onboard-system-migration-tests.ps1'
```

Expected: FAIL because functions/scripts do not exist.

- [ ] **Step 3: Implement strict phases**

```text
DryRun        read-only API, desired-state diff, zero business writes
ApplyV19      preview → expectedVersion apply → read-back per vehicle
ContractCheck read-only verification before V20
```

Manifest stores safe aliases and private file references. Output contains alias, step, HTTP status, version, warnings and file SHA-256 only. Fail on duplicate identity/role, missing capability, role/profile mismatch, stale UUID/version, attachment field, invalid UTF-8 or gateway/7611 not stopped.

`cloud-onboard-system-deployment.private.json` contains the already approved SSH host alias, server root `/home/ubuntu/p6-2-cloud-7fa38d0`, SSH user, local secure-key path and known-hosts path. The loader validates the known-host fingerprint through the existing SSH command before any upload and never prints these values.

- [ ] **Step 4: Run private GREEN and secret scan**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\cloud-deployment\p6-2-cloud-7fa38d0\cloud-onboard-system-migration-tests.ps1'
```

Expected: all cases pass; output scan against private UTF-8/GBK strings has zero hits.

- [ ] **Step 5: Update public template and runbook**

CSV columns:

```text
vehicle_alias,terminal_alias,network_mode,capabilities,transport_profile,business_profile,safety_profile,media_profile,roles,active_position_interval_seconds,idle_position_interval_seconds,materials_status,notes
```

Document V19 target deployment, DryRun, ApplyV19, ContractCheck, V20 restart, rollback limits and gateway-stopped checks. Do not include cloud IP or private contents.

- [ ] **Step 6: Commit only public artifacts**

```powershell
git status --short .private docs/pilot
git add docs/pilot/evidence/p6-2/onboard-system-intake-template.csv `
  docs/pilot/jt-gateway-operations.md
git commit --only -m 'docs: add composite onboard migration runbook' -- `
  docs/pilot/evidence/p6-2/onboard-system-intake-template.csv `
  docs/pilot/jt-gateway-operations.md
```

Expected: private files remain absent from the commit.

---

### Task 12: Offline Build, V19/V20 Cutover, and Real Acceptance

**Files:**
- Create: private release directory under `.private/cloud-deployment/p6-2-cloud-7fa38d0/`
- Create: `docs/pilot/evidence/p6-2/cloud-composite-onboard-acceptance-2026-08-29.md`
- Modify: `progress.md`

**Interfaces:**
- Consumes: all prior tasks and private migration runner.
- Produces: offline images, stopped V19/V20 cutover, simulator evidence and user-authorized real evidence.

- [ ] **Step 1: Re-run release gates**

Run Task 10 full regression, Task 11 private tests, Compose `config --quiet`, `git diff --check`, secret scan and process cleanup. Stop on stale reports or remaining Java processes.

- [ ] **Step 2: Build deterministic offline artifacts**

```powershell
& $Maven @MavenBase -pl apps/api,apps/jt-gateway -am `
  "-Djava.io.tmpdir=$MavenTemp" package -DskipTests
$SourceMaterial = git ls-files -s apps/api apps/jt-gateway libs/jt-protocol pom.xml | Out-String
$SourceBytes = [Text.Encoding]::UTF8.GetBytes($SourceMaterial)
$SourceSha = [Convert]::ToHexString(
  [Security.Cryptography.SHA256]::HashData($SourceBytes)).ToLowerInvariant()
$Tag = "p6-2-composite-$($SourceSha.Substring(0,7))"
```

Build API/gateway with `--network=none --pull=false --no-cache`, export one Docker tar, gzip deterministically, and produce SHA-256 manifest. Verify linux/amd64, non-root user, JAR hashes and zero secret hits.

```powershell
$ReleaseDir = ".private\cloud-deployment\p6-2-cloud-7fa38d0\composite-$($SourceSha.Substring(0,7))"
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
docker build --network none --pull=false --no-cache `
  -f apps/api/Dockerfile `
  -t "drt-ops-jt-cloud-api:$Tag" .
docker build --network none --pull=false --no-cache `
  -f apps/jt-gateway/Dockerfile `
  -t "drt-ops-jt-cloud-gateway:$Tag" .
docker save `
  -o "$ReleaseDir\p6-2-composite-images.tar" `
  "drt-ops-jt-cloud-api:$Tag" `
  "drt-ops-jt-cloud-gateway:$Tag"
$Python = 'C:\Users\Davy\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $Python -c "import gzip,shutil,sys; src,dst=sys.argv[1:3]; fi=open(src,'rb'); fo=gzip.GzipFile(dst,'wb',mtime=0); shutil.copyfileobj(fi,fo); fo.close(); fi.close()" `
  "$ReleaseDir\p6-2-composite-images.tar" `
  "$ReleaseDir\p6-2-composite-images.tar.gz"
$Artifacts = @(
  "$ReleaseDir\p6-2-composite-images.tar",
  "$ReleaseDir\p6-2-composite-images.tar.gz",
  'apps\api\target\drt-ops-api-0.1.0-SNAPSHOT.jar',
  'apps\jt-gateway\target\drt-ops-jt-gateway-0.1.0-SNAPSHOT.jar'
)
$ChecksumLines = $Artifacts | ForEach-Object {
  $hash = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()
  "$hash  $([IO.Path]::GetFileName($_))"
}
[IO.File]::WriteAllLines(
  "$ReleaseDir\SHA256SUMS",
  $ChecksumLines,
  [Text.UTF8Encoding]::new($false))
```

- [ ] **Step 3: Upload and verify while gateway remains stopped**

Use the approved private SFTP/SSH runner and `.private/cloud-deployment/p6-2-cloud-7fa38d0/cloud-onboard-system-deployment.private.json`. Upload into server directory `.private-recovery/composite-$($SourceSha.Substring(0,7))`, verify all hashes, `docker load`, Compose config and image IDs. Do not start gateway; `7611` listener count must remain zero.

- [ ] **Step 4: Back up database before V19**

Create a custom-format PostgreSQL dump and SHA-256 in `.private-recovery`, mode 600. Verify archive entry count and readability.

- [ ] **Step 5: Deploy API with Flyway target 19**

Use a private Compose override containing:

```text
SPRING_FLYWAY_TARGET=19
```

Recreate only API. Verify health `UP`, latest successful Flyway 19, old binding index present, gateway stopped and `7611=0`.

- [ ] **Step 6: Run real DryRun and ApplyV19**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\cloud-deployment\p6-2-cloud-7fa38d0\Invoke-CloudOnboardSystemMigration.ps1' `
  -Mode DryRun
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\cloud-deployment\p6-2-cloud-7fa38d0\Invoke-CloudOnboardSystemMigration.ps1' `
  -Mode ApplyV19
```

DryRun reports zero writes. Current recorder-only test vehicle ends `SAFETY_MONITOR_ONLY`, non-dispatchable, roles `LOCATION_PRIMARY/ACTIVE_SAFETY/VIDEO`, no `DISPATCH`.

- [ ] **Step 7: Run ContractCheck and second backup**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\cloud-deployment\p6-2-cloud-7fa38d0\Invoke-CloudOnboardSystemMigration.ps1' `
  -Mode ContractCheck
```

Expected: every terminal has one membership, exclusive roles are unique, verified capability covers roles, all dispatchable vehicles are ready, and gateway remains stopped. Create a second mode-600 dump.

- [ ] **Step 8: Apply V20**

Remove `SPRING_FLYWAY_TARGET`, recreate only API, then verify health `UP`, Flyway 20, old active-vehicle unique index absent, legacy write trigger present, counts unchanged, and restart count zero after stabilization.

- [ ] **Step 9: Run simulator acceptance**

Start observer before gateway. Verify dual connection, shared vehicle identifier, independent auth, primary/backup source, forced timeout and three-report failback. Stop gateway and verify `7611=0`.

- [ ] **Step 10: Pause for real-traffic authorization**

Require explicit confirmation of device power, target host/port, security group and bounded window. Successful deployment is not authorization.

- [ ] **Step 11: Execute real acceptance**

1. recorder-only registration/auth/location/ADAS/DMS, no dispatch;
2. add dispatch terminal to same production system;
3. verify two sessions and shared vehicle identifier;
4. verify dispatch-primary/recorder-backup location;
5. verify failover and three-report failback;
6. verify SIM in each device without identity/role migration;
7. verify dispatch-only degradation state.

Any role violation, cross-vehicle plate conflict, wrong snapshot source, secret output, API health failure or Outbox dead letter stops the window.

- [ ] **Step 12: Close out evidence and progress**

Report statuses, timestamps, aliases, versions, counts and SHA-256 only. State that full GB/T 28787 business messages and attachments remain out of scope. Update `progress.md` with branch/HEAD, migrations, test counts, image IDs, outcome, exceptions and next entry point.

- [ ] **Step 13: Commit public closeout**

```powershell
git add docs/pilot/evidence/p6-2/cloud-composite-onboard-acceptance-2026-08-29.md `
  progress.md
git commit --only -m 'docs: record composite onboard acceptance' -- `
  docs/pilot/evidence/p6-2/cloud-composite-onboard-acceptance-2026-08-29.md `
  progress.md
```

## Review Checkpoints

- After Task 1: review V19 schema and migration safety.
- After Task 2: review aggregate invariants and authorization.
- After Task 3: review registration warning/conflict semantics and secrets.
- After Task 4: review cleanup and dual-session isolation.
- After Task 6: review location thresholds, quality and concurrency.
- After Task 7: review dispatch business impact.
- After Task 8: independent V20 migration review is mandatory.
- After Task 9: UI behavior and accessibility review.
- After Task 10: independent GREEN review and complete regression.
- After Task 11: private artifact security review.
- Before Task 12 Step 10: explicit user authorization for real traffic.
