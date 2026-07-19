# 通渭县试点规则组首建实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 在规则组为空时，系统管理员能够创建“通渭县试点动态调度规则”，并在创建后继续编辑和保存规则。

**架构：** 后端在既有 `dispatch_rule_sets` 领域对象和控制器上新增创建命令；前端新增独立的创建表单，通过现有规则配置页在空状态或管理员主动点击时展示。创建结果进入既有规则列表并自动选中，后续更新仍复用现有 `PUT` 接口。

**技术栈：** Java 21、Spring Boot、Spring MVC Test、Vue 3、TypeScript、Vitest、Testing Library。

## 全局约束

- 默认值固定为：名称“通渭县试点动态调度规则”、最大等候 5、最大绕行 8、预约窗口 60、自动阈值 82、人工阈值 62、权重 0.35/0.20/0.30/0.15、`REALTIME_INSERTION`。
- 创建接口必须受既有 `RULE_MANAGE` 权限保护；不得通过 SQL 补写规则组。
- 不改动服务区、虚拟站点、车辆、驾驶员、订单或调度状态机。
- 所有新增界面和业务错误文案使用中文；创建失败时保留用户输入。
- 每项任务先写失败测试，观察失败后再编写最小实现；每项完成后等待审阅。

---

## 文件职责

| 文件 | 职责 |
| --- | --- |
| `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchRuleSet.java` | 提供不依赖演示默认值的规则组领域创建工厂。 |
| `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetController.java` | 暴露 `POST /api/dispatch-rule-sets` 并复用既有字段校验。 |
| `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetApiTest.java` | 覆盖规则组创建成功、字段校验和权限边界。 |
| `apps/admin-web/src/api/rules.ts` | 定义创建请求类型并调用后端创建接口。 |
| `apps/admin-web/src/components/RuleSetCreateForm.vue` | 展示可编辑的试点默认参数并提交创建请求。 |
| `apps/admin-web/src/components/rule-set-create-form.test.ts` | 覆盖默认值、提交事件和提交中禁用状态。 |
| `apps/admin-web/src/pages/RulesPage.vue` | 在零规则组或点击“新建规则组”时展示创建表单，成功后选中新建规则组。 |
| `apps/admin-web/src/pages/rules-page.test.ts` | 覆盖零规则组首建和创建后的页面状态。 |

### Task 1：后端规则组创建接口

