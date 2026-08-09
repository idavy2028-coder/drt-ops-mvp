# P1-4 算法不可用错误映射实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将算法调用失败稳定映射为 `ALGORITHM_UNAVAILABLE` HTTP 503，并让订单页面显示“算法服务不可用”而不误报登录失效。

**Architecture:** 算法 WebClient 适配器把调用边界内的运行时失败转换为专用异常，全局异常处理器输出固定错误码。前端错误解析器只信任该白名单错误码，订单页面沿用统一 `userMessage` 展示安全文案；其他 5xx 继续使用通用提示。

**Tech Stack:** Java 21、Spring Boot 3.5、WebClient、JUnit 5、AssertJ、Vue 3、TypeScript、Vitest、Testing Library。

## Global Constraints

- 唯一业务依据为 `docs/release/tongwei-pilot-next-phase-plan.md` 的 P1-4：API 容器内可访问算法 `/health`；算法不可用时页面显示“算法服务不可用”，不得错误提示为登录失效。
- 不修复重复点击或并发调度问题，不新增数据库迁移，不改变订单、调度决策或审计领域模型。
- 真实断路请求必须回滚；测试订单保持 `PENDING_DISPATCH`，不产生决策、任务或调度审计。
- 恢复算法后不执行成功调度，不提前进入 P1-5。
- 不删除容器或数据卷，不修改既有业务数据。
- 不提交、不推送、不创建 PR；每个任务用 `git diff` 和测试结果作为审阅检查点。
- 保留既有未跟踪文件 `progress.md`，不得修改或删除。

---

### Task 1: 建立干净测试基线

**Files:**
- Read: `pom.xml`
- Read: `apps/api/pom.xml`
- Read: `apps/admin-web/package.json`

**Interfaces:**
- Consumes: 当前独立 worktree 和已安装的 Maven、npm 依赖。
- Produces: 修改前的后端、前端测试基线；后续失败可与基线区分。

- [ ] **Step 1: 运行后端全量基线**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -f 'D:\codex-projects\.worktrees\p1-vehicle-location-calibration\pom.xml' -pl apps/api test
```

Expected: Maven exit code 0，`apps/api` 测试无失败。

- [ ] **Step 2: 运行前端全量基线**

Run:

```powershell
npm.cmd --prefix apps/admin-web run test
```

Expected: Vitest exit code 0，现有测试全部通过。

- [ ] **Step 3: 记录检查点**

Run:

```powershell
git status --short
```

Expected: 仅设计文档、实施计划和既有 `progress.md` 为未跟踪文件，没有业务代码差异。

---

### Task 2: 用专用异常封装算法连接失败

**Files:**
- Create: `apps/api/src/test/java/com/idavy/drtops/integration/algorithm/WebClientAlgorithmClientTest.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/algorithm/AlgorithmUnavailableException.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/integration/algorithm/WebClientAlgorithmClient.java`

**Interfaces:**
- Consumes: `AlgorithmClient.evaluate(DispatchEvaluateRequest)` 和 Spring `WebClient`。
- Produces: `AlgorithmUnavailableException.ERROR_CODE = "ALGORITHM_UNAVAILABLE"`；异常对客户端使用固定文案“算法服务不可用”，原始异常保留为 `cause`。

- [ ] **Step 1: 写连接失败的回归测试**

先新增测试，测试名明确捕获“删除异常转换后会重新暴露 WebClient 异常”这一缺陷：

```java
package com.idavy.drtops.integration.algorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

class WebClientAlgorithmClientTest {

    @Test
    void convertsConnectionFailureIntoAlgorithmUnavailableErrorWithoutLeakingDetails() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(httpRequest -> Mono.error(
                new WebClientRequestException(
                        new ConnectException("connection refused at 127.0.0.1:8090"),
                        httpRequest.method(),
                        httpRequest.url(),
                        httpRequest.headers())));
        WebClientAlgorithmClient client = new WebClientAlgorithmClient(builder, "http://algorithm");

