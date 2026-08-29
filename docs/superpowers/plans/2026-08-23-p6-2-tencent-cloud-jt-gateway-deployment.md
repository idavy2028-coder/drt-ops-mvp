# P6-2 腾讯云 JT Gateway 云端部署实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不泄露服务器、终端和凭证信息的前提下，把本地已验证的 `jt-gateway-deployment` 基线部署到腾讯云 Linux 实例，完成 4 台真实终端的注册、鉴权、位置和报警基础链路验收。

**Architecture:** 推荐使用 Docker Compose 作为唯一首发路径：PostgreSQL/PostGIS、Redis、运营 API、管理端、算法服务、路由模拟器和 `jt-gateway` 位于同一隔离 Compose 网络，公网只发布终端 TCP 7611；API、管理端、数据库、Redis、算法、路由和 gateway 7612 管理端点均不直接暴露公网。JAR + systemd 只作为经过单独本地烟测后才可启用的备选路径，首轮部署不得混用两种运行方式。

**Tech Stack:** Linux 64-bit、Docker Engine、Docker Compose 2.24.4+、Java 21（仅 JAR 备选路径要求宿主安装）、Spring Boot 3.5.3、PostgreSQL/PostGIS 16-3.5、Redis 7、Netty、H2 文件型持久 Outbox、腾讯云 CVM 安全组或轻量应用服务器防火墙。

**Spec:** `docs/pilot/jt-gateway-operations.md`；`docs/superpowers/specs/2026-08-12-p6-2-jt808-active-safety-gateway-design.md`

## Global Constraints

- 当前文档只提供计划；没有服务器登录凭据、实例类型、操作系统和安全组确认时，不执行 SSH、端口探测、文件传输或云端变更。
- 目标服务器地址、SSH 用户、私钥路径、终端出口地址、终端 ID、手机号、车辆标识、经纬度和鉴权信息只通过进程环境变量或 Git 忽略的私密文件传递，不写入本计划或 Git。
- P6-2 云端验收范围固定为注册、鉴权、位置和报警。`0x9208`、`0x1210`、`0x1206` 在真实媒体服务接入前不启用；`ALARM_IDENTIFIER_UNAVAILABLE` 保持不变。
- 腾讯云公网入站只允许 SSH 管理端口和 TCP 7611。8080、7612、5432、6379、8090、8091、5173 不得绑定公网地址。
- 若终端出口 CIDR 固定，7611 安全组和主机规则只允许这些 CIDR；无法固定时，`0.0.0.0/0` 仅能在明确批准的真实验收窗口临时使用，并在完成或失败后立即删除。
- Docker Compose 主路径不要求宿主安装 Java；Dockerfile 已固定 Maven 3.9.11、Temurin Java 21 和 Java 21 JRE。只有 JAR + systemd 备选路径要求宿主 `java -version` 为 21。
- Compose override 使用 `!override`，服务器 Docker Compose 必须为 2.24.4 或更高版本，并以 `docker compose ... config --quiet` 实际解析通过为准。
- 网关必须以 UID/GID 10001 非 root 运行、只读根文件系统、`cap_drop: ALL`、`no-new-privileges`，只给 `/var/lib/jt-gateway` 命名卷写权限。
- `JT_GATEWAY_SERVICE_CREDENTIAL_PLAINTEXT` 只进入 gateway；API 只接收版本号和 SHA-256。不得在命令行、日志、工单或 Compose 展开输出中显示明文。
- gateway H2 Outbox、PostgreSQL 和 Redis 使用新命名卷。禁止 `docker compose down -v`、禁止删除旧卷、禁止直接打开 H2 执行 UPDATE/DELETE 或伪造 redrive。
- gateway liveness UP 是进程门禁；readiness 在首次已认证合同前可能为 503。附件未启用导致的已知 503 不阻塞 P6-2 基础链路，但必须记录具体组件状态，不能宣称附件或完整生产就绪。
- 任一真实终端失败时停止扩大到下一台，先关闭/收窄 7611、停止 gateway、保留卷和脱敏证据，再报告错误。
- 本地工作树当前不是干净发布提交。部署前必须先冻结、测试和提交 Dockerfile、云端 override、合同测试及文档；不得从脏工作树直接打包上传。

---

## 部署拓扑与端口

```text
真实终端
   │  TCP 7611（腾讯云安全组 + 主机 DOCKER-USER/防火墙）
   ▼
jt-gateway 容器
   ├─ 7611：唯一公网端口
   ├─ 7612：仅容器内 liveness/readiness
   └─ H2 Outbox：独立命名卷
          │  服务凭证认证，Compose 内网 HTTP
          ▼
运营 API 容器 :8080
   ├─ PostgreSQL/PostGIS :5432
   ├─ algorithm :8090
   └─ route-simulator :8091

管理员浏览器 ── SSH tunnel ──► 127.0.0.1:5173 / 127.0.0.1:8080
```

| 端口 | 云主机绑定 | 腾讯云入站 | 用途 |
| --- | --- | --- | --- |
| 22 或已确认的 SSH 端口 | 公网 | 仅管理员固定 IP/CIDR | 管理登录和隧道 |
| 7611/TCP | `0.0.0.0:7611` | 终端出口 CIDR；无固定 CIDR 时仅验收窗口临时全开 | JT/T 808 终端接入 |
| 5173 | `127.0.0.1:5173` | 不开放 | 管理端，经 SSH tunnel |
| 8080 | `127.0.0.1:8080` | 不开放 | API，经 SSH tunnel或容器内访问 |
| 5432、6379、8090、8091 | `127.0.0.1` | 不开放 | 基础依赖和内部服务 |
| 7612 | 不发布 | 不开放 | gateway 容器内健康检查 |

## 执行前必须由用户提供或确认的输入

以下名称是执行会话变量，不是要写入仓库的值：

| 会话变量/确认项 | 要求 |
| --- | --- |
| `DRT_CLOUD_PRODUCT` | 明确为 `CVM` 或 `LIGHTHOUSE`，决定使用安全组还是轻量防火墙 |
| `DRT_CLOUD_HOST` | 目标服务器地址，仅进程环境变量保存 |
| `DRT_CLOUD_USER` | 实际 SSH 管理用户；Ubuntu 常见为 `ubuntu`，不得猜测 |
| `DRT_CLOUD_SSH_KEY` | 本地私钥绝对路径；私钥不得上传服务器或仓库 |
| `DRT_CLOUD_SSH_PORT` | 实际 SSH 端口 |
| `DRT_ADMIN_CIDR` | 管理员出口 CIDR，用于 SSH 入站限制 |
| `DRT_TERMINAL_CIDRS` | 终端出口 CIDR 列表；未知时需另行批准临时全开 7611 |
| 服务器环境 | Linux 发行版/版本、架构、CPU、内存、磁盘、时区、sudo 权限 |
| 网络环境 | 安全组/防火墙管理权限、公网带宽、Docker Hub/Maven/npm 出站可达性 |
| 真实验收 | 4 台终端服务器配置、上电/重连方式、厂商安全报警触发方式和验收窗口 |
| 运行方式 | 首轮固定为 Docker Compose；JAR/systemd 需单独批准 |

---

### Task 1: 服务器只读盘点与登录安全门禁

