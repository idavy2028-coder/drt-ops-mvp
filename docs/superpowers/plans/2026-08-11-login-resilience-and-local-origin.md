# 登录韧性与本地来源配置实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让管理端登录准确区分凭据、CORS、网络与导航故障，避免浏览器旧密码被连续提交，并固化 5173、5174、5176 的精确本地来源配置。

**Architecture:** 登录页把认证和导航拆成两个阶段，登录专用错误分类保留在页面边界，不改变全局业务错误语义；401 清空并聚焦密码框，403 显示来源配置提示，临时密码用户直接进入改密路由。API 将来源默认值集中到 `application.yml`，Docker Compose 显式注入同一精确列表，既支持本地验证又继续拒绝通配来源。

**Tech Stack:** Vue 3、Vue Router 4、TypeScript、Vitest、Testing Library、Spring Boot、Spring MVC CORS、JUnit 5、MockMvc、Docker Compose。

## Global Constraints

- 不禁用浏览器密码管理器；用户名和密码输入框继续使用标准 `autocomplete` 语义。
- 不读取、记录或回显密码、密码哈希、Cookie、访问令牌或刷新令牌。
- CORS 仅使用精确来源，不允许 `*`、正则、动态回显或端口范围。
- HTTP 401 才清空密码；HTTP 403、网络错误和导航错误不得清空密码。
- `mustChangePassword=true` 登录成功后直接进入 `changePassword`，路由守卫继续作为第二道保护。
- 所有新行为必须先看到目标测试因行为缺失而失败，再写最小实现。
- 当前 `LoginPage.vue` 和 `login-page.test.ts` 中已有的诊断性差异必须纳入正式测试矩阵，不得绕过或直接丢弃。
- 不修改账号、密码、角色、刷新令牌或业务数据。

---

## File Structure

### 前端登录边界

- `apps/admin-web/src/pages/LoginPage.vue`
  - 负责表单状态、登录专用错误分类、密码框清空/聚焦和登录后目标路由选择。
- `apps/admin-web/src/pages/login-page.test.ts`
  - 使用真实 `LoginPage`、真实路由和受控 `fetch` 响应验证用户可见行为。
- `apps/admin-web/src/api/errors.ts`
  - 本计划默认不修改；登录专用 403 文案不得污染业务页面通用 403 文案。只有实现过程中发现重复的非登录错误逻辑时才允许在单独测试保护下修改。

### API 与运行配置

- `apps/api/src/main/java/com/idavy/drtops/config/WebCorsConfiguration.java`
  - 从集中配置读取精确来源，保留过滤空项和拒绝 `*` 的约束。
- `apps/api/src/main/resources/application.yml`
  - 定义 `drt.web.allowed-origins` 及 5173、5174、5176 的本地精确默认值。
- `apps/api/src/test/java/com/idavy/drtops/WebCorsConfigurationTest.java`
  - 覆盖允许来源、未知来源和通配符拒绝。
- `apps/api/src/test/resources/application.yml`
  - 为测试类路径提供与主配置一致的精确本地来源，避免测试资源覆盖主资源后缺少属性。
- `infra/docker-compose.pilot.yml`
  - 显式注入 `DRT_WEB_ALLOWED_ORIGINS`，允许部署环境覆盖。

### 运维与进度

- `docs/pilot/admin-login-troubleshooting.md`
  - 提供无敏感信息的登录排障顺序。
- `progress.md`
  - 记录根因、修复内容、验证证据和当前分支状态。

---

### Task 1: 登录失败分类与旧密码恢复引导

**Files:**
- Modify: `apps/admin-web/src/pages/LoginPage.vue`
- Modify: `apps/admin-web/src/pages/login-page.test.ts`

**Interfaces:**
- Consumes: `authStore.login(username: string, password: string): Promise<void>`、`ApiError.status`、`userMessage(error, fallback)`、Vue Router `replace()`。
- Produces: 页面稳定代码 `LOGIN-401`、`LOGIN-ORIGIN-403`、`LOGIN-NETWORK`、`LOGIN-UNKNOWN`；401 后密码清空与焦点恢复。

