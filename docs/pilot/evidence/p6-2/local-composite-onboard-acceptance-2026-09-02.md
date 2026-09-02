# P6-2 复合车载系统本地隔离迁移与四设备验收记录

## 结论

**DONE_WITH_CONCERNS**。

2026-09-02 在分支 `codex/p6-2-composite-onboard-system`、HEAD
`ecae1b7a429b0781fa6dbef76d6b64d54fba24d9` 上完成本地隔离验收。实际 API JAR、实际
PostgreSQL 16/PostGIS 和实际业务 API 依次执行 V18 seed、V19 expand、能力核验、
`DryRun`、`ApplyV19`、`ContractCheck`、备份和 V20 contract；四条当前私密记录均以
`terminal-01` 至 `terminal-04` 安全别名完成。

本结论只证明本地隔离迁移与功能验收，不是云端部署，也不是真实设备或真实流量验收。
全程未执行 SSH/SFTP、云 Docker、云 PostgreSQL、安全组操作或真实流量接入。JT gateway
从未启动，TCP 7611 在各阶段和清理后监听数均为 0。

## 边界与工件

- API 构建退出码为 0，验收 JAR SHA-256 为
  `BD62961A81E0FDB9570503A876AC2CEF61AF48E19A9BDF524EB9FF94965B2DC3`。
- Task 11 canonical manifest 未写入本地 UUID 或 API 派生别名；允许公开引用的 manifest
  artifact SHA-256 为
  `3D93E67918898A486C2EC3B7B27B92E38572154EBF43B1F904534E2346896364`。
- run-specific working manifest、原始身份引用、随机本地鉴权材料、数据库快照、两个 custom-format
  备份及其完整 SHA-256 只保存在 ignored 私密证据中；本记录不包含这些值或私密绝对路径。
- 附件、媒体和完整 GB/T 28787 业务消息不在本次验收范围内。

## 迁移与四设备证据

| 阶段 | 结果 |
| --- | --- |
| V18 | 4 辆 source-derived legacy 车辆、4 台独立物理终端、4 条活动 legacy binding；pre-V19 custom-format 备份可读，202 个 archive entries |
| V19 | Flyway 19；旧 active-vehicle 唯一索引为 1；4 systems、4 runtime states、4 active memberships 回填；UUID、鉴权和时间戳保持 |
| 能力核验 | actual loopback API 对 4 台设备逐一完成 `JT808_LOCATION`、`ADAS`、`DMS`、`VIDEO`，共 16 次 HTTP 200、状态 `VERIFIED`、version 0 |
| DryRun | 4/4 preview HTTP 200；memberships、roles、profiles、audits、system-version-sum、terminal-version-sum、capabilities 七个观测维度未变化；结合 preview API 只读合同，本次未观察到配置写入，但该证据不等价于所有相关表的内容级零写证明 |
| ApplyV19 | 4/4 经 preview → expectedVersion apply → read-back 完成；四个 aggregate version 均为 1；working manifest 仅在私密副本中固定 API 派生别名 |
| ContractCheck | 4/4 只读 HTTP 200；membership、exclusive role、verified capability、readiness 和 gateway/7611 scoped gates 全部通过 |
| post-Apply 备份 | custom-format 备份可读，249 个 archive entries；完整 SHA-256 留在私密证据中 |
| V20 | Flyway 20；旧 active-vehicle 唯一索引为 0；legacy write trigger 为 1；迁移前后业务计数和 UUID/鉴权/时间戳保持；API `UP`，意外重启 0 |

最终 private-derived 状态：4 台物理设备、4 个活动 onboard systems、4 个活动 memberships、
4 个活动 protocol profiles、16 个 verified capability facts、16 个活动 role assignments；
`LOCATION_PRIMARY`、`ACTIVE_SAFETY`、`VIDEO`、`WAN_UPLINK` 各 4。4 辆目标车辆均为
`SAFETY_MONITOR_ONLY` 且不可调度，terminal identity 唯一性为 4/4，attachment 相关非空字段数为 0。

Flyway V2 还会生成 2 辆没有 legacy binding 的 demo 车辆。它们不是四条私密源记录，但默认
`dispatchable=true` 会触发 V20 全局 gate；本次只在一次性数据库 fixture 中将这 2 行归一为不可调度，
未改变四辆 source-derived 车辆、源私密数据或业务代码。该例外已保留在私密审计证据中。