**Files:**
- Read: `docs/pilot/jt-gateway-operations.md`
- Create on execution host only: `.private/cloud-known-hosts`
- Create in evidence package: `docs/pilot/evidence/p6-2/cloud-environment-check-YYYY-MM-DD.md`

**Interfaces:**
- Consumes: 用户通过安全渠道提供的 SSH 变量与腾讯云产品类型。
- Produces: 已确认的 OS、架构、资源、端口、Docker/Java 状态和登录指纹；后续任务不得重新猜测。

- [ ] **Step 1: 在本地 PowerShell 验证变量存在，不打印值**

```powershell
$required = @(
  'DRT_CLOUD_HOST',
  'DRT_CLOUD_USER',
  'DRT_CLOUD_SSH_KEY',
  'DRT_CLOUD_SSH_PORT'
)
$missing = @($required | Where-Object {
  [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
})
if ($missing.Count -gt 0) {
  throw "缺少云端执行变量：$($missing -join ', ')"
}
if (-not (Test-Path -LiteralPath $env:DRT_CLOUD_SSH_KEY)) {
  throw 'SSH 私钥路径不存在'
}
```

- [ ] **Step 2: 通过腾讯云控制台或已确认渠道取得 SSH 主机指纹**

不得仅信任 `ssh-keyscan` 输出。先从控制台/VNC/既有可信通道取得指纹，再把扫描值与可信值人工比对：

```powershell
New-Item -ItemType Directory -Force .private | Out-Null
$knownHosts = Join-Path $PWD '.private/cloud-known-hosts'
ssh-keyscan -p $env:DRT_CLOUD_SSH_PORT -H $env:DRT_CLOUD_HOST `
  | Set-Content -Encoding ascii -LiteralPath $knownHosts
ssh-keygen -lf $knownHosts
```

预期：显示的 ED25519/RSA 指纹与腾讯云可信来源一致；不一致立即停止。

- [ ] **Step 3: 建立只读 SSH 会话并盘点环境**

```powershell
$ssh = @(
  '-i', $env:DRT_CLOUD_SSH_KEY,
  '-p', $env:DRT_CLOUD_SSH_PORT,
  '-o', "UserKnownHostsFile=$knownHosts",
  '-o', 'StrictHostKeyChecking=yes',
  "$($env:DRT_CLOUD_USER)@$($env:DRT_CLOUD_HOST)"
)
ssh @ssh @'
set -eu
printf '%s\n' '--- os ---'
cat /etc/os-release
printf '%s\n' '--- arch ---'
uname -m
printf '%s\n' '--- kernel ---'
uname -r
printf '%s\n' '--- cpu/memory/disk ---'
nproc
free -h
df -h /
printf '%s\n' '--- clock ---'
timedatectl status
printf '%s\n' '--- listeners ---'
ss -lntp
printf '%s\n' '--- runtimes ---'
java -version 2>&1 || true
docker version 2>&1 || true
docker compose version 2>&1 || true
sudo -n true && echo SUDO_NONINTERACTIVE_OK || echo SUDO_REQUIRES_APPROVED_INTERACTIVE_AUTH
'@
```

- [ ] **Step 4: 审阅资源门禁**

项目建议值（不是腾讯云产品最低要求）：若在服务器上构建全部镜像，至少 4 vCPU、8 GiB 内存和 60 GiB 可用磁盘；若采用已构建镜像传输，可在实测后评估更小配置。磁盘必须容纳 Docker layers、PostgreSQL、H2 Outbox、备份和至少一个旧版本。

- [ ] **Step 5: 确认当前关键端口无冲突**

```bash
sudo ss -lntp | grep -E ':(7611|7612|8080|5173|5432|6379|8090|8091)\b' || true
```

任何冲突都要先识别进程和所有者；不得直接 kill 或复用未知数据目录。

- [ ] **Step 6: 记录环境报告并停在人工 Gate 1**

报告只记录发行版、版本、架构、资源总量、运行时版本、端口占用数量和结论；不记录公网地址、用户名、主机名、密钥路径或完整进程参数。

---

### Task 2: 安装或校准 Docker，按运行方式决定 Java

**Files:**
- Read: Docker 官方发行版安装文档
- Read: `apps/api/Dockerfile`
- Read: `apps/jt-gateway/Dockerfile`
- Modify on server: Docker package state only after Gate 1 approval

**Interfaces:**
- Consumes: Task 1 确认的 Linux 发行版、架构和 sudo 模式。
- Produces: 可运行 Compose 2.24.4+ 的 Docker Engine；JAR 备选模式另产出 Java 21。

- [ ] **Step 1: 若 Docker 已存在，先验证而不是重装**

```bash
sudo docker version
sudo docker compose version
sudo docker info --format '{{json .SecurityOptions}}'
sudo systemctl is-enabled docker
sudo systemctl is-active docker
```

通过条件：daemon active；Compose 版本不低于 2.24.4；架构与目标镜像一致。失败时记录错误，不卸载现有 Docker。

- [ ] **Step 2: 仅在确认 Ubuntu 22.04/24.04 后使用 Docker 官方 apt 仓库**

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
source /etc/os-release
DOCKER_APT_SUITE="${UBUNTU_CODENAME:-$VERSION_CODENAME}"
DOCKER_APT_ARCH="$(dpkg --print-architecture)"
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $DOCKER_APT_SUITE
Components: stable
Architectures: $DOCKER_APT_ARCH
Signed-By: /etc/apt/keyrings/docker.asc
EOF
```

检查生成文件中的 Suite 和 Architecture 与 Task 1 结果一致后，再执行：

```bash
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker run --rm hello-world
sudo docker compose version
```

若不是已确认的 Ubuntu 22.04/24.04，停止并依据 Docker 官方对应发行版页面更新本节命令；不得套用 Ubuntu 命令。

- [ ] **Step 3: 不把普通用户加入 `docker` 组**

Docker 组等价于高权限。首轮使用受审计的 `sudo docker ...`；是否委派 Docker 权限另行评审。

- [ ] **Step 4: 仅 JAR/systemd 备选路径安装 Java 21**

先用发行版包管理器列出 Java 21 JRE 候选并人工确认包来源，再安装 headless JRE。完成后：

```bash
java -version
```

通过条件：主版本 21。Compose 主路径跳过此步骤。

---

### Task 3: 补齐并测试云端 Compose override

**Files:**
- Create: `infra/docker-compose.cloud.yml`
- Create: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/CloudDeploymentBaselineContractTest.java`
- Modify: `docs/pilot/jt-gateway-operations.md`
- Test: `apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/CloudDeploymentBaselineContractTest.java`

**Interfaces:**
- Consumes: `infra/docker-compose.pilot.yml` 服务名、Dockerfile 和 `.env.example`。
- Produces: 只有 7611 公网发布的云端组合配置，以及自动化端口/安全属性合同。

- [ ] **Step 1: 写失败的云端 Compose 合同测试**

测试读取 base 与 cloud override 的文本约束，至少验证：

```java
assertThat(cloudOverride).contains("0.0.0.0:7611:7611");
assertThat(cloudOverride).contains("127.0.0.1:8080:8080");
assertThat(cloudOverride).contains("127.0.0.1:5432:5432");
assertThat(cloudOverride).contains("JT_GATEWAY_MANAGEMENT_ADDRESS: 127.0.0.1");
assertThat(cloudOverride).doesNotContain("0.0.0.0:7612");
assertThat(baseCompose).contains("read_only: true");
assertThat(baseCompose).contains("no-new-privileges:true");
assertThat(baseCompose).contains("cap_drop:");
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q `
  -pl apps/jt-gateway `
  '-Dtest=CloudDeploymentBaselineContractTest' test
