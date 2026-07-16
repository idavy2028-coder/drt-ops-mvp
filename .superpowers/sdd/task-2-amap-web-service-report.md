# Task 2 高德 Web Service API 适配层报告

## TDD 记录

1. 先新增地址提示、地理编码、驾车路径、距离、失败降级和控制器权限测试。
2. 第一次执行指定 Maven 命令时，测试因 Task 2 的 DTO、Provider、指标和控制器尚不存在而编译失败（RED）。
3. 实现最小 domain 接口、Amap Web Service 适配器、指标、控制器和中文异常映射后，适配器测试通过（GREEN）。
4. 自审发现 `status=1` 但响应结构不完整时未统计降级指标；补充失败测试确认问题后，将解析失败收敛到共享客户端的 `upstream-response-invalid` 降级路径，测试再次通过（RED/GREEN）。

## 改动文件

- `apps/api/src/main/java/com/idavy/drtops/domain/map/`：内部坐标、搜索与路径 DTO、Provider 接口和受保护控制器。
- `apps/api/src/main/java/com/idavy/drtops/integration/amap/`：高德 Web Service HTTP 调用、JSON 解析和 Micrometer 指标。
- `apps/api/src/main/java/com/idavy/drtops/common/GlobalExceptionHandler.java`：地图服务不可用统一返回中文 503。
- `apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java`：`/api/map/**` 纳入既有 `RESOURCE_MANAGE` 运营资源权限。
- `apps/api/src/main/java/com/idavy/drtops/domain/map/MapProviderException.java`：改为中文业务错误，避免泄漏高德原始错误。
- `apps/api/src/test/java/com/idavy/drtops/integration/amap/`：本地 HTTP 模拟的高德适配器测试。
- `apps/api/src/test/java/com/idavy/drtops/domain/map/MapProviderControllerTest.java`：控制器权限测试。

## 测试结果

- RED：`./.tools/apache-maven-3.9.11/bin/mvn.cmd -q -pl apps/api '-Dtest=AmapMapSearchProviderTest,AmapRoutePlanningProviderTest' test`，预期因生产类型不存在而失败。
- GREEN：同一命令通过，9 项适配器测试通过；后续增加不完整响应降级指标测试后，`AmapRoutePlanningProviderTest` 5 项通过。
- 控制器：`./.tools/apache-maven-3.9.11/bin/mvn.cmd -q -pl apps/api '-Dtest=MapProviderControllerTest' test` 通过。
- 合并验证：`./.tools/apache-maven-3.9.11/bin/mvn.cmd -q -pl apps/api '-Dtest=AmapMapSearchProviderTest,AmapRoutePlanningProviderTest,MapProviderControllerTest' test` 通过。
- 全量回归：`./.tools/apache-maven-3.9.11/bin/mvn.cmd -q -pl apps/api test` 已启动，但被用户中断并终止测试进程，未取得可用的全量通过结果。

## 已知限制

- 未调用真实高德，测试仅使用本地 HTTP 服务和 WebClient 超时桩。
- 高德 Key 仅从服务端 `AmapProperties` 读取并作为 Web Service 请求参数使用；没有客户端 Key、真实 Key 或付费服务调用。
- 全量 `apps/api` 回归需要在未中断环境中补跑。

## 独立审阅修复（2026-07-16）

- 网络失败分类：`ReadTimeoutException` 和 `WebClientRequestException` 包装的读超时归为 `request-timeout`，返回“地图服务请求超时，请稍后重试”；DNS、连接和 Socket 失败归为 `upstream-network-unavailable`，返回“地图上游网络不可用，请稍后重试”。两类均记录对应的降级指标，且不泄漏主机名或上游细节。
- 请求校验：`ConstraintViolationException`、缺少查询参数、不可读请求体和请求体字段校验统一产生中文业务错误。空 `keyword`/`city` 分别返回 `keyword不能为空`/`city不能为空`，缺失距离终点返回 `destination不能为空`。
- 边界与权限：新增 16 个途经点的完整请求编码断言，保留 17 个途经点拒绝测试；新增匿名用户访问地图端点被拒绝的覆盖。
- 本轮 TDD：先运行新增测试，确认 Reactor Netty 读超时/DNS 异常被错误归类、空查询参数未处理且请求体缺失返回英文文案（RED）；修复后运行以下命令通过（GREEN）：

  ```powershell
  .\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api '-Dtest=AmapMapSearchProviderTest,AmapRoutePlanningProviderTest,MapProviderControllerTest' test
  ```

  Surefire 结果：地址搜索 8 项、路径规划 6 项、控制器 6 项，均为 0 failures / 0 errors。

## 复审补充覆盖（2026-07-16）

- 新增 `MapProviderControllerTest` 回归：已认证且具备 `RESOURCE_MANAGE` 权限的运营角色省略 `GET /api/map/address-suggestions` 的必填 `keyword` 时，断言 HTTP 400 与稳定中文消息 `缺少请求参数：keyword`。
- 验证命令：

  ```powershell
  .\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api '-Dtest=MapProviderControllerTest' test
  ```

  结果：通过（0 failures / 0 errors）。既有 `GlobalExceptionHandler` 已处理 `MissingServletRequestParameterException`，因此无需调整生产代码。