任何未来云端 V19→V20 cutover 都必须先在最新只读备份的恢复克隆上完成全库盘点：每一辆
`dispatchable=true` 车辆必须具有满足 V20 合同的活动 onboard system、`DISPATCH_SERVICE` 模式、
活动 membership、`DISPATCH`/`LOCATION_PRIMARY` 角色及相应 verified capability；否则只能在业务所有者
明确授权后，通过另行审计的业务变更将对应车辆设为不可调度。本次一次性数据库的 fixture SQL 禁止直接
复用于云端，也不得据此未经业务授权修改生产车辆。全库盘点或处置未闭环时，云端 V20 cutover 为
**NO-GO**。

## 功能矩阵与回归

| 门禁 | 结果 |
| --- | --- |
| 外部 PostgreSQL 迁移测试 | 57 tests，0 failure，0 error，1 个未启用 Testcontainers 的既有 conditional skip；显式 external-ephemeral P6/V20 路径实际执行 |
| Task 10 三模块矩阵 | 41/41：ScenarioRunner 24、gateway runtime 13、composite API 4；0 failure/error/skip |
| Java 全回归 | 125 suites、963/963 tests、0 failure、0 error、83 conditional skips，Maven exit 0 |
| 前端 | 54/54 files、304/304 tests；typecheck exit 0；build exit 0，191 modules |
| Task 11 私密测试 | 43/43，退出码 0 |
| Task 12 私密 orchestration 测试 | RED 为 library missing、退出码 1；最小实现后 6/6 GREEN、退出码 0 |

前端生产构建仍报告 `>500 kB` chunk warning，本轮按既有裁决记录为非阻断警告，未因本任务修改
前端业务代码。

初审修复轮次使用新的唯一 disposable PostGIS 环境，仅重跑 focused external migration 57 项，并从本轮
新鲜 Surefire XML 机械生成独立 safe summary：`P6CompositeOnboardSystemMigrationTest` 54/54、
`DatabaseMigrationTest` 3 项（0 failure/error、1 conditional skip），合计 57/0/0/1、Maven exit 0、
`external-ephemeral=true`。生成时间为 `2026-09-02T07:23:03Z`，safe summary SHA-256 为
`80A22B8B6F2F295C387890DE3B06FE18B9B3A3DDD5BF0419EE45BE4B9861F6CA`，源日志 SHA-256 为
`2D12988B996C14ADF935F1186020F985E61A45CBBF03A2CBCA730CA95ECFBBC0`；摘要不含 URL、用户名、密码、
命令行或 system properties。该轮没有重跑 963 项 Java 全回归。

## 清理与安全

- 只创建一组唯一命名、run label、loopback 随机端口、独立卷的一次性 PostGIS 资源；未停止、
  重启、重建或修改任何现有容器/卷。
- 清理后 task container、task volume 和 run-label 资源计数均为 0；现有容器名称、卷名称和容器
  状态类别与任务前快照一致，状态类别漂移数为 0。
- 清理后 PostgreSQL、API 和 7611 listener 均为 0；Java residue 0，Task-owned Node residue 0，
  PowerShell residue 0。
- 本地 Compose 未被使用，因此不执行无意义的 `docker compose config --quiet`。

最终泄漏扫描覆盖 19 个公开/私密安全输出目标和 14 个当前敏感值，并对每个值同时检查原值、
UTF-8/GBK 表示和身份单值 SHA-256：公开原值 0、私密安全输出原值 0、身份摘要 0、公开私密绝对路径 0、
长 credential pattern 0，全部目标严格 UTF-8 可读。最终 `git diff --check` 通过，工作区状态严格只有
本报告和 `progress.md` 两项，staged 0、tracked `.private` 0；未执行 stage/commit/push。

## 残余风险与下一入口

1. 本记录不能替代云端 V19/V20 cutover、真实设备双会话或真实流量授权；任何此类动作仍需新的
   明确授权、目标/端口/安全组复核和有界窗口。
2. Task 10 ledger 中两个 deferred Minor 继续只读保留：异常路径 `ByteBuf` 释放时机，以及 instance
   runner 首个失败步骤/原因的诊断保真。本任务禁止修改业务代码，未尝试修复或重分类。
3. 首次 external migration 运行因一次性测试角色缺少 disposable superuser 权限而在 V1 PostGIS
   extension 处失败；只重建精确的任务测试库/角色后 57 项完整通过。首次 Java `clean test` 又被
   历史 `hsperfdata` 目录的 Windows clean 权限阻断；清空 Surefire 报告并使用新 TEMP/TMP 的完整
   `mvn test` 才形成有效 963/963 证据。两次失败均未改业务代码，且失败证据保留在私密日志中。

下一授权入口是统一最终 review：复核本报告、私密证据索引、tracked allowlist、泄漏扫描和清理门禁。
只有该 review 接受后，才可另行提出云端/真实设备验收计划；本次结果本身不授权部署或流量接入。