```

预期：因 `infra/docker-compose.cloud.yml` 不存在而失败。

- [ ] **Step 3: 创建云端 override**

```yaml
services:
  postgres:
    ports: !override
      - "127.0.0.1:5432:5432"
  redis:
    ports: !override
      - "127.0.0.1:6379:6379"
  algorithm:
    ports: !override
      - "127.0.0.1:8090:8090"
  route-simulator:
    ports: !override
      - "127.0.0.1:8091:8091"
  api:
    ports: !override
      - "127.0.0.1:8080:8080"
    logging:
      driver: json-file
      options:
        max-size: "20m"
        max-file: "5"
  admin-web:
    ports: !override
      - "127.0.0.1:5173:80"
  jt-gateway:
    ports: !override
      - "0.0.0.0:7611:7611"
    environment:
      JT_GATEWAY_MANAGEMENT_ADDRESS: 127.0.0.1
    logging:
      driver: json-file
      options:
        max-size: "20m"
        max-file: "5"
```

- [ ] **Step 4: 用无效示例凭证只做静态合并验证**

创建 Git 忽略的 `.tmp/cloud-compose-test.env`，所有 secret 使用明确的不可部署测试值。不得调用不带 `--quiet` 的 `config`，避免以后真实值被展开到终端：

```powershell
docker compose -p drt-ops-jt-cloud-contract `
  --env-file .tmp/cloud-compose-test.env `
  -f infra/docker-compose.pilot.yml `
  -f infra/docker-compose.cloud.yml `
  config --quiet
```

- [ ] **Step 5: 运行 GREEN 和回归**

```powershell
$cloudMavenTemp = Join-Path $PWD '.tmp/cloud-maven-temp'
New-Item -ItemType Directory -Force $cloudMavenTemp | Out-Null
$env:TEMP = $cloudMavenTemp
$env:TMP = $cloudMavenTemp
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q `
  -pl apps/jt-gateway `
  '-Dtest=CloudDeploymentBaselineContractTest' test
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -am test
git diff --check
```

通过条件：合同测试与四模块回归均为 0 失败/0 错误；Compose 静态合并退出码 0。

- [ ] **Step 6: 单独提交云端部署基线**

```bash
git add infra/docker-compose.cloud.yml \
  apps/jt-gateway/src/test/java/com/idavy/drtops/jtgateway/CloudDeploymentBaselineContractTest.java \
  docs/pilot/jt-gateway-operations.md
