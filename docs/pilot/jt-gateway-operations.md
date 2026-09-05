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

### 型号级鉴权码兼容

`JT_GATEWAY_REGISTRATION_AUTHENTICATION_COMPATIBILITY_MODELS` 默认必须留空。默认注册鉴权码继续使用 32 字节随机熵，经无填充 Base64URL 编码为 43 个字符。只有完成真实设备证据复核的型号，才允许在私密环境文件中以逗号分隔配置；匹配大小写敏感，命中后使用 16 字节随机熵，编码为 22 个字符，仍保留 128 位随机强度。

不得把真实型号清单、鉴权码、鉴权码摘要写入基础 Compose、公开报告、日志、健康详情或 Outbox。变更该列表后必须轮换目标终端认证状态并重新执行注册、鉴权真实验收；未命中型号的令牌长度必须保持 43 个字符。

API 只接收凭证版本和摘要；明文只进入网关容器。H2 文件保存在独立 `jt-gateway-data` 卷，API 容器没有该卷的挂载权限。

## 临时注册维护白名单

真实终端无法断网、但只允许单台进入详细注册诊断时，可以启用临时注册维护白名单。该模式只保证非目标终端不会改变云端终端状态、鉴权令牌、车辆绑定、位置或报警；非目标终端会收到 `0x8100` 失败响应、断开并按设备策略继续重试，因此不等于维持成功的平台会话。

维护模式默认关闭。启用时必须通过 Git 忽略的私密环境文件同时提供：

```text
JT_GATEWAY_REGISTRATION_MAINTENANCE_ENABLED=true
JT_GATEWAY_REGISTRATION_MAINTENANCE_ALLOWED_IDENTITY_SHA256=<私密摘要>
JT_GATEWAY_REGISTRATION_MAINTENANCE_EXPIRES_AT=<UTC ISO-8601 时间>
JT_GATEWAY_REGISTRATION_MAINTENANCE_AUDIT_INTERVAL_SECONDS=60
```

摘要规范固定为 `SHA-256(protocolVersion + NUL + terminalIdentity)`。只能使用 `.private/cloud-deployment/.../cloud-registration-maintenance-lib.ps1` 从私密资料在本机生成；终端身份和摘要都不得写入控制台、公开报告、基础 Compose 文件或 Git。`Write-CloudRegistrationMaintenanceArtifacts` 会生成 `.env.registration-maintenance` 和只引用环境变量的 `docker-compose.registration-maintenance.yml`；上传服务器后两者必须为 mode `600`。

网关在收到 `0x0100` 并读出 JT808 消息头身份后、解析注册体和调用 API 前执行常量时间摘要比较：

- 目标终端进入完整字段解析、运营 API verify 和 complete 流程。
- 非目标终端不调用注册 API，返回失败并以 `REGISTERED/REJECTED + TEMPORARILY_BLOCKED_FOR_MAINTENANCE` 记录首次及每 60 秒一次的持久审计；其余重试只增加内存聚合计数。
- 维护窗口在启动时已过期、摘要非法或配置缺失时，gateway 拒绝启动；运行中到期后拒绝全部新注册并使 readiness 降级，绝不自动放开全部终端。

健康详情只公开 `registrationMaintenanceEnabled`、`registrationMaintenanceExpired`、允许/拦截尝试数、拦截身份数和审计抑制数，不公开身份或摘要。详细注册失败只记录固定字段空值码、既有身份不匹配码，或 `REGISTRATION_VERIFY_*` / `REGISTRATION_COMPLETE_*` 阶段分类；禁止记录 API 响应正文和异常消息。

启停必须遵循以下顺序：

1. 保持 gateway 停止，生成并校验私密环境文件与维护 override。
2. 使用基础 Compose、已部署版本 override 和维护 override 执行 `config --quiet`，不得执行会展开秘密变量的普通 `config`。
3. 先启动观察器，再启动 gateway；诊断窗口最长 10 分钟，目标终端出现首个明确结果后停止。
4. 停止 gateway 并确认 7611 无监听后，才允许移除维护 override。其他终端仍在线时，禁止在运行中关闭维护模式。

本模式不修改附件边界；`0x9208`、`0x1210`、`0x1206` 仍不纳入 P6-2 基础链路验收。

## API 交付合同

### 逐项 ingress 结果

