# C1 Hibernate validate 差异评估（2026-09-12）

状态：只读评估及修复提案，未修改实体、配置或迁移，未重新启动数据库。基准为视频声明提交 `da6eaeb7b82b1cb0d03f75c36b0e6423e9e7f028`。

## 决策建议

可以保持当前 `ddl-auto=none`，继续媒体设计和后续获准的隔离原型；不需要把“全库 Hibernate validate 全绿”作为开始媒体建设的前置条件。Flyway 继续负责结构演进，不使用 `update/create` 自动修表。

建议把已识别的摘要字段映射修复安排在媒体基础阶段，媒体最终 PostgreSQL 回归前完成，预计 2–4 人日。它涉及报警、取证摘要及去重键，长期忽略会降低结构漂移的发现能力。修复实体映射与是否启用生产 `validate` 是两个决定：本提案不要求更改生产 `none`，也不改变 A 门禁。

如果后续暴露实际读写、摘要、去重或事务错误，必须阻断相应链路；不能用 `none` 掩盖。全库 validate 未通过时仍须如实报告，不以局部验证替代全库结论。

## 已知事实与覆盖限制

- 上轮新建隔离 PG17/PostGIS、执行 V22 后，强制全库 `validate` 首先失败于 `jt_gateway_audit_events.payload_digest`：实际 `bpchar (Types#CHAR)`，Hibernate 预期 `char(64) (Types#VARCHAR)`。
- 该列出自既有 API V13。实体使用 String 和 `@Column(length=64, columnDefinition="char(64)")`，没有显式 JDBC CHAR 类型。
- 实际测试依赖为 Hibernate `6.6.18.Final`、PostgreSQL JDBC `42.7.7`，API Spring Boot `3.5.3`。运行配置 `apps/api/src/main/resources/application.yml` 原本就是 `ddl-auto: none`。
- 恢复相同的 `none` 后，上轮 PG 视频测试 2/2，通过 JSONB、外键、声明事务、审计失败回滚及 V21 备份恢复；此前审计 PG 复验为 13/13。这些证明对应行为，不证明完整 ORM/schema 一致。
- 本轮仅重新读取现存报告和源码，没有复跑测试。失败现场 `.tmp/c1-pg-audit-942b9d5950604f15ae0e3506229178be` 保留且实例已停止；最终成功证据见[视频隔离报告](evidence/p6-2/c1-video-declaration-isolation-2026-09-12.md)。私有数据库和原始日志不提交。

Hibernate 官方文档说明 String 默认映射 JDBC VARCHAR；`@JdbcTypeCode` 可以明确指定 JDBC 类型。结合异常和实体，这是 DDL 类型描述与 JDBC 类型选择未对齐的强证据；不是 V22 改坏了旧列，也尚无证据表明该错误造成已测试业务的数据损坏。[默认字符串映射](https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/cfg/MappingSettings.html#USE_NATIONALIZED_CHARACTER_DATA)、[JdbcTypeCode](https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/annotations/JdbcTypeCode.html)

## 源码盘点

| 表／列 | 迁移定义 | 实体映射 | 结论 |
| --- | --- | --- | --- |
| jt_gateway_audit_events.payload_digest | V13 CHAR(64) | String + columnDefinition char(64) | 已复现 validate 错误 |
| jt_terminals.auth_token_hash | V13 CHAR(64) | 同上 | 同类候选，尚未单独复现 |
| vehicle_alarms.payload_digest | V15 CHAR(64) | 同上 | 同类候选 |
| vehicle_alarms.deduplication_key | V15 CHAR(64) | 同上 | 须覆盖唯一键／重放 |
| vehicle_alarm_attachments.payload_digest | V15 CHAR(64) | 同上，可空 | 媒体取证直接相关 |
| vehicle_location_events.payload_digest | V14 CHAR(64) | String + length=64 | 另一个候选，不能只搜 columnDefinition |
| video_declaration_observations.payload_digest | V22 VARCHAR(64) | String + length=64 | 不应批量改成 CHAR |

另见 `VehicleLocationEvent.location` 的 JDBC 类型为 GEOGRAPHY，columnDefinition 却是 geometry，而 V6 实际列为 `geography(POINT,4326)`。这是待复核的声明差异，不是已复现的第二项 validate 错误。不能为消除文字差异随意改变空间类型、SRID 或查询语义。

这里只做目标类型盘点。validate 遇到第一处错误即停止，其他 JSON、空间、枚举、时间精度等映射是否全部匹配，目前没有完整证据。

## 最小修复提案

1. 固定现有 Hibernate/JDBC 版本，在全新隔离 PG/PostGIS 上运行既有 Flyway 迁移；读 JDBC 元数据和 pg_catalog，记录类型、长度、可空性、约束，先复现已知错误。
2. 对实际 CHAR(64) 的六个候选字段，评估显式 `@JdbcTypeCode(SqlTypes.CHAR)`，逐个验证后合并。保留长度和现有 schema；不全局覆盖 String，不改 VARCHAR 摘要列，不改旧迁移或 Flyway history。
3. 对允许为空的摘要验证 null；对合法 64 位小写十六进制值验证 insert/read-back、按摘要查询、去重唯一约束及重放。非法长度／字符按现有字段合同拒绝，核对数据库 CHECK 与应用校验，不引入自动 trim。
4. 重跑全库 validate，分类后续差异。可用实体声明解决的独立修复；空间映射保留真实 geography/SRID。任何需要 DDL 的问题单独提交审阅，不在本提案内改表。
5. 定向 PG 回归注册鉴权哈希、租约审计、定位、ADAS/DMS 去重、附件摘要及视频声明的读写／审计原子性，再检查 H2 兼容。
6. 仅实体映射变更时，退回前一 API JAR 即恢复原映射，无需数据库回滚；先在隔离副本验证新旧 API 对同一 schema 的读写。若最终需要结构变更，该恢复结论不适用。

以上为待审方案，本轮没有实施，也没有声称该注解已解决完整 validate。

## 风险与临时控制

| 风险 | 控制 |
| --- | --- |
| CHAR 空格语义 | PostgreSQL CHAR 会补空格，比较与 VARCHAR 有差异。完整十六进制 CHECK 降低风险，但仍需验证绑定、读取和索引查询，不能用 trim 掩盖问题。[PostgreSQL 字符类型](https://www.postgresql.org/docs/17/datatype-character.html) |
| 暴露后续类型差异 | 2–4 人日覆盖已识别映射及定向回归；复杂空间／自定义类型需重估，不承诺该时间内全库通过。 |
| none 不发现映射漂移 | 隔离验收保留 Flyway checksum 校验、实际列／约束断言及 PG 事务测试；Flyway validate 校验迁移历史，不能替代 ORM 检查。 |
| 同时改依赖／实体／表难定位 | 固定依赖，只改必要映射；升级 Hibernate 或重写方言不作为首选。 |
| H2 掩盖 PG 差异 | PG 是该问题验收依据，H2 只是兼容回归。 |

结论：可与媒体准备并行；建议媒体整体验收前修复已知映射并完成相应 PG 合同验证。A 当前主要阻挡仍是实时监控、报警取证及真实 VIDEO VERIFIED 证据缺失，而非生产未开启 Hibernate validate。
