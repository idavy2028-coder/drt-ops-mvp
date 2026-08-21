# JT Gateway 试点运维手册

## 本轮边界

本轮只交付部署基线，不启动真实环境、不开放真实 7611、不录入终端，也不执行真机互通验收。所有示例都使用脱敏别名；日志和工单不得粘贴完整终端号、原始报文、Bearer 明文或媒体上传目标。

**本轮没有执行 `docker compose build`，也没有启动 Docker daemon。** Maven package 与 jar 内容审查不能替代镜像 build、容器只读文件系统或容器健康验证；这些命令只能在批准的真实部署窗口执行。

## 配置准备

1. 将 `.env.example` 复制为本机 `.env`，逐项替换 `REPLACE_WITH_...` 占位符。PostgreSQL、JWT、管理员账号/密码、地图 key、gateway 凭证和 `JT_GATEWAY_DATA_VOLUME` 均为必填。
2. 生成一段足够长的随机服务凭证，只把明文写入 `JT_GATEWAY_SERVICE_CREDENTIAL_PLAINTEXT`。
3. 计算该明文的 SHA-256 小写十六进制摘要，只把摘要写入 `JT_GATEWAY_SERVICE_CREDENTIAL_SHA256`。
4. `JT_GATEWAY_SERVICE_CREDENTIAL_VERSION` 使用正整数；`JT_GATEWAY_INSTANCE` 使用脱敏实例别名。
5. 核对 `JT_GATEWAY_OPERATIONS_API_BASE_URL` 指向受控运营 API。缺少地址、凭证明文、版本或实例标识时，启用 TCP 的网关会拒绝启动。

API 只接收凭证版本和摘要；明文只进入网关容器。H2 文件保存在独立 `jt-gateway-data` 卷，API 容器没有该卷的挂载权限。

## 启动与停止

启动前先做离线配置展开并人工复核：

```powershell
docker compose --env-file .env -f infra/docker-compose.pilot.yml config
```

确认无误后，真实部署窗口才可执行：

```powershell
docker compose --env-file .env -f infra/docker-compose.pilot.yml build --pull jt-gateway
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d postgres redis algorithm route-simulator api jt-gateway
docker compose --env-file .env -f infra/docker-compose.pilot.yml stop jt-gateway
docker compose --env-file .env -f infra/docker-compose.pilot.yml rm -f jt-gateway
```

`rm -f jt-gateway` 只移除容器，不得附加 `-v`，也不得执行 `docker compose down -v`；删除 `JT_GATEWAY_DATA_VOLUME` 指向的卷会丢失尚未投递的 outbox。本文不授权在本轮执行上述 build、启动、停止或删除命令。

## 日志与健康

查看最近日志：

```powershell
docker compose --env-file .env -f infra/docker-compose.pilot.yml logs --tail 200 jt-gateway
```

健康端点只在容器网络的 7612 提供。必须从容器内分别验证 liveness 与 readiness：

```powershell
docker compose --env-file .env -f infra/docker-compose.pilot.yml exec -T jt-gateway curl --fail --silent http://127.0.0.1:7612/actuator/health/liveness
docker compose --env-file .env -f infra/docker-compose.pilot.yml exec -T jt-gateway curl --fail --silent http://127.0.0.1:7612/actuator/health/readiness
```

liveness 验证真实 Netty listener channel；readiness 还要求 outbox 可写、运营 API 已有成功事实、无 dead-letter 且最老未解决记录未超阈值。`jtGateway` 详情至少包含：

- `tcpListening`：启用后设备监听是否存活；
- `bufferWritable`：H2 outbox 是否可写；
- `operationsApiReachable`：最近一次投递请求是否获得运营 API 的 2xx 响应；尚未尝试时为 `UNKNOWN`；
- `lastDeliverySuccessful`：最近一次 2xx 响应是否逐项覆盖整批并全部为 `ACCEPTED`/`REPLAYED`；
- `outboxPending`、`outboxDelivering`、`outboxDeadLetter`：各状态数量；
- `oldestUnresolvedAgeSeconds`：最老 PENDING/DELIVERING/DEAD_LETTER 记录年龄。

媒体服务不参与网关健康判定，媒体不可用不能把普通位置接入判为 DOWN。健康详情不包含凭证、完整终端身份、原始报文或媒体目标。

## 7611 连通性与 API 连接

宿主机只映射 7611；7612 不映射到宿主。部署窗口内可从授权测试机执行：

```powershell
Test-NetConnection -ComputerName <网关受控域名> -Port 7611
docker compose --env-file .env -f infra/docker-compose.pilot.yml exec -T jt-gateway curl --fail --silent http://api:8080/actuator/health
```

端口可达只证明 TCP 监听，不证明终端已注册。注册失败时依次核对：运营 API 健康、网关到 API 的 DNS/TLS、服务凭证版本与摘要是否匹配、终端是否已预置并绑定。不得在日志中打印请求 Authorization 头。

## 缓冲积压与恢复

`outboxPending` 持续增长时：