`POST /internal/jt-gateway/ingress` 只接受 1 至 50 条的 JSON 数组。每个可关联输入都必须按原顺序返回一条结果，`idempotencyKey` 与输入 UUID 完全相同，`status` 只能是 `ACCEPTED`、`REPLAYED` 或 `REJECTED`，`reasonCodes` 是稳定有序的数组。缺失 key、重复 key 或 null envelope 无法形成无歧义关联，API 会在写入任何一项前返回 400；有 key 的坏 schema、时间、payload 或业务事实只拒绝自身，不遮蔽同批邻项。

该单一路由在服务凭证校验后、MVC 将请求物化为 `JsonNode` 前执行有界读取和 streaming JSON 检查。默认请求体上限为 1 MiB、JSON 最大嵌套深度为 32、单个字段名或字符串值最大长度为 262144；分别可由 `JT_GATEWAY_INGRESS_MAX_REQUEST_BYTES`、`JT_GATEWAY_INGRESS_MAX_JSON_NESTING_DEPTH`、`JT_GATEWAY_INGRESS_MAX_JSON_STRING_LENGTH` 配置。带 `Content-Length` 和 chunked/未知长度请求都受同一字节上限约束，超过任一资源上限返回 413，且不创建 ingress receipt、audit、位置、告警事实或 outbox。正常 50 项批次不受影响；这些限制不应用到 `/internal/jt-gateway` 的其他 API。未经容量和安全复核不得在部署时调高。

- `LOCATION` 使用既有 GPS 质量和幂等语义；历史 `POSITION` 别名仍兼容，但 gateway 生产投递使用 `LOCATION`。
- `ALARM` 新 START/END 事实及其 outbox 同事务返回 `ACCEPTED`；只有已接受的 ingress receipt 或既有业务事实重放返回 `REPLAYED`；绑定、位置依赖、字段或状态非法及其同 key 重投都返回稳定 `REJECTED`。每个 START/END 在任何新建、结束、去重或历史重放分支前，都必须同时找到已完成且 `ACCEPTED` 的 `LOCATION/POSITION` receipt，以及同 key、同 terminal、同 vehicle 的 GPS event；已拒绝位置、人工位置、其他车辆、其他 kind、仅有 receipt 或仅有 event 均不可复用。END 早于对应 open 或最新历史 START 时固定返回 `ALARM_STATE_INVALID`，只有时间合法的重复 END 才返回 `REPLAYED`。
- `PROTOCOL_AUDIT` 首次持久化返回 `ACCEPTED`，同 receipt 重投返回 `REPLAYED`，非法输入返回 `REJECTED`。
- `ATTACHMENT_METADATA` 的运营映射仍未授权；首次和每次重投都固定返回 `REJECTED` 与 `ATTACHMENT_METADATA_CONTRACT_UNAVAILABLE`，不写 GPS、告警附件或媒体业务表。

gateway 对返回结果执行严格全批校验：数量、每个索引的 key 与输入顺序、key 集合、重复 key 和状态任一不符，或任一项为 `REJECTED`，本次领取的整批都不会标记 delivered，而是按既有退避和 dead-letter 策略保留。HTTP 2xx 本身不代表投递成功。排障时只记录稳定错误码、计数和时间窗，不记录 payload、完整终端身份或远端媒体信息。

### session audit 幂等

gateway 把 durable outbox 的随机 UUID `idempotencyKey` 同时写入 `POST /internal/jt-gateway/audit-events` 请求；API 响应必须回显相同 key，并返回 `ACCEPTED` 或 `REPLAYED`。升级前已存在、payload 尚无该字段的 SESSION_AUDIT 记录由 delivery client 在发送时以 outbox UUID 补齐，不能另生成 key。API 数据库 V17 为 `jt_gateway_audit_events.idempotency_key` 回填历史行自身 `id`，随后施加 `NOT NULL` 和唯一约束；同时为 ingress receipt 增加可空的 kind、terminal 和 vehicle 归属，用于拒绝跨类型或跨车辆的告警位置依赖。API 已落库但响应丢失时，gateway 使用原 outbox UUID 重投，API 返回 `REPLAYED`，数据库仍只有一行；并发唯一冲突也归一为 `REPLAYED`，不得当成 HTTP 500。

部署 API 前必须先完成 Flyway V17。当前隔离测试已覆盖 H2 机械兼容检查以及真实 Spring API/Netty/outbox 的响应丢失重试；本轮因没有可用的临时 PostgreSQL/Docker，真实 PostgreSQL 的 V0→V17 与 V16→V17 迁移仍是上线门禁，不能用 H2 结果替代。部署窗口应在明确批准的空白临时库和 V16 快照库分别执行迁移、核对回填与唯一约束，再允许生产数据库升级。

