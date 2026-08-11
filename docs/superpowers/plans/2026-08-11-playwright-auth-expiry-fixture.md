# Playwright 登录与业务夹具修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清除固定日历日期和页面依赖演进造成的 E2E 基线漂移，使管理端完整 Playwright 套件在任意运行日期稳定达到 5/5。

**Architecture:** 登录 mock 在请求执行时生成未来 24 小时的 ISO 到期时间；为当前页面实际加载的只读资源补齐最小 API mock；按当前订单表单要求录入地址与坐标；让车辆位置页面在已完成登录后挂载。E2E 包装器每次预留独立空闲端口、启动本工作树 Vite，并通过环境变量把地址交给 Playwright，禁止复用任意已存在的本地服务。保留 `auth-rbac.spec.ts` 中故意过期的会话语义，不修改生产鉴权、页面或 API 代码。

**Tech Stack:** TypeScript、Playwright、Vue 3、npm。

## 已证实的根因

- `authStore.ts` 当前只保存 `accessToken` 和 `user`，不读取 `expiresAt`。固定的 2026-07 值属于过时夹具，但不是本次 401 的直接来源。
- `DispatchWorkbenchPage`、`TasksPage` 和订单创建对话框已新增位置、服务区、虚拟站点及任务位置事件请求；测试未拦截时后端返回 401，随后 `/api/auth/refresh` mock 返回 401，最终清空会话。
- 订单创建对话框现在要求起点和终点坐标，旧用例只填乘客字段，已不满足当前表单约束。
- 车辆位置第一条用例在退出后登录管理员时会先落到看板；未 mock 的运营指标请求会触发同一 401 链路。
- 车辆位置第二条用例通过受保护地址的登录重定向进入页面，页面在报告权限可用前执行一次 `onMounted`，没有加载待命上报资源；该用例应先完成登录，再通过导航进入它真正要验证的位置页面。
- `run-e2e.mjs` 过去只要发现固定 `5173` 可访问就复用；本机该端口由其他服务占用，导致测试运行在错误构建上。独立端口诊断已证明同一待命用例在本工作树 Vite 上通过。

## Global Constraints

- 变更只涉及两份业务 E2E spec、`playwright.config.ts`、`scripts/run-e2e.mjs` 和本计划文档。
- 不修改 `auth-rbac.spec.ts`、生产鉴权/页面/API 代码、依赖版本或业务事件时间。
- 只 mock 当前用例所需 API，响应形状复用已有页面/测试契约；不得用宽泛 catch-all 伪造成功。
- 登录会话到期时间必须在每次 mock 登录请求执行时生成，不得替换成新的固定日历日期。
- E2E 修复与 P6-1 评估证据保持独立分支、独立提交和独立 PR。

---

### Task 1: 修复持续有效登录与当前业务页面夹具

**Files:**
- Modify: `apps/admin-web/e2e/dispatch-flow.spec.ts`
- Modify: `apps/admin-web/e2e/vehicle-location-flow.spec.ts`
- Modify: `apps/admin-web/playwright.config.ts`
- Modify: `apps/admin-web/scripts/run-e2e.mjs`
- Test: `apps/admin-web/e2e/dispatch-flow.spec.ts`
- Test: `apps/admin-web/e2e/vehicle-location-flow.spec.ts`
- Test: `apps/admin-web/e2e/auth-rbac.spec.ts`

- [x] **Step 1: 保留并记录 RED 证据**

基线已运行：

```powershell
cd apps/admin-web
npm.cmd run e2e -- dispatch-flow.spec.ts vehicle-location-flow.spec.ts
```

实际为 0/4。单 worker 复现排除了并发因素；网络 trace 和错误快照证明失败来自未拦截 API 的 401、过时表单交互和登录后页面挂载时序，而非生产代码回归。

- [x] **Step 2: 修复调度流程夹具**

在 `dispatch-flow.spec.ts`：

1. 将登录响应的固定 `expiresAt` 改为请求时计算的未来 24 小时。
2. 为 `/api/vehicles/locations/latest`、`/api/service-areas`、`/api/virtual-stops` 和任务位置事件补齐最小响应，并在订单流和人工复核流中复用。
3. 先打开订单创建对话框，再展开两个手工坐标区域，按现有 mock 使用的地址和坐标填入起终点字段后提交订单。
4. 保留对业务状态变化的断言，但按当前 `StatusBadge` 展示契约断言中文状态标签；不得改变 API 状态码。

- [x] **Step 3: 修复车辆位置流程夹具**

在 `vehicle-location-flow.spec.ts`：

1. 将登录响应的固定 `expiresAt` 改为请求时计算的未来 24 小时。
2. 为管理员登录后看板带日期查询参数的 `/api/metrics/operations-summary**` 补齐与现有 RBAC 用例一致的完整零值指标响应。
3. 待命上报用例先访问已具备完整 mock 的任务页并完成登录，再点击“位置历史”导航，使 `VehicleLocationHistoryPage` 在已认证、已授权状态下挂载并加载上报资源。
4. 不修改故意验证过期语义的 `auth-rbac.spec.ts`，也不修改 2026-07 业务事件时间。

- [x] **Step 4: 运行目标 GREEN**

先让 `run-e2e.mjs` 预留空闲端口并始终启动自己的 Vite；`playwright.config.ts` 从 `PLAYWRIGHT_BASE_URL` 读取该地址，未设置时保留原默认值。这样下列标准命令即使 `5173` 已被占用，也必须运行本工作树代码。

```powershell
cd apps/admin-web
npm.cmd run e2e -- dispatch-flow.spec.ts vehicle-location-flow.spec.ts --workers=1
npm.cmd run e2e -- dispatch-flow.spec.ts vehicle-location-flow.spec.ts
```

Expected: 两次均为 4/4 通过。

- [x] **Step 5: 运行完整管理端验证**

```powershell
cd apps/admin-web
npm.cmd run typecheck
npm.cmd test
npm.cmd run e2e
```

Expected: 类型检查退出码 0；Vitest 全量通过；Playwright 5/5 通过。

- [x] **Step 6: 检查范围并提交**

```powershell
git diff --check
git status --short
git diff --stat origin/master
```

Expected: 只包含本计划、两份 E2E spec、Playwright 配置和 E2E 包装器，共 5 个目标文件；无格式错误。

```powershell
git add docs/superpowers/plans/2026-08-11-playwright-auth-expiry-fixture.md apps/admin-web/e2e/dispatch-flow.spec.ts apps/admin-web/e2e/vehicle-location-flow.spec.ts apps/admin-web/playwright.config.ts apps/admin-web/scripts/run-e2e.mjs
git commit -m "test: stabilize Playwright business fixtures"
```
