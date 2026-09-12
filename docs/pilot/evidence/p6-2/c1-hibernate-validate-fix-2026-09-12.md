# C1 Hibernate CHAR 映射修复与完整 validate 验证（2026-09-12）

## 结论

六个实际 CHAR(64) 字段已显式使用 `@JdbcTypeCode(SqlTypes.CHAR)`，新建隔离 PostgreSQL17/PostGIS 上完整 API persistence unit 的 Hibernate `validate` 通过，覆盖 31 个实体。两组 PostgreSQL 测试共 18/18，本地相关 H2／HTTP／TCP 回归 151/151，失败、错误、跳过均为 0。

基准 HEAD `2c6e71ac9f2e764fe40f038d9fd816130f57370b`，分支 `codex/p6-2-ops-safety-gates`；该文档提交已获用户单独授权并推送。本轮修复保留为未提交改动，未推送、未部署、未构建 gateway 镜像、未启动媒体建设。

## 修复范围

| 实体字段 | 实际数据库列 | 变更 |
| --- | --- | --- |
| JtTerminal.authTokenHash | jt_terminals.auth_token_hash | 显式 JDBC CHAR |
| JtGatewayAuditEvent.payloadDigest | jt_gateway_audit_events.payload_digest | 显式 JDBC CHAR |
| VehicleAlarm.payloadDigest | vehicle_alarms.payload_digest | 显式 JDBC CHAR |
| VehicleAlarm.deduplicationKey | vehicle_alarms.deduplication_key | 显式 JDBC CHAR |
| VehicleAlarmAttachment.payloadDigest | vehicle_alarm_attachments.payload_digest | 显式 JDBC CHAR |
| VehicleLocationEvent.payloadDigest | vehicle_location_events.payload_digest | 显式 JDBC CHAR |

只改变五个实体的六个字段及必要 import；保留原有长度、列定义和校验，不增加 trim，不全局覆盖 String，不升级 Hibernate/JDBC。V22 声明摘要仍是 VARCHAR。生产 `application.yml` 的 `ddl-auto=none` 保持不变，两个隔离 PostgreSQL 集成测试强制 `validate`。

本次全库 validate 没有再暴露需要变更 DDL 的错误；未修改任何迁移、Flyway history、队列白名单、manifest 或云配置。空间字段的 GEOGRAPHY/geometry 声明差异未触发当前 Hibernate 的校验失败，未趁此修改空间类型或 SRID。

## 先复现再修复

1. 先只将 `PostgresVideoDeclarationMigrationTest` 的隔离配置从 none 改为 validate，保留旧实体映射。
2. 新建私有、仅监听 127.0.0.1 的 PG17/PostGIS，既有迁移加合成前置数据到 V22。启动失败准确复现：

   `jt_gateway_audit_events.payload_digest: found bpchar (Types#CHAR), expecting char(64) (Types#VARCHAR)`。

3. 对照 V13/V14/V15 实际列类型及实体默认 String 映射，为六个 CHAR 字段明确 JDBC CHAR，再在另一个全新实例复验。
4. 完整 API 启动校验、视频声明事务及 V21 备份恢复 2/2 通过；随后第三个新实例执行加强后的报警／摘要合同测试 16/16。

| 运行 | 用途／结果 | 端口／清理 |
| --- | --- | --- |
| `.tmp/c1-pg-audit-b9b42e9a1ada48f8bfa34ac15ae19047` | 修复前 RED，已知 CHAR/VARCHAR 校验错误 | 52312，已停止，监听 0 |
| `.tmp/c1-pg-audit-784e50fa2bf942898ab1965dc037aaaf` | 修复后完整 validate + 视频／恢复，2/2 PASS，schema 22 | 52521，已停止，监听 0 |
| `.tmp/c1-pg-audit-4758fd7be723454cb88e59428364b318` | 完整 validate + 摘要／审计合同，16/16 PASS，schema 22 | 52892，已停止，监听 0 |

使用本机 PostgreSQL 17.9、Hibernate 6.6.18.Final、JDBC 42.7.7 和现有 JDK21。监督器限定数据库名 alarm_authority、随机本地端口、私有目录及随机凭据，清除非必要继承环境；按 PID、启动时间、postgres.exe 路径、数据目录和 pidfile 核对归属后停止。没有连接现有数据库、真实设备或云端业务库。

原始数据库、口令、日志和测试 XML 保留在私有忽略目录，不提交。记录完整停止证据的 result.json 可以复核，公开报告不复制原始凭据。

