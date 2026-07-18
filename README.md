# 区域动态响应公交运营管理 MVP

这是一个面向公交企业内部运营闭环的区域动态响应公交运营管理 MVP monorepo。第一版只覆盖“虚拟站点 + 区域动态响应公交”的核心运营链路，不包含完整乘客端、司机端、监管端、支付、会员、营销和完整客服体系。

## 目录结构

- `apps/api`: Spring Boot 业务 API，负责订单、任务、审计和规则的最终业务状态。
- `apps/algorithm`: FastAPI 调度算法服务，只返回调度建议和解释，不直接写业务表。
- `apps/admin-web`: Vue 3 管理端首页骨架。
- `infra`: 本地 PostgreSQL/PostGIS 与 Redis 依赖。

## 本地端口

- `postgres`: `5432`
- `redis`: `6379`
- `api`: `8080`
- `algorithm`: `8090`
- `admin-web`: `5173`

## 本地启动

1. 先启动基础依赖：
   ```powershell
   docker compose -f infra/docker-compose.yml up -d
   ```
2. 再启动后端：
   ```powershell
   $env:DRT_AUTH_JWT_SECRET = "replace-with-at-least-32-random-bytes"
   $env:DRT_AUTH_BOOTSTRAP_ADMIN_USERNAME = "admin"
   $env:DRT_AUTH_BOOTSTRAP_ADMIN_PASSWORD = "replace-this-temporary-password"
   mvn -pl apps/api spring-boot:run
   ```
3. 准备并启动算法服务：
   ```powershell
   cd apps/algorithm
   python -m venv .venv
   .\.venv\Scripts\python -m pip install -e .[dev]
   .\.venv\Scripts\python -m uvicorn drt_algorithm.main:app --host 0.0.0.0 --port 8090 --reload
   ```
4. 准备并启动前端：
   ```powershell
   cd apps/admin-web
   npm install
   npm run dev
   ```

## 健康检查

- API: `GET http://localhost:8080/actuator/health`
- 算法: `GET http://localhost:8090/health`
- 前端: `GET http://localhost:5173/`

## 开放瓦片地图本机配置

管理端默认使用 Leaflet 加载 OpenStreetMap 标准公开瓦片：`https://tile.openstreetmap.org/{z}/{x}/{y}.png`。页面会显示 `© OpenStreetMap contributors` 归属信息。业务接口、服务区 WKT 与虚拟站点仍统一保存 GCJ-02 坐标；仅在地图显示和点选边界转换为 WGS84。

可按受控部署环境配置瓦片地址、归属文本和最大缩放级别：

```powershell
$env:VITE_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
$env:VITE_TILE_ATTRIBUTION = "&copy; <a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\" rel=\"noreferrer\">OpenStreetMap contributors</a>"
$env:VITE_TILE_MAX_ZOOM = "19"
```

试点只允许交互式浏览器按需请求瓦片，禁止预取、离线下载、服务端代理和批量缓存。公共 OSM 瓦片不提供 SLA；底图不可用时，围栏文本、虚拟站点、手工经纬度和订单/任务操作仍可使用。未来可切换至已获授权的商业瓦片服务，或在完成企业授权后接入官方高德增强能力。

## 通渭县地图与虚拟站点试点准备

- [试点准备清单](docs/pilot/tongwei-amap-virtual-stop-readiness.md)
- [虚拟站点采集与导入指南](docs/pilot/tongwei-virtual-stop-collection-guide.md)
- [开放瓦片合规与运行检查表](docs/pilot/tongwei-map-quota-checklist.md)
- [地图与站点验收记录](docs/pilot/tongwei-map-and-stop-acceptance-record.md)

## 本地验证

- 后端普通测试：`mvn -pl apps/api test`
- 后端真实 PostGIS 持久化测试：先启动本地 `infra/docker-compose.yml`，再运行 `mvn -pl apps/api -Dtest=PostgisDispatchPersistenceIntegrationTest -Ddrt.integration.postgis=true test`
- 后端 Testcontainers 版 PostGIS 迁移测试：Docker/Testcontainers 环境可用时运行 `mvn -pl apps/api -Dtest=DatabaseMigrationTest -Ddrt.integration.postgis=true test`。在部分 Windows Docker Desktop named pipe 环境下，Docker CLI 可用但 Java Testcontainers 仍可能无法连接，此时测试会跳过，应以 Docker-capable CI 或 Linux 环境补跑。

### 车辆位置管理自动化验证

```powershell
mvn -q -pl apps/api test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run test
npm.cmd --prefix apps/admin-web run build
npm.cmd --prefix apps/admin-web run e2e -- vehicle-location-flow.spec.ts
```

车辆位置模块的人工上报、异常处置和试点前待关闭项见[通渭县车辆位置管理试点运行手册](docs/release/tongwei-vehicle-location-runbook.md)；本机自动化验收范围与未关闭风险见[车辆位置管理模块本机验收记录（2026-07）](docs/release/vehicle-location-acceptance-2026-07.md)。模块自动化验证不代表真实浏览器瓦片加载、生产 PostGIS、容量验证或备份恢复演练已经通过。

## 端到端演示链路

完整演示建议按下面顺序启动本地服务：

```powershell
docker compose -f infra/docker-compose.yml up -d
mvn -pl apps/api spring-boot:run
cd apps/algorithm
python -m uvicorn drt_algorithm.main:app --port 8090 --reload
cd ../admin-web
npm run dev
```

自动化验证命令：

```powershell
mvn -pl apps/api -Dtest=DispatchFlowIntegrationTest,AuthRbacFlowIntegrationTest test
cd apps/admin-web
npm run e2e -- dispatch-flow.spec.ts
npm run e2e -- auth-rbac.spec.ts
```

后端集成测试覆盖真实业务闭环：录入需求、虚拟站点匹配、自动派发、车辆任务生成、站点执行和订单完成。前端 Playwright 流程覆盖管理端页面操作和接口连线，默认使用路由 mock 固定后端响应，便于在没有同时启动全栈服务时验证 UI 流程。

## 企业管理端认证

- 仅系统管理员可创建账号、修改角色、重置密码和启停账号；系统提供 `SYSTEM_ADMIN`、`DISPATCHER`、`OPERATOR`、`AUDITOR` 四类固定角色。
- 访问令牌只保存在管理端内存；刷新令牌通过 `HttpOnly` Cookie 轮换。生产环境必须启用 HTTPS 并设置 `DRT_AUTH_REFRESH_COOKIE_SECURE=true`。
- 初始管理员只会在用户库为空且三个 `DRT_AUTH_*` 变量均已设置时创建。示例变量是占位符，不应提交真实密码。
- OIDC/LDAP、组织级多租户和乘客/司机端认证不在本 MVP 范围内。

## MVP 就绪清单

- [MVP 就绪检查清单](docs/release/mvp-readiness-checklist.md)
