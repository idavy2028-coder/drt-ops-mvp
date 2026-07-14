# 车辆任务操作链路专项排查与修复计划

> 本计划先排查并锁定根因，再实施最小修复。执行前不修改权限边界、跨域策略或业务状态机。

## 任务 1：建立“选择的任务就是操作的任务”回归证据

**目标：** 复现并阻止任务选择被首条任务静默替代。

**涉及文件：**
- `apps/admin-web/src/pages/tasks-page.test.ts`
- 仅在测试失败后修改：`apps/admin-web/src/pages/TasksPage.vue`

**步骤：**
1. 构造首条 `COMPLETED` 任务和非首条 `DISPATCHED` 任务，且两条任务使用不同的站点名称与 ID。
2. 点击非首条任务的“选择”，断言：选中行状态、时间线内容、“发车”可用、“到站/上车/下车/完成”不可用。
3. 点击“发车”时，mock 的 `startTask` 必须收到非首条任务 ID。
4. 增加“重新加载后选中任务不存在”的测试；期望清空选择并禁用动作，而非回退到首条任务。
5. 如测试失败，提取显式的 `selectTask` 与 `selectedTask` 空状态；在加载时仅在没有选择时选择首条任务。

**验证：**
```powershell
Set-Location apps/admin-web
npm run test -- tasks-page.test.ts
npm run typecheck
```

## 任务 2：锁定任务动作 `401` 的请求与令牌状态

**目标：** 判断 `401` 的来源是前端未附带令牌、刷新失败，还是后端拒绝了有效令牌。

**涉及文件：**
- `apps/admin-web/src/api/http.ts` 及其现有或新增测试文件
- `apps/api/src/test/java/.../auth/` 下的认证或授权测试
- 必要时：`apps/api/src/main/java/.../config/JwtAuthenticationFilter.java`

**步骤：**
1. 为 `POST /api/vehicle-tasks/{id}/start` 建立请求测试：首个请求必须带 `Authorization: Bearer <token>`。
2. 模拟首个动作请求 `401`、刷新成功、重试成功；断言重试使用新令牌且只刷新一次。
3. 模拟刷新失败；断言页面获得业务化错误反馈，且不会以首条任务重试动作。
4. 用 `dispatcher01` 取得新令牌，对同一 `start` API 做最小直接调用；分别记录 HTTP 状态、请求任务 ID 与服务端审计结果。
5. 如直接调用 `200` 而页面仍 `401`，根因定位前端会话/请求组装；如直接调用也 `401`，检查 JWT 的 tokenVersion、角色 claims 与过滤器失败分支。

**验证：**
```powershell
Set-Location apps/admin-web
npm run test -- http
Set-Location ..\..
$env:Path = "$PWD\.tools\apache-maven-3.9.11\bin;$env:Path"
mvn -q -pl apps/api -Dtest=AuthorizationApiTest,VehicleTaskApiTest test
```

## 任务 3：最小修复与端到端复验

**前置条件：** 任务 1、2 已确认根因并且对应失败测试已建立。

**步骤：**
1. 仅修复经证实的选择状态或认证请求缺陷，不调整状态机和角色权限集合。
2. 运行前端完整测试、类型检查和生产构建；运行 API 全量测试。
3. 重启本地服务并以 `operator01` 创建新复验订单，以 `dispatcher01` 完成调度、人工确认和所有车辆任务动作。
4. 用管理员核对运营看板与审计日志；确认上海运营日指标和任务完成序列。
5. 更新 `docs/release/internal-acceptance-2026-07-13.md` 与 `.superpowers/sdd/progress.md`。

**验收门槛：**
- 非首条任务选择、发车及完整状态机均由真实页面完成。
- 不出现裸露的 `Request failed with status 401`。
- 代码检查、自动化测试、浏览器复验和审计记录均有可追溯证据。
