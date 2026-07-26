# 通渭试点完整 Compose 编排设计

**日期：** 2026-07-26  
**对应任务：** P0-4「固化本机启动顺序」

## 1. 目标

提供一份 `infra/docker-compose.pilot.yml`，用单条 Compose 命令启动 PostgreSQL/PostGIS、Redis、算法服务、API 和管理前端。编排必须用健康检查控制依赖顺序，并在 Docker 或容器异常重启后自动恢复服务间连通。

本任务只完成 P0-4，不进入 P1，不处理生产部署、TLS、OIDC/LDAP、监控告警、备份恢复或真实路径服务。

## 2. 方案选择

采用完整容器化 Compose，而不是用 PowerShell 同时管理 Maven、Python 和 npm 宿主进程。

原因：

- 五个服务共享同一个 Compose 网络，服务地址稳定，不依赖临时容器 IP。
- `healthcheck`、条件依赖和 `restart: unless-stopped` 可以表达启动与恢复规则。
- 运行入口统一，减少本机 Maven、Python、Node 版本和启动顺序造成的差异。

保留现有 `infra/docker-compose.yml`，它继续承担仅启动 PostgreSQL 和 Redis 的开发用途；试点编排使用独立文件，避免改变既有工作流。

## 3. 文件边界

计划新增或修改以下文件：

- `infra/docker-compose.pilot.yml`：五个服务、网络、数据卷、健康检查、条件依赖和重启策略。
- `apps/api/Dockerfile`：多阶段构建 Spring Boot API，运行阶段只保留 JRE 和应用产物。
- `apps/algorithm/Dockerfile`：安装算法包并用 Uvicorn 监听 `0.0.0.0:8090`。
- `apps/admin-web/Dockerfile`：构建前端静态资源并由 Nginx 提供服务。
- `apps/admin-web/nginx.pilot.conf`：提供 SPA 回退、`/api` 反向代理和 Nginx 健康检查入口。
- `.dockerignore`：排除 Git、构建产物、依赖目录、测试缓存和本机工具，缩小构建上下文。
- `README.md`：记录试点编排的启动、检查、停止和重启恢复验证命令。

如果实施核查发现某个文件并非运行所必需，则不新增该文件。

## 4. 服务架构

### 4.1 PostgreSQL/PostGIS

- 使用现有 `postgis/postgis:16-3.5` 镜像和初始化脚本。
- 使用命名卷保存数据库数据。
- 用 `pg_isready` 检查指定数据库和用户是否可连接。
- 数据库名、用户名和本机试点密码提供非生产默认值，同时允许环境变量覆盖。

### 4.2 Redis

- 使用现有 `redis:7` 镜像。
- 使用命名卷保存 Redis 数据。
- 用 `redis-cli ping` 检查服务是否响应。
- Redis 暂不作为 API 的强依赖；它进入统一编排和健康状态，但不人为引入尚不存在的业务依赖。

### 4.3 算法服务

- 从 `apps/algorithm` 构建 Python 3.12 镜像。
- Uvicorn 监听容器端口 `8090`。
- 用应用现有 `/health` 端点检查真实服务状态。
- 仅依赖自身安装完成，不依赖数据库或 Redis。

### 4.4 API

- 从仓库根目录构建，以便 Maven 多模块工程可以解析根 `pom.xml` 和 `apps/api` 模块。
- 容器内数据库地址固定为 Compose 服务名 `postgres:5432`。
- 容器内算法地址固定为 Compose 服务名 `algorithm:8090`，通过现有配置入口注入；如当前代码缺少环境变量入口，只做最小配置补充。
- 启动前等待 PostgreSQL 和算法服务健康；Redis 不作为不存在的代码级依赖。
- 用 Spring Boot Actuator `/actuator/health` 检查应用和数据库的真实健康状态。
- JWT 密钥、初始管理员账号和密码由环境变量注入；仓库内只允许非生产本机默认值，README 明确禁止复用于生产。

### 4.5 管理前端

- 使用 Node 多阶段构建生成静态资源，运行阶段使用 Nginx。
- Nginx 将 `/api` 转发到 `http://api:8080`，浏览器继续使用同源路径，无需知道容器服务名。
- 未匹配静态文件的路由回退到 `index.html`，支持 Vue Router。
- 前端在 API 健康后启动；健康检查访问本容器 HTTP 首页。

## 5. 启动、依赖与恢复

统一依赖关系为：

1. PostgreSQL、Redis 和算法服务可并行启动。
2. API 等待 PostgreSQL和算法服务达到 `service_healthy`。
3. 前端等待 API 达到 `service_healthy`。

五个服务均使用 `restart: unless-stopped`。服务间只使用 Compose 服务名，不绑定容器 IP，因此单个容器重建后 DNS 会重新解析。健康检查包含启动宽限、有限间隔、超时和重试次数，避免正常冷启动被误判。

`depends_on` 只负责启动阶段排序，不等同于运行期级联重启。运行期恢复依靠各服务的 restart 策略、稳定服务名、客户端重连行为和健康状态；验证必须覆盖 API 或算法容器重启后的重新连通。

## 6. 端口与数据

保留当前本机访问习惯：

- PostgreSQL：`5432`
- Redis：`6379`
- API：`8080`
- 算法服务：`8090`
- 管理前端：`5173`

数据库和 Redis 使用命名卷，普通 `docker compose down` 不删除数据。文档不把 `down -v` 作为常规停止命令，避免误删试点数据。

## 7. 错误处理与可观测性

- 任一服务健康检查失败时，`docker compose ps` 必须能显示非健康状态。
- API 未健康时前端不会被 Compose 判定为就绪。
- 算法服务暂时不可用时，API 容器保持独立运行或按既有客户端行为恢复连接；不得通过固定 IP 手工重绑网络。
- 所有服务日志通过标准输出和标准错误输出，可用 `docker compose logs <service>` 查看。
- 不在 Compose 文件或镜像中写入生产密钥、真实账号或生产数据库密码。

## 8. 验证策略

### 8.1 静态验证

- `docker compose -f infra/docker-compose.pilot.yml config` 成功展开配置。
- 展开结果包含五个服务、五个健康检查、五个 `restart: unless-stopped` 和预期条件依赖。
- `git diff --check` 不报告空白错误。

### 8.2 构建与启动验证

- 构建五服务所需镜像。
- 后台启动后，轮询直到五个服务均为 running/healthy。
- 分别访问前端首页、API `/actuator/health` 和算法 `/health`。
- 从 API 容器网络验证算法健康端点可访问。

### 8.3 重启恢复验证

- 重启算法服务，确认它恢复健康，API 仍能通过服务名访问算法。
- 重启 API，确认它恢复健康，前端反向代理仍能访问 API。
- 验证期间不删除 PostgreSQL 和 Redis 数据卷。

若本机 Docker 引擎、镜像仓库或端口占用导致运行验证无法完成，必须记录实际失败命令和原因，不得用静态验证代替运行成功结论。

## 9. 完成标准

满足以下条件才可认定 P0-4 完成：

- `infra/docker-compose.pilot.yml` 覆盖 PostgreSQL、Redis、API、算法服务和前端。
- 五个服务均配置健康检查和 `restart: unless-stopped`。
- API 和前端使用 `service_healthy` 建立明确启动顺序。
- 容器内数据库、算法和 API 通信只使用 Compose 服务名。
- 完成静态配置、镜像构建、五服务健康和关键重启恢复验证，或如实记录外部环境阻塞。
- README 提供单一启动入口、状态检查、日志、停止和恢复验证说明。
- 不执行 P1 或后续任务。