## 实际验证覆盖

`PostgisVehicleAlarmIngressIntegrationTest` 从既有 13 项扩为 16 项，完整 Spring Boot 启动使用 validate。新增三项验证：

- 完整 persistence unit 校验及 JDBC 元数据：六个 CHAR、一处 V22 VARCHAR，长度 64，所需可空性；Flyway 当前版本 22。实际输出 `C1_FULL_VALIDATE entities=31 CHAR_columns=6 VARCHAR_columns=1 schema=22`。
- 实际 ORM 写入、flush、清除一级缓存后读回，并用 JPQL 参数绑定查询六个摘要／哈希字段。审计、定位和附件三个可空摘要验证 null；没有用内存实体相等冒充数据库 round-trip。
- 附件摘要 63 字符、65 字符、64 位大写和尾随空格分别被 PostgreSQL CHECK／长度约束拒绝，SQLSTATE 为 23514／22001；失败事务没有留下附件行。

保留并通过原有租约续租／释放审计与失败回滚、ADAS/DMS 审计、重放、并发去重和归属等 13 项。`PostgresVideoDeclarationMigrationTest` 的 2 项继续覆盖声明／冲突／审计回滚和 V21 备份恢复，并现在强制完整 validate。

本地相关回归：

| 测试类 | 项数 |
| --- | --- |
| TerminalManagementServiceTest | 35 |
| GatewayRegistrationCapabilityContractTest | 1 |
| JtTerminalSessionLeaseServiceTest | 6 |
| GatewayStateAuditTest | 2 |
| VehicleAlarmIngressServiceTest | 15 |
| AlarmAttachmentServiceTest | 12 |
| GpsLocationIngressIntegrationTest | 67 |
| VideoDeclarationIngressServiceTest | 1 |
| JtGatewayApiContractEndToEndTest | 12 |
| 合计 | 151 |

命令（PowerShell，使用已安装的 Maven/JDK；本轮离线依赖）：

```powershell
python .tmp/Run-C1PgHibernate.py
python .tmp/Run-C1PgHibernateContracts.py
& 'C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.4/plugins/maven/lib/maven3/bin/mvn.cmd' -q -o -pl apps/api -am '-Dtest=TerminalManagementServiceTest,GatewayRegistrationCapabilityContractTest,JtTerminalSessionLeaseServiceTest,GatewayStateAuditTest,VehicleAlarmIngressServiceTest,AlarmAttachmentServiceTest,GpsLocationIngressIntegrationTest,VideoDeclarationIngressServiceTest,JtGatewayApiContractEndToEndTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dserver.address=127.0.0.1' test
```

监督脚本为本工作区私有运行辅助，改编自上轮隔离监督器，不是可直接用于任意数据库的公共脚本。代码中的外部 PG 测试入口仍须显式启用，禁止把连接参数指向已有或云端数据库。

摘要：

- 视频／validate 成功收据 SHA256：`c0a430b6fd53bb0f5f6eed079fbec8acbe093f39a2a2dd71da0ed3ae00208423`。
- 摘要／审计成功收据 SHA256：`39069a53b46ad57839550e22326c1e0882dafb75cbfa1f9c50fc99eae2b817f1`。
- 修复 API JAR SHA256：`6987c14f40f91681fae23e94c91981c50c78eda1dca1eda2ea76cf983cfdddee`；本地构建，无部署或镜像发布。

## 审查、限制与后续

只读审查未发现需修复的问题，`git diff --check` 通过。覆盖边界：Hibernate validate 检查完整 ORM 映射，不等于对所有未映射表、触发器、CHECK、索引和业务约束的穷尽验证；本轮没有跑全仓测试。非法值数据库测试直接覆盖附件摘要，注册哈希仅验证实体更新，新增定位映射测试绕过 GPS 业务入口；它们不等于完整 PostgreSQL 注册鉴权／定位流程复验。本地实际业务回归单列如上。

本次没有 DDL，回退方案为退回原 API 制品，数据库无需回滚；旧 JAR 回退启动／读写本轮未另行实测，不能把 V21 备份恢复测试说成该二进制回退测试。

用户要求“先修 Hibernate、再讨论媒体启动”的前置条件现已满足。媒体方案仍是待审设计，没有开始实施，A 条件也没有改变：仍需双路径媒体及另行授权的真实 VIDEO VERIFIED、最终候选组合和配置验收。