1. 保持 `jt-gateway-data` 卷不变，不手工编辑 H2 文件。
2. 检查 `operationsApiReachable` 与运营 API 健康；先恢复网络、DNS、TLS 或凭证配置。
3. 恢复后观察 pending 是否下降；dispatcher 会按退避策略重试，高优先级告警、协议审计和附件元数据先于普通位置投递。
4. 若出现 dead letter，先保留卷快照和脱敏健康证据，再由开发人员评估；不要直接删除记录。
5. 容器异常退出后使用同一数据卷重建，启动时会把中断的 `DELIVERING` 记录恢复为可重试状态。

### 命名卷备份

备份必须在停止 gateway 后执行，备份文件放入已被 Git 忽略的 `.private/backups/`：

```powershell
docker compose --env-file .env -f infra/docker-compose.pilot.yml stop jt-gateway
New-Item -ItemType Directory -Force .private/backups | Out-Null
$gatewayDataVolume = "REPLACE_WITH_CURRENT_JT_GATEWAY_DATA_VOLUME"
docker run --rm -v "${gatewayDataVolume}:/source:ro" -v "${PWD}/.private/backups:/backup" alpine:3.20 sh -c "tar -C /source -czf /backup/jt-gateway-h2-backup.tgz ."
```

### 恢复到新卷并保留回退

不得覆盖原卷。创建新卷、恢复备份，修改 `.env` 中的 `JT_GATEWAY_DATA_VOLUME` 后再重建 gateway：

```powershell
$restoreVolume = "REPLACE_WITH_NEW_RESTORE_VOLUME"
docker volume create $restoreVolume
docker run --rm -v "${restoreVolume}:/target" -v "${PWD}/.private/backups:/backup:ro" alpine:3.20 sh -c "tar -C /target -xzf /backup/jt-gateway-h2-backup.tgz"
# 将 .env 的 JT_GATEWAY_DATA_VOLUME 改为 $restoreVolume 后执行：
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d --no-deps --force-recreate jt-gateway
```

若 readiness 不恢复，立即停止 gateway，把 `.env` 的卷名改回备份前旧卷，再执行同一 `up -d --no-deps --force-recreate` 命令回退。

### Dead-letter 处置边界

当前版本没有受支持的人工 redrive API 或运维命令。`outboxDeadLetter>0` 时 readiness 必须失败：停止真实终端接入扩容，保留命名卷备份、健康 JSON、错误码和时间窗，升级给开发负责人决定补丁或受控迁移。禁止直接打开 H2、执行 DELETE/UPDATE、复制单表或手工把状态改回 PENDING；也不得用删除卷“清除告警”。

## 凭证轮换

1. 生成新明文和摘要，将版本号递增。
2. 在 API 侧把旧的 current 复制到 previous，再发布新的 current 版本和摘要。
3. 更新网关的 current 明文与版本，重建网关容器。
4. 验证健康和一笔脱敏测试投递后，观察一个完整轮换窗口。
5. 确认没有旧版本请求后，清空 API 的 previous 版本和摘要。

任何阶段都不得把明文同步到 API，也不得把摘要当作可登录凭证复用。若轮换导致 401，回退网关到仍在 API previous 窗口内的旧版本，恢复投递后再重新执行轮换。

执行前把旧 `.env` 复制到 `.private/`，API 先重建，确认健康后再重建 gateway：

```powershell
Copy-Item .env .private/jt-gateway-before-credential-rotation.env
# 编辑 .env：API previous=旧版本/旧摘要，current=新版本/新摘要；gateway plaintext=新明文。
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d --no-deps --force-recreate api
docker compose --env-file .env -f infra/docker-compose.pilot.yml exec -T jt-gateway curl --fail --silent http://api:8080/actuator/health
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d --no-deps --force-recreate jt-gateway
docker compose --env-file .env -f infra/docker-compose.pilot.yml exec -T jt-gateway curl --fail --silent http://127.0.0.1:7612/actuator/health/readiness
```

若出现 401 或 readiness DOWN，按保存的文件回退，并仍按 API→gateway 顺序重建：

```powershell
Copy-Item .private/jt-gateway-before-credential-rotation.env .env -Force
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d --no-deps --force-recreate api
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d --no-deps --force-recreate jt-gateway
```

## 附件元数据的剩余审批边界

当前网关能够解码、脱敏、持久化并投递 `ATTACHMENT_METADATA`，但现有运营 API 尚未批准附件类型、通道、媒体格式和 0x1206 的落库合同。网关因此要求 API 的 `ApiResponse.data` 用 idempotencyKey 对整批逐项确认；API 返回 `REJECTED`、遗漏、重复或未知状态时，整批保持重试并使 `lastDeliverySuccessful=false`。这避免 2xx 静默丢失，但不等于附件全链路验收通过。

在真实附件验收前，必须另行审批并实现 API metadata contract；本轮不得通过手工清空 pending、降低确认规则或伪造 ACCEPTED 响应绕过该边界。
