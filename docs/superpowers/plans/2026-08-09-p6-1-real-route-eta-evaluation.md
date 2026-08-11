# P6-1 真实路径服务与 ETA 能力专项评估 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用真实高德 Web Service 验证路径与地理编码连通性，通过现有 Java 适配链路采集 20 组、共 200 次固定路线技术样本，并生成可复算且不泄露 Key 的 P6-1 证据。

**Architecture:** 连通性预检使用 PowerShell 直接请求高德官方接口，只输出脱敏状态。正式评估在 API 测试源码中增加显式开关控制的验收工具，复用 `AmapRoutePlanningProvider`、`AmapWebServiceClient` 和 `TravelEstimateService`；每次正式采样创建新的 `TravelEstimateService`，避免 2 分钟业务缓存把缓存命中误算为外部调用。评估工具从已发布服务区的启用虚拟站点只读快照生成候选路线，先预分类，再采集 200 次正式结果并生成 CSV、JSON 和 Markdown 证据。

**Tech Stack:** PowerShell 7/Windows PowerShell、Java 21、Spring WebClient、JUnit 5、Jackson、AssertJ、Maven、PostgreSQL/PostGIS、高德 Web Service API。

## Global Constraints

- Key 仅从 `DRT_AMAP_WEB_SERVICE_KEY` 读取，不得输出、写入文件、完整请求 URL 或 Git 历史。
- 预检和正式采样只发送公共测试地址、虚拟站点名称与 GCJ-02 坐标，不发送乘客姓名、电话或订单地址。
- 评估过程不得创建或修改订单、任务、位置事件、审计记录、车辆、驾驶员、规则或服务区数据。
- 正式样本固定为短距离 5 组、中距离 10 组、长距离 5 组；每组调用 10 次，相邻调用至少间隔 1 秒。
- 200 次正式调用成功率必须不低于 99%，即至少 198 次成功；20 组均须至少成功一次。
- 同组距离变化超过 5%或 ETA 变化超过 20%时记录为异常跳变，无法解释的异常阻止技术评估收口。
- ETA 全程标注“参考”，不用于绩效、拒单或硬性派单判定。
- 现有未提交 `progress.md` 不得混入任何中间代码提交。

## File Structure

