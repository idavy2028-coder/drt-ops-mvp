DONE_WITH_CONCERNS

提交 SHA：待提交。

审阅修复：
- 已发布 `boundary` 与 `draft_boundary` 分离。手工保存和高德导入仅更新草稿；发布事务才原子复制草稿至已发布边界、更新发布版本和时间。V7 兼容已有非空 boundary，将其标记为已发布 legacy 边界。
- `POST /api/service-areas` 现在复用命令服务的 WKT/GeoJSON 正规化及稳定中文校验。
- `/api/service-areas/{id}/contains` 使用带 serviceAreaId 过滤的 PostGIS 查询，响应保留 inside、serviceAreaId、distanceToBoundaryMeters 和 GCJ02。
- Polygon 闭合判断改为 BigDecimal.compareTo，避免小数 scale 差异造成误拒绝。
- 增加服务区 API 的 RESOURCE_MANAGE 权限、无高德 Key 导入失败、创建校验、contains 响应测试；增加服务区导入/发布审计、订单区外拒绝及草稿不替换已发布边界测试。
- PostGIS 条件集成测试覆盖 published_at 过滤、指定服务区、多服务区距离、无已发布围栏兼容和草稿 B 不影响已发布 A。

测试隔离根因与修复：
- 根因：Docker 不可用时 DatabaseMigrationTest 和 PostgisServiceAreaLocationCheckerTest 回退连接固定共享库 jdbc:postgresql://127.0.0.1:15432/drt_ops，Flyway 不清理历史数据，破坏新库种子断言。
- 修复：两个测试现在仅在 Docker/Testcontainers 可用时启动隔离 PostGIS；设置 drt.integration.postgis=true 但 Docker 不可用时，以“需要 Docker/Testcontainers 提供隔离 PostGIS 数据库”明确跳过，绝不再迁移或写入共享库。

TDD 红绿证据：
- RED 1：新增草稿/已发布分离测试后，指定测试编译失败，缺少 CreateServiceAreaCommand 和草稿视图字段。
- GREEN 1：补齐实体、命令服务、V7 和 compareTo 后，ServiceAreaCommandServiceTest 与 RideOrderServiceAreaValidationTest 通过。
- RED 2：新增指定 serviceAreaId 的真实 PostGIS 测试后，编译失败，checker 缺少带 ID 的查询签名。
- GREEN 2：补齐带 ID 的 PostGIS SQL 和 contains 调用后，H2 定向/API 测试通过；Docker 不可用时真实 PostGIS 断言按隔离策略跳过。

测试命令与结果：
- `& '.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd' -q -pl apps/api '-Dtest=ServiceAreaCommandServiceTest,RideOrderServiceAreaValidationTest' test`：通过，exit code 0。
- `& '.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd' -q -pl apps/api '-Dtest=ServiceAreaCommandServiceTest,RideOrderServiceAreaValidationTest,ServiceAreaApiTest' test`：通过，exit code 0，16 个测试通过。
- `& '.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd' -q -pl apps/api '-Ddrt.integration.postgis=true' '-Dtest=PostgisServiceAreaLocationCheckerTest,DatabaseMigrationTest' test`：通过，exit code 0；1 个迁移测试和 2 个 PostGIS 空间测试因 Docker 不可用按 assumption 跳过，静态迁移脚本断言通过。

改动文件：
- apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceArea.java
- apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaCommandService.java
- apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaController.java
- apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaView.java
- apps/api/src/main/java/com/idavy/drtops/domain/location/PostgisServiceAreaLocationChecker.java
- apps/api/src/main/java/com/idavy/drtops/domain/location/ServiceAreaLocationChecker.java
- apps/api/src/main/resources/db/migration/V7__enhance_service_area_boundaries.sql
- apps/api/src/test/java/com/idavy/drtops/DatabaseMigrationTest.java
- apps/api/src/test/java/com/idavy/drtops/domain/area/ServiceAreaApiTest.java
- apps/api/src/test/java/com/idavy/drtops/domain/area/ServiceAreaCommandServiceTest.java
- apps/api/src/test/java/com/idavy/drtops/domain/location/PostgisServiceAreaLocationCheckerTest.java

已知限制：
- 本机 Docker/Testcontainers 不可用，隔离 PostGIS 容器中的迁移与空间断言未实际执行；这是试点前必须恢复的环境验证项。
- 高德行政区返回多片区边界时仍稳定拒绝，需人工拆分或调整表类型后再导入；不会影响手工草稿保存、本地已发布围栏判定或既有流程。
