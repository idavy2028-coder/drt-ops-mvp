# 通渭试点完整 Compose 编排实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供一条 Compose 命令启动 PostgreSQL/PostGIS、Redis、算法服务、API 和管理前端，并验证健康依赖与重启恢复连通。

**Architecture:** 保留现有基础设施 Compose，新增独立试点编排。API 和算法分别构建运行镜像，前端构建为由 Nginx 托管的静态站点；所有容器通过 Compose 服务名通信，以健康检查、条件依赖和 `restart: unless-stopped` 固化启动及恢复行为。

**Tech Stack:** Docker Compose、PostGIS 16-3.5、Redis 7、Java 21、Spring Boot 3.5.3、Python 3.12、FastAPI/Uvicorn、Node.js、Vue/Vite、Nginx

## Global Constraints

- 唯一执行依据是 `docs/release/tongwei-pilot-next-phase-plan.md`，本计划只执行 P0-4，不进入 P1。
- 保留 `infra/docker-compose.yml`，不得改变现有仅启动 PostgreSQL 和 Redis 的开发方式。
- 五个服务均配置真实健康检查和 `restart: unless-stopped`。
- 容器间使用 Compose 服务名，不使用固定容器 IP。
- 不提交生产密钥、真实账号或生产数据库密码。
- 普通停止流程不得删除 PostgreSQL 或 Redis 命名卷。
- 不扩展到生产部署、TLS、OIDC/LDAP、监控告警、备份恢复或真实路径服务。

---

### Task 1: 算法服务容器镜像

**Files:**
- Create: `apps/algorithm/Dockerfile`

**Interfaces:**
- Consumes: `apps/algorithm/pyproject.toml` 和 `drt_algorithm.main:app`
- Produces: 监听 `0.0.0.0:8090`、提供 `GET /health` 的算法镜像

- [ ] **Step 1: 验证镜像构建在 Dockerfile 缺失时失败**

Run:

```powershell
docker build -f apps/algorithm/Dockerfile apps/algorithm
```

Expected: FAIL，提示无法找到 `apps/algorithm/Dockerfile`。

- [ ] **Step 2: 编写最小算法 Dockerfile**

```dockerfile
FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir .

EXPOSE 8090
CMD ["python", "-m", "uvicorn", "drt_algorithm.main:app", "--host", "0.0.0.0", "--port", "8090"]
```

- [ ] **Step 3: 构建算法镜像**

Run:

```powershell
docker build -t drt-ops-algorithm:pilot -f apps/algorithm/Dockerfile apps/algorithm
```

Expected: PASS，退出码为 0。

---

### Task 2: API 容器镜像

**Files:**
- Create: `apps/api/Dockerfile`

**Interfaces:**
- Consumes: 根 `pom.xml`、`apps/api/pom.xml` 和 `apps/api/src`
- Produces: 监听 `8080`、提供 `GET /actuator/health` 的 Java 21 API 镜像

- [ ] **Step 1: 验证镜像构建在 Dockerfile 缺失时失败**

Run:

```powershell
docker build -f apps/api/Dockerfile .
```

Expected: FAIL，提示无法找到 `apps/api/Dockerfile`。

- [ ] **Step 2: 编写多阶段 API Dockerfile**

```dockerfile
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps/api/pom.xml apps/api/pom.xml
RUN mvn -q -pl apps/api -am dependency:go-offline
COPY apps/api/src apps/api/src
RUN mvn -q -pl apps/api -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/apps/api/target/drt-ops-api-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 3: 构建 API 镜像**

Run:

```powershell
docker build -t drt-ops-api:pilot -f apps/api/Dockerfile .
```

Expected: PASS，退出码为 0。

---

### Task 3: 前端静态镜像与 API 代理

**Files:**
- Create: `apps/admin-web/Dockerfile`
- Create: `apps/admin-web/nginx.pilot.conf`

**Interfaces:**
- Consumes: `apps/admin-web/package.json`、锁文件和 Vue 源码
- Produces: 监听 `80` 的静态站点；`/api/` 转发至 `http://api:8080`

- [ ] **Step 1: 验证前端镜像构建在 Dockerfile 缺失时失败**

Run:

```powershell
docker build -f apps/admin-web/Dockerfile apps/admin-web
```

Expected: FAIL，提示无法找到 `apps/admin-web/Dockerfile`。