- Create: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupport.java` — 负责候选生成、分层选择、统计和证据渲染，不访问网络。
- Create: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupportTest.java` — 对候选覆盖、5/10/5 分层、99% 判定、波动告警和脱敏证据做纯单元测试。
- Create: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61AmapRouteEvaluationIT.java` — 仅在显式系统属性开启时访问真实高德，执行预分类和正式采样。
- Create: `docs/pilot/evidence/p6-1-virtual-stop-snapshot-2026-08-09.csv` — 只读导出的启用虚拟站点快照。
- Create: `docs/pilot/evidence/p6-1-route-preclassification-2026-08-09.csv` — 候选路线预分类明细。
- Create: `docs/pilot/evidence/p6-1-fixed-route-samples-2026-08-09.csv` — 最终 20 组固定路线目录。
- Create: `docs/pilot/evidence/p6-1-route-calls-2026-08-09.csv` — 200 次正式调用明细。
- Create: `docs/pilot/evidence/p6-1-route-shapes-2026-08-09.csv` — 20 组脱敏路线点数与 SHA-256 指纹。
- Create: `docs/pilot/evidence/p6-1-route-summary-2026-08-09.json` — 脱敏汇总统计。
- Create: `docs/pilot/evidence/p6-1-real-route-eta-evaluation-2026-08-09.md` — 技术评估报告。
- Create: `docs/pilot/evidence/p6-1-real-eta-observation-log.csv` — 后续 10 笔真实运营偏差样本追加台账模板。
- Modify: `progress.md` — 仅在技术证据完成后追加 P6-1 状态，由独立文档提交收口。

---

### Task 1: 真实 Key 与配额连通性预检

**Files:**
- Modify: none

**Interfaces:**
- Consumes: 环境变量 `DRT_AMAP_WEB_SERVICE_KEY`。
- Produces: 三次脱敏控制台结果：驾车路线、地理编码、重复驾车路线；不产生仓库文件。

- [ ] **Step 1: 只判断 Key 是否存在**

Run:

```powershell
$keyPresent = -not [string]::IsNullOrWhiteSpace($env:DRT_AMAP_WEB_SERVICE_KEY)
Write-Output "AMAP_KEY_PRESENT=$($keyPresent.ToString().ToLowerInvariant())"
if (-not $keyPresent) { throw 'DRT_AMAP_WEB_SERVICE_KEY is not configured in this process' }
function Invoke-SanitizedAmapRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][hashtable]$Body
    )
    try {
        Invoke-RestMethod -Uri $Uri -Method Get -Body $Body -TimeoutSec 10 -ErrorAction Stop
    }
    catch {
        $httpStatus = if ($null -ne $_.Exception.Response) {
            [int]$_.Exception.Response.StatusCode
        }
        else {
            'unavailable'
        }
        throw "AMAP preflight transport failure; httpStatus=$httpStatus"
    }
}
```

Expected: 只输出 `AMAP_KEY_PRESENT=true`，不输出 Key 值。

- [ ] **Step 2: 执行首次驾车路线预检**

Run:

```powershell
$routeBody = @{
    key = $env:DRT_AMAP_WEB_SERVICE_KEY
    origin = '105.242100,35.210300'
    destination = '105.244000,35.212000'
}
$route = Invoke-SanitizedAmapRequest -Uri 'https://restapi.amap.com/v3/direction/driving' -Body $routeBody
$routePath = $route.route.paths | Select-Object -First 1
[pscustomobject]@{
    operation = 'driving-route-1'
    status = $route.status
    info = $route.info
    infocode = $route.infocode
    pathCount = @($route.route.paths).Count
    distanceMeters = $routePath.distance
    durationSeconds = $routePath.duration
} | ConvertTo-Json -Compress
```

Expected: `status=1`、`infocode=10000`，路线数量至少 1，距离和耗时均为正数。

- [ ] **Step 3: 执行地理编码预检**

Run:

```powershell
$geoBody = @{
    key = $env:DRT_AMAP_WEB_SERVICE_KEY
    address = '甘肃省定西市通渭县人民政府'
    city = '通渭县'
}
$geo = Invoke-SanitizedAmapRequest -Uri 'https://restapi.amap.com/v3/geocode/geo' -Body $geoBody
$firstGeocode = $geo.geocodes | Select-Object -First 1
[pscustomobject]@{
    operation = 'geocode'
    status = $geo.status
    info = $geo.info
    infocode = $geo.infocode
    resultCount = @($geo.geocodes).Count
    district = $firstGeocode.district
    locationPresent = -not [string]::IsNullOrWhiteSpace([string]$firstGeocode.location)
} | ConvertTo-Json -Compress
```

Expected: `status=1`、`infocode=10000`、结果至少 1 条，区县为通渭县且坐标存在。

- [ ] **Step 4: 重复路线请求并检查配额/限流状态**

Run: 再执行 Step 2，但把 `operation` 改为 `driving-route-2`。

Expected: 第二次仍为 `status=1`、`infocode=10000`，没有权限、配额或限流错误。若失败，只记录 `status/info/infocode`，不得输出请求对象、请求 URL 或异常中包含的 Key。

- [ ] **Step 5: 记录预检结论**

在执行日志中记录三项是否通过。预检失败则停止，不进入真实批量采样；先按 `systematic-debugging` 查明权限、网络、坐标或接口问题。

### Task 2: 评估计算与证据渲染核心

**Files:**
- Create: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupport.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupportTest.java`

**Interfaces:**
- Consumes: `List<StopSample>`、`List<PreclassificationResult>`、`List<RouteCallResult>`。
- Produces: `candidatePairs(List<StopSample>)`、`selectFixedSamples(List<PreclassificationResult>)`、`summarize(List<FixedRouteSample>, List<RouteCallResult>)`、`writeEvidence(Path, EvaluationSummary)`。
- Enum: `DistanceBand { SHORT, MEDIUM, LONG }`。
- Records: `StopSample(String id, String name, BigDecimal longitude, BigDecimal latitude)`、`RoutePair(String id, StopSample origin, StopSample destination, String bearingSector)`、`PreclassificationResult(RoutePair pair, boolean success, Integer distanceMeters, Integer durationSeconds, long latencyMs, String failureReason)`、`FixedRouteSample(String sampleId, DistanceBand band, RoutePair pair, int baselineDistanceMeters, int baselineDurationSeconds)`、`RouteCallResult(String sampleId, int iteration, Instant requestedAt, long latencyMs, boolean success, Integer distanceMeters, Integer durationSeconds, String provider, boolean degraded, String failureReason)`、`RouteAnomaly(String sampleId, String type, BigDecimal changeRate, int iteration)`、`EvaluationSummary(List<FixedRouteSample> samples, List<RouteCallResult> calls, int successCount, int failureCount, BigDecimal successRate, boolean meetsSuccessThreshold, Map<String, Integer> failuresByReason, List<RouteAnomaly> anomalies, LatencySummary latency)`、`LatencySummary(long minimumMs, long medianMs, long p90Ms, long maximumMs)`。