- [ ] **Step 1: 扩展 401 失败测试**

在 `login-page.test.ts` 增加测试，真实渲染页面并让 `fetch` 返回 401：

```ts
it("401 时清空密码、聚焦密码框并提示检查浏览器保存的旧密码", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
  const { router } = await renderLogin();

  const passwordInput = screen.getByLabelText("密码");
  await fireEvent.update(screen.getByLabelText("用户名"), "admin");
  await fireEvent.update(passwordInput, "TemporaryPassword123!");
  await fireEvent.click(screen.getByRole("button", { name: "登录" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-401");
  expect(screen.getByRole("alert")).toHaveTextContent("浏览器保存的旧密码");
  expect(passwordInput).toHaveValue("");
  expect(passwordInput).toHaveFocus();
  expect(router.currentRoute.value.name).toBe("login");
});
```

测试辅助函数必须创建真实内存路由并访问 `/login?redirect=/dispatch`，不得用假的路由组件替代：

```ts
async function renderLogin() {
  const router = createAppRouter(createMemoryHistory());
  await router.push("/login?redirect=/dispatch");
  await router.isReady();
  render(LoginPage, { global: { plugins: [router] } });
  return { router };
}
```

- [ ] **Step 2: 运行 401 测试并确认 RED**

Run:

```powershell
npm.cmd test -- src/pages/login-page.test.ts --maxWorkers=1
```

Expected: FAIL；当前页面不会显示 `LOGIN-401`，也不会清空和聚焦密码框。

- [ ] **Step 3: 增加 403、网络和未知异常测试**

加入三个独立测试：

```ts
it("403 时提示来源配置错误且保留密码", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 403 })));
  await renderLogin();
  const passwordInput = screen.getByLabelText("密码");
  await fireEvent.update(screen.getByLabelText("用户名"), "admin");
  await fireEvent.update(passwordInput, "TemporaryPassword123!");
  await fireEvent.click(screen.getByRole("button", { name: "登录" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-ORIGIN-403");
  expect(screen.getByRole("alert")).toHaveTextContent("当前访问地址未被运营服务允许");
  expect(screen.queryByText("用户名或密码不正确")).not.toBeInTheDocument();
  expect(passwordInput).toHaveValue("TemporaryPassword123!");
});

it("网络失败时显示 LOGIN-NETWORK", async () => {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
  await renderLogin();
  await submitCredentials();
  expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-NETWORK");
  expect(screen.getByRole("alert")).toHaveTextContent("暂时无法连接运营服务");
});

it("未知异常时显示 LOGIN-UNKNOWN", async () => {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("unexpected")));
  await renderLogin();
  await submitCredentials();
  expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-UNKNOWN");
  expect(screen.getByRole("alert")).toHaveTextContent("登录失败，请稍后重试");
});
```

- [ ] **Step 4: 运行分类测试并确认 RED**

Run: `npm.cmd test -- src/pages/login-page.test.ts --maxWorkers=1`

Expected: FAIL；现有诊断实现没有稳定错误代码，403 仍复用通用权限文案。

- [ ] **Step 5: 最小实现认证错误分类**

在 `LoginPage.vue` 增加密码输入元素引用：

```ts
import { nextTick, ref } from "vue";

const passwordInput = ref<HTMLInputElement | null>(null);
```

将认证异常处理收敛为登录页私有函数：

```ts
async function handleAuthenticationFailure(error: unknown): Promise<void> {
  if (error instanceof ApiError && error.status === 401) {
    password.value = "";
    errorMessage.value = "用户名或密码不正确；若密码刚被重置，请重新输入并更新浏览器保存的旧密码。[LOGIN-401]";
    await nextTick();
    passwordInput.value?.focus();
    return;
  }
  if (error instanceof ApiError && error.status === 403) {
    errorMessage.value = "当前访问地址未被运营服务允许，请检查本地前端地址和 CORS 白名单。[LOGIN-ORIGIN-403]";
    return;
  }
  if (error instanceof TypeError) {
    errorMessage.value = `${userMessage(error, "登录失败，请稍后重试")} [LOGIN-NETWORK]`;
    return;
  }
  errorMessage.value = "登录失败，请稍后重试。[LOGIN-UNKNOWN]";
}
```

