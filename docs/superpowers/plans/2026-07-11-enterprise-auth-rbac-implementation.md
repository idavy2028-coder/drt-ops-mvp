# 企业管理端认证与 RBAC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为企业管理端提供本地账号登录、可撤销 JWT 会话、四类固定角色和端到端 API 权限控制。

**Architecture:** Spring Boot 负责用户、角色、刷新令牌和 API 授权。JWT 访问令牌只保存用户标识、角色和令牌版本；每次请求仍从数据库确认账号启用状态与令牌版本。Vue 前端维护内存访问令牌，使用 `HttpOnly` 刷新令牌 Cookie 恢复会话，并通过同一份权限语义控制导航和操作入口。

**Tech Stack:** Java 21、Spring Boot 3.5.3、Spring Security、Spring Security OAuth2 JOSE、JPA、Flyway、PostgreSQL/PostGIS、Vue 3、TypeScript、Vue Router、Vitest、Playwright。

## Global Constraints

- 本期仅支持单一公交企业部署；`organization_code` 仅为未来扩展预留字段，不过滤现有业务数据。
- 仅系统管理员可创建账号、重置密码、启停账号和分配角色；不实现自助注册或自助找回密码。
- 访问令牌有效期 15 分钟；刷新令牌有效期 7 天并在每次刷新时轮换。
- 密码必须用 BCrypt 保存；刷新令牌只保存哈希；不得写死初始管理员密码。
- 每次保护请求必须校验用户启用状态和令牌版本；停用账号与重置密码必须立即使旧令牌失效。
- 算法服务不实现终端用户认证或 RBAC；管理端仅通过 Java 业务 API 访问业务能力。
- 所有新增或变更的认证、授权、用户管理操作必须写入现有 `audit_logs`。
- 每个任务先写失败测试，再写最小实现；通过后单独提交。

---

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `apps/api/pom.xml` | 引入 Spring Security 与 JWT 编解码依赖。 |
| `apps/api/src/main/resources/application.yml` | 认证有效期、签名密钥、Cookie 与初始管理员配置。 |
| `apps/api/src/main/resources/db/migration/V3__create_auth_schema.sql` | 用户、角色、用户角色和刷新令牌表及预置角色。 |
| `apps/api/src/main/java/com/idavy/drtops/auth/*` | 用户、角色、令牌领域、认证服务、控制器与 DTO。 |
| `apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java` | Spring Security 过滤链和现有 API 的权限矩阵。 |
| `apps/api/src/main/java/com/idavy/drtops/config/JwtAuthenticationFilter.java` | JWT 解析、用户状态与令牌版本校验。 |
| `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionController.java` | 为审计角色提供只读调度决策列表。 |
| `apps/admin-web/src/auth/*` | 前端认证状态、权限判断和路由守卫。 |
| `apps/admin-web/src/api/auth.ts` | 登录、刷新、退出和用户管理 API 客户端。 |
| `apps/admin-web/src/api/http.ts` | Bearer 令牌注入、单次刷新和 401/403 归一化。 |
| `apps/admin-web/src/pages/LoginPage.vue` | 本地账号登录界面。 |
| `apps/admin-web/src/pages/UserManagementPage.vue` | 管理员用户与角色管理界面。 |
| `apps/admin-web/src/router/index.ts` | 登录路由、权限元数据和全局路由守卫。 |
| `apps/admin-web/src/layouts/AppLayout.vue` | 角色感知导航、当前用户与退出入口。 |

## 权限矩阵

| 权限 | 系统管理员 | 调度员 | 运营人员 | 审计人员 |
| --- | --- | --- | --- | --- |
| `USER_MANAGE` | 是 | 否 | 否 | 否 |
| `RULE_MANAGE` | 是 | 否 | 否 | 否 |
| `RESOURCE_MANAGE` | 是 | 否 | 是 | 否 |
| `ORDER_CREATE` | 否 | 否 | 是 | 否 |
| `ORDER_READ` | 是 | 是 | 是 | 否 |
| `DISPATCH_EXECUTE` | 否 | 是 | 否 | 否 |
| `MANUAL_REVIEW` | 否 | 是 | 否 | 否 |
| `TASK_READ` | 是 | 是 | 是 | 否 |
| `TASK_EXECUTE` | 否 | 是 | 否 | 否 |
| `AUDIT_READ` | 是 | 否 | 否 | 是 |
| `METRICS_READ` | 是 | 否 | 是 | 是 |
| `DECISION_READ` | 是 | 是 | 否 | 是 |