- [ ] **Step 1: 写候选路线与 5/10/5 分层失败测试**

```java
@Test
void selectsFiveShortTenMediumAndFiveLongRoutesWithCardinalCoverage() {
    List<PreclassificationResult> candidates = fixturesAcrossEightBearingSectors();

    List<FixedRouteSample> selected = P61RouteEvaluationSupport.selectFixedSamples(candidates);

    assertThat(selected).hasSize(20);
    assertThat(selected).filteredOn(sample -> sample.band() == DistanceBand.SHORT).hasSize(5);
    assertThat(selected).filteredOn(sample -> sample.band() == DistanceBand.MEDIUM).hasSize(10);
    assertThat(selected).filteredOn(sample -> sample.band() == DistanceBand.LONG).hasSize(5);
    assertThat(selected).extracting(FixedRouteSample::bearingSector)
            .contains("N", "E", "S", "W");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=P61RouteEvaluationSupportTest test`

Expected: FAIL，原因是 `P61RouteEvaluationSupport` 和相关记录类型尚不存在。

- [ ] **Step 3: 实现候选生成和确定性分层**

实现规则：

1. 对所有不同站点生成有向站点对，计算 Haversine 距离和 8 方位扇区。
2. 每个扇区按 Haversine 距离取 10%、25%、40%、60%、75%、90% 六个分位候选，共最多 48 组；同一路线去重。
3. 只允许真实预分类成功且距离、耗时均为正数的候选进入选择。
4. 按真实驾车距离排序：短距离从低端轮询扇区取 5 组，长距离从高端轮询扇区取 5 组，中距离从剩余候选中按距离接近中位数且轮询扇区取 10 组。
5. 最终样本编号固定为 `S01`–`S05`、`M01`–`M10`、`L01`–`L05`。
6. 若无法满足 5/10/5 或 N/E/S/W 覆盖，抛出明确异常，禁止正式采样。

- [ ] **Step 4: 写成功率、波动和失败分类失败测试**

```java
@Test
void requiresAtLeast198SuccessfulCallsAndFlagsDistanceOrEtaJumps() {
    EvaluationSummary summary = P61RouteEvaluationSupport.summarize(
            twentyFixedSamples(), twoHundredCallsWithTwoFailuresAndOneEtaJump());

    assertThat(summary.successCount()).isEqualTo(198);
    assertThat(summary.successRate()).isEqualByComparingTo("0.9900");
    assertThat(summary.meetsSuccessThreshold()).isTrue();
    assertThat(summary.anomalies()).extracting(RouteAnomaly::type).contains("ETA_CHANGE_OVER_20_PERCENT");
}
```

再增加 197/200 不通过、某组 0 次成功不通过、距离变化恰好 5%不告警、超过 5%告警、ETA 恰好 20%不告警、超过 20%告警的边界测试。

- [ ] **Step 5: 实现汇总计算**

使用 `BigDecimal` 计算四位小数成功率；每组首次成功结果作为波动基准。汇总至少包含总调用数、成功/失败数、成功率、每组成功数、延迟最小值/中位数/P90/最大值、失败原因计数和异常列表。

- [ ] **Step 6: 写证据脱敏失败测试**

```java
@Test
void evidenceNeverContainsKeyOrCompleteRequestUrl() throws IOException {
    Path output = tempDir.resolve("evidence");
    P61RouteEvaluationSupport.writeEvidence(output, passingSummary());

    String allEvidence = Files.walk(output)
            .filter(Files::isRegularFile)
            .map(path -> readUtf8(path))
            .collect(Collectors.joining("\n"));
    assertThat(allEvidence)
            .doesNotContain("test-secret-key", "key=", "restapi.amap.com/v3/")
            .contains("ETA 仅作参考");
}
```