模板中的密码输入框绑定真实元素：

```vue
<input
  ref="passwordInput"
  v-model="password"
  type="password"
  autocomplete="current-password"
  required
/>
```

- [ ] **Step 6: 运行登录分类测试并确认 GREEN**

Run: `npm.cmd test -- src/pages/login-page.test.ts --maxWorkers=1`

Expected: PASS；401、403、网络和未知异常行为均满足断言。

- [ ] **Step 7: 提交 Task 1**

```powershell
git add -- apps/admin-web/src/pages/LoginPage.vue apps/admin-web/src/pages/login-page.test.ts
git commit -m "fix: clarify admin login failures"
```

---

### Task 2: 登录成功后的稳定导航

**Files:**
- Modify: `apps/admin-web/src/pages/LoginPage.vue`
- Modify: `apps/admin-web/src/pages/login-page.test.ts`

**Interfaces:**
- Consumes: Task 1 的认证错误处理、`authStore.user?.mustChangePassword`、命名路由 `changePassword`。
- Produces: `mustChangePassword=true` 直接导航；导航失败稳定代码 `LOGIN-NAVIGATION`。

- [ ] **Step 1: 编写强制改密导航测试**

构造完整真实登录响应：

```ts
const temporaryAdminSession = {
  data: {
    accessToken: "temporary-access-token",
    expiresAt: "2099-01-01T00:00:00Z",
    user: {
      id: "admin-1",
      username: "admin",
      roles: ["SYSTEM_ADMIN"],
      mustChangePassword: true
    }
  }
};

it("临时密码账号登录成功后直接进入改密页", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(temporaryAdminSession), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  })));
  const { router } = await renderLogin();
  await submitCredentials();
  await waitFor(() => expect(router.currentRoute.value.name).toBe("changePassword"));
});
```

- [ ] **Step 2: 编写导航失败测试**

先完成路由初始化，再监视 `replace`：

```ts
it("认证成功但导航失败时不误报密码错误", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(successfulNormalSessionResponse()));
  const { router } = await renderLogin();
  vi.spyOn(router, "replace").mockRejectedValueOnce(new Error("navigation failed"));
  await submitCredentials();
  expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-NAVIGATION");
  expect(screen.queryByText("用户名或密码不正确")).not.toBeInTheDocument();
  expect(authStore.authenticated).toBe(true);
});
```

- [ ] **Step 3: 运行导航测试并确认 RED**

Run: `npm.cmd test -- src/pages/login-page.test.ts --maxWorkers=1`

Expected: FAIL；当前单一 `try/catch` 会把导航失败当成登录失败，且没有显式目标选择。

- [ ] **Step 4: 最小实现两阶段提交**

将 `submit()` 改为认证和导航两个 `try/catch`：

```ts
async function submit(): Promise<void> {
  submitting.value = true;
  errorMessage.value = "";
  try {
    try {
      await authStore.login(username.value, password.value);
    } catch (error) {
      await handleAuthenticationFailure(error);
      return;
    }

    const destination = authStore.user?.mustChangePassword === true
      ? { name: "changePassword" }
      : typeof route.query.redirect === "string"
        ? route.query.redirect
        : "/";
    try {
      await router.replace(destination);
    } catch {
      errorMessage.value = "登录成功，但页面跳转失败，请刷新后重试。[LOGIN-NAVIGATION]";
    }
  } finally {
    submitting.value = false;
  }
}
```

- [ ] **Step 5: 运行登录页测试并确认 GREEN**

Run: `npm.cmd test -- src/pages/login-page.test.ts --maxWorkers=1`

Expected: PASS。

- [ ] **Step 6: 运行路由和改密相关回归测试**

Run:

```powershell
npm.cmd test -- src/router/router-auth.test.ts src/pages/change-password-page.test.ts src/pages/login-page.test.ts --maxWorkers=1
```