## PostgreSQL 新部署与既有卷密码迁移

### 新部署

新部署必须使用一个从未初始化过的 `postgres-data` 卷，并在首次 `up` 前把 `.env` 中的数据库名、用户和密码全部替换为新值。官方 PostgreSQL 镜像的 `POSTGRES_PASSWORD` **只在数据目录首次初始化时生效**；已有卷再次启动时，即使 `.env` 改了密码，也不会修改数据库角色密码。

新卷首次启动可在批准的部署窗口执行：

```powershell
$compose = @('--env-file', '.env', '-f', 'infra/docker-compose.pilot.yml')
docker compose @compose config --quiet
docker compose @compose up -d --wait postgres
```

若 `postgres-data` 已存在或不确定是否用过，必须按下一节升级，不能把它当成新部署，也不能通过删除卷让新密码“生效”。

### 已有 `postgres-data` 卷升级

以下命令必须在同一个受控 PowerShell 会话执行。第一步先备份 `.env` 和全库；SQL 备份含业务数据和角色哈希，只能保存在 Git 已忽略的 `.private/backups/`，不得上传工单或流水线日志。

```powershell
$compose = @('--env-file', '.env', '-f', 'infra/docker-compose.pilot.yml')
$rotationStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDirectory = Join-Path $PWD '.private/backups'
$envBackup = Join-Path $backupDirectory "postgres-password-$rotationStamp.env"
$databaseBackup = Join-Path $backupDirectory "postgres-before-password-$rotationStamp.sql"
New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
Copy-Item -LiteralPath '.env' -Destination $envBackup

# postgres-backup-contract-begin
function Invoke-CheckedPostgresDump {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $Destination,
        [Parameter(Mandatory)] [string] $ContainerPath,
        [Parameter(Mandatory)] [string[]] $ComposeArguments
    )

    Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
    $completed = $false
    $primaryFailure = $null
    try {
        docker compose @ComposeArguments exec -T postgres sh -ceu `
            'umask 077; pg_dumpall --username "$POSTGRES_USER" > "$1"' `
            sh $ContainerPath
        $execExitCode = $LASTEXITCODE
        if ($null -eq $execExitCode -or $execExitCode -ne 0) {
            throw "container pg_dumpall failed with exit code $execExitCode"
        }

        docker compose @ComposeArguments cp "postgres:$ContainerPath" $Destination
        $copyExitCode = $LASTEXITCODE
        if ($null -eq $copyExitCode -or $copyExitCode -ne 0) {
            throw "docker compose cp failed with exit code $copyExitCode"
        }
        if (!(Test-Path -LiteralPath $Destination) -or
                (Get-Item -LiteralPath $Destination).Length -le 0) {
            throw 'copied PostgreSQL backup is empty'
        }

        $hasHeader = $false
        $hasRole = $false
        [string[]] $tailLines = New-Object string[] 32
        $lineNumber = 0
        $reader = [System.IO.File]::OpenText($Destination)
        try {
            while (($line = $reader.ReadLine()) -ne $null) {
                $lineNumber++
                if ($lineNumber -le 32 -and
                        $line -eq '-- PostgreSQL database cluster dump') {
                    $hasHeader = $true
                }
                if (!$hasRole -and $line -match '^CREATE ROLE\s+.+;$') {
                    $hasRole = $true
                }
                $tailLines[($lineNumber - 1) % $tailLines.Length] = $line
            }
        } finally {
            $reader.Dispose()
        }
        $hasCompletion = $tailLines -contains
            '-- PostgreSQL database cluster dump complete'
        if (!$hasHeader -or !$hasRole -or !$hasCompletion) {
            throw 'pg_dumpall output is incomplete or not a cluster dump'
        }

        $completed = $true
    } catch {
        $primaryFailure = $_
    } finally {
        $cleanupFailure = $null
        try {
            docker compose @ComposeArguments exec -T postgres sh -ceu `
                'rm -f -- "$1"' sh $ContainerPath
            $cleanupExitCode = $LASTEXITCODE
            if ($null -eq $cleanupExitCode -or $cleanupExitCode -ne 0) {
                throw "container dump cleanup failed with exit code $cleanupExitCode"
            }
        } catch {
            $cleanupFailure = $_
        }
        if (!$completed -or $null -ne $cleanupFailure) {
            Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        }
        if ($null -ne $primaryFailure) {
            if ($null -ne $cleanupFailure) {
                throw "$($primaryFailure.Exception.Message); cleanup also failed: " +
                    $cleanupFailure.Exception.Message
            }
            throw $primaryFailure
        }
        if ($null -ne $cleanupFailure) {
            throw $cleanupFailure
        }
    }
}
# postgres-backup-contract-end

