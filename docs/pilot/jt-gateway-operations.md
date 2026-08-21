# JT Gateway 试点运维手册

## 本轮边界

本轮只交付部署基线，不启动真实环境、不开放真实 7611、不录入终端，也不执行真机互通验收。所有示例都使用脱敏别名；日志和工单不得粘贴完整终端号、原始报文、Bearer 明文或媒体上传目标。

## 配置准备

1. 将 `.env.example` 复制为本机 `.env`，逐项替换 `REPLACE_WITH_...` 占位符。
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
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d jt-gateway
docker compose --env-file .env -f infra/docker-compose.pilot.yml stop jt-gateway
```

需要移除容器时使用 `docker compose ... rm jt-gateway`，不要删除 `jt-gateway-data` 卷；删除卷会丢失尚未投递的 outbox。本文不授权在本轮执行上述启动、停止或删除命令。

## 日志与健康

查看最近日志：

```powershell
docker compose --env-file .env -f infra/docker-compose.pilot.yml logs --tail 200 jt-gateway
```

健康端点只在容器网络的 7612 提供：`http://jt-gateway:7612/actuator/health`。`jtGateway` 组件至少包含：

- `tcpListening`：启用后设备监听是否存活；
- `bufferWritable`：H2 outbox 是否可写；
- `operationsApiReachable`：最近一次投递请求是否获得运营 API 的 2xx 响应；尚未尝试时为 `UNKNOWN`；
- `lastDeliverySuccessful`：最近一次 2xx 响应是否逐项覆盖整批并全部为 `ACCEPTED`/`REPLAYED`；
- `outboxPending`：当前待投递数量。

媒体服务不参与网关健康判定，媒体不可用不能把普通位置接入判为 DOWN。健康详情不包含凭证、完整终端身份、原始报文或媒体目标。

## 7611 连通性与 API 连接

宿主机只映射 7611；7612 不映射到宿主。部署窗口内可从授权测试机执行：

```powershell
Test-NetConnection -ComputerName <网关受控域名> -Port 7611
```

端口可达只证明 TCP 监听，不证明终端已注册。注册失败时依次核对：运营 API 健康、网关到 API 的 DNS/TLS、服务凭证版本与摘要是否匹配、终端是否已预置并绑定。不得在日志中打印请求 Authorization 头。

## 缓冲积压与恢复

`outboxPending` 持续增长时：

1. 保持 `jt-gateway-data` 卷不变，不手工编辑 H2 文件。
2. 检查 `operationsApiReachable` 与运营 API 健康；先恢复网络、DNS、TLS 或凭证配置。
3. 恢复后观察 pending 是否下降；dispatcher 会按退避策略重试，高优先级告警、协议审计和附件元数据先于普通位置投递。
4. 若出现 dead letter，先保留卷快照和脱敏健康证据，再由开发人员评估；不要直接删除记录。
5. 容器异常退出后使用同一数据卷重建，启动时会把中断的 `DELIVERING` 记录恢复为可重试状态。

## 凭证轮换

1. 生成新明文和摘要，将版本号递增。
2. 在 API 侧把旧的 current 复制到 previous，再发布新的 current 版本和摘要。
3. 更新网关的 current 明文与版本，重建网关容器。
4. 验证健康和一笔脱敏测试投递后，观察一个完整轮换窗口。
5. 确认没有旧版本请求后，清空 API 的 previous 版本和摘要。

任何阶段都不得把明文同步到 API，也不得把摘要当作可登录凭证复用。若轮换导致 401，回退网关到仍在 API previous 窗口内的旧版本，恢复投递后再重新执行轮换。

## 附件元数据的剩余审批边界

当前网关能够解码、脱敏、持久化并投递 `ATTACHMENT_METADATA`，但现有运营 API 尚未批准附件类型、通道、媒体格式和 0x1206 的落库合同。网关因此要求 API 的 `ApiResponse.data` 用 idempotencyKey 对整批逐项确认；API 返回 `REJECTED`、遗漏、重复或未知状态时，整批保持重试并使 `lastDeliverySuccessful=false`。这避免 2xx 静默丢失，但不等于附件全链路验收通过。

在真实附件验收前，必须另行审批并实现 API metadata contract；本轮不得通过手工清空 pending、降低确认规则或伪造 ACCEPTED 响应绕过该边界。