Expected: PASS。

- [ ] **Step 7: 提交 Task 2**

```powershell
git add -- apps/admin-web/src/pages/LoginPage.vue apps/admin-web/src/pages/login-page.test.ts
git commit -m "fix: separate login and navigation failures"
```

---

### Task 3: 精确本地 CORS 来源配置

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/java/com/idavy/drtops/config/WebCorsConfiguration.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/WebCorsConfigurationTest.java`
- Modify: `apps/api/src/test/resources/application.yml`
- Modify: `infra/docker-compose.pilot.yml`

**Interfaces:**
- Consumes: 环境变量 `DRT_WEB_ALLOWED_ORIGINS`，逗号分隔精确来源。
- Produces: 配置项 `drt.web.allowed-origins`；5173、5174、5176 的 localhost/127.0.0.1 默认列表；通配符启动拒绝。

- [ ] **Step 1: 扩展允许来源与未知来源测试**

在 `WebCorsConfigurationTest` 添加：

```java
@ParameterizedTest
@ValueSource(strings = {
        "http://localhost:5173",
        "http://127.0.0.1:5173",
        "http://localhost:5174",
        "http://127.0.0.1:5174",
        "http://localhost:5176",
        "http://127.0.0.1:5176"
})
void apiPreflightAllowsConfiguredLocalOrigins(String origin) throws Exception {
    mockMvc.perform(options("/api/auth/login")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", origin));
}

@Test
void apiPreflightRejectsUnknownOrigin() throws Exception {
    mockMvc.perform(options("/api/auth/login")
                    .header("Origin", "http://127.0.0.1:5999")
                    .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
}

@Test
void wildcardOriginIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new WebCorsConfiguration("*"));
}
```

- [ ] **Step 2: 运行 CORS 测试并确认 RED**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api -Dtest=WebCorsConfigurationTest test
```

Expected: FAIL；5174、5176 尚未出现在默认来源列表。

- [ ] **Step 3: 集中应用配置默认值**

在 `application.yml` 的 `drt` 下增加：

```yaml
  web:
    allowed-origins: ${DRT_WEB_ALLOWED_ORIGINS:http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174,http://localhost:5176,http://127.0.0.1:5176}
```

把 `WebCorsConfiguration` 构造参数改为只读取集中配置：

```java
public WebCorsConfiguration(@Value("${drt.web.allowed-origins}") String allowedOrigins) {
```

不得删除空项过滤、`*` 拒绝、`allowCredentials(true)` 或 `/api/**` 限定。

- [ ] **Step 4: 显式配置 Docker Compose**

在 API 服务环境变量中加入：

```yaml
DRT_WEB_ALLOWED_ORIGINS: ${DRT_WEB_ALLOWED_ORIGINS:-http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174,http://localhost:5176,http://127.0.0.1:5176}
```

- [ ] **Step 5: 运行 CORS 测试并确认 GREEN**

Run: `& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api -Dtest=WebCorsConfigurationTest test`

Expected: PASS。

- [ ] **Step 6: 验证 Compose 展开结果**

Run:

```powershell
docker compose -f infra/docker-compose.pilot.yml config
```

Expected: API 环境中存在精确 `DRT_WEB_ALLOWED_ORIGINS`，值不含 `*`，YAML 可解析。

- [ ] **Step 7: 提交 Task 3**

```powershell
git add -- apps/api/src/main/resources/application.yml apps/api/src/main/java/com/idavy/drtops/config/WebCorsConfiguration.java apps/api/src/test/java/com/idavy/drtops/WebCorsConfigurationTest.java apps/api/src/test/resources/application.yml infra/docker-compose.pilot.yml
git commit -m "fix: declare local admin web origins"
```

---

### Task 4: 登录排障文档与进度记录

**Files:**
- Create: `docs/pilot/admin-login-troubleshooting.md`
- Modify: `progress.md`

**Interfaces:**
- Consumes: Task 1-3 的稳定错误代码和配置变量。
- Produces: 不含敏感信息的现场排障流程与项目恢复上下文。