$containerBackup = "/tmp/postgres-before-password-$rotationStamp-$PID.sql"
Invoke-CheckedPostgresDump -Destination $databaseBackup `
    -ContainerPath $containerBackup -ComposeArguments $compose
Get-FileHash -Algorithm SHA256 -LiteralPath $databaseBackup
```

`pg_dumpall` 在 PostgreSQL 容器内直接写临时文件，再由 `docker compose cp` 原样复制，SQL 字节不会经过 Windows PowerShell 5.1 的 native stdout 文本转码。门禁会逐项检查 exec、cp 和容器清理的 native exit；任一步失败、宿主文件为空或 cluster dump 结构不完整，都会删除宿主部分文件并终止。函数无论成功失败都会尝试删除容器临时文件；若主流程与清理同时失败，异常会同时保留主失败和清理失败，避免丢失首个故障点。头部只检查前 32 行、角色逐行扫描、completion marker 只检查末 32 行，不把整份 SQL 读入内存。

### 密码轮换前的隔离恢复硬门禁

**本轮 Docker daemon 不可用，尚未执行恢复演练，不得声称上述备份已验证可恢复。** 在真实部署窗口中，必须先把 `$databaseBackup` 恢复到不映射宿主端口、不复用 `postgres-data` 的隔离临时 PostgreSQL，确认恢复命令无错误，并核对角色数、数据库数及已批准关键业务表计数；结果由数据库负责人签字后，才能停止写入方并轮换密码。不得在原卷上试恢复，也不得因恢复失败继续执行 `\password`。

隔离恢复应使用单独的临时容器和临时卷；操作时不要输出 SQL 内容、角色名、数据库名或密码。完成核对后删除临时容器和临时卷，保留宿主备份及其 SHA-256。只有该恢复门禁通过后，才通过 `psql` 的交互式 `\password` 输入新密码两次。输入不会出现在命令行；不要改用 `ALTER ROLE ... PASSWORD`、`PGPASSWORD=`、PowerShell 历史变量或把真实 `.env` 的 `docker compose config` 输出保存到日志。

```powershell
$compose = @('--env-file', '.env', '-f', 'infra/docker-compose.pilot.yml')
docker compose @compose stop jt-gateway api
docker compose @compose exec postgres sh -ceu 'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --command "\password $POSTGRES_USER"'
```

交互修改成功后，用本地编辑器只更新 `.env` 的 `DRT_OPS_DATASOURCE_PASSWORD`。随后依次重建 PostgreSQL 容器以同步其环境元数据、切换 API 并验证数据库健康，最后再启动 gateway：

```powershell
$compose = @('--env-file', '.env', '-f', 'infra/docker-compose.pilot.yml')
docker compose @compose up -d --no-deps --force-recreate --wait postgres
docker compose @compose up -d --no-deps --force-recreate --wait api
docker compose @compose exec -T api curl --fail --silent http://127.0.0.1:8080/actuator/health
docker compose @compose up -d --no-deps --force-recreate --wait jt-gateway
# 用已批准的终端/模拟器执行一次真实注册鉴权或已认证 ingress，再检查 readiness。
docker compose @compose exec -T jt-gateway curl --fail --silent http://127.0.0.1:7612/actuator/health/readiness
```

只有 API 健康明确为 `UP`，并且真实已认证 registry/ingress 合同成功后 gateway readiness 为 `UP`，才算切换完成。匿名 probe 成功不能替代该合同验证。`postgres` 容器环境中的新 `POSTGRES_PASSWORD` 只是保持部署元数据一致；真正的角色密码变更来自前面的交互式 `\password`。

### 数据库密码回退

若 API 无法连接数据库或 gateway readiness 不恢复，停止写入方，用同一个交互命令输入备份 `.env` 中的旧密码，然后恢复旧 `.env` 并按 PostgreSQL→API→gateway 顺序重建。旧密码仍只在交互提示中输入，不得拼进命令或日志：