**文件：**
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchRuleSet.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetController.java`
- 测试：`apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetApiTest.java`

**接口：**
- 产出：`POST /api/dispatch-rule-sets`，请求体为 `CreateDispatchRuleSetRequest`，成功返回 `201 Created` 与 `DispatchRuleSet`。
- 后续依赖：前端调用该接口，成功响应包含 `id`、`name` 与全部规则字段。

- [ ] **步骤 1：写失败 API 测试**

在 `DispatchRuleSetApiTest` 中增加空库创建场景，测试必须先清空规则组并断言新接口尚不存在：

```java
@Test
void createsRuleSetForPilotBootstrap() throws Exception {
    repository.deleteAll();

    mockMvc.perform(post("/api/dispatch-rule-sets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"通渭县试点动态调度规则","maxWaitMinutes":5,
                             "maxDetourMinutes":8,"bookingWindowMinutes":60,
                             "autoDispatchScoreThreshold":82,"manualReviewScoreThreshold":62,
                             "waitWeight":0.35,"detourWeight":0.20,
                             "stabilityWeight":0.30,"utilizationWeight":0.15,
                             "insertionPolicy":"REALTIME_INSERTION"}
                            """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value("通渭县试点动态调度规则"))
            .andExpect(jsonPath("$.data.maxWaitMinutes").value(5))
            .andExpect(jsonPath("$.data.insertionPolicy").value("REALTIME_INSERTION"));
}

@Test
void rejectsCreateWithoutRuleManagePermission() throws Exception {
    mockMvc.perform(post("/api/dispatch-rule-sets")
                    .with(user("operator").authorities(() -> "ORDER_READ"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"无权限规则\",\"maxWaitMinutes\":5,\"maxDetourMinutes\":8,\"bookingWindowMinutes\":60,\"autoDispatchScoreThreshold\":82,\"manualReviewScoreThreshold\":62,\"waitWeight\":0.35,\"detourWeight\":0.20,\"stabilityWeight\":0.30,\"utilizationWeight\":0.15,\"insertionPolicy\":\"REALTIME_INSERTION\"}"))
            .andExpect(status().isForbidden());
}

@Test
void rejectsCreateWithBlankName() throws Exception {
    mockMvc.perform(post("/api/dispatch-rule-sets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\" \",\"maxWaitMinutes\":5,\"maxDetourMinutes\":8,\"bookingWindowMinutes\":60,\"autoDispatchScoreThreshold\":82,\"manualReviewScoreThreshold\":62,\"waitWeight\":0.35,\"detourWeight\":0.20,\"stabilityWeight\":0.30,\"utilizationWeight\":0.15,\"insertionPolicy\":\"REALTIME_INSERTION\"}"))
            .andExpect(status().isBadRequest());
}
```

同时在测试文件补充静态导入：

```java
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
```

- [ ] **步骤 2：运行测试，确认失败原因是缺少创建接口**

运行：

```powershell
$env:Path = "$PWD\.tools\apache-maven-3.9.11\bin;$env:Path"
mvn -q -pl apps/api -Dtest=DispatchRuleSetApiTest test
```

预期：`createsRuleSetForPilotBootstrap` 返回 `405 Method Not Allowed`，证明测试覆盖的是缺失行为。

- [ ] **步骤 3：实现领域工厂与控制器创建端点**

在 `DispatchRuleSet` 增加通用工厂，不能调用 `defaultRules`，以免把演示名称和 10 分钟等待时间带入试点：

```java
public static DispatchRuleSet create(
        UUID id, String name, int maxWaitMinutes, int maxDetourMinutes,
        int bookingWindowMinutes, BigDecimal autoDispatchScoreThreshold,
        BigDecimal manualReviewScoreThreshold, BigDecimal waitWeight,
        BigDecimal detourWeight, BigDecimal stabilityWeight,
        BigDecimal utilizationWeight, String insertionPolicy) {
    DispatchRuleSet ruleSet = new DispatchRuleSet(id, name);
    ruleSet.updateRules(maxWaitMinutes, maxDetourMinutes, bookingWindowMinutes,
            autoDispatchScoreThreshold, manualReviewScoreThreshold, waitWeight,
            detourWeight, stabilityWeight, utilizationWeight, insertionPolicy);
    return ruleSet;
}
```

在控制器增加与现有更新字段保持一致的请求记录和端点：

```java
@PostMapping
ResponseEntity<ApiResponse<DispatchRuleSet>> create(
        @Valid @RequestBody CreateDispatchRuleSetRequest request) {
    DispatchRuleSet created = DispatchRuleSet.create(UUID.randomUUID(), request.name(),
            request.maxWaitMinutes(), request.maxDetourMinutes(), request.bookingWindowMinutes(),
            request.autoDispatchScoreThreshold(), request.manualReviewScoreThreshold(),
            request.waitWeight(), request.detourWeight(), request.stabilityWeight(),
            request.utilizationWeight(), request.insertionPolicy());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(repository.save(created)));
}
```

`CreateDispatchRuleSetRequest` 使用 `@NotBlank String name`，其余注解与 `UpdateDispatchRuleSetRequest` 完全一致。为避免重复，`UpdateDispatchRuleSetRequest` 保持现状，本任务只新增创建请求记录。
同时补充控制器所需导入：`org.springframework.web.bind.annotation.PostMapping` 和 `org.springframework.http.HttpStatus`。

- [ ] **步骤 4：重新运行 API 测试**

运行与步骤 2 相同的 Maven 命令。

预期：`DispatchRuleSetApiTest` 全部通过；创建测试返回 `201`，权限测试返回 `403`。

- [ ] **步骤 5：提交任务变更**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchRuleSet.java apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetController.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchRuleSetApiTest.java
git commit -m "feat: create dispatch rule sets"
```

### Task 2：前端创建表单与 API 客户端

**文件：**
- 修改：`apps/admin-web/src/api/rules.ts`
- 创建：`apps/admin-web/src/components/RuleSetCreateForm.vue`
- 测试：`apps/admin-web/src/components/rule-set-create-form.test.ts`

**接口：**
- 消费：Task 1 的 `POST /api/dispatch-rule-sets`。
- 产出：`createDispatchRuleSet(input: CreateDispatchRuleSetInput): Promise<DispatchRuleSet>` 与 `create` 组件事件。

- [ ] **步骤 1：写失败组件测试**

创建 `rule-set-create-form.test.ts`，验证表单显示确认的默认值并发送完整创建请求：

```ts
it("emits the confirmed Tongwei defaults with the supplied name", async () => {
  const { emitted } = render(RuleSetCreateForm, { props: { saving: false } });
  await fireEvent.update(screen.getByLabelText("规则组名称"), "通渭县试点动态调度规则");
  await fireEvent.click(screen.getByRole("button", { name: "创建规则组" }));

  expect(emitted().create).toEqual([[{
    name: "通渭县试点动态调度规则",
    maxWaitMinutes: 5, maxDetourMinutes: 8, bookingWindowMinutes: 60,
    autoDispatchScoreThreshold: 82, manualReviewScoreThreshold: 62,
    waitWeight: 0.35, detourWeight: 0.20, stabilityWeight: 0.30,
    utilizationWeight: 0.15, insertionPolicy: "REALTIME_INSERTION"
  }]]);
});
```

- [ ] **步骤 2：运行测试，确认组件尚不存在**

运行：

```powershell
npm.cmd --prefix apps/admin-web test -- rule-set-create-form.test.ts
```

预期：失败信息包含无法解析 `RuleSetCreateForm.vue`。

- [ ] **步骤 3：实现 API 客户端和创建表单**

在 `rules.ts` 定义输入类型及请求函数：

```ts
export type CreateDispatchRuleSetInput = UpdateDispatchRuleSetInput & { name: string };

export function createDispatchRuleSet(input: CreateDispatchRuleSetInput): Promise<DispatchRuleSet> {
  return request<DispatchRuleSet>("/api/dispatch-rule-sets", {
    method: "POST",
    body: JSON.stringify(input)
  });
}
```

`RuleSetCreateForm.vue` 使用 `reactive<CreateDispatchRuleSetInput>`，初始值严格为全局约束中的数值。模板提供“规则组名称”、所有规则字段和两个策略选项；提交按钮条件为 `!form.name.trim() || saving`。组件事件声明如下：

```ts
const emit = defineEmits<{
  create: [value: CreateDispatchRuleSetInput];
  cancel: [];
}>();
```

提交时执行 `emit("create", { ...form, name: form.name.trim() })`；不在组件中调用 HTTP，确保表单可独立测试且失败后输入不会丢失。

- [ ] **步骤 4：运行组件测试与类型检查**

运行：

```powershell
npm.cmd --prefix apps/admin-web test -- rule-set-create-form.test.ts
npm.cmd --prefix apps/admin-web run typecheck
```

预期：组件测试通过，`vue-tsc` 无类型错误。

- [ ] **步骤 5：提交任务变更**

```powershell
git add apps/admin-web/src/api/rules.ts apps/admin-web/src/components/RuleSetCreateForm.vue apps/admin-web/src/components/rule-set-create-form.test.ts
git commit -m "feat: add pilot rule set creation form"
```

### Task 3：规则配置页首建状态集成

**文件：**
- 修改：`apps/admin-web/src/pages/RulesPage.vue`
- 创建：`apps/admin-web/src/pages/rules-page.test.ts`

**接口：**
- 消费：Task 2 的 `createDispatchRuleSet` 和 `RuleSetCreateForm`。
- 产出：零规则组时可创建；创建成功后规则列表刷新、规则组自动选中并进入现有编辑模式。

- [ ] **步骤 1：写失败页面测试**

创建 `rules-page.test.ts`，通过 `fetch` 依次模拟空列表、创建成功和保存请求：

```ts
it("creates the first rule set and selects it for subsequent editing", async () => {
  authStore.setSessionForTest({ accessToken: "admin-token", user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(json([]))
    .mockResolvedValueOnce(json({ id: "rule-1", name: "通渭县试点动态调度规则", ...ruleFields }))
    .mockResolvedValueOnce(json({ id: "rule-1", name: "通渭县试点动态调度规则", ...ruleFields }));
  vi.stubGlobal("fetch", fetchMock);

  render(RulesPage);
  await fireEvent.update(await screen.findByLabelText("规则组名称"), "通渭县试点动态调度规则");
  await fireEvent.click(screen.getByRole("button", { name: "创建规则组" }));

  expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/dispatch-rule-sets", expect.objectContaining({ method: "POST" }));
  expect(await screen.findByDisplayValue("5")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "保存规则" })).toBeEnabled();
});
```

- [ ] **步骤 2：运行测试，确认当前页面没有首建入口**

运行：

```powershell
npm.cmd --prefix apps/admin-web test -- rules-page.test.ts
```

预期：失败信息包含找不到标签“规则组名称”或按钮“创建规则组”。

- [ ] **步骤 3：实现规则页状态切换和创建提交**

在 `RulesPage.vue`：

```ts
const creating = ref(false);
const creatingRuleSet = ref(false);

async function createRuleSet(input: CreateDispatchRuleSetInput) {
  creatingRuleSet.value = true;
  status.value = "";
  try {
    const created = await createDispatchRuleSet(input);
    ruleSets.value = [created, ...ruleSets.value];
    selectedRuleSetId.value = created.id;
    creating.value = false;
    status.value = "调度规则组已创建，可继续保存修改。";
  } catch (error) {
    status.value = userMessage(error, "调度规则组创建失败");
  } finally {
    creatingRuleSet.value = false;
  }
}
```

页面规则：`ruleSets.length === 0` 时始终显示 `RuleSetCreateForm`；存在规则组时，工具栏显示“新建规则组”，点击后显示同一表单并提供取消按钮；创建成功后渲染既有 `RuleSetForm`。创建期间禁用新建、刷新与表单提交，避免重复写入。

- [ ] **步骤 4：运行页面测试、前端全量测试和生产构建**

运行：

```powershell
npm.cmd --prefix apps/admin-web test -- rules-page.test.ts
npm.cmd --prefix apps/admin-web test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
```

预期：新页面测试和全量 Vitest 通过，类型检查与生产构建退出码为 0。

- [ ] **步骤 5：手工验收与提交任务变更**

使用管理员账号访问 `http://127.0.0.1:5173/rules`，创建“通渭县试点动态调度规则”，核对名称、默认参数和“保存规则”可点击。仅在该验收通过后提交：

```powershell
git add apps/admin-web/src/pages/RulesPage.vue apps/admin-web/src/pages/rules-page.test.ts
git commit -m "feat: support initial rule set setup"
```

## 计划自检

- 设计说明中的接口、权限、默认参数、空状态、失败保留输入和验收条件均分别被 Task 1-3 覆盖。
- 本计划不包含服务区、站点、车辆、驾驶员或任何 SQL 数据恢复操作。
- 所有后续任务依赖的接口、类型和组件名称均在前序任务中明确给出。