- [ ] **Step 2: 编写 Nginx 配置**

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;

    location = /health {
        access_log off;
        add_header Content-Type text/plain;
        return 200 "ok\n";
    }

    location /api/ {
        proxy_pass http://api:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

- [ ] **Step 3: 编写前端多阶段 Dockerfile**

```dockerfile
FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.pilot.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```

- [ ] **Step 4: 构建前端镜像**

Run:

```powershell
docker build -t drt-ops-admin-web:pilot -f apps/admin-web/Dockerfile apps/admin-web
```

Expected: PASS，退出码为 0。

---

### Task 4: 五服务 Compose 编排

**Files:**
- Create: `infra/docker-compose.pilot.yml`
- Create: `.dockerignore`

**Interfaces:**
- Consumes: Task 1 至 Task 3 的三个 Dockerfile和 `infra/postgres/init-postgis.sql`
- Produces: `postgres`、`redis`、`algorithm`、`api`、`admin-web` 五个服务及 `postgres-data`、`redis-data` 命名卷

- [ ] **Step 1: 验证试点 Compose 尚不可解析**

Run:

```powershell
docker compose -f infra/docker-compose.pilot.yml config
```

Expected: FAIL，提示找不到 `infra/docker-compose.pilot.yml`。

- [ ] **Step 2: 编写构建上下文忽略规则**

```gitignore
.git
.gitignore
.tools
.superpowers
**/node_modules
**/dist
**/target
**/.venv
**/__pycache__
**/.pytest_cache
**/.vite
```

- [ ] **Step 3: 编写试点 Compose**

Compose 必须实现以下精确约束：

```yaml
name: drt-ops-pilot
services:
  postgres:
    image: postgis/postgis:16-3.5
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]

  redis:
    image: redis:7
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]

  algorithm:
    build:
      context: ../apps/algorithm
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8090/health')"]

  api:
    build:
      context: ..
      dockerfile: apps/api/Dockerfile
    restart: unless-stopped
    environment:
      DRT_OPS_DATASOURCE_URL: jdbc:postgresql://postgres:5432/drt_ops
      DISPATCH_ALGORITHM_BASE_URL: http://algorithm:8090
    depends_on:
      postgres:
        condition: service_healthy
      algorithm:
        condition: service_healthy

  admin-web:
    build:
      context: ../apps/admin-web
    restart: unless-stopped
    depends_on:
      api:
        condition: service_healthy
```

各服务发布设计文档约定的宿主机端口；PostgreSQL 挂载 `./postgres/init-postgis.sql` 和 `postgres-data`，Redis 挂载 `redis-data`。所有健康检查统一使用 `interval: 10s`、`timeout: 5s`、`retries: 12` 和 `start_period: 20s`。API 健康检查使用 `curl --fail --silent http://localhost:8080/actuator/health`；前端健康检查使用 `wget -qO- http://127.0.0.1/health`，避免 Alpine 将 `localhost` 优先解析为 Nginx 未监听的 IPv6 回环地址。JWT 密钥和初始管理员凭据使用可被同名环境变量覆盖的本机试点默认值，并在 README 标注不得用于生产。

- [ ] **Step 4: 展开并核对 Compose**

Run:

```powershell
docker compose -f infra/docker-compose.pilot.yml config
```

Expected: PASS，输出包含五个服务、五个 healthcheck、五个 `restart: unless-stopped`，API 依赖健康的 PostgreSQL 和算法服务，前端依赖健康的 API。

---

### Task 5: 使用文档与完整运行验收

**Files:**
- Modify: `README.md`
- Modify: `docs/release/mvp-readiness-checklist.md`

**Interfaces:**
- Consumes: `infra/docker-compose.pilot.yml`
- Produces: P0-4 单一启动入口、状态/日志/停止命令和验收证据

- [ ] **Step 1: 在 README 记录操作入口**

添加以下命令及用途说明：

```powershell
docker compose -f infra/docker-compose.pilot.yml up -d --build
docker compose -f infra/docker-compose.pilot.yml ps
docker compose -f infra/docker-compose.pilot.yml logs -f api algorithm admin-web
docker compose -f infra/docker-compose.pilot.yml restart algorithm
docker compose -f infra/docker-compose.pilot.yml restart api
docker compose -f infra/docker-compose.pilot.yml down
```

明确本机默认凭据只供隔离试点使用，正式环境必须通过环境变量覆盖；常规停止不使用 `down -v`。

- [ ] **Step 2: 启动五服务**

Run:

```powershell
docker compose -f infra/docker-compose.pilot.yml up -d --build
```

Expected: PASS，最终五个服务均为 running/healthy。

- [ ] **Step 3: 验证对外和容器内连通**

Run:

```powershell
Invoke-RestMethod http://localhost:8090/health
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-WebRequest http://localhost:5173/health -UseBasicParsing
docker compose -f infra/docker-compose.pilot.yml exec -T api curl --fail --silent http://algorithm:8090/health
```

Expected: 三个宿主机请求成功，API 容器到算法服务的请求返回健康响应。

- [ ] **Step 4: 验证算法和 API 重启恢复**

Run:

```powershell
docker compose -f infra/docker-compose.pilot.yml restart algorithm
docker compose -f infra/docker-compose.pilot.yml restart api
docker compose -f infra/docker-compose.pilot.yml ps
```

Expected: 等待健康检查后五个服务重新达到 running/healthy，前端、API 和算法健康端点仍可访问。

- [ ] **Step 5: 更新 P0-4 验收记录**

在 `docs/release/mvp-readiness-checklist.md` 记录执行日期、Compose 文件、构建/健康/重启验证结果、证据命令、发现的问题和剩余风险；不得声称完成 P1。

- [ ] **Step 6: 执行最终静态验证**

Run:

```powershell
docker compose -f infra/docker-compose.pilot.yml config --quiet
git diff --check
git status --short
```

Expected: Compose 配置和空白检查退出码为 0；状态只包含 P0-4 计划内文件。