```powershell
$compose = @('--env-file', '.env', '-f', 'infra/docker-compose.pilot.yml')
docker compose @compose stop jt-gateway api
docker compose @compose exec postgres sh -ceu 'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --command "\password $POSTGRES_USER"'
Copy-Item -LiteralPath $envBackup -Destination '.env' -Force
docker compose @compose up -d --no-deps --force-recreate --wait postgres
docker compose @compose up -d --no-deps --force-recreate --wait api
docker compose @compose exec -T api curl --fail --silent http://127.0.0.1:8080/actuator/health
docker compose @compose up -d --no-deps --force-recreate --wait jt-gateway
docker compose @compose exec -T jt-gateway curl --fail --silent http://127.0.0.1:7612/actuator/health/readiness
```

若交互回退也失败，保持服务停止，保留原卷和第一步生成的 SQL/`.env` 备份并升级给数据库负责人；不要删除 `postgres-data`，也不要在未演练的卷上直接导入 `pg_dumpall` 备份。

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

liveness 只验证真实 Netty listener channel，Compose 容器 healthcheck 也只使用 liveness，避免尚未发生业务流量时反复重启。readiness 还要求 outbox 可写、90 秒内有已认证 registry/ingress 合同成功、没有更新的失败、无 dead-letter 且最老未解决记录未超阈值。90 秒覆盖终端 60 秒空闲上报和调度抖动。主动 probe 每 10 秒以不带服务凭证的 GET 请求访问运营 API `/actuator/health`，只说明网络/进程可达，绝不能把 authenticated contract readiness 恢复为 UP。`jtGateway` 详情至少包含：

- `tcpListening`：启用后设备监听是否存活；
- `bufferWritable`：H2 outbox 是否可写；
- `operationsApiReachable`：已认证 registry/ingress 合同的聚合结果；窗口内任一失败优先为 `false`，其次已认证成功为 `true`，全部过期或只有匿名 probe 成功时为 `false`，从未观测时为 `UNKNOWN`；
- `operationsApiRegistryStatus`、`operationsApiIngressStatus`、`operationsApiProbeStatus`：各来源分别显示 `UNKNOWN`、`UP`、`DOWN` 或 `STALE`，防止一个成功请求覆盖另一来源的失败；
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
4. 使用已批准的测试终端/模拟器执行一次真实注册鉴权或已认证 ingress，确认对应 `operationsApiRegistryStatus`/`operationsApiIngressStatus=UP` 且 readiness 为 `UP`；匿名 probe 或 liveness 为 UP 不算凭证验证。
5. 确认没有旧版本请求后，清空 API 的 previous 版本和摘要。

任何阶段都不得把明文同步到 API，也不得把摘要当作可登录凭证复用。若轮换导致 401，回退网关到仍在 API previous 窗口内的旧版本，恢复投递后再重新执行轮换。

执行前把旧 `.env` 复制到 `.private/`，API 先重建，确认健康后再重建 gateway：

```powershell
Copy-Item .env .private/jt-gateway-before-credential-rotation.env
# 编辑 .env：API previous=旧版本/旧摘要，current=新版本/新摘要；gateway plaintext=新明文。
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d --no-deps --force-recreate api
docker compose --env-file .env -f infra/docker-compose.pilot.yml exec -T jt-gateway curl --fail --silent http://api:8080/actuator/health
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d --no-deps --force-recreate jt-gateway
# 用已批准的终端/模拟器执行一次真实注册鉴权或已认证 ingress 后再检查：
docker compose --env-file .env -f infra/docker-compose.pilot.yml exec -T jt-gateway curl --fail --silent http://127.0.0.1:7612/actuator/health/readiness
```

若出现 401 或 readiness DOWN，按保存的文件回退，并仍按 API→gateway 顺序重建：

```powershell
Copy-Item .private/jt-gateway-before-credential-rotation.env .env -Force
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d --no-deps --force-recreate api
docker compose --env-file .env -f infra/docker-compose.pilot.yml up -d --no-deps --force-recreate jt-gateway
# 使用旧版本凭证完成一次真实已认证合同后，确认 readiness 恢复。
docker compose --env-file .env -f infra/docker-compose.pilot.yml exec -T jt-gateway curl --fail --silent http://127.0.0.1:7612/actuator/health/readiness
```

## 附件元数据的剩余审批边界

