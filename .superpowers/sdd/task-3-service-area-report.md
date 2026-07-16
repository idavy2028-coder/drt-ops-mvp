DONE_WITH_CONCERNS

本轮代码提交：`4891cbcf1d4254774e072ff2f852f87d9e9c1a21`（`fix: persist service area geofences as geography`）。

## 审阅修复

- `ServiceArea.boundary` 和 `draftBoundary` 已改为 JTS `Polygon`，使用项目既有的 `@JdbcTypeCode(SqlTypes.GEOGRAPHY)` 持久化范式；DTO/API 继续返回 WKT。新增 `GeographyPolygon` 负责边界 WKT 与 JTS 的转换。
- 已发布边界与草稿边界仍严格隔离。手工保存和高德导入只更新草稿；发布时才将草稿复制为已发布边界。命令服务及真实 PostGIS 条件测试覆盖 A 已发布、B 草稿不影响本地判定、发布 B 后再切换。
- V7 对旧非空边界回填为已发布基线版本 1；新建草稿版本从 1 开始，首次导入或保存不再显示为 2；每次实际发布使已发布版本单调递增。
- `ServiceArea` 增加 `@Version`。保存草稿和发布经 `saveAndFlush` 执行，乐观锁冲突转换为中文 409 错误“服务区边界已被其他操作更新，请刷新后重试”；单测覆盖并发旧实体提交失败。
- 保留首轮修复：创建入口复用 WKT 正规化和中文校验、contains 严格按 `serviceAreaId` 查询、闭合点使用 `BigDecimal.compareTo`、RBAC/审计、订单区外拒绝与 Docker 不可用时不访问共享数据库。

## TDD 红绿证据

- RED：先增加 JTS 持久化、首次导入草稿版本、发布版本递增及并发冲突测试；实现前缺少 JTS 几何转换/实体版本字段及相应命令行为，测试无法通过。
- GREEN：补齐 `GeographyPolygon`、实体 geography 映射、版本规则和乐观锁翻译后，H2/API 定向测试退出码为 0。
- RED：新增命令端真实 PostGIS 测试首次运行时，静态构造容器使 Docker 不可用场景出现 `ExceptionInInitializerError`，且 PostGIS 镜像未声明 PostgreSQL 兼容性。
- GREEN：容器改为条件满足后延迟创建，并使用 `asCompatibleSubstituteFor("postgres")`；Docker 不可用时隔离测试退出码为 0 且按 assumption 跳过。

## 测试命令与结果

- `& '.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd' -q -pl apps/api '-Dtest=ServiceAreaCommandServiceTest,RideOrderServiceAreaValidationTest,ServiceAreaApiTest' test`：通过，exit code 0。
- `& '.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd' -q -pl apps/api '-Ddrt.integration.postgis=true' '-Dtest=PostgisServiceAreaLocationCheckerTest,DatabaseMigrationTest,ServiceAreaCommandPostgisIntegrationTest' test`：通过，exit code 0。Docker 不可用时三个隔离测试按 assumption 跳过，没有迁移或写入共享 `drt_ops` 库。
- `git diff --check`：通过。

## 改动文件

- `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceArea.java`
- `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaCommandService.java`
- `apps/api/src/main/java/com/idavy/drtops/domain/location/GeographyPolygon.java`
- `apps/api/src/main/resources/db/migration/V7__enhance_service_area_boundaries.sql`
- `apps/api/src/test/java/com/idavy/drtops/domain/area/ServiceAreaCommandServiceTest.java`
- `apps/api/src/test/java/com/idavy/drtops/domain/area/ServiceAreaCommandPostgisIntegrationTest.java`

## 已知限制

- 本机 Docker/Testcontainers 不可用。真实隔离 PostGIS 容器内的 migration、空间查询及命令端点 geography 写读/发布切换测试已安全跳过，必须在试点前具备 Docker 的环境执行。
- 高德多片行政区边界仍要求人工拆分或调整模型后导入；这不影响手工草稿保存和本地已发布围栏判定。