- [ ] **Step 7: 实现 CSV、JSON、Markdown 渲染并通过测试**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=P61RouteEvaluationSupportTest test`

Expected: PASS，且临时证据包含逐调用 CSV、汇总 JSON 和 Markdown，均不含 Key 或完整 URL。

- [ ] **Step 8: 提交评估核心**

```powershell
git add -- apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupport.java apps/api/src/test/java/com/idavy/drtops/integration/amap/P61RouteEvaluationSupportTest.java
git commit -m "test: add P6 route evaluation support"
```

### Task 3: 显式开关控制的真实高德评估入口

**Files:**
- Create: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61AmapRouteEvaluationIT.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/integration/amap/P61AmapRouteEvaluationIT.java`

**Interfaces:**
- Consumes: `DRT_AMAP_WEB_SERVICE_KEY`、`drt.integration.amap-evaluation`、`drt.integration.amap-phase`、`drt.integration.amap-input`、`drt.integration.amap-output`。
- Produces: `preclassify` 阶段输出候选真实路线和 20 组目录；`formal` 阶段输出 200 次调用与汇总证据。

- [ ] **Step 1: 写默认禁用失败测试**

增加测试，确认没有 `-Ddrt.integration.amap-evaluation=true` 时使用 JUnit assumption 跳过，不访问网络；Key 缺失时在任何网络调用前失败，错误只写“Key 未配置”。

- [ ] **Step 2: 运行测试确认入口尚不存在**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=P61AmapRouteEvaluationIT test`

Expected: FAIL，原因是测试类尚不存在。

- [ ] **Step 3: 实现真实提供方构造与安全门禁**

构造真实链路：

```java
AmapProperties properties = new AmapProperties();
properties.setEnabled(true);
properties.setWebServiceKey(requireSecretEnvironment("DRT_AMAP_WEB_SERVICE_KEY"));
properties.setBaseUrl("https://restapi.amap.com");
HttpClient httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs())
        .responseTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