当前网关能够解码、脱敏、持久化并投递 `ATTACHMENT_METADATA`，但现有运营 API 尚未批准附件类型、通道、媒体格式和 0x1206 的落库合同。API 因此对首次和重复投递都固定返回 `REJECTED + ATTACHMENT_METADATA_CONTRACT_UNAVAILABLE`；网关要求 `ApiResponse.data` 用 idempotencyKey 对整批逐项确认，看到该拒绝、遗漏、重复或未知状态时，整批保持重试并使 `lastDeliverySuccessful=false`。这避免 2xx 静默丢失，但不等于附件全链路验收通过。

在真实附件验收前，必须另行审批并实现 API metadata contract；本轮不得通过手工清空 pending、降低确认规则或伪造 ACCEPTED 响应绕过该边界。

## P6-2 复合车载系统 V19/V20 本地隔离迁移

### 适用范围与禁止边界

本节只批准在本机 loopback API 和隔离数据副本上演练复合车载系统迁移，不构成云端部署、生产变更或真实设备接入批准。迁移期间不得连接 SSH/SFTP，不得消费云部署配置，也不得把私密终端标识、车辆标识、号牌、坐标、认证材料或其摘要写入日志和公开证据。

附件、媒体上传目标、二进制内容以及完整 GB/T 28787 业务消息仍不在本批范围内。`media_profile` 仅表示已审批的协议档案字段，不能据此执行媒体链路或附件验收。

公开录入模板为 `docs/pilot/evidence/p6-2/onboard-system-intake-template.csv`。模板中的 `vehicle-01`、`terminal-01` 等均为合成示例；真实录入必须留在 Git 忽略的私密目录，且原始终端别名必须重新映射为 `terminal-01` 至 `terminal-04`，不得复制到公开文件。

### 停服门禁和 V19 展开演练

先说明目的：V19 只展开复合车载模型并保留旧绑定兼容面；在配置预览或应用前停止 gateway，避免 7611 会话和运行时事件与管理配置并发。

执行窗口必须逐项确认：

1. 使用隔离数据库备份完成一次 V18→V19 演练，并保留备份 SHA-256、Flyway 版本和计数证据。
2. 停止 gateway，确认宿主与容器均无 7611 监听；不能用“暂时没有终端连接”替代端口关闭证据。
3. API 仅绑定本机 loopback；runner 会拒绝非 loopback 地址。
4. 私密 CSV、JSON、manifest 和脚本均通过 PowerShell 5.1 严格 UTF-8 读取；发现非法字节立即终止。
5. 每台物理设备的终端身份唯一，能力材料状态为 `VERIFIED`，独占角色无冲突，角色与协议档案匹配。

下面命令只展示调用形状。先在当前受控 PowerShell 进程中从批准的安全位置设置 `P6_2_PRIVATE_SOURCE_ROOT`；不要回显其值，也不要把认证令牌写入命令行。runner 只从进程环境读取可选的 `P6_2_MIGRATION_API_TOKEN`。

```powershell
# 目的：只做字段级 preview，固定系统 UUID/版本供后续恢复检查，不执行配置 apply。
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\cloud-deployment\p6-2-cloud-7fa38d0\Invoke-CloudOnboardSystemMigration.ps1' `
  -Mode DryRun `
  -ApiBaseUri 'http://127.0.0.1:8080' `
  -GatewayStopped
```

`DryRun` 必须只有车辆/detail 读取和配置 preview，业务配置 apply 次数为零。输出只允许安全别名、阶段/步骤、HTTP 状态、对象版本、告警码、记录数和文件 SHA-256。

### ApplyV19、恢复和失败边界

先说明目的：`ApplyV19` 对每辆车严格执行 preview→带 `expectedVersion` 的 apply→detail read-back，确认版本和期望状态一致后才处理下一辆车。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\cloud-deployment\p6-2-cloud-7fa38d0\Invoke-CloudOnboardSystemMigration.ps1' `
  -Mode ApplyV19 `
  -ApiBaseUri 'http://127.0.0.1:8080' `
  -GatewayStopped
