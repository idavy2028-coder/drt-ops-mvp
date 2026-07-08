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
   mvn -pl apps/api spring-boot:run
   ```
3. 然后启动算法服务：
   ```powershell
   cd apps/algorithm
   python -m uvicorn drt_algorithm.main:app --host 0.0.0.0 --port 8090 --reload
   ```
4. 最后启动前端：
   ```powershell
   cd apps/admin-web
   npm run dev
   ```

## 健康检查

- API: `GET http://localhost:8080/actuator/health`
- 算法: `GET http://localhost:8090/health`
- 前端: `GET http://localhost:5173/`
