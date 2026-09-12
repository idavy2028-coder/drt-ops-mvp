# P6-2 C1 视频声明隔离实施报告（2026-09-12）

## 结论

声明最小链路和本地gateway候选镜像已完成隔离验证，可以继续媒体闭环建设；不能进入A。首期实时监控、报警录像取证均必需，目前没有媒体接收/播放/录像取证闭环，也没有真实设备VIDEO VERIFIED证据。

本轮未连接真实设备或云端业务库，未修改manifest/云配置，未推送或部署。API既有V21及旧迁移未修改。源码尚未提交，基线HEAD为1dba2f2e03ee74539327ab57bb7038d036e6f4d0。

## Docker与产物

日志证明Docker HTTP500发生在WSL引擎启动未就绪阶段；2026-09-12T08:48:53Z进入running。当前客户端/服务端29.4.1。没有重装、重置或删Docker数据。

| 产物 | 证据 |
| --- | --- |
| V21兼容基线 | c1-jt-gateway:1dba2f2-local；JAR acf34ae56a844fb7be69a3ad8507aa0b6db2aaf9a381b454bd75938828c36c9d |
| 视频候选镜像 | c1-jt-gateway:video-v6-local |
| 候选image ID | sha256:8e01e31d9c2bd97eea420d243a6f6dd44196e2ea90f38f780bd746d73834cb02 |
| 候选镜像JAR | 9418e0f92e69f4cfbd69ff62cd7d00bd186a218b149df5cb742e3f20895df6f6 |
| 最终本地API JAR | 905258df3b189e8af8897ceb9a0ce6f582bfbd255effb6fd78d664b3db76df24 |
| Maven基础镜像 | sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 |
| JRE基础镜像 | sha256:a80c51f2d09a3e7e00d521f1c817bbceb6b3be94109b4a784d46078099882dda |

构建上下文仅126个必要源码/构建文件，与当前对应文件逐字节一致；不含私有数据库/日志。候选运行用户10001:10001、linux/amd64。无网络容器实测H2迁移到V6、应用启动、liveness UP；该容器未开放宿主端口、未挂载数据，已停止移除。TCP/HTTP合同在本地Java测试运行器中验证，未声称完成容器化API＋gateway双容器全链路验收。

## 迁移与恢复

- API新增V22创建video_declaration_observations，gateway独立序列新增V6允许CAPABILITY_DECLARATION。版本不冲突。
- V22 SHA256：37af21776e4648edfe6c821441889823a2fabf9e8c1a5239051465e5a58b6761。
- V6 SHA256：b275d7f212237089f97c39835527b8c5d1158886c238882c8bdc6bbca317303b。
- PostgreSQL在新建回环实例保存V21 custom备份，再升级V22；恢复到另一隔离库video_restore并核对V21、新表不存在。gateway测试从V5 SCRIPT备份恢复至独立数据库，验证原队列记录和V5约束。
- 操作流程：[隔离恢复说明](../../p6-2-c1-video-isolated-recovery.md)。不删Flyway历史、不在原库执行破坏性降级。
- 最终PG结果：.tmp/c1-pg-audit-9e979358b71d422fae4f8c5630cf9b05/result.json，SHA256 a2089f288991fca93811bfa489f50af632d1011562645674e058200d5c958aaf。实例已停止，61166监听0；备份、私有日志及失败现场保留，不提交。

## 功能与验证

0x9003查询→0x1003严格解析→当前鉴权设备与租约→本地持久队列→受保护internal API→同事务观测/能力/审计→设备VIDEO DECLARED已通过。查询默认关闭，隔离运行器显式设置JVM属性`-Djt.gateway.video-declaration.enabled=true`；没有自动改云端配置。

固定长度解析依据JT/T1078—2016第5.3.3节的[公开标准转载件](https://24526617.s21i.faiusr.com/61/ABUIABA9GAAg7O67kAYo6KbRzwE.pdf)。该来源不是发布机构网站；实现采用明确字段偏移/WORD宽度，支持标准音频编码1–28、视频98–101，未知或厂商自定义格式拒绝，不能宣称所有终端兼容。

同帧重传复用查询标识/接收时间；API以微秒精度比较持久事件，防止数据库时间精度导致误拒。旧会话无法证明归属时保留QUARANTINED观测，不改变能力；传输接收成功与能力可用分开。已有VERIFIED不被声明覆盖；一致观测延续UNCHANGED，差异阻挡preview/apply，人工通过受TERMINAL_MANAGE保护的解除接口提交版本、理由及证据，独立审计。解除不赋予VERIFIED。read-back支持分页与unresolvedOnly，历史冲突可检索。

| 最终相关回归 | 项数 | 结果 |
| --- | ---: | --- |
| VideoAttributesCodecTest | 3 | PASS |
| VideoDeclarationDispatchTest（含V5恢复） | 2 | PASS |
| ProtocolModuleRegistryActiveSafetyDispatchTest | 13 | PASS |
| VideoDeclarationIngressServiceTest | 1 | PASS |
| OnboardSystemConfigurationServiceTest选定方法 | 3 | PASS |
| JtGatewayApiContractEndToEndTest完整套件 | 12 | PASS |
| 本地回归合计 | 34 | 失败/错误/跳过0 |
| PostgresVideoDeclarationMigrationTest | 2 | 失败/错误/跳过0 |

API场景测试包含首次声明、重传、连续相同声明、冲突解除、旧租约隔离、审计故障事务回滚。测试中构造的VERIFIED仅用于隔离状态机夹具，不是实际设备核验。HTTP/TCP测试独立断言新声明最终为DECLARED。最终回归日志.tmp/c1-video-final-regression.log；不是全仓测试，也不是媒体负载或真实设备验收。

## 发现与限制

已修复：幂等时间精度、过期夹具时钟、审计故障注入覆盖、冲突永久阻塞、连续UNCHANGED误判、旧事件阻塞及历史冲突无法发现。只读代码审查发现已进入修复/回归。

新增的全库Hibernate validate试验发现既有jt_gateway_audit_events.payload_digest CHAR/VARCHAR映射差异，未修改旧映射。正式API和历史PG测试使用ddl-auto=none；本轮最终按此真实配置运行，并显式验证V22 JSONB/外键及事务。**全库Hibernate validate仍未通过**，失败现场942b9d...保留。不能把这一差异写成已解决。

下一步为实时媒体与报警取证闭环的分阶段实施、厂商协议兼容性核对和最终候选组合验收；真实设备接入、云端运行或发布仍需单独授权。A门禁保持不变。