```

恢复执行必须先比较 manifest 固定的车载系统 UUID、`expectedVersion` 和已应用期望状态。任一业务步骤失败后 runner 记录安全失败步骤并停止，不自动重试；由负责人核对审计、版本和数据库状态后决定新操作。禁止修改 manifest 跳过失败，也禁止直接写表。

首次 `DryRun/ApplyV19` 对尚未固定 API 别名的设备只发送私密 `terminalCode` 选择器，不同时发送 `deviceAlias`。apply 成功后的 read-back 使用网络模式、角色、已验证能力、四类协议档案和上报间隔组成的非身份期望签名，要求每条未映射记录与一个且仅一个 `device-<12hex>` API 派生别名匹配；重复期望签名在写入前失败关闭。映射只保存在 ignored 私密 manifest，后续恢复和 `ContractCheck` 只用 API 派生别名核对。别名改变、重复、缺失或期望状态漂移均终止，不回退到模糊顺序匹配，也不保存终端号码、终端 ID、车辆标识或其单值摘要。

apply 请求发出前，runner 先持久化 `apply-requested`；响应合同通过后、read-back 前持久化 `read-back-pending`。从 apply 发出开始，任何 HTTP、JSON、UUID、版本、别名映射或期望状态无法确认的结果都会落为 `manual-review-required`，保留旧 `expectedVersion` 并禁止自动续跑。

回退只允许通过现有受控 API 提交一份新的、带新 `expectedVersion`、原因和审计记录的期望配置。已追加的能力、成员、角色和协议档案历史不可删除；V20 收口后的旧绑定只读门禁也不能通过回滚业务配置撤销。数据库版本回退必须使用部署窗口前的完整备份恢复到新实例，不能在原卷上覆盖恢复，不能自动降级 V20。

### ContractCheck、V20 收口和重启

先说明目的：`ContractCheck` 在应用 V20 前只读核对本批车辆的活动系统、成员、已验证能力、协议档案、独占角色、主备分离及 WAN 网络模式，不执行任何 POST。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.private\cloud-deployment\p6-2-cloud-7fa38d0\Invoke-CloudOnboardSystemMigration.ps1' `
  -Mode ContractCheck `
  -ApiBaseUri 'http://127.0.0.1:8080' `
  -GatewayStopped
```

runner 的本批只读检查不能替代 V20 SQL 的全局门禁。只有 `ContractCheck` 通过、gateway 仍停止、7611 仍无监听、隔离备份可恢复且 V20 对全部活动终端/车辆的合同检查通过后，才能执行 V20。V20 会冻结旧绑定写入；应用后先验证 API 健康和 Flyway 版本，再启动 gateway，最后用另行批准的模拟器或真实设备验收恢复 readiness。任何一步失败都保持 gateway 停止并升级处理。

### 日常设备与角色运维

所有日常变更都必须先加载最新 detail，携带 `expectedVersion`、非空原因、操作者和证据引用，并保留变更前后版本与审计事件：

- 新增物理设备：先校验终端身份唯一，完成能力声明与独立验证，再加入成员、设置网络模式/协议档案，最后分配角色。材料未验证时不得分配依赖该能力的角色。
- 移除物理设备：先转移其独占角色和 WAN 职责，确认主备/调度合同仍成立，再撤销成员；不能先删设备再补角色。
- 能力验证：证据只记录批准的私密引用，公开证据仅记录安全别名、能力类型、状态、版本和审计号。能力撤销会影响角色时应先完成受控降级。
- 独占角色：同一车载系统的 `DISPATCH`、`LOCATION_PRIMARY`、`ACTIVE_SAFETY` 各最多一个活动设备。变更主/备时先验证两台不同物理设备的定位能力，再以一个完整期望状态原子提交。
- WAN/SIM 迁移：先验证目标设备网络可达及 `DIRECT_CELLULAR`，再迁移 `WAN_UPLINK`。SIM/WAN 变化不迁移或重建终端身份、认证凭证、业务角色和在线会话；旧会话必须按正常鉴权生命周期结束，新链路重新鉴权。
- 单设备降级：调度终端丢失时不得把未验证调度能力的记录仪提升为 `DISPATCH`；记录仪丢失时保留可验证的调度/定位能力，并把安全、视频状态明确标记为不可用。降级原因、告警、开始/结束时间和恢复版本必须进入审计。

### 证据收集

每阶段至少保存：时间窗、操作者角色、Git HEAD、Flyway 版本、gateway 停止与 7611 关闭证据、模式、成功/失败计数、对象版本、告警码、manifest SHA-256、数据库备份 SHA-256 和审计号。证据不得包含私密绝对路径、原始 CSV/JSON、HTTP 请求/响应正文、终端或车辆真实标识、认证头及云地址。
