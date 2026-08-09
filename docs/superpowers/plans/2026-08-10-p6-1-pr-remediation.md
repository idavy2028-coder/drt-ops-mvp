# P6-1 发布整改实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不向远端泄露乘客或高德 Key 的前提下，重写未发布历史、修复 P6-1 评估工具、按已批准抽样规则重新采集技术样本，并创建可审阅的草稿 PR。

**Architecture:** 先把运营样本证据改成匿名、可复算的时长口径，再把当前工作树重建到最新 `origin/master` 之上，使敏感文本不进入分支可达历史。评估工具继续位于 API 测试源码中：选择器按距离端点和八方位扇区轮询，汇总器在写文件前校验全部正式采样不变量，真实外呼异常只保留白名单失败分类。最后复用已有 48 组预分类结果离线选择 20 组路线，执行 200 次正式调用并按需要刷新 20 组路线指纹。

**Tech Stack:** Java 21、JUnit 5、AssertJ、Maven、PowerShell、CSV/JSON/Markdown、高德 Web Service。

## 全局约束

- 当前工作树固定为 `D:\codex-projects\.worktrees\p1-vehicle-location-calibration`，分支固定为 `codex/p1-vehicle-location-calibration`。
- 不强推、不合并、不清理工作树；远端分支在全部验证和独立审阅通过前保持不变。
- 只重写尚未推送的 13 个 P6-1 提交；重建后的分支从最新 `origin/master` 开始，并对现有远端分支保持快进关系。
- 不提交乘客姓名、电话、订单 UUID、精确业务事件时间、车辆与乘客的关联明细、高德 Key 或包含 Key 的完整 URL。
- 固定技术样本仍为短距 5 组、中距 10 组、长距 5 组；每组正式调用 10 次，相邻调用至少间隔 1 秒。
- 正式成功行必须是 `AMAP`、`degraded=false`、距离和耗时均为正数；200 次成功率不得低于 99%。
- ETA 始终仅作参考；运营样本 10/10 完成后仍只进入人工审阅。

---

### Task 1: 脱敏当前树并重建未发布历史

**Files:**
- Modify: `docs/pilot/evidence/p6-1-real-eta-observation-log.csv`
- Modify: `docs/pilot/evidence/p6-1-real-route-eta-evaluation-2026-08-09.md`
- Modify: `progress.md`

**Interfaces:**
- Consumes: 10 笔已核验运营样本的预测时长、实际时长、误差和方向。
- Produces: 16 列匿名运营样本 CSV；不含订单 UUID和精确事件时间，仍可独立复算绝对误差、MAPE 和高估/低估方向。

- [ ] **Step 1: 将运营样本改为匿名时长口径**

CSV 固定为以下 16 列：

```text
sample_id,audit_reference,predicted_pickup_eta_seconds,actual_pickup_elapsed_seconds,pickup_absolute_error_seconds,predicted_trip_eta_seconds,actual_trip_seconds,trip_absolute_error_seconds,absolute_percentage_error,pickup_estimate_direction,trip_estimate_direction,pickup_time_source,dropoff_time_source,estimate_provider,estimate_degraded,review_status
```

`audit_reference` 使用 `P6-ETA-R01` 至 `P6-ETA-R10`，真实映射只保留在受控审计系统。方向按精确时长比较，误差字段按现有整秒口径保留。

- [ ] **Step 2: 删除进度文件中的乘客关联明细**

删除姓名、订单前缀、任务、车辆、地点和调度评分之间的关联，仅保留聚合数量、完成状态和 P6-1 统计结论。

- [ ] **Step 3: 扫描当前树**

Run:

```powershell
rg -n -g 'p6-1-*' 'key=|DRT_AMAP_WEB_SERVICE_KEY|restapi\.amap\.com/v3/' docs/pilot/evidence
```

Expected: 无匹配。另以脚本确认运营样本 CSV 不含 UUID、手机号或 ISO 精确时间戳。

- [ ] **Step 4: 重建分支历史**

在当前树已脱敏后执行 `git reset --soft origin/master`，核对暂存区只包含 P6-1 目标文件，再创建新的干净提交。不得创建包含旧敏感历史的远端引用。

### Task 2: 按距离端点和扇区轮询选择固定路线

**Files:**
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupportTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupport.java`

**Interfaces:**
- Consumes: 成功且距离、耗时为正数的 `PreclassificationResult`。
- Produces: `selectFixedSamples(...)` 返回严格有序的 5/10/5 样本；每个距离层按 `N, NE, E, SE, S, SW, W, NW` 轮询扇区，避免单一方向集中。

- [ ] **Step 1: 写失败测试**

新增偏斜夹具：全局最短结果集中在一个扇区、全局最长结果集中在另一个扇区。断言短、中、长三个距离层分别至少覆盖 4 个扇区，样本 ID 仍为 `S01`–`L05`，且距离层严格有序。

- [ ] **Step 2: 运行 RED**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api '-Dtest=P61RouteEvaluationSupportTest' test
```