WebClient webClient = WebClient.builder()
        .baseUrl(properties.getBaseUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
AmapProviderMetrics metrics = new AmapProviderMetrics(new SimpleMeterRegistry());
AmapRoutePlanningProvider routeProvider = new AmapRoutePlanningProvider(webClient, properties, metrics);
```

日志只能输出阶段、样本编号、迭代、成功状态、距离、ETA、延迟和安全失败分类。禁止打印 `properties`、环境变量、请求 URI、WebClient request 或异常 cause 链。

- [ ] **Step 4: 实现 `preclassify` 阶段**

读取 UTF-8 虚拟站点 CSV，调用 `candidatePairs` 生成最多 48 组候选；相邻调用至少间隔 1 秒。对每组调用 `routeProvider.drivingRoute`，保存脱敏结果，再调用 `selectFixedSamples` 写出预分类 CSV 和固定 20 组 CSV。

- [ ] **Step 5: 实现 `formal` 阶段并确保 200 次真实外呼**

对 20 组按轮次交错调用，避免同一路线瞬时连发。每次调用使用新的服务实例绕过业务缓存：

```java
TravelEstimateService service = new TravelEstimateService(null, routeProvider);
TravelEstimate estimate = service.estimateBetween(sample.origin(), sample.destination());
```

断言每次成功结果满足 `provider=AMAP`、`degraded=false`、距离和耗时为正数。每次完成后等待至距离上一次调用至少 1 秒。无论成功或失败都追加内存结果；完成 200 次后统一写证据，避免半截 CSV 被误判为完整验收。

- [ ] **Step 6: 运行默认禁用测试**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=P61AmapRouteEvaluationIT test`

Expected: 测试被跳过，外部调用数为 0。

- [ ] **Step 7: 提交真实评估入口**

```powershell
git add -- apps/api/src/test/java/com/idavy/drtops/integration/amap/P61AmapRouteEvaluationIT.java
git commit -m "test: add gated real AMap route evaluation"
```

### Task 4: 站点快照、预分类与 200 次正式采样

**Files:**
- Create: `docs/pilot/evidence/p6-1-virtual-stop-snapshot-2026-08-09.csv`
- Create: `docs/pilot/evidence/p6-1-route-preclassification-2026-08-09.csv`
- Create: `docs/pilot/evidence/p6-1-fixed-route-samples-2026-08-09.csv`
- Create: `docs/pilot/evidence/p6-1-route-calls-2026-08-09.csv`
- Create: `docs/pilot/evidence/p6-1-route-summary-2026-08-09.json`

**Interfaces:**
- Consumes: 当前隔离试点 PostGIS 中已发布服务区内的启用虚拟站点；Task 3 的 `preclassify` 和 `formal` 阶段。
- Produces: 可复算的站点、候选、固定样本、逐调用和汇总证据。

- [ ] **Step 1: 只读导出启用虚拟站点**

在确认实际试点数据库容器和数据库名后，使用只读 SQL：

```sql
SELECT id,
       name,
       ST_X(location::geometry) AS longitude,
       ST_Y(location::geometry) AS latitude,
       coordinate_system
FROM virtual_stops
WHERE enabled = true
ORDER BY normalized_name, id;
```

导出 UTF-8 CSV。执行前后分别查询 `count(*)`，预期均为 31；不得执行 `INSERT`、`UPDATE`、`DELETE`、DDL 或事务锁命令。

- [ ] **Step 2: 运行预分类**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api `
  '-Dtest=P61AmapRouteEvaluationIT' `
  '-Ddrt.integration.amap-evaluation=true' `
  '-Ddrt.integration.amap-phase=preclassify' `
  '-Ddrt.integration.amap-input=docs/pilot/evidence/p6-1-virtual-stop-snapshot-2026-08-09.csv' `
  '-Ddrt.integration.amap-output=docs/pilot/evidence' test
```

Expected: 候选均为真实 `AMAP` 且未降级；生成 5/10/5 共 20 组，覆盖 N/E/S/W。

- [ ] **Step 3: 人工审阅固定样本目录**

核对 20 组均位于已发布服务区、没有同起终点、没有乘客或订单数据，短/中/长为 5/10/5，方向覆盖满足设计。任何不合格路线必须回到候选选择，不能直接手改采样结果。

- [ ] **Step 4: 运行 200 次正式采样**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api `
  '-Dtest=P61AmapRouteEvaluationIT' `
  '-Ddrt.integration.amap-evaluation=true' `
  '-Ddrt.integration.amap-phase=formal' `
  '-Ddrt.integration.amap-input=docs/pilot/evidence/p6-1-fixed-route-samples-2026-08-09.csv' `
  '-Ddrt.integration.amap-output=docs/pilot/evidence' test
```

Expected: 总调用数 200、成功数至少 198、20 组均至少成功一次；CSV 和 JSON 不含 `key=` 或高德完整 URL。

- [ ] **Step 5: 独立复算核心指标**

从 CSV 独立计算总数、成功数、成功率、每组成功数、延迟中位数/P90、距离与 ETA 最大变化率；与 JSON 汇总逐项一致。使用 `rg -n -g 'p6-1-*' "key=|DRT_AMAP_WEB_SERVICE_KEY|restapi\.amap\.com/v3/" docs/pilot/evidence`，预期无匹配。

- [ ] **Step 6: 采集脱敏路线摘要**

对固定 20 组各追加 1 次真实驾车路线调用，保存路径点数和坐标序列的 SHA-256 指纹，不保存完整路线坐标。预期 20/20 点数为正、指纹均为 64 位且 Key 扫描无匹配。

- [ ] **Step 7: 提交固定技术样本证据**

```powershell
git add -- docs/pilot/evidence/p6-1-virtual-stop-snapshot-2026-08-09.csv docs/pilot/evidence/p6-1-route-preclassification-2026-08-09.csv docs/pilot/evidence/p6-1-fixed-route-samples-2026-08-09.csv docs/pilot/evidence/p6-1-route-calls-2026-08-09.csv docs/pilot/evidence/p6-1-route-shapes-2026-08-09.csv docs/pilot/evidence/p6-1-route-summary-2026-08-09.json
git commit -m "test: collect P6 real route samples"
```

### Task 5: 降级复验、报告与运营偏差台账

**Files:**
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/AmapMapSearchProviderTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/integration/amap/AmapRoutePlanningProviderTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/TravelEstimateServiceTest.java`
- Create: `docs/pilot/evidence/p6-1-real-route-eta-evaluation-2026-08-09.md`
- Create: `docs/pilot/evidence/p6-1-real-eta-observation-log.csv`
- Modify: `progress.md`

**Interfaces:**
- Consumes: Task 4 的技术样本汇总，以及现有 `MapProviderException`/`TravelEstimateService` 降级契约。
- Produces: 五类故障证据、技术结论、后续 10 笔真实运营样本台账模板和进度状态。

- [ ] **Step 1: 补齐五类故障的明确断言**

在现有测试中分别覆盖：

- `INVALID_USER_KEY/10001`：安全转换为 `upstream-rejected`，消息不含原始 info/infocode；
- `DAILY_QUERY_OVER_LIMIT/10003`：安全转换为 `upstream-rejected`；
- `TimeoutException` 和 `ReadTimeoutException`：转换为 `request-timeout`；
- 非法经纬度：在 `Coordinate` 或入口校验阶段拒绝，不发起 HTTP；
- `status=1` 但缺少路线必需字段：转换为 `upstream-response-invalid`。

并在 `TravelEstimateServiceTest` 断言以上提供方失败进入 `STRAIGHT_LINE`、`degraded=true`，调度相关既有测试继续断言 `MAP_ROUTE_UNAVAILABLE` 阻止自动派单。

- [ ] **Step 2: 运行专项测试**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api '-Dtest=AmapMapSearchProviderTest,AmapRoutePlanningProviderTest,TravelEstimateServiceTest,DispatchOrchestratorMapEstimateTest,P61RouteEvaluationSupportTest' test
```

Expected: 全部通过，失败/错误为 0；真实评估入口未开启，不发生外部调用。

- [ ] **Step 3: 编写技术评估报告**

报告必须列出：预检三项结果、Key/配额结论、站点快照口径、候选与 20 组样本构成、200 次成功率、延迟分布、每组距离/ETA 波动、失败分类、五类降级测试、异常解释、限制和技术收口结论。标题附近和结论中均写明“ETA 仅作参考”。

- [ ] **Step 4: 创建真实运营偏差台账模板**

CSV 表头固定为：

```csv
sample_id,order_id,dispatch_decision_at,predicted_pickup_eta_seconds,actual_pickup_at,pickup_time_source,pickup_absolute_error_seconds,predicted_trip_eta_seconds,actual_dropoff_at,dropoff_time_source,trip_absolute_error_seconds,absolute_percentage_error,estimate_provider,estimate_degraded,exclusion_reason,review_status
```

不填造任何真实订单。只有预测时间与可信实际时间齐备的完成订单才填写；人工补录且无法证明实际事件时间的样本填 `exclusion_reason`，不进入 10 笔有效样本。

- [ ] **Step 5: 运行全量回归和证据检查**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test
git diff --check
rg -n "key=|DRT_AMAP_WEB_SERVICE_KEY|restapi\.amap\.com/v3/" docs/pilot/evidence/p6-1-*
```

Expected: Maven 退出码 0，`git diff --check` 无错误，密钥/完整 URL 扫描无匹配。

- [ ] **Step 6: 更新 P6-1 状态并提交**

若技术收口条件全部满足，`progress.md` 记录“P6-1 技术评估完成、运营偏差观察进行中”；不得写成 P6-1 全部完成。

```powershell
git add -- apps/api/src/test/java/com/idavy/drtops/integration/amap/AmapMapSearchProviderTest.java apps/api/src/test/java/com/idavy/drtops/integration/amap/AmapRoutePlanningProviderTest.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/TravelEstimateServiceTest.java docs/pilot/evidence/p6-1-real-route-eta-evaluation-2026-08-09.md docs/pilot/evidence/p6-1-real-eta-observation-log.csv progress.md
git commit -m "docs: record P6 route ETA technical evaluation"
```

### Task 6: 最终验证与交接

**Files:**
- Modify: none

**Interfaces:**
- Consumes: 所有代码、测试、证据和 Git 提交。
- Produces: 可审阅的技术完成结论与 10 笔运营样本后续入口。

- [ ] **Step 1: 使用 verification-before-completion 复核证据**

重新执行专项测试、全量 Maven 测试、证据脱敏扫描、`git status --short` 和最近提交检查。不得用旧日志代替本轮输出。

- [ ] **Step 2: 核对技术收口条件**

逐条核对：预检通过、20 组有效、200 次成功率至少 99%、异常均有解释、五类降级保护通过、证据可复算且无 Key、ETA 仍为参考。

- [ ] **Step 3: 报告阶段状态**

只有全部技术条件满足时报告“P6-1 技术评估完成、运营偏差观察进行中”。明确尚需随试运行积累 10 笔真实运营偏差样本，不能报告 P6-1 全部完成。