        assertThatThrownBy(() -> client.evaluate(request()))
                .isInstanceOfSatisfying(AlgorithmUnavailableException.class, exception -> {
                    assertThat(exception).hasMessage("算法服务不可用");
                    assertThat(exception.getMessage()).doesNotContain("127.0.0.1", "connection refused");
                    assertThat(exception.getCause()).isInstanceOf(WebClientRequestException.class);
                });
    }

    private DispatchEvaluateRequest request() {
        UUID boardingStopId = UUID.fromString("55555555-5555-5555-5555-555555555551");
        UUID alightingStopId = UUID.fromString("55555555-5555-5555-5555-555555555552");
        return new DispatchEvaluateRequest(
                new DispatchEvaluateRequest.Order(
                        UUID.fromString("77777777-7777-7777-7777-777777777777"),
                        1,
                        "IMMEDIATE",
                        OffsetDateTime.parse("2026-07-29T16:00:00+08:00"),
                        boardingStopId,
                        alightingStopId),
                new DispatchEvaluateRequest.RuleSet(
                        30,
                        20,
                        new BigDecimal("0.80"),
                        new BigDecimal("0.60"),
                        new DispatchEvaluateRequest.Weights(
                                new BigDecimal("0.40"),
                                new BigDecimal("0.30"),
                                new BigDecimal("0.20"),
                                new BigDecimal("0.10")),
                        "BEST_POSITION"),
                List.of());
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -f 'D:\codex-projects\.worktrees\p1-vehicle-location-calibration\pom.xml' -pl apps/api '-Dtest=WebClientAlgorithmClientTest' test
```

Expected: 编译或断言失败，原因是 `AlgorithmUnavailableException` 尚不存在或现有客户端传播 `WebClientRequestException`；不得是测试夹具语法错误。

- [ ] **Step 3: 写最小专用异常**

```java
package com.idavy.drtops.integration.algorithm;

public final class AlgorithmUnavailableException extends RuntimeException {

    public static final String ERROR_CODE = "ALGORITHM_UNAVAILABLE";
    public static final String USER_MESSAGE = "算法服务不可用";

    public AlgorithmUnavailableException(Throwable cause) {
        super(USER_MESSAGE, cause);
    }
}
```

- [ ] **Step 4: 在算法适配器边界转换失败**

将 `evaluate` 改为：

```java
@Override
public DispatchEvaluateResponse evaluate(DispatchEvaluateRequest request) {
    try {
        DispatchEvaluateResponse response = webClient.post()
                .uri("/dispatch/evaluate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DispatchEvaluateResponse.class)
                .block(Duration.ofSeconds(5));
        if (response == null) {
            throw new IllegalStateException("Algorithm returned an empty response");
        }
        return response;
    } catch (RuntimeException exception) {
        throw new AlgorithmUnavailableException(exception);
    }
}
```

该边界只包围算法 HTTP 调用和响应解析，不捕获 `DispatchOrchestrator` 的订单或规则领域错误。

- [ ] **Step 5: 运行测试并确认 GREEN**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -f 'D:\codex-projects\.worktrees\p1-vehicle-location-calibration\pom.xml' -pl apps/api '-Dtest=WebClientAlgorithmClientTest' test
```

Expected: exit code 0，连接失败被转换且不向用户文案泄露地址。

- [ ] **Step 6: 记录检查点**

Run:

```powershell
git diff --check
git diff -- apps/api/src/main/java/com/idavy/drtops/integration/algorithm apps/api/src/test/java/com/idavy/drtops/integration/algorithm
```

Expected: 只有本任务的异常类、适配器和测试差异，无空白错误。

---

### Task 3: 将专用异常输出为带错误码的 HTTP 503

**Files:**
- Modify: `apps/api/src/test/java/com/idavy/drtops/common/GlobalExceptionHandlerTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/common/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: `AlgorithmUnavailableException.ERROR_CODE` 和 `USER_MESSAGE`。
- Produces: HTTP 503 响应 `{"data":{"code":"ALGORITHM_UNAVAILABLE","message":"算法服务不可用"}}`。

- [ ] **Step 1: 写 HTTP 响应回归测试**

在 `GlobalExceptionHandlerTest` 增加：

```java
@Test
void returnsStableCodeForAlgorithmUnavailableWithoutLeakingCause() {
    var response = handler.handleAlgorithmUnavailable(
            new AlgorithmUnavailableException(new ConnectException("127.0.0.1:8090 refused")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isEqualTo(ApiResponse.ok(Map.of(
            "code", "ALGORITHM_UNAVAILABLE",
            "message", "算法服务不可用")));
    assertThat(response.getBody().data().toString()).doesNotContain("127.0.0.1", "refused");
}
```

并导入：

```java
import com.idavy.drtops.integration.algorithm.AlgorithmUnavailableException;
import java.net.ConnectException;
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -f 'D:\codex-projects\.worktrees\p1-vehicle-location-calibration\pom.xml' -pl apps/api '-Dtest=GlobalExceptionHandlerTest' test
```

Expected: 编译失败，指出 `handleAlgorithmUnavailable` 尚不存在。

- [ ] **Step 3: 写最小异常处理器**

在 `GlobalExceptionHandler` 增加：

```java
@ExceptionHandler(AlgorithmUnavailableException.class)
ResponseEntity<ApiResponse<Map<String, String>>> handleAlgorithmUnavailable(
        AlgorithmUnavailableException exception) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.ok(Map.of(
                    "code", AlgorithmUnavailableException.ERROR_CODE,
                    "message", AlgorithmUnavailableException.USER_MESSAGE)));
}
```

并导入：

```java
import com.idavy.drtops.integration.algorithm.AlgorithmUnavailableException;
```

- [ ] **Step 4: 运行测试并确认 GREEN**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -f 'D:\codex-projects\.worktrees\p1-vehicle-location-calibration\pom.xml' -pl apps/api '-Dtest=GlobalExceptionHandlerTest,WebClientAlgorithmClientTest' test
```

Expected: exit code 0，两组后端定向测试通过。

- [ ] **Step 5: 记录检查点**

Run:

```powershell
git diff --check
git diff -- apps/api/src/main/java/com/idavy/drtops/common/GlobalExceptionHandler.java apps/api/src/test/java/com/idavy/drtops/common/GlobalExceptionHandlerTest.java
```

Expected: 仅新增算法专用 503 处理，不改变其他异常映射。

---

### Task 4: 前端只对白名单算法错误显示专用文案

**Files:**
- Modify: `apps/admin-web/src/api/errors.test.ts`
- Modify: `apps/admin-web/src/api/errors.ts`
- Modify: `apps/admin-web/src/pages/orders-page.test.ts`
- Read: `apps/admin-web/src/pages/OrdersPage.vue`
- Read: `apps/admin-web/src/api/orders.ts`
- Read: `apps/admin-web/src/api/http.ts`

**Interfaces:**
- Consumes: HTTP 503 响应中的 `data.code`。
- Produces: `ApiError.code?: string`；`ALGORITHM_UNAVAILABLE` 对应固定用户文案“算法服务不可用”；订单页面走真实错误解析链路且不显示登录失效。

- [ ] **Step 1: 写安全映射回归测试**

在 `errors.test.ts` 增加两个测试：

```typescript
it("maps the whitelisted algorithm error code without trusting the server message", async () => {
  const error = await apiErrorFromResponse(new Response(JSON.stringify({
    data: {
      code: "ALGORITHM_UNAVAILABLE",
      message: "java.net.ConnectException: 127.0.0.1:8090"
    }
  }), {
    status: 503,
    headers: { "Content-Type": "application/json" }
  }));

  expect(error.code).toBe("ALGORITHM_UNAVAILABLE");
  expect(userMessage(error, "调度操作失败")).toBe("算法服务不可用");
});

it("keeps an untagged service unavailable response generic", async () => {
  const error = await apiErrorFromResponse(new Response(JSON.stringify({
    data: { message: "org.postgresql.util.PSQLException: connection refused" }
  }), {
    status: 503,
    headers: { "Content-Type": "application/json" }
  }));

  expect(error.code).toBeUndefined();
  expect(userMessage(error, "加载失败")).toBe("服务暂时不可用，请稍后重试");
});
```

- [ ] **Step 2: 写订单页面回归测试**

更新 `orders-page.test.ts` 的测试导入：

```typescript
import { fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
```

让清理恢复 fetch：

```typescript
afterEach(() => {
  authStore.clearSessionForTest();
  vi.restoreAllMocks();
});
```

增加测试：

```typescript
it("shows algorithm unavailable instead of login expired when dispatch returns the tagged 503", async () => {
  const orderId = "77777777-7777-7777-7777-777777777777";
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, options) => {
    const url = String(input);
    if (url.endsWith(`/api/orders/${orderId}/dispatch`) && options?.method === "POST") {
      return new Response(JSON.stringify({
        data: {
          code: "ALGORITHM_UNAVAILABLE",
          message: "算法服务不可用"
        }
      }), {
        status: 503,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response(JSON.stringify({
      data: [{
        id: orderId,
        passengerName: "P1-4 测试乘客",
        passengerPhone: "13800000000",
        passengerCount: 1,
        requestType: "IMMEDIATE",
        originLng: 105.327705,
        originLat: 35.283669,
        destinationLng: 105.258224,
        destinationLat: 35.197636,
        originAddress: "P1-4 测试起点",
        destinationAddress: "P1-4 测试终点",
        coordinateSystem: "GCJ-02",
        originAddressSource: "VIRTUAL_STOP",
        destinationAddressSource: "VIRTUAL_STOP",
        boardingStopId: "55555555-5555-5555-5555-555555555551",
        alightingStopId: "55555555-5555-5555-5555-555555555552",
        requestedDepartureAt: "2026-07-29T17:00:00+08:00",
        status: "PENDING_DISPATCH"
      }]
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  });
  authStore.setSessionForTest({
    accessToken: "dispatcher-token",
    user: {
      id: "dispatcher-1",
      username: "dispatcher01",
      roles: ["DISPATCHER"],
      mustChangePassword: false
    }
  });
  render(OrdersPage);

  await fireEvent.click(await screen.findByRole("button", { name: "调度" }));

  expect(await screen.findByText("算法服务不可用")).toBeInTheDocument();
  expect(screen.queryByText("登录状态已失效，请重新登录")).not.toBeInTheDocument();
});
```

- [ ] **Step 3: 运行错误解析和页面测试并确认 RED**

Run:

```powershell
npm.cmd --prefix apps/admin-web run test -- errors.test.ts orders-page.test.ts
```

Expected: TypeScript 编译或断言失败，原因是 `ApiError` 尚无 `code` 且 503 未识别白名单；不得是 fetch 夹具或页面选择器错误。

- [ ] **Step 4: 写最小安全解析**

将 `errors.ts` 的响应解析改为：

```typescript
const ALGORITHM_UNAVAILABLE = "ALGORITHM_UNAVAILABLE";

interface ErrorPayload {
  code?: string;
  message?: string;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message?: string,
    public readonly code?: string
  ) {
    super(message || defaultMessage(status));
    this.name = "ApiError";
  }
}

export async function apiErrorFromResponse(response: Response): Promise<ApiError> {
  const shouldReadPayload = response.status === 400 || response.status === 409 || response.status === 503;
  const payload = shouldReadPayload ? extractPayload(await response.text()) : {};
  const isBusinessError = response.status === 400 || response.status === 409;
  const code = payload.code === ALGORITHM_UNAVAILABLE ? payload.code : undefined;
  const message = isBusinessError
    ? payload.message
    : code === ALGORITHM_UNAVAILABLE
      ? "算法服务不可用"
      : undefined;
  return new ApiError(response.status, message, code);
}

function extractPayload(body: string): ErrorPayload {
  if (!body) {
    return {};
  }
  try {
    const parsed = JSON.parse(body) as { data?: { code?: unknown; message?: unknown } };
    return {
      code: typeof parsed.data?.code === "string" ? parsed.data.code : undefined,
      message: typeof parsed.data?.message === "string" ? parsed.data.message : undefined
    };
  } catch {
    return {};
  }
}
```

删除被替代的 `extractMessage`，保留 `userMessage` 和其他状态默认文案不变。

- [ ] **Step 5: 运行测试并确认 GREEN**

Run:

```powershell
npm.cmd --prefix apps/admin-web run test -- errors.test.ts orders-page.test.ts
```

Expected: exit code 0，白名单算法错误显示专用文案，未知 503 仍显示通用文案，订单页面不显示登录失效。

- [ ] **Step 6: 记录检查点**

Run:

```powershell
git diff --check
git diff -- apps/admin-web/src/api/errors.ts apps/admin-web/src/api/errors.test.ts apps/admin-web/src/pages/orders-page.test.ts
```

Expected: 前端只新增安全错误码解析和页面失败路径测试，不改变 401 流程或页面生产组件。

---

### Task 5: 复核订单页面的真实错误展示路径

**Files:**
- Verify: `apps/admin-web/src/pages/orders-page.test.ts`
- Read: `apps/admin-web/src/pages/OrdersPage.vue`
- Read: `apps/admin-web/src/api/orders.ts`
- Read: `apps/admin-web/src/api/http.ts`

**Interfaces:**
- Consumes: 真实 `OrdersPage`、`dispatchOrder`、`request` 和 `apiErrorFromResponse` 链路，仅用 `fetch` 代替外部 HTTP 服务。
- Produces: 页面回归证据：点击 `PENDING_DISPATCH` 订单的“调度”后显示“算法服务不可用”，且不显示登录失效。

- [ ] **Step 1: 复核 Task 4 已先行写入的页面回归测试**

确认测试导入为：

```typescript
import { fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
```

确认清理会恢复 fetch：

```typescript
afterEach(() => {
  authStore.clearSessionForTest();
  vi.restoreAllMocks();
});
```

确认以下测试已经在生产映射实现前运行并按预期失败：

```typescript
it("shows algorithm unavailable instead of login expired when dispatch returns the tagged 503", async () => {
  const orderId = "77777777-7777-7777-7777-777777777777";
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, options) => {
    const url = String(input);
    if (url.endsWith(`/api/orders/${orderId}/dispatch`) && options?.method === "POST") {
      return new Response(JSON.stringify({
        data: {
          code: "ALGORITHM_UNAVAILABLE",
          message: "算法服务不可用"
        }
      }), {
        status: 503,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response(JSON.stringify({
      data: [{
        id: orderId,
        passengerName: "P1-4 测试乘客",
        passengerPhone: "13800000000",
        passengerCount: 1,
        requestType: "IMMEDIATE",
        originLng: 105.327705,
        originLat: 35.283669,
        destinationLng: 105.258224,
        destinationLat: 35.197636,
        originAddress: "P1-4 测试起点",
        destinationAddress: "P1-4 测试终点",
        coordinateSystem: "GCJ-02",
        originAddressSource: "VIRTUAL_STOP",
        destinationAddressSource: "VIRTUAL_STOP",
        boardingStopId: "55555555-5555-5555-5555-555555555551",
        alightingStopId: "55555555-5555-5555-5555-555555555552",
        requestedDepartureAt: "2026-07-29T17:00:00+08:00",
        status: "PENDING_DISPATCH"
      }]
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  });
  authStore.setSessionForTest({
    accessToken: "dispatcher-token",
    user: {
      id: "dispatcher-1",
      username: "dispatcher01",
      roles: ["DISPATCHER"],
      mustChangePassword: false
    }
  });
  render(OrdersPage);

  await fireEvent.click(await screen.findByRole("button", { name: "调度" }));

  expect(await screen.findByText("算法服务不可用")).toBeInTheDocument();
  expect(screen.queryByText("登录状态已失效，请重新登录")).not.toBeInTheDocument();
});
```

- [ ] **Step 2: 执行页面测试的变异检查**

先运行正常测试并确认 GREEN；随后仅在测试夹具中把响应 `code` 临时改为 `UNKNOWN_SERVICE`，再次运行并确认页面目标文案断言 FAIL，最后立即恢复为 `ALGORITHM_UNAVAILABLE` 并再次确认 GREEN。这个变异证明测试依赖真实错误解析行为，而不是 mock 自证。

Run:

```powershell
npm.cmd --prefix apps/admin-web run test -- orders-page.test.ts
```

Expected: 正常夹具 GREEN；未知错误码变异时 FAIL，实际文案为“服务暂时不可用，请稍后重试”；恢复后再次 GREEN。

- [ ] **Step 3: 运行页面与错误解析联合测试**

Run:

```powershell
npm.cmd --prefix apps/admin-web run test -- errors.test.ts orders-page.test.ts
```

Expected: exit code 0；页面测试走过真实 API 错误解析路径。

- [ ] **Step 4: 记录检查点**

Run:

```powershell
git diff --check
git diff -- apps/admin-web/src/pages/orders-page.test.ts
```

Expected: 只新增页面失败路径测试，没有修改页面生产组件。

---

### Task 6: 自动化全量验证

**Files:**
- Verify: `apps/api`
- Verify: `apps/admin-web`

**Interfaces:**
- Consumes: Tasks 2–5 的全部代码和测试。
- Produces: 后端与前端完整回归、类型和构建证据。

- [ ] **Step 1: 运行后端全量测试**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -f 'D:\codex-projects\.worktrees\p1-vehicle-location-calibration\pom.xml' -pl apps/api test
```

Expected: exit code 0，无失败测试。

- [ ] **Step 2: 运行前端类型检查**

Run:

```powershell
npm.cmd --prefix apps/admin-web run typecheck
```

Expected: exit code 0，无 TypeScript/Vue 类型错误。

- [ ] **Step 3: 运行前端全量测试**

Run:

```powershell
npm.cmd --prefix apps/admin-web run test
```

Expected: exit code 0，无失败测试。

- [ ] **Step 4: 运行前端生产构建**

Run:

```powershell
npm.cmd --prefix apps/admin-web run build
```

Expected: exit code 0，Vite 生产构建完成。

- [ ] **Step 5: 核对最终代码差异**

Run:

```powershell
git diff --check
git status --short
git diff --stat
```

Expected: 只有设计、计划、本修复代码和测试；`progress.md` 仍为原有未跟踪文件。

---

### Task 7: 重建试点服务并创建专用测试订单

**Files:**
- Runtime: `drt-ops-pilot-api-debug`
- Runtime: `drt-ops-pilot-web`
- Runtime: `drt-ops-pilot-algorithm`
- Database: `drt_ops_pilot_bootstrap`（通过页面写入测试订单；核查只读）

**Interfaces:**
- Consumes: 自动化验证通过的当前工作副本、现有已登录 `dispatcher01` 页面会话。
- Produces: 一笔名称明确包含 `P1-4` 且状态为 `PENDING_DISPATCH` 的即时测试订单。

- [ ] **Step 1: 检查现有容器、挂载和健康状态**

使用 Docker 只读检查容器状态、源码挂载和端口。确认 API/前端确实使用当前 worktree；若不是，停止并报告，不在错误环境创建订单。

- [ ] **Step 2: 使修复后的代码在容器中生效**

根据现有只读源码挂载方式重新编译或重启 API/前端，只操作 `drt-ops-pilot-api-debug` 和 `drt-ops-pilot-web`。不得删除容器、数据库或数据卷。

- [ ] **Step 3: 验证健康基线**

从 API 容器访问：

```text
http://127.0.0.1:8090/health
```

Expected: `{"status":"UP"}`。

访问 API：

```text
http://127.0.0.1:8080/actuator/health
```

Expected: HTTP 200。

- [ ] **Step 4: 通过后台页面创建测试订单**

使用 `http://127.0.0.1:5174` 的现有登录会话，录入：

- 乘客姓名：`P1-4算法断路测试`
- 联系电话：`13800000000`
- 人数：`1`
- 类型：即时需求
- 起终点：从现有启用虚拟站点中选择两个不同站点
- 坐标系：页面现有 GCJ-02 流程
- 出发时间：页面允许的最近有效时间

只提交一次，记录页面返回的订单 ID；不点击调度。

- [ ] **Step 5: 只读记录数据库基线**

在 `BEGIN TRANSACTION READ ONLY` 中核对该订单：

- 状态为 `PENDING_DISPATCH`；
- 调度决策数为 0；
- 关联车辆任务数为 0；
- 调度审计数为 0；
- `SHOW transaction_read_only` 为 `on`。

---

### Task 8: 执行真实算法断路验收并恢复环境

**Files:**
- Runtime: `drt-ops-pilot-algorithm`
- UI: `http://127.0.0.1:5174`
- Database: `drt_ops_pilot_bootstrap`（只读核查）

**Interfaces:**
- Consumes: Task 7 的测试订单 ID 和已登录订单页面。
- Produces: P1-4 页面文案、事务回滚、容器恢复和无副作用证据。

- [ ] **Step 1: 在停服前定位唯一测试订单**

刷新订单页面，按订单 ID 前八位和乘客姓名定位唯一行，确认状态为待调度且只有该行的“调度”按钮将被点击。

- [ ] **Step 2: 停止算法并确认断路**

只停止 `drt-ops-pilot-algorithm`。从 API 容器请求算法 `/health`，Expected: 连接失败；API 自身 Actuator 仍为 HTTP 200。

- [ ] **Step 3: 单击一次调度并采集页面证据**

在唯一订单行单击一次“调度”：

- Expected: 页面显示“算法服务不可用”；
- Expected: 页面不显示“登录状态已失效，请重新登录”；
- 不重复点击，不执行其他业务操作。

- [ ] **Step 4: 立即恢复算法并按条件等待健康**

无论 Step 3 成功或失败，都启动 `drt-ops-pilot-algorithm`，轮询容器状态和 API 容器内 `/health`，直到返回 `{"status":"UP"}`；不得用固定长等待替代条件轮询。

- [ ] **Step 5: 只读核对事务副作用**

在 `BEGIN TRANSACTION READ ONLY` 中验证：

- 订单仍为 `PENDING_DISPATCH`；
- 调度决策数仍为 0；
- 关联车辆任务数仍为 0；
- 调度审计数仍为 0；
- `SHOW transaction_read_only` 为 `on`。

- [ ] **Step 6: 最终健康和工作区核查**

验证算法 `/health` 为 `UP`、API Actuator 为 HTTP 200，并运行：

```powershell
git diff --check
git status --short
git diff --stat
```

Expected: 环境恢复；没有数据库清理、成功调度、提交或推送。
