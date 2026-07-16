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