Expected: 新的方向分布断言在旧的全局最短/最长选择算法上失败。

- [ ] **Step 3: 实现最小轮询算法**

增加 `selectRoundRobinBySector(candidates, count, comparator, excludedPairIds)`：各扇区内部按指定距离优先级排序，按固定八扇区顺序每轮最多取一条，直到达到数量。短距离按距离升序，长距离按距离降序，中距离仅在短距最大值和长距最小值之间按距全局中位数的绝对差排序。

- [ ] **Step 4: 运行 GREEN**

重新执行专项测试，预期全部通过。

### Task 3: 在生成证据前校验正式采样不变量

**Files:**
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupportTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupport.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61AmapRouteEvaluationIT.java`

**Interfaces:**
- Produces: `validateFormalEvidence(samples, calls)`；`summarize` 与 `writeEvidence` 均在计算或写文件前调用。

- [ ] **Step 1: 写失败测试**

分别构造重复迭代、未知样本、每组数量不等、降级成功行、非 AMAP 成功行、成功行缺失正距离/正耗时。断言抛出明确异常，并确认无 CSV、JSON 或 Markdown 被创建。

- [ ] **Step 2: 运行 RED**

预期旧汇总器错误接受至少一个非法夹具，或在失败前已经创建文件。

- [ ] **Step 3: 实现最小校验**

校验 20 个唯一合法样本、5/10/5 数量、200 个调用、每组恰好 10 次、迭代唯一且为 1–10、样本引用合法，以及成功行的提供方、降级标志、距离、耗时和失败原因。任何不变量失败时先抛异常，不创建目录或覆盖证据。

- [ ] **Step 4: 运行 GREEN**

专项测试全部通过，并对现有合法 200 行夹具保持原有统计结果。

### Task 4: 净化路线形状采集异常

**Files:**
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupportTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupport.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61AmapRouteEvaluationIT.java`

**Interfaces:**
- Produces: `sanitizedEvaluationFailure(phase, sampleId, failureReason)`，返回无 cause、无 URI、只含白名单失败分类的异常。

- [ ] **Step 1: 写失败测试并运行 RED**

输入包含 `key=test-secret-key` 和完整高德 URL 的异常文本，断言返回异常的消息不含敏感值、`getCause()` 为 `null`。

- [ ] **Step 2: 实现并运行 GREEN**

路线形状阶段捕获 `MapProviderException` 和其他运行时异常，转换为净化异常；不得传递原 cause。专项测试全部通过。

### Task 5: 重新选择并采集技术样本

**Files:**
- Modify: `docs/pilot/evidence/p6-1-fixed-route-samples-2026-08-09.csv`
- Modify: `docs/pilot/evidence/p6-1-route-calls-2026-08-09.csv`
- Modify: `docs/pilot/evidence/p6-1-route-shapes-2026-08-09.csv`
- Modify: `docs/pilot/evidence/p6-1-route-summary-2026-08-09.json`
- Modify: `docs/pilot/evidence/p6-1-real-route-eta-evaluation-2026-08-09.md`
- Modify: `progress.md`

- [ ] **Step 1: 离线重选 20 组路线**

使用已提交的 48 组真实预分类结果运行 `select` 阶段。先核对 5/10/5、每层方向分布、严格距离顺序和无乘客信息。

- [ ] **Step 2: 执行 200 次正式调用**

通过 `formal` 阶段顺序调用 20×10 次；不得输出 Key。预期至少 198 次成功、每组至少成功一次。

- [ ] **Step 3: 刷新路线形状证据**

若固定样本目录发生变化，执行 `shapes` 阶段追加 20 次调用，只保存点数、SHA-256 指纹、距离、耗时和延迟。

- [ ] **Step 4: 更新人工报告**

保留新采样统计、异常解释、10/10 匿名运营偏差统计和“ETA 仅作参考；等待人工审阅”的边界。

### Task 6: 复验、审阅和发布

**Files:**
- Modify: none unless验证发现问题。

- [ ] **Step 1: 数据复算和脱敏扫描**

验证技术 CSV/JSON 数量、唯一性、间隔、成功率、延迟分位数和异常一致；验证运营 CSV 16 列、10 个匿名样本、误差和方向可复算；运行 `git diff --check`。

- [ ] **Step 2: 完整自动化验证**

执行 API 全量测试、算法服务 pytest、管理端 typecheck/Vitest/build。Playwright 若仍因 `origin/master` 未改动的过期登录夹具失败，必须在 PR 中如实注明，不得写成通过。

- [ ] **Step 3: 独立代码审阅**

审阅范围为 `origin/master..HEAD`。Critical 和 Important 必须清零；错误反馈要用可复算证据反驳或修复。

- [ ] **Step 4: 推送和创建草稿 PR**

确认远端不存在同分支开放 PR 后，快进推送 `codex/p1-vehicle-location-calibration`，并创建目标为 `master` 的草稿 PR；不自动合并。