### Task 1: 认证持久化模型与安全配置基础

**Files:**
- Modify: `apps/api/pom.xml`
- Modify: `apps/api/src/main/resources/application.yml`
- Create: `apps/api/src/main/resources/db/migration/V3__create_auth_schema.sql`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/RoleCode.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/Permission.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/AuthConfiguration.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/UserAccount.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/Role.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/RefreshToken.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/UserAccountRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/RoleRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/RefreshTokenRepository.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/auth/AuthSchemaRepositoryTest.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java`

**Interfaces:**
- Produces `RoleCode` with `SYSTEM_ADMIN`, `DISPATCHER`, `OPERATOR`, `AUDITOR`.
- Produces `Permission` with the twelve permission codes in the matrix and `Set<Permission> permissionsFor(Set<RoleCode> roles)`.
- Produces `UserAccountRepository.findByUsernameIgnoreCase(String username): Optional<UserAccount>`.
- Produces `RefreshTokenRepository.findByTokenHashAndRevokedAtIsNull(String tokenHash): Optional<RefreshToken>`.
- Produces `RefreshTokenRepository.revokeAllActiveByUserId(UUID userId, OffsetDateTime revokedAt): int`.
- Produces `PasswordEncoder` as a BCrypt bean from `AuthConfiguration`.

- [ ] **Step 1: 写失败的迁移与仓储测试**

```java
@Test
void persistsRolesAndRevocableRefreshToken() {
    UserAccount operator = UserAccount.create("operator01", "运营一组", passwordEncoder.encode("Secret123!"));
    operator.assignRoles(Set.of(RoleCode.OPERATOR));
    userAccountRepository.save(operator);

    RefreshToken token = RefreshToken.issue(operator, "token-hash", OffsetDateTime.now().plusDays(7));
    refreshTokenRepository.save(token);

    assertThat(userAccountRepository.findByUsernameIgnoreCase("OPERATOR01")).isPresent();
    assertThat(refreshTokenRepository.findByTokenHashAndRevokedAtIsNull("token-hash")).isPresent();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl apps/api -Dtest=AuthSchemaRepositoryTest test`  
Expected: FAIL，因为 `UserAccount`、仓储和认证表尚不存在。

- [ ] **Step 3: 创建迁移、领域对象和依赖**

在 `pom.xml` 增加 `spring-boot-starter-security` 与 `spring-security-oauth2-jose`。迁移创建：

```sql
CREATE TABLE user_accounts (
  id UUID PRIMARY KEY,
  username VARCHAR(80) NOT NULL UNIQUE,
  display_name VARCHAR(120) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  organization_code VARCHAR(80),
  token_version BIGINT NOT NULL DEFAULT 0,
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE roles (
  code VARCHAR(40) PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  description VARCHAR(200) NOT NULL
);
INSERT INTO roles (code, name, description) VALUES
  ('SYSTEM_ADMIN', '系统管理员', '管理账号、规则与基础配置'),
  ('DISPATCHER', '调度员', '执行调度、人工复核和任务异常处理'),
  ('OPERATOR', '运营人员', '维护资源并录入运营需求'),
  ('AUDITOR', '审计人员', '只读查看决策、审计与指标');
CREATE TABLE user_roles (
  user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
  role_code VARCHAR(40) NOT NULL REFERENCES roles(code),
  PRIMARY KEY (user_id, role_code)
);
CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
  token_hash VARCHAR(128) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_from VARCHAR(120) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

创建 `Role` JPA 实体和 `RoleRepository`，但角色编码仍受 `RoleCode` 枚举约束。将角色映射集中在 `Permission.permissionsFor`，不要在控制器复制角色判断。`AuthConfiguration` 提供 BCrypt `PasswordEncoder`。增加 `drt.auth` 配置键：`jwt-secret`、`access-token-minutes: 15`、`refresh-token-days: 7`、`bootstrap-admin-username`、`bootstrap-admin-password`、`refresh-cookie-secure`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl apps/api -Dtest=AuthSchemaRepositoryTest,DatabaseMigrationTest test`  
Expected: PASS；迁移能在 Postgres 与现有 H2 测试配置下创建认证模型。

- [ ] **Step 5: 提交**

```bash
git add apps/api/pom.xml apps/api/src/main/resources/application.yml apps/api/src/main/resources/db/migration/V3__create_auth_schema.sql apps/api/src/main/java/com/idavy/drtops/auth apps/api/src/test/java/com/idavy/drtops/auth/AuthSchemaRepositoryTest.java apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java
git commit -m "feat: add auth persistence foundation"
```

### Task 2: 登录、令牌轮换与初始管理员

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/AuthService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/JwtTokenService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/BootstrapAdminInitializer.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/AuthController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/dto/LoginRequest.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/dto/AuthSessionResponse.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/dto/CurrentUserResponse.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/auth/AuthApiTest.java`

**Interfaces:**
- Consumes `UserAccountRepository`、`RefreshTokenRepository`、`Permission.permissionsFor`。
- Produces `POST /api/auth/login`、`POST /api/auth/refresh`、`POST /api/auth/logout`、`GET /api/auth/me`。
- `AuthSessionResponse` 返回 `accessToken`、`expiresAt` 和 `CurrentUserResponse`；刷新令牌只通过 Cookie 返回。

- [ ] **Step 1: 编写失败的认证 API 测试**

```java
mockMvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"operator01\",\"password\":\"Secret123!\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
    .andExpect(header().string("Set-Cookie", containsString("drt_refresh=")));

mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
    .andExpect(status().isOk())
    .andExpect(header().string("Set-Cookie", containsString("drt_refresh=")));
```

增加失败场景：错误密码返回 401、旧刷新令牌轮换后不可再次使用、退出后刷新返回 401、停用用户后旧访问令牌返回 401、数据库非空时不执行初始管理员创建。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl apps/api -Dtest=AuthApiTest test`  
Expected: FAIL，因为认证端点和令牌服务尚不存在。

- [ ] **Step 3: 实现最小认证服务**

`AuthService.login` 使用 `PasswordEncoder.matches` 验证密码，调用 `JwtTokenService.issueAccessToken(user)`，生成随机刷新令牌原文并保存 SHA-256 哈希。`refresh` 必须在同一事务中撤销旧记录并写入新记录。JWT Claims 固定为：

```java
Map<String, Object> claims = Map.of(
    "sub", user.getId().toString(),
    "username", user.getUsername(),
    "roles", user.getRoles().stream().map(Enum::name).toList(),
    "tokenVersion", user.getTokenVersion());
```

`BootstrapAdminInitializer` 仅在 `userAccountRepository.count() == 0` 且用户名、密码配置均非空时创建 `SYSTEM_ADMIN`，并设 `mustChangePassword=true`。缺少任一配置时记录安全日志但不创建账号。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl apps/api -Dtest=AuthApiTest test`  
Expected: PASS；登录、刷新轮换、退出撤销、停用失效和初始管理员边界均通过。

- [ ] **Step 5: 提交**

```bash
git add apps/api/src/main/java/com/idavy/drtops/auth apps/api/src/test/java/com/idavy/drtops/auth/AuthApiTest.java
git commit -m "feat: add local login and token rotation"
```

### Task 3: Spring Security 过滤链与现有业务 API 授权

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/config/JwtAuthenticationFilter.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/config/AuthenticationFailureHandler.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/config/WebCorsConfiguration.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionReadService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionRepository.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/auth/AuthorizationApiTest.java`

**Interfaces:**
- Consumes `JwtTokenService.decode(String bearerToken)` 和 `UserAccountRepository.findById(UUID)`。
- Produces Spring `Authentication`，其 `GrantedAuthority` 值为 `Permission.name()`。
- Produces `GET /api/dispatch-decisions`，返回审计角色可读的调度决策摘要。

- [ ] **Step 1: 写失败的角色矩阵测试**

```java
mockMvc.perform(post("/api/orders")
        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(orderJson))
    .andExpect(status().isCreated());

mockMvc.perform(post("/api/orders/{id}/dispatch", orderId)
        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
    .andExpect(status().isForbidden());

mockMvc.perform(get("/api/audit-logs")
        .header(HttpHeaders.AUTHORIZATION, bearer(auditorToken)))
    .andExpect(status().isOk());
```

覆盖无令牌 401、无效 JWT 401、停用用户 401、调度员派单 200、运营人员创建订单 201、审计员读取决策 200、运营人员读取审计日志 403。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl apps/api -Dtest=AuthorizationApiTest test`  
Expected: FAIL，因为现有 API 尚未默认要求认证或按权限授权。

- [ ] **Step 3: 实现过滤链和权限路由规则**

在 `SecurityConfiguration` 中禁用服务端会话，允许 `/actuator/health` 与 `/api/auth/**`，其余 `/api/**` 要求认证；`JwtAuthenticationFilter` 在每个请求读取 Bearer JWT，再确认用户 `enabled` 和 `tokenVersion`。

使用 HTTP 方法与路径建立集中授权规则：

```java
requestMatchers(POST, "/api/orders").hasAuthority("ORDER_CREATE");
requestMatchers(POST, "/api/orders/*/dispatch").hasAuthority("DISPATCH_EXECUTE");
requestMatchers(POST, "/api/dispatch-decisions/*/approve", "/api/dispatch-decisions/*/reject")
        .hasAuthority("MANUAL_REVIEW");
requestMatchers("/api/audit-logs/**").hasAuthority("AUDIT_READ");
requestMatchers("/api/metrics/**").hasAuthority("METRICS_READ");
```

为 `WebCorsConfiguration` 加 `allowCredentials(true)`，并保留已配置的显式来源列表，不使用通配来源。`DispatchDecisionReadService` 仅返回已有决策的只读字段，不改变算法或订单状态。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl apps/api -Dtest=AuthorizationApiTest,ManualReviewApiTest,AuditLogApiTest test`  
Expected: PASS；既有人工复核与审计 API 在授权后仍保持原业务行为。

- [ ] **Step 5: 提交**

```bash
git add apps/api/src/main/java/com/idavy/drtops/config apps/api/src/main/java/com/idavy/drtops/domain/dispatch apps/api/src/main/java/com/idavy/drtops/domain/audit apps/api/src/test/java/com/idavy/drtops/auth/AuthorizationApiTest.java
git commit -m "feat: protect operations APIs with rbac"
```

### Task 4: 管理员用户管理与认证审计

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/UserManagementService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/UserManagementController.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/dto/CreateUserRequest.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/dto/UpdateUserRolesRequest.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/dto/ResetPasswordRequest.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/dto/UserAccountResponse.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/auth/AuthAuditService.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/auth/UserManagementApiTest.java`

**Interfaces:**
- Produces `GET /api/users`、`POST /api/users`、`PUT /api/users/{id}/roles`、`POST /api/users/{id}/reset-password`、`POST /api/users/{id}/enable`、`POST /api/users/{id}/disable`。
- Consumes authenticated actor id from Spring Security and writes `AuditLog.record("USER_ACCOUNT", userId, action, "USER", actorId, reason, metadataJson)`.

- [ ] **Step 1: 写失败的管理员 API 与审计测试**

```java
mockMvc.perform(post("/api/users")
        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"dispatcher01\",\"displayName\":\"调度一组\",\"temporaryPassword\":\"Temp123!\",\"roles\":[\"DISPATCHER\"]}"))
    .andExpect(status().isCreated())
    .andExpect(jsonPath("$.data.mustChangePassword").value(true));

mockMvc.perform(post("/api/users/{id}/disable", userId)
        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken)))
    .andExpect(status().isForbidden());
```

断言创建、角色变更、密码重置和停用均产生对应 `audit_logs.action`；密码重置与停用使所有刷新令牌撤销并递增 `tokenVersion`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl apps/api -Dtest=UserManagementApiTest test`  
Expected: FAIL，因为用户管理端点和认证审计服务尚不存在。

- [ ] **Step 3: 实现受限用户管理**

所有 `/api/users/**` 由 `USER_MANAGE` 保护。`UserManagementService` 只接受 `RoleCode` 枚举中的预置角色；创建和重置密码均使用 BCrypt；停用、重置密码、角色变更统一执行：

```java
user.revokeAllSessions(); // tokenVersion += 1
refreshTokenRepository.revokeAllActiveByUserId(user.getId(), OffsetDateTime.now());
authAuditService.record(actorId, user, "USER_DISABLED", reason);
```

登录失败、刷新令牌重放和授权拒绝同样经 `AuthAuditService` 写入 `audit_logs`，且不记录密码或原始令牌。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl apps/api -Dtest=UserManagementApiTest,AuthApiTest test`  
Expected: PASS；仅管理员可以操作用户，敏感操作立即撤销会话并具备审计记录。

- [ ] **Step 5: 提交**

```bash
git add apps/api/src/main/java/com/idavy/drtops/auth apps/api/src/test/java/com/idavy/drtops/auth/UserManagementApiTest.java
git commit -m "feat: add audited user administration"
```

### Task 5: 前端认证状态、请求拦截和受保护路由

**Files:**
- Create: `apps/admin-web/src/auth/authStore.ts`
- Create: `apps/admin-web/src/auth/permissions.ts`
- Create: `apps/admin-web/src/api/auth.ts`
- Modify: `apps/admin-web/src/api/http.ts`
- Modify: `apps/admin-web/src/router/index.ts`
- Modify: `apps/admin-web/src/main.ts`
- Create: `apps/admin-web/src/pages/LoginPage.vue`
- Test: `apps/admin-web/src/auth/auth-store.test.ts`
- Test: `apps/admin-web/src/api/http.test.ts`
- Test: `apps/admin-web/src/router/router-auth.test.ts`

**Interfaces:**
- Produces `authStore.login(username, password): Promise<void>`、`authStore.restore(): Promise<boolean>`、`authStore.logout(): Promise<void>`、`authStore.has(permission): boolean` 和仅供 Vitest 使用的 `authStore.setSessionForTest(session): void`。
- `request<T>` 自动发送 `Authorization: Bearer <accessToken>`，遇到 401 时只调用一次 `POST /api/auth/refresh` 后重放原请求。
- 路由 `meta.permission` 使用 `PermissionCode`，`/login` 不要求登录。

- [ ] **Step 1: 编写失败的前端认证测试**

```ts
it("retries one protected request after refresh", async () => {
  fetchMock
    .mockResolvedValueOnce(new Response("", { status: 401 }))
    .mockResolvedValueOnce(jsonResponse({ accessToken: "new-token", user: operator }))
    .mockResolvedValueOnce(jsonResponse({ data: [] }));

  await expect(request("/api/orders")).resolves.toEqual([]);
  expect(fetchMock).toHaveBeenCalledTimes(3);
});

it("redirects an unauthenticated visitor to login", async () => {
  await router.push("/dispatch");
  expect(router.currentRoute.value.name).toBe("login");
});
```

同时覆盖刷新失败后清空会话、403 不刷新、无 `RESOURCE_MANAGE` 权限不能进入资源配置、运营人员可进入订单中心。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm.cmd run test -- auth-store.test.ts http.test.ts router-auth.test.ts`  
Expected: FAIL，因为认证状态、刷新逻辑和路由守卫尚不存在。

- [ ] **Step 3: 实现前端认证边界**

`authStore` 使用 Vue `reactive` 保存访问令牌与 `CurrentUserResponse`，不写入 `localStorage` 或 `sessionStorage`。`auth.ts` 的刷新请求必须使用 `credentials: "include"`；`http.ts` 对所有普通请求添加 Bearer 令牌，并用模块内 `refreshPromise` 防止并发请求重复刷新。

路由定义先添加登录与已有业务路由的权限元数据：

```ts
{ path: "/login", name: "login", component: LoginPage, meta: { public: true } }
{ path: "/dispatch", name: "dispatch", component: DispatchWorkbenchPage,
  meta: { title: "调度工作台", permission: "DISPATCH_EXECUTE" } }
```

全局守卫先调用一次 `authStore.restore()`；未认证转 `/login?redirect=<target>`，已认证但无权限保留原页面并转至首个可用路由。

- [ ] **Step 4: 运行测试确认通过**

Run: `npm.cmd run typecheck; npm.cmd run test -- auth-store.test.ts http.test.ts router-auth.test.ts`  
Expected: PASS；访问令牌不落盘，刷新只重试一次，路由权限正确。

- [ ] **Step 5: 提交**

```bash
git add apps/admin-web/src/auth apps/admin-web/src/api/auth.ts apps/admin-web/src/api/http.ts apps/admin-web/src/router/index.ts apps/admin-web/src/main.ts apps/admin-web/src/pages/LoginPage.vue apps/admin-web/src/api/http.test.ts
git commit -m "feat: add admin web authentication flow"
```

### Task 6: 角色感知导航与用户权限管理页面

**Files:**
- Create: `apps/admin-web/src/pages/UserManagementPage.vue`
- Modify: `apps/admin-web/src/layouts/AppLayout.vue`
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/router/index.ts`
- Modify: `apps/admin-web/src/pages/OrdersPage.vue`
- Modify: `apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- Modify: `apps/admin-web/src/pages/TasksPage.vue`
- Modify: `apps/admin-web/src/pages/ResourcesPage.vue`
- Modify: `apps/admin-web/src/pages/RulesPage.vue`
- Test: `apps/admin-web/src/pages/user-management-page.test.ts`
- Test: `apps/admin-web/src/layouts/app-layout.test.ts`

**Interfaces:**
- Consumes `authStore.has(permission)`、`listUsers()`、`createUser()`、`updateUserRoles()`、`resetPassword()`、`setUserEnabled()`。
- Produces `/users` 管理页面，仅 `USER_MANAGE` 可访问。

- [ ] **Step 1: 写失败的页面与导航测试**

```ts
render(AppLayout, { global: { plugins: [router] } });
expect(screen.queryByRole("link", { name: "用户与权限" })).not.toBeInTheDocument();

    authStore.setSessionForTest(adminSession);
await router.push("/");
expect(await screen.findByRole("link", { name: "用户与权限" })).toBeInTheDocument();
```

为用户管理页覆盖创建用户、分配 `DISPATCHER`、禁用账号和密码重置；为订单页覆盖运营人员可见录入动作，为调度和任务页覆盖无操作权限时动作不渲染或禁用。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm.cmd run test -- user-management-page.test.ts app-layout.test.ts`  
Expected: FAIL，因为导航和用户管理页尚不存在。

- [ ] **Step 3: 实现最小管理界面与操作权限**

在 `router/index.ts` 增加仅由 `USER_MANAGE` 访问的 `/users` 路由。在 `AppLayout.vue` 按权限过滤 `navItems`，在顶部显示当前用户显示名和退出图标按钮。`UserManagementPage.vue` 使用紧凑表格展示用户名、姓名、角色、状态和更新时间；管理员可通过表单创建用户、通过多选框分配预置角色、执行重置密码和启停操作。

现有页面动作使用权限保护：

```vue
<button v-if="authStore.has('DISPATCH_EXECUTE')" @click="dispatchOrder(order.id)">
  发起调度
</button>
```

不得仅依赖前端隐藏；所有操作仍由后端返回 403 兜底。

- [ ] **Step 4: 运行测试确认通过**

Run: `npm.cmd run typecheck; npm.cmd run test -- user-management-page.test.ts app-layout.test.ts orders-page.test.ts dispatch-workbench.test.ts tasks-page.test.ts`  
Expected: PASS；角色可见性与用户管理交互通过。

- [ ] **Step 5: 提交**

```bash
git add apps/admin-web/src/pages/UserManagementPage.vue apps/admin-web/src/layouts/AppLayout.vue apps/admin-web/src/api/types.ts apps/admin-web/src/router/index.ts apps/admin-web/src/pages/user-management-page.test.ts apps/admin-web/src/layouts/app-layout.test.ts apps/admin-web/src/pages/OrdersPage.vue apps/admin-web/src/pages/DispatchWorkbenchPage.vue apps/admin-web/src/pages/TasksPage.vue apps/admin-web/src/pages/ResourcesPage.vue apps/admin-web/src/pages/RulesPage.vue
git commit -m "feat: add role aware admin web controls"
```

### Task 7: 全栈验收、部署说明与回归验证

**Files:**
- Create: `apps/admin-web/e2e/auth-rbac.spec.ts`
- Modify: `apps/admin-web/e2e/dispatch-flow.spec.ts`
- Modify: `README.md`
- Modify: `docs/release/mvp-readiness-checklist.md`
- Modify: `.superpowers/sdd/progress.md`
- Test: `apps/api/src/test/java/com/idavy/drtops/e2e/AuthRbacFlowIntegrationTest.java`

**Interfaces:**
- 消费 Task 2-6 的登录、用户管理与权限接口。
- 产出可重复的本地初始管理员配置说明和权限验收证据。

- [ ] **Step 1: 写失败的全栈验收测试**

```ts
test("operator can create an order but cannot dispatch it", async ({ page }) => {
  await loginAs(page, "operator01", "Secret123!");
  await page.getByRole("button", { name: "录入需求" }).click();
  await expect(page.getByRole("button", { name: "发起调度" })).toHaveCount(0);
});

test("disabled account loses access after the next request", async ({ page }) => {
  await loginAs(page, "admin01", "Secret123!");
  await disableUser(page, "dispatcher01");
  await expect(dispatcherRequest()).rejects.toThrow(/401/);
});
```

后端集成测试使用真实认证过滤链验证管理员创建运营人员、运营人员录入订单、运营人员派单返回 403、调度员派单成功、审计员读取日志成功。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl apps/api -Dtest=AuthRbacFlowIntegrationTest test; npm.cmd run e2e -- auth-rbac.spec.ts`  
Expected: FAIL，因为角色登录和权限流程尚未完整接线。

- [ ] **Step 3: 完成验收说明和安全配置说明**

README 必须说明以下本地启动变量，示例只使用占位符，不提交真实密码：

```powershell
$env:DRT_AUTH_JWT_SECRET = "replace-with-at-least-32-random-bytes"
$env:DRT_AUTH_BOOTSTRAP_ADMIN_USERNAME = "admin"
$env:DRT_AUTH_BOOTSTRAP_ADMIN_PASSWORD = "replace-this-temporary-password"
```

说明生产环境必须设置 HTTPS、`DRT_AUTH_REFRESH_COOKIE_SECURE=true`、强随机 JWT 密钥和安全的初始管理员密码。将认证/RBAC 验证结果、尚未实现的 OIDC/LDAP 与多租户范围更新到就绪清单和进度记录。

- [ ] **Step 4: 运行完整回归验证**

Run:

```powershell
mvn -q -pl apps/api test
python -m pytest -v -p no:cacheprovider
cd apps/admin-web
npm.cmd run typecheck
npm.cmd run test
npm.cmd run build
npm.cmd run e2e -- dispatch-flow.spec.ts
npm.cmd run e2e -- auth-rbac.spec.ts
```

Expected: 所有命令退出码为 0；MapLibre chunk 告警可保留为已知非阻断风险。

- [ ] **Step 5: 提交**

```bash
git add apps/api/src/test/java/com/idavy/drtops/e2e/AuthRbacFlowIntegrationTest.java apps/admin-web/e2e README.md docs/release/mvp-readiness-checklist.md .superpowers/sdd/progress.md
git commit -m "test: verify enterprise auth rbac flow"
```

## 计划自检

- 规格覆盖：Task 1-2 覆盖本地账号、密码哈希、JWT、刷新令牌和初始管理员；Task 3-4 覆盖固定角色、后端授权、调度决策读取和审计；Task 5-6 覆盖前端登录、路由、导航与用户管理；Task 7 覆盖全栈验收、文档和已知限制。
- 无占位符：任务、文件路径、接口、权限、测试命令和提交消息均明确给出。
- 类型一致性：后端统一使用 `RoleCode`、`Permission`、`UserAccount`、`RefreshToken`；前端使用相同的 `PermissionCode` 字符串和 `CurrentUserResponse`。