git commit -m "feat: add hardened cloud deployment baseline"
```

不得把现有其他未提交文件顺带加入该提交。

---

### Task 4: 冻结发布物并生成 SHA-256 清单

**Files:**
- Read: `apps/api/target/drt-ops-api-0.1.0-SNAPSHOT.jar`
- Read: `apps/jt-gateway/target/drt-ops-jt-gateway-0.1.0-SNAPSHOT.jar`
- Create locally: `.artifacts/cloud-release/$RELEASE_ID/source.tar.gz`
- Create locally: `.artifacts/cloud-release/$RELEASE_ID/jars/*.jar`
- Create locally: `.artifacts/cloud-release/$RELEASE_ID/SHA256SUMS`

**Interfaces:**
- Consumes: Task 3 通过测试的干净提交。
- Produces: 可复核 release ID、源码归档、两个 JAR 和哈希清单；不包含私密文件。

- [ ] **Step 1: 拒绝从脏工作树发布**

当前工作树已知含未提交 Dockerfile、模拟器和证据文件。执行者必须先逐项审阅并按责任边界提交，不能 stash、reset、丢弃或把所有文件打成一个提交。只有人工确认这些改动都已进入批准的发布分支后，才运行下列硬门禁：

```powershell
$status = @(git status --porcelain -uall)
if ($status.Count -ne 0) {
  $status
  throw '工作树不干净，禁止生成云端发布物'
}
$releaseCommit = git rev-parse HEAD
$releaseShort = git rev-parse --short=12 HEAD
$releaseId = "p6-2-cloud-$releaseShort"
New-Item -ItemType Directory -Force .private | Out-Null
$releaseId | Set-Content -Encoding ascii -LiteralPath .private/cloud-release-id
```

- [ ] **Step 2: 从该提交重新构建 JAR**

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q `
  -pl apps/jt-gateway -am -DskipTests package
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q `
  -pl apps/api -am -DskipTests package
```

- [ ] **Step 3: 只从 Git 提交生成源码归档**

```powershell
$releaseRoot = Join-Path $PWD ".artifacts/cloud-release/$releaseId"
$jarRoot = Join-Path $releaseRoot 'jars'
New-Item -ItemType Directory -Force $jarRoot | Out-Null
git archive --format=tar.gz --output=(Join-Path $releaseRoot 'source.tar.gz') HEAD
Copy-Item -LiteralPath `
  'apps/api/target/drt-ops-api-0.1.0-SNAPSHOT.jar' $jarRoot
Copy-Item -LiteralPath `
  'apps/jt-gateway/target/drt-ops-jt-gateway-0.1.0-SNAPSHOT.jar' $jarRoot
```

`git archive` 天然排除 `.git`、`.private`、`.env.real`、真实 intake、secret JSON 和未跟踪文件。

- [ ] **Step 4: 生成 Linux 可验证的 SHA-256 清单**

```powershell
$files = Get-ChildItem -LiteralPath $releaseRoot -File -Recurse |
  Where-Object Name -ne 'SHA256SUMS'
$lines = foreach ($file in $files) {
  $relative = [IO.Path]::GetRelativePath($releaseRoot, $file.FullName).Replace('\','/')
  $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
  "$hash  $relative"
}
$lines | Set-Content -Encoding ascii -LiteralPath (Join-Path $releaseRoot 'SHA256SUMS')
```

- [ ] **Step 5: 本地复核发布物不含敏感路径或已知私密值**

检查归档文件表、JAR 内容和文本扫描结果；只输出命中数量，不打印待匹配私密值。命中不为 0 时停止传输。

---

### Task 5: 通过 SCP 或 rsync 传输发布物

**Files:**
- Consumes locally: `.artifacts/cloud-release/$RELEASE_ID/`
- Creates remotely: `~/drt-ops-inbox/$RELEASE_ID/`

**Interfaces:**
- Consumes: Task 1 SSH 参数、Task 4 发布物。
- Produces: 哈希验证通过的远端只读发布收件箱。

- [ ] **Step 1: 远端创建仅当前 SSH 用户可访问的收件箱**

```powershell
$releaseId = (Get-Content -Raw -LiteralPath .private/cloud-release-id).Trim()
if ($releaseId -notmatch '^p6-2-cloud-[0-9a-f]{12}$') {
  throw '本地 release ID 无效'
}
$knownHosts = Join-Path $PWD '.private/cloud-known-hosts'
$ssh = @(
  '-i', $env:DRT_CLOUD_SSH_KEY,
  '-p', $env:DRT_CLOUD_SSH_PORT,
  '-o', "UserKnownHostsFile=$knownHosts",
  '-o', 'StrictHostKeyChecking=yes',
  "$($env:DRT_CLOUD_USER)@$($env:DRT_CLOUD_HOST)"
)
$remote = "$($env:DRT_CLOUD_USER)@$($env:DRT_CLOUD_HOST)"
ssh @ssh "umask 077; install -d -m 700 ~/drt-ops-inbox/$releaseId"
```

- [ ] **Step 2A: Windows PowerShell 使用 SCP**

```powershell
$releaseRoot = Join-Path $PWD ".artifacts/cloud-release/$releaseId"
scp -i $env:DRT_CLOUD_SSH_KEY `
  -P $env:DRT_CLOUD_SSH_PORT `
  -o "UserKnownHostsFile=$knownHosts" `
  -o StrictHostKeyChecking=yes `
  (Join-Path $releaseRoot 'source.tar.gz') `
  (Join-Path $releaseRoot 'SHA256SUMS') `
  "${remote}:~/drt-ops-inbox/$releaseId/"
scp -i $env:DRT_CLOUD_SSH_KEY `
  -P $env:DRT_CLOUD_SSH_PORT `
  -o "UserKnownHostsFile=$knownHosts" `
  -o StrictHostKeyChecking=yes `
  -r (Join-Path $releaseRoot 'jars') `
  "${remote}:~/drt-ops-inbox/$releaseId/"
```

- [ ] **Step 2B: 已安装 rsync 的 WSL/Git Bash 可替代 SCP**

```bash
: "${RELEASE_ID:?RELEASE_ID is required}"
: "${DRT_CLOUD_USER:?DRT_CLOUD_USER is required}"
: "${DRT_CLOUD_HOST:?DRT_CLOUD_HOST is required}"
: "${DRT_CLOUD_SSH_KEY:?DRT_CLOUD_SSH_KEY is required}"
: "${DRT_CLOUD_SSH_PORT:?DRT_CLOUD_SSH_PORT is required}"
rsync -az --checksum --partial --progress \
  -e "ssh -i $DRT_CLOUD_SSH_KEY -p $DRT_CLOUD_SSH_PORT -o StrictHostKeyChecking=yes" \
  ".artifacts/cloud-release/$RELEASE_ID/" \
  "$DRT_CLOUD_USER@$DRT_CLOUD_HOST:~/drt-ops-inbox/$RELEASE_ID/"
```

不得使用 `--delete`；不得同步工作树根目录、`.git`、`.private` 或任何真实 `.env`。

- [ ] **Step 3: 远端验证 SHA-256**

```powershell
ssh @ssh "cd ~/drt-ops-inbox/$releaseId && sha256sum -c SHA256SUMS"
```

通过条件：每个文件均为 `OK`。任一失败则删除该次不完整收件箱后重新传输；不得继续解包。

- [ ] **Step 4: 把源码解包为不可变 release 目录**

```bash
read -r -p 'Verified release ID from SHA256SUMS directory: ' RELEASE_ID
if ! [[ "$RELEASE_ID" =~ ^p6-2-cloud-[0-9a-f]{12}$ ]]; then
  echo 'invalid release ID' >&2
  exit 1
fi
sudo install -d -m 0755 /opt/drt-ops/releases
sudo install -d -m 0750 /etc/drt-ops
sudo install -d -m 0750 /var/lib/drt-ops
sudo install -d -m 0750 /var/backups/drt-ops
sudo install -d -m 0755 "/opt/drt-ops/releases/$RELEASE_ID"
sudo tar -xzf "$HOME/drt-ops-inbox/$RELEASE_ID/source.tar.gz" \
  -C "/opt/drt-ops/releases/$RELEASE_ID"
sudo install -d -m 0755 "/opt/drt-ops/releases/$RELEASE_ID/jars"
sudo install -m 0644 "$HOME/drt-ops-inbox/$RELEASE_ID/jars/"*.jar \
  "/opt/drt-ops/releases/$RELEASE_ID/jars/"
printf '%s\n' "$RELEASE_ID" | sudo tee /etc/drt-ops/release-id >/dev/null
```

---

### Task 6: 在服务器端生成配置，不传输真实 `.env`

**Files:**
- Create remotely: `/etc/drt-ops/cloud.env`
- Read remotely: `/etc/drt-ops/release-id`
- Never transfer: local `.env.real`、secret JSON、真实终端 manifest

**Interfaces:**
- Consumes: release ID、管理员交互输入、服务器端随机数源。
- Produces: mode 0600 的 Compose substitution 文件；API 与 gateway 容器仍只获得各自需要的变量。

- [ ] **Step 1: 在服务器交互生成 secret**

```bash
RELEASE_ID="$(sudo cat /etc/drt-ops/release-id)"
if ! [[ "$RELEASE_ID" =~ ^p6-2-cloud-[0-9a-f]{12}$ ]]; then
  echo 'stored release ID is invalid' >&2
  exit 1
fi
set +o history
umask 077
DB_PASSWORD="$(openssl rand -base64 48 | tr -d '\n')"
JWT_SECRET="$(openssl rand -base64 64 | tr -d '\n')"
SERVICE_CREDENTIAL="$(openssl rand -base64 48 | tr -d '\n')"
SERVICE_HASH="$(printf '%s' "$SERVICE_CREDENTIAL" | sha256sum | awk '{print $1}')"
read -r -p 'Bootstrap admin login alias: ' ADMIN_USER
read -r -s -p 'Bootstrap admin password: ' ADMIN_PASSWORD
printf '\n'
```

- [ ] **Step 2: 写入临时文件再原子安装**

```bash
CONFIG_TMP="$(mktemp)"
cat >"$CONFIG_TMP" <<EOF
DRT_OPS_DATABASE=drt_ops_cloud_pilot
DRT_OPS_DATASOURCE_USERNAME=drt_ops_cloud_pilot
DRT_OPS_DATASOURCE_PASSWORD=$DB_PASSWORD
DRT_AUTH_JWT_SECRET=$JWT_SECRET
DRT_AUTH_BOOTSTRAP_ADMIN_USERNAME=$ADMIN_USER
DRT_AUTH_BOOTSTRAP_ADMIN_PASSWORD=$ADMIN_PASSWORD
DRT_AUTH_REFRESH_COOKIE_SECURE=false
DRT_WEB_ALLOWED_ORIGINS=http://127.0.0.1:15173
DRT_AMAP_WEB_SERVICE_KEY=cloud-pilot-route-simulator-only
JT_GATEWAY_OPERATIONS_API_BASE_URL=http://api:8080
JT_GATEWAY_SERVICE_CREDENTIAL_VERSION=1
JT_GATEWAY_SERVICE_CREDENTIAL_PLAINTEXT=$SERVICE_CREDENTIAL
JT_GATEWAY_SERVICE_CREDENTIAL_SHA256=$SERVICE_HASH
JT_GATEWAY_PREVIOUS_SERVICE_CREDENTIAL_VERSION=
JT_GATEWAY_PREVIOUS_SERVICE_CREDENTIAL_SHA256=
JT_GATEWAY_INSTANCE=cloud-jt-pilot-01
JT_GATEWAY_DATA_VOLUME=jt-gateway-data-cloud-pilot-01
JT_GATEWAY_HTTP_CONNECT_TIMEOUT_MS=2000
JT_GATEWAY_HTTP_READ_TIMEOUT_MS=5000
JT_GATEWAY_API_STATUS_TTL_SECONDS=90
JT_GATEWAY_TCP_PORT=7611
EOF
sudo install -o root -g root -m 0600 "$CONFIG_TMP" /etc/drt-ops/cloud.env
rm -f "$CONFIG_TMP"
unset DB_PASSWORD JWT_SECRET SERVICE_CREDENTIAL SERVICE_HASH ADMIN_PASSWORD
set -o history
```

- [ ] **Step 3: 只检查键和权限，不打印值**

```bash
sudo stat -c '%U %G %a %n' /etc/drt-ops/cloud.env
sudo awk -F= '{print $1}' /etc/drt-ops/cloud.env
sudo test "$(sudo stat -c '%a' /etc/drt-ops/cloud.env)" = 600
```

- [ ] **Step 4: 在内存中复算服务凭证摘要**

通过 root-only 脚本读取文件、复算 plaintext SHA-256 并只输出 `MATCH`/`MISMATCH`；禁止输出任一值。`MISMATCH` 时删除该配置并重新生成。

---

### Task 7: 腾讯云安全组、轻量防火墙和主机防火墙

**Files:**
- Create in evidence: `docs/pilot/evidence/p6-2/cloud-network-rule-record-YYYY-MM-DD.md`
- Modify in Tencent console: CVM security group or Lighthouse firewall only after approval
- Modify on server: `DOCKER-USER`/firewalld rules only after OS/firewall inspection

**Interfaces:**
- Consumes: `DRT_CLOUD_PRODUCT`、管理员 CIDR、终端 CIDR、验收窗口。
- Produces: 22/7611 最小入站面；后续任务不得额外开放端口。

- [ ] **Step 1: 根据产品类型选择唯一控制面**

- `CVM`：使用腾讯云安全组。
- `LIGHTHOUSE`：使用轻量应用服务器防火墙/模板。

不得同时假设二者存在。记录规则 ID、方向、协议、端口、来源 CIDR、创建时间和计划删除时间；不记录服务器公网地址。

- [ ] **Step 2: 配置 SSH 入站**

只允许 `DRT_ADMIN_CIDR` 到实际 SSH 端口。保持当前会话，另开一个新 SSH 会话验证成功后，才删除旧的宽泛 SSH 规则，避免锁死实例。

- [ ] **Step 3: 配置终端 7611 入站**

优先规则：`DRT_TERMINAL_CIDRS → TCP 7611 → Allow`。若终端出口不可固定，只能在书面批准的验收窗口临时配置 `0.0.0.0/0 → TCP 7611 → Allow`，并设置现场计时器在结束或失败时删除。

- [ ] **Step 4: 核对其他业务端口没有公网规则**

安全组/轻量防火墙不得允许公网访问 5173、8080、7612、5432、6379、8090、8091。出站规则必须允许 Docker registry、Maven/npm 下载源和必要时间同步；若采用离线镜像传输，可进一步收窄。

- [ ] **Step 5: 核对 Docker 与主机防火墙实际链路**

```bash
sudo iptables -S DOCKER-USER
sudo nft list ruleset
sudo firewall-cmd --state 2>/dev/null || true
sudo ufw status verbose 2>/dev/null || true
```

Docker 官方说明发布端口可能绕过 UFW INPUT 规则。不得关闭 Docker 的 iptables/ip6tables 管理；如需主机二次限制，在确认后把允许/拒绝规则放入 `DOCKER-USER` 链，并在另一个会话验证不会阻断 SSH。

- [ ] **Step 6: 外部测试机验证 7611，服务器同时抓取计数而非 payload**

Windows 授权测试机：

```powershell
Test-NetConnection -ComputerName $env:DRT_CLOUD_HOST -Port 7611
```

此时 gateway 尚未启动，预期失败；它证明安全组已配置但没有错误地由其他进程占用端口。gateway 启动后重复一次，才应成功。

---

### Task 8: Compose 主路径分阶段启动

**Files:**
- Read remotely: `/opt/drt-ops/releases/$RELEASE_ID/infra/docker-compose.pilot.yml`
- Read remotely: `/opt/drt-ops/releases/$RELEASE_ID/infra/docker-compose.cloud.yml`
- Read remotely: `/etc/drt-ops/cloud.env`
- Create remotely: `/opt/drt-ops/current` symlink

**Interfaces:**
- Consumes: Task 5 release、Task 6 配置、Task 7 网络规则。
- Produces: 健康基础服务、V17 数据库、最后启动的 gateway。

- [ ] **Step 1: 原子切换 current symlink，但不启动服务**

```bash
RELEASE_ID="$(sudo cat /etc/drt-ops/release-id)"
if ! [[ "$RELEASE_ID" =~ ^p6-2-cloud-[0-9a-f]{12}$ ]]; then
  echo 'stored release ID is invalid' >&2
  exit 1
fi
if [ -L /opt/drt-ops/current ]; then
  PREVIOUS_RELEASE_ID="$(basename "$(readlink -f /opt/drt-ops/current)")"
  printf '%s\n' "$PREVIOUS_RELEASE_ID" \
    | sudo tee /etc/drt-ops/previous-release-id >/dev/null
fi
sudo ln -sfn "/opt/drt-ops/releases/$RELEASE_ID" /opt/drt-ops/current
cd /opt/drt-ops/current
```

- [ ] **Step 2: 静态配置门禁**

```bash
COMPOSE_PROJECT_NAME=drt-ops-jt-cloud-pilot
COMPOSE_ARGS=(
  -p "$COMPOSE_PROJECT_NAME"
  --env-file /etc/drt-ops/cloud.env
  -f infra/docker-compose.pilot.yml
  -f infra/docker-compose.cloud.yml
)
sudo docker compose "${COMPOSE_ARGS[@]}" config --quiet
```

禁止执行会展开真实变量的无 `--quiet` 配置输出。

- [ ] **Step 3: 构建镜像并记录镜像 ID**

```bash
sudo docker compose "${COMPOSE_ARGS[@]}" build \
  algorithm route-simulator api admin-web jt-gateway
sudo docker image inspect \
  drt-ops-jt-cloud-pilot-api:latest \
  drt-ops-jt-cloud-pilot-jt-gateway:latest \
  --format '{{.RepoTags}}|{{.Id}}|{{.Created}}'
```

若服务器无法访问 Maven/npm/Docker registry，停止；改走经本地同架构构建、`docker save`、SHA-256、SCP、`docker load` 验证的离线镜像路径，不能临时改用未验证 JAR 模式。

- [ ] **Step 4: 只启动新 PostgreSQL 并确认新卷**

```bash
sudo docker compose "${COMPOSE_ARGS[@]}" up -d --wait postgres
sudo docker compose "${COMPOSE_ARGS[@]}" ps postgres
sudo docker volume inspect drt-ops-jt-cloud-pilot_postgres-data
```

卷若已存在且来源不明，停止。不得删除卷重试。

- [ ] **Step 5: 启动 API 依赖与 API，仍不启动 gateway**

```bash
sudo docker compose "${COMPOSE_ARGS[@]}" up -d --wait \
  redis algorithm route-simulator api admin-web
sudo docker compose "${COMPOSE_ARGS[@]}" ps
curl --fail --silent http://127.0.0.1:8080/actuator/health
```

- [ ] **Step 6: 验证 Flyway V17 与空基线**

```bash
sudo docker compose "${COMPOSE_ARGS[@]}" exec -T postgres sh -lc '
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -c "
    SELECT version || '"'"':'"'"' || success::text
    FROM flyway_schema_history
    WHERE version IS NOT NULL
    ORDER BY installed_rank DESC LIMIT 1;
    SELECT count(*) FROM jt_terminals;
    SELECT count(*) FROM information_schema.table_constraints
    WHERE table_schema='"'"'public'"'"'
      AND table_name='"'"'jt_gateway_audit_events'"'"'
      AND constraint_name='"'"'uq_jt_gateway_audit_events_idempotency_key'"'"'
      AND constraint_type='"'"'UNIQUE'"'"';
  "'
```

新部署预期：`17:true`、终端 0、唯一约束 1。

- [ ] **Step 7: 通过 SSH tunnel 访问管理端，不开放公网 Web/API**

本地新终端执行：

```powershell
ssh @ssh `
  -L 15173:127.0.0.1:5173 `
  -L 18080:127.0.0.1:8080 `
  -N
```

浏览器只访问 `http://127.0.0.1:15173`；API 健康可访问 `http://127.0.0.1:18080/actuator/health`。

- [ ] **Step 8: 通过私密流程预录入 4 台终端和唯一车辆绑定**

真实资料不进入 release 或服务器日志。使用 SSH tunnel 调用管理 API，或在服务器 root-only 临时目录运行已审阅的私密导入脚本；输出必须只显示 `terminal-01` 至 `terminal-04`。预录入后聚合门禁为：4 台 PENDING、注册 0、鉴权 0、4 个唯一 ACTIVE 绑定。

- [ ] **Step 9: 先启动自动激活观察器，再启动 gateway**

本地观察器通过 SSH tunnel 连接 API；确认轮询已运行后：

```bash
sudo docker compose "${COMPOSE_ARGS[@]}" up -d --wait jt-gateway
sudo docker compose "${COMPOSE_ARGS[@]}" exec -T jt-gateway \
  curl --fail --silent http://127.0.0.1:7612/actuator/health/liveness
sudo ss -lntp | grep ':7611 '
```

外部授权测试机此时重复 `Test-NetConnection`，预期 TCP 7611 成功。

---

### Task 9: 健康、日志和 Outbox 验证

**Files:**
- Create in evidence: `docs/pilot/evidence/p6-2/cloud-health-check-YYYY-MM-DD.md`
- Create privately: `.private/cloud-evidence/gateway-readiness.json`

**Interfaces:**
- Consumes: 运行中的 Compose 项目。
- Produces: liveness、readiness、API、端口、卷和日志轮转证据。

每次新建远端 shell 都先重建同一 Compose 会话参数：

```bash
RELEASE_ID="$(sudo cat /etc/drt-ops/release-id)"
cd /opt/drt-ops/current
COMPOSE_PROJECT_NAME=drt-ops-jt-cloud-pilot
COMPOSE_ARGS=(
  -p "$COMPOSE_PROJECT_NAME"
  --env-file /etc/drt-ops/cloud.env
  -f infra/docker-compose.pilot.yml
  -f infra/docker-compose.cloud.yml
)
```

- [ ] **Step 1: 容器和服务健康**

```bash
sudo docker compose "${COMPOSE_ARGS[@]}" ps
curl --fail --silent http://127.0.0.1:8080/actuator/health
sudo docker compose "${COMPOSE_ARGS[@]}" exec -T jt-gateway \
  curl --fail --silent http://api:8080/actuator/health
sudo docker compose "${COMPOSE_ARGS[@]}" exec -T jt-gateway \
  curl --fail --silent http://127.0.0.1:7612/actuator/health/liveness
```

- [ ] **Step 2: 读取 readiness，但按状态语义判定**

```bash
sudo docker compose "${COMPOSE_ARGS[@]}" exec -T jt-gateway \
  curl --silent --show-error --write-out '\nHTTP_STATUS=%{http_code}\n' \
  http://127.0.0.1:7612/actuator/health/readiness
```

流量前 HTTP 503/UNKNOWN 可以是冷启动状态。完成真实已认证 registry/ingress 后，应核对 registry、ingress、probe、Outbox 计数，而不是只看顶层状态。终端若自行发送已延期附件报文，相关 503 记录为已知降级，不覆盖注册、鉴权、位置和报警的逐项证据。

- [ ] **Step 3: 验证公网只监听授权端口**

```bash
sudo ss -lntp
sudo docker compose "${COMPOSE_ARGS[@]}" port jt-gateway 7611
sudo docker compose "${COMPOSE_ARGS[@]}" port api 8080
sudo docker compose "${COMPOSE_ARGS[@]}" port postgres 5432
```

API/PostgreSQL 输出必须是 `127.0.0.1`，gateway 7611 才能是 `0.0.0.0`。7612 不得有宿主映射。

- [ ] **Step 4: 日志只做脱敏计数**

```bash
sudo docker compose "${COMPOSE_ARGS[@]}" logs --since 10m --no-color jt-gateway api
```

在保存前必须用私密值集合替换终端 ID、手机号、车辆标识、经纬度、远端地址和 token；公开证据只保留错误码、数量和 UTC 时间窗。

- [ ] **Step 5: 验证命名卷与日志轮转**

```bash
sudo docker volume inspect \
  drt-ops-jt-cloud-pilot_postgres-data \
  drt-ops-jt-cloud-pilot_redis-data \
  jt-gateway-data-cloud-pilot-01
sudo docker inspect drt-ops-jt-cloud-pilot-jt-gateway-1 \
  --format '{{json .HostConfig.LogConfig}}'
```

---

### Task 10: 四台真实终端基础链路验收

**Files:**
- Create: `docs/pilot/evidence/p6-2/cloud-real-terminal-acceptance-YYYY-MM-DD.md`
- Create privately: `.private/cloud-evidence/terminal-01.json` 至 `terminal-04.json`
- Modify: `progress.md`

**Interfaces:**
- Consumes: 预录入终端、自动激活观察器、已开放 7611 和厂商安全报警方式。
- Produces: 4 台逐台注册、鉴权、位置、报警证据与 P6-2 收口判定。

每次新建远端 shell 都先执行：

```bash
RELEASE_ID="$(sudo cat /etc/drt-ops/release-id)"
cd /opt/drt-ops/current
COMPOSE_PROJECT_NAME=drt-ops-jt-cloud-pilot
COMPOSE_ARGS=(
  -p "$COMPOSE_PROJECT_NAME"
  --env-file /etc/drt-ops/cloud.env
  -f infra/docker-compose.pilot.yml
  -f infra/docker-compose.cloud.yml
)
```

- [ ] **Step 1: 先只启用 terminal-01**

现场确认终端当前服务器地址/端口已指向云端 gateway，并执行上电或网络重连。不得同时启动四台，以便明确失败归属。

- [ ] **Step 2: 观察 TCP、注册和自动激活**

```bash
sudo ss -tn state established '( sport = :7611 )'
sudo docker compose "${COMPOSE_ARGS[@]}" logs --since 5m --no-color jt-gateway
```

数据库聚合门禁：注册时间非空 1、ACTIVE 1、`REGISTERED + ACCEPTED` audit 1。不得输出终端 code、手机号或远端地址。

- [ ] **Step 3: 验证鉴权**

聚合门禁：`last_authenticated_at` 非空 1、`AUTHENTICATED + ACCEPTED` audit 1。失败 token 不得更新时间戳。若终端在人工激活前抢先鉴权，观察其标准重试；不得直接改数据库为 ACTIVE。

- [ ] **Step 4: 验证位置**

要求至少一条 `source=JT808` 的位置事件，terminal/vehicle 归属一致，坐标质量状态和转换版本可追溯。车辆创建时的 MANUAL_REPORT 首配位置不计入真实位置通过项。

- [ ] **Step 5: 使用厂商安全测试模式触发报警**

禁止公共道路危险驾驶。要求至少一个经厂商安全模式触发的真实 `0x0200` 主动安全报警，报警事实与对应位置 receipt/event 关联成功。每台至少 1 个报警，全批至少覆盖 ADAS 和 DMS。附件报文即使终端自动发送也不作为本阶段通过项，不下发 `0x9208`。

- [ ] **Step 6: terminal-01 全部通过后再依序执行 terminal-02 至 terminal-04**

每台均重复注册、鉴权、位置和报警四段门禁。任一台失败立即停止剩余设备、收窄或关闭 7611 并冻结证据。

- [ ] **Step 7: 最终聚合门禁**

```sql
SELECT
  count(*) AS terminals,
  count(*) FILTER (WHERE status = 'ACTIVE') AS active,
  count(last_registered_at) AS registered,
  count(last_authenticated_at) AS authenticated
FROM jt_terminals;

SELECT count(*), count(DISTINCT terminal_id)
FROM vehicle_location_events
WHERE source = 'JT808';

SELECT count(*), count(DISTINCT terminal_id)
FROM vehicle_alarms;

SELECT event_type, result, count(*)
FROM jt_gateway_audit_events
GROUP BY event_type, result
ORDER BY event_type, result;
```

基础收口最低结果：4 台终端、4 ACTIVE、4 registered、4 authenticated；JT808 位置和报警均覆盖 4 个不同终端；成功注册/鉴权审计各 4。报警总数可以大于 4，但必须说明重复或 START/END 口径。

- [ ] **Step 8: 生成脱敏报告并做私密值扫描**

逐台只记录 `terminal-01` 至 `terminal-04`、步骤、UTC 时间、HTTP/协议结果、业务事实数量和私密日志 SHA-256。扫描完整终端 ID、手机号、车辆标识、经纬度、远端地址、token 和原始帧，命中必须为 0。

---

### Task 11: 失败冻结、回滚和安全组回收

**Files:**
- Read: `docs/pilot/jt-gateway-operations.md`
- Create privately: `/var/backups/drt-ops/$RELEASE_ID/`
- Modify: cloud network rule record、acceptance report、`progress.md`

**Interfaces:**
- Consumes: 当前 release、旧 release、命名卷和安全组规则 ID。
- Produces: 可恢复失败现场；不会通过删卷或改 H2 掩盖问题。

每次新建远端 shell 都先执行：

```bash
RELEASE_ID="$(sudo cat /etc/drt-ops/release-id)"
cd /opt/drt-ops/current
COMPOSE_PROJECT_NAME=drt-ops-jt-cloud-pilot
COMPOSE_ARGS=(
  -p "$COMPOSE_PROJECT_NAME"
  --env-file /etc/drt-ops/cloud.env
  -f infra/docker-compose.pilot.yml
  -f infra/docker-compose.cloud.yml
)
```

- [ ] **Step 1: 任何失败先停止 gateway**

```bash
sudo docker compose "${COMPOSE_ARGS[@]}" stop jt-gateway
```

- [ ] **Step 2: 删除临时全网 7611 规则或恢复终端 CIDR 白名单**

先在腾讯云控制台确认规则 ID，再删除本次临时规则。复核外部 7611 不可达；保留 SSH 管理通道。

- [ ] **Step 3: 备份 H2 命名卷，不直接读取业务内容**

```bash
sudo install -d -m 0700 "/var/backups/drt-ops/$RELEASE_ID"
sudo docker run --rm \
  -v jt-gateway-data-cloud-pilot-01:/source:ro \
  -v "/var/backups/drt-ops/$RELEASE_ID:/backup" \
  alpine:3.20 sh -c 'tar -C /source -czf /backup/jt-gateway-h2.tgz .'
sudo sha256sum "/var/backups/drt-ops/$RELEASE_ID/jt-gateway-h2.tgz"
```

- [ ] **Step 4: PostgreSQL 使用受控备份流程**

复用 `docs/pilot/jt-gateway-operations.md` 中经过合同测试的 `pg_dumpall → docker compose cp → 结构门禁 → SHA-256 → 隔离恢复` 流程。不得用 PowerShell/native stdout 重定向 SQL，不得在原卷试恢复。

- [ ] **Step 5: 代码回退只切换 release，不删除数据卷**

```bash
PREVIOUS_RELEASE_ID="$(sudo cat /etc/drt-ops/previous-release-id)"
if ! [[ "$PREVIOUS_RELEASE_ID" =~ ^p6-2-cloud-[0-9a-f]{12}$ ]]; then
  echo 'previous release ID is invalid' >&2
  exit 1
fi
sudo ln -sfn "/opt/drt-ops/releases/$PREVIOUS_RELEASE_ID" /opt/drt-ops/current
cd /opt/drt-ops/current
sudo docker compose "${COMPOSE_ARGS[@]}" config --quiet
sudo docker compose "${COMPOSE_ARGS[@]}" up -d --no-deps --force-recreate --wait api
sudo docker compose "${COMPOSE_ARGS[@]}" up -d --no-deps --force-recreate --wait jt-gateway
```

若旧版本与当前 H2/PostgreSQL schema 不兼容，保持服务停止，使用“备份恢复到新卷”流程；不得让旧二进制直接打开未知新 schema。

- [ ] **Step 6: 正常验收结束也要回收临时权限**

关闭临时 `0.0.0.0/0:7611`；如需继续试运行，改成终端出口 CIDR 白名单。删除服务器收件箱中的重复发布包，保留 `/opt/drt-ops/releases` 当前和上一版本、配置、卷备份和哈希。

---

### Task 12: JAR + systemd 备选路径（首轮不执行）

**Files:**
- Transfer: `jars/drt-ops-api-0.1.0-SNAPSHOT.jar`
- Transfer: `jars/drt-ops-jt-gateway-0.1.0-SNAPSHOT.jar`
- Create remotely: `/etc/drt-ops/api.env`
- Create remotely: `/etc/drt-ops/jt-gateway.env`
- Create remotely: `/etc/systemd/system/drt-ops-api.service`
- Create remotely: `/etc/systemd/system/jt-gateway.service`

**Interfaces:**
- Consumes: Java 21、loopback 依赖容器和两个已验证 JAR。
- Produces: 不依赖应用容器的 systemd 服务；必须与 Compose 主路径互斥。

- [ ] **Step 1: 先在独立测试机完成 JAR/systemd 烟测**

验证 Java 21、环境变量、H2 目录权限、graceful shutdown、systemd hardening、API/gateway 启停顺序和真实 health。未完成该烟测时不得在腾讯云首轮使用本路径。

- [ ] **Step 2: SCP 两个 JAR 和 SHA256SUMS**

复用 Task 5 的 SCP 命令，把 `jars/` 与清单传到 release 收件箱；远端 `sha256sum -c` 全部为 OK 后安装到 `/opt/drt-ops/releases/$RELEASE_ID/jars/`。

- [ ] **Step 3: 把 secret 拆成两个 EnvironmentFile**

- `api.env`：数据库 URL/用户/密码、JWT、管理员账号/密码、gateway 凭证版本和 SHA-256；不得含 gateway 明文。
- `jt-gateway.env`：`JT_GATEWAY_TCP_ENABLED=true`、TCP 7611、管理地址 `127.0.0.1`、H2 路径、API `http://127.0.0.1:8080`、服务凭证版本和明文；不得含 API JWT 或数据库密码。

两个文件 owner `root:drtops`、mode `0640`；不得通过 `systemctl show Environment` 输出值。

- [ ] **Step 4: API systemd unit**

```ini
[Unit]
Description=DRT Operations API
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=drtops
Group=drtops
EnvironmentFile=/etc/drt-ops/api.env
WorkingDirectory=/opt/drt-ops/current/jars
ExecStart=/usr/bin/java -Xms512m -Xmx1024m -jar /opt/drt-ops/current/jars/drt-ops-api-0.1.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 5: gateway systemd unit**

```ini
[Unit]
Description=DRT JT Gateway
After=network-online.target drt-ops-api.service
Requires=drt-ops-api.service

[Service]
Type=simple
User=drtops
Group=drtops
EnvironmentFile=/etc/drt-ops/jt-gateway.env
WorkingDirectory=/var/lib/drt-ops/jt-gateway
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/drt-ops/current/jars/drt-ops-jt-gateway-0.1.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/drt-ops/jt-gateway

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 6: 启动与健康**

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now drt-ops-api.service
curl --fail --silent http://127.0.0.1:8080/actuator/health
sudo systemctl enable --now jt-gateway.service
curl --fail --silent http://127.0.0.1:7612/actuator/health/liveness
sudo ss -lntp | grep ':7611 '
```

不得让 Compose 版 API/gateway 与 systemd 版同时运行。回退使用 `systemctl stop/disable` 和 previous release symlink，仍保留 H2 数据目录。

---

## 用户需求追踪

| 用户要求 | 计划覆盖 |
| --- | --- |
| 1. 服务器环境确认与准备 | Task 1 只读盘点；Task 2 Docker/Java 分支；Task 7 云安全组和主机防火墙 |
| 2. JAR 与配置传输 | Task 4 构建与 SHA-256；Task 5 SCP/rsync；Task 12 JAR 专用路径 |
| 3. 服务启动与配置模板 | Task 3 cloud override；Task 6 root-only `cloud.env`；Task 8 Compose；Task 12 systemd 备选 |
| 4. 健康和端口监听验证 | Task 8 分阶段健康；Task 9 liveness/readiness/API/`ss`/外部 TCP 检查 |
| 5. 真实终端接入测试 | Task 10 单台到四台；Task 11 失败冻结；最终 G7/G8/G9/G10 |

本文档自身只完成计划交付。G0 人工批准之前以及缺少 SSH 登录凭据、服务器环境报告和网络规则确认时，所有执行复选框保持未勾选。

---

## 最终人工 Gate 清单

| Gate | 通过条件 |
| --- | --- |
| G0 计划批准 | 用户批准本文，仍未执行服务器操作 |
| G1 环境确认 | 产品类型、OS、架构、资源、sudo、SSH 指纹、端口和出站网络已确认 |
| G2 本地发布冻结 | 工作树干净；云 override 合同测试、四模块回归和镜像 build 通过；发布提交固定 |
| G3 传输完整性 | 远端 `sha256sum -c` 全部 OK，release 不含私密文件 |
| G4 云网络 | SSH 仅管理员 CIDR；7611 仅终端 CIDR或批准的临时窗口；其他端口不公网开放 |
| G5 数据库/API | 新卷、Flyway `17:true`、唯一约束 1、API health UP |
| G6 gateway | 非 root/只读安全属性保持；liveness UP；7611 外部授权测试成功；7612 不公网 |
| G7 单台真实终端 | terminal-01 注册、激活、鉴权、JT808 位置和安全测试报警全部通过 |
| G8 四台真实终端 | 4/4 注册、鉴权、位置和报警覆盖；成功注册/鉴权审计各 4 |
| G9 安全与证据 | 私密值扫描 0；附件禁用边界保留；临时规则回收；卷和备份保留 |
| G10 P6-2 收口 | 真实报告、异常、SHA-256、提交/分支、残余风险和 `progress.md` 完成人工复核 |

## 失败即暂停错误分类

| 错误码 | 判定 | 立即动作 |
| --- | --- | --- |
| `CLOUD_SSH_HOST_KEY_MISMATCH` | SSH 指纹不一致 | 不登录，核对控制台和密钥 |
| `CLOUD_RUNTIME_UNSUPPORTED` | OS/架构/Compose 不满足 | 不安装应用，更新计划 |
| `CLOUD_ARTIFACT_DIGEST_MISMATCH` | 传输损坏或被替换 | 删除该收件箱，重新传输 |
| `CLOUD_COMPOSE_PUBLIC_PORT_VIOLATION` | 非 7611 绑定公网 | 不启动，修正 override/tests |
| `CLOUD_FLYWAY_GATE_FAILED` | V17/约束/基线异常 | 停止 API/gateway，保留卷 |
| `CLOUD_GATEWAY_LIVENESS_FAILED` | 监听器未存活 | 不启用终端，保存日志/卷 |
| `TERMINAL_TCP_CONNECTION_NOT_OBSERVED` | 7611 无真实建连 | 关闭临时规则，核对终端配置/上电/NAT |
| `TERMINAL_REGISTRATION_REJECTED` | 白名单、版本、车辆不匹配 | 停止下一台，查稳定错误码 |
| `TERMINAL_AUTHENTICATION_REJECTED` | 激活或 token/版本问题 | 不改数据库，查 session audit |
| `TERMINAL_LOCATION_NOT_PERSISTED` | 位置 receipt/event 缺失 | 停止报警验证，查 ingress 逐项结果 |
| `TERMINAL_ALARM_NOT_PERSISTED` | 报警或位置依赖失败 | 保存 START/END 错误码，不触发附件 |
| `GATEWAY_OUTBOX_DEAD_LETTER` | 投递到达最大重试 | 停止扩容，备份 H2，禁止人工 redrive |

## 官方参考

- [腾讯云 CVM SSH 登录](https://cloud.tencent.com/document/product/213/35700)
- [腾讯云 SSH 密钥管理](https://cloud.tencent.com/document/product/213/16691)
- [腾讯云 CVM 添加安全组规则](https://cloud.tencent.com/document/product/213/112614/)
- [腾讯云轻量应用服务器防火墙模板](https://cloud.tencent.com/document/product/1207/96199)
- [Docker Engine 安装入口](https://docs.docker.com/engine/install/)
- [Docker Ubuntu 安装](https://docs.docker.com/engine/install/ubuntu/)
- [Docker packet filtering 与 `DOCKER-USER`](https://docs.docker.com/engine/network/packet-filtering-firewalls/)
- [Compose 多文件合并与 `!override`](https://docs.docker.com/reference/compose-file/merge/)