- [ ] **Step 1: 编写登录排障文档**

文档必须按以下顺序给出检查动作：

1. 记录页面稳定错误代码，不抄录密码或 Cookie。
2. `LOGIN-401`：重新手工输入密码，并更新或删除浏览器保存的旧凭据。
3. `LOGIN-ORIGIN-403`：核对地址栏 Origin 与 `DRT_WEB_ALLOWED_ORIGINS` 的精确匹配。
4. `LOGIN-NETWORK`：检查前端服务、代理目标与 API health。
5. `LOGIN-NAVIGATION`：刷新后检查路由和角色权限，不重置密码。
6. 浏览器失败而命令行成功时，优先检查 Origin 和密码管理器差异。
7. 明确禁止直接修改数据库密码哈希、伪造令牌或在文档中保存真实凭据。

- [ ] **Step 2: 更新 progress.md**

记录：

- admin 密码重置与首次改密已由用户本人完成。
- 根因是 5176 Origin 不在白名单，以及 Chrome 提交已保存旧密码。
- 新版前端在 5174 连接真实 API 后登录成功。
- 本次修复仍保持 ETA 与 P6-1 结论不变，不启动 P6-2。
- 列出测试和提交证据，不写真实用户名以外的个人信息，不写密码或令牌。

- [ ] **Step 3: 执行占位符与敏感信息扫描**

Run:

```powershell
rg -n "T[B]D|T[O]DO|Authorization: Bearer [A-Za-z0-9._-]{20,}|Cookie: [^ ]+=[^ ]+" docs/pilot/admin-login-troubleshooting.md progress.md
```

Expected: 无真实凭据、令牌或占位符命中；若 `password_hash` 仅作为禁止项文字出现，应改写为“密码哈希字段”避免扫描歧义。

- [ ] **Step 4: 提交 Task 4**

```powershell
git add -- docs/pilot/admin-login-troubleshooting.md progress.md
git commit -m "docs: add admin login troubleshooting"
```

---

### Task 5: 全量验证与真实浏览器验收

**Files:**
- Verify only: Task 1-4 全部文件

**Interfaces:**
- Consumes: 已提交的前端、API、配置和文档变更。
- Produces: 可供人工审阅的测试、构建、真实登录和敏感信息检查证据。

- [ ] **Step 1: 运行管理端完整验证**

```powershell
cd apps/admin-web
npm.cmd test -- --maxWorkers=1
npm.cmd run typecheck
npm.cmd run build
```

Expected: 全部 exit 0；若既有 E2E 夹具失败，不得写成通过，必须区分基线与本次改动。

- [ ] **Step 2: 运行 API 验证**

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api -Dtest=WebCorsConfigurationTest test
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api test
```

Expected: exit 0。

- [ ] **Step 3: 运行差异与敏感信息检查**

```powershell
git diff --check
git status --short
rg -n "Authorization: Bearer [A-Za-z0-9._-]{20,}|Cookie: [^ ]+=[^ ]+|password[_-]?hash\s*[:=]\s*[^$]" apps/admin-web apps/api infra docs/pilot progress.md
```

Expected: 无真实密码、Cookie、令牌或密码哈希；仅允许代码中的 Cookie 常量等预期命中，并逐条解释。

- [ ] **Step 4: 真实 Chrome 验收**

使用 `http://127.0.0.1:5174` 和真实 API：

- 使用测试错误密码验证 `LOGIN-401`、清空和聚焦；不得使用或读取用户正式密码。
- 使用受控测试账号验证登录成功与目标路由；不得替用户设置正式密码。
- 使用未允许来源验证 `LOGIN-ORIGIN-403`，结束后恢复允许地址。
- 截图不得包含密码、Token、Cookie、手机号或乘客信息。

- [ ] **Step 5: 最终提交检查**

```powershell
git log -5 --oneline
git status --short
```

Expected: 工作树仅保留已知且明确说明的非本任务差异；本任务文件全部已提交，不推送、不创建 PR，除非用户另行授权。
