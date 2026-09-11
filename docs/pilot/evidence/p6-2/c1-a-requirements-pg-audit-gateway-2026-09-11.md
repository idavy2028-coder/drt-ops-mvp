# C1 A准入判定、PostgreSQL审计复验与gateway评估（2026-09-11）

## A阶段视频要求

按当前未修改的目标配置，A阶段启用VIDEO角色必须具有VIDEO=VERIFIED；DECLARED不能替代。OnboardSystemConfigurationService只将VERIFIED事实纳入角色校验，缺少时返回ROLE_CAPABILITY_MISSING:VIDEO；mediaProfile还必须非NONE。VIDEO与JT1078_MEDIA是不同能力枚举，不相互自动证明。

这不是“任何名为A的阶段都必须视频”的普遍要求：若A仅指技术准备/声明收集，可保留DECLARED但不能视为目标配置验收。如果要将A改为无视频试点，必须明确调整阶段业务范围和目标配置。用户此前已确认首期视频监控或取证必需，本轮又禁止修改manifest，因此不采用该变更，不放行仅DECLARED的现有目标。

VERIFIED是配置门禁；业务上还必须有与目标设备/固件/通道对应的实测视频证据，不能仅由数据库状态或通用信令应答证明媒体可用。声明链路用于采集声明，不能自动核验。当前缺少已通过的替代媒体证据。

## PostgreSQL复验

本轮新建私有native PostgreSQL17/PostGIS回环实例和alarm_authority数据库，使用专用随机口令；复用既有PostgisVehicleAlarmIngressIntegrationTest的external-ephemeral入口，禁止连接地址为非loopback或其他数据库名。没有复用任何现有数据库。

执行结果：13 tests / 0 failures / 0 errors / 0 skipped。最终Flyway最新21/success=true（21|t）。测试只在本轮新库运行原有迁移和合成fixture初始化，不修改迁移文件、manifest或云端配置。

通过项目：

- 续租expires_at延长、version+1、leaseGeneration不变；续租和首次释放各1条业务审计，重复释放/已释放后续租不追加成功审计，released_at非空。
- PostgreSQL JSON操作符直接核验租约审计connectionId与当前会话匹配。
- ADAS/DMS创建各1条、ADAS结束1条独立审计；重放不增加记录。审计JSON terminalId正确，module包含ADAS/DMS。
- 在本轮测试库创建临时拒绝audit_logs INSERT的触发器，让真实数据库抛C1_SYNTHETIC_AUDIT_REJECT；续租/释放失败后version/expires_at/released_at前后一致。报警创建及幂等receipt回滚；移除测试触发器后同一key可成功写入，再投递为REPLAYED，创建审计只有1条。
- 原有真实数据库报警事务、归属、并发等回归亦通过。本轮没有再次运行模拟TCP或镜像；此前H2/HTTP/TCP证据与本次PostgreSQL证据分层保留。

执行目录：.tmp/c1-pg-audit-2065c68f6de244f894613ccc834fd8c6；result.json记录Status=PASS、PostgresStopped=true、PortListeners=0。端口63016，本轮PostgreSQL已按原进程句柄、启动时间、可执行路径、pidfile及数据路径核验后停止。数据及私有日志保留用于复核；不是仍运行的环境。Surefire目录已保护为codex用户私有，含测试连接信息的XML/日志不复制到公开报告。

首次准备尝试c1-pg-audit-353401cab2584f2db87dd742c50b2bbf因旧ACL辅助函数仅接受native-*命名失败，数据库未启动。该早期报告CleanupUnproven为监督器保守状态，不能解读成遗留运行数据库。随后新目录使用同等私有ACL并完成独立运行；失败目录未覆盖。

新增/扩展测试位于PostgisVehicleAlarmIngressIntegrationTest；本轮未改产品实现。只读审阅未发现可操作缺陷。覆盖限制：没有单独验证报警END的审计失败回滚，不能据创建回滚声称该独立场景已测。

## 视频推进顺序

1. 审阅已提交最小设计及队列/证据存储合同，确定首期实时监控或录像取证主路径。当前设计见docs/pilot/p6-2-c1-video-declaration-minimal-design.md。
2. 声明链路：codec/分发→当前鉴权设备→可靠投递→DECLARED→审计与冲突/read-back，预计7–12人日（含已识别的队列约束/证据存储工作）。现有gateway_outbox.kind CHECK不允许CAPABILITY_DECLARATION，必须另行审阅新增迁移及白名单变更；本轮禁止修改，因此未开始视频实现。不能只改枚举或借用SESSION_AUDIT绕过。
3. 媒体闭环：建设或集成独立接收服务与播放器，或录像检索下载/证据存储；单条最小业务主路径额外10–20人日，低置信度，盘点已有外部媒体服务后重估。源码没有完整可复用媒体链路，不等于已证明组织内没有其他服务。仅0x1003声明或控制应答不足。
4. 隔离合成验证后，在另行授权的真实设备窗口核验目标能力，记录证据并通过已有接口人工VERIFIED，再做目标配置/实际媒体验收。此计划不授权当前真实设备接入、迁移或部署。

如果未来确有成熟独立媒体平台，可用实际收流/录像取回证据替代对声明帧的依赖，仍要满足能力核验和配置门禁；当前没有可直接放行的此类证据。

## gateway版本与镜像

- 当前gateway及jt-protocol/模拟器源码与已通过V21本地演练的c84a75e无差异。OperationsTerminalRegistryClient已经调用session-leases/renew和release，验证leaseGeneration及新版上下文。旧镜像是否含这些字节本轮不连接云端复查，也不假设它已兼容。
- 仓库已有多阶段Dockerfile：JDK21/Maven构建，JRE21运行，非root UID10001，7611/7612。不需要重写gateway租约实现；需固定当前源码、基础镜像digest及依赖，构建并记录JAR SHA/镜像digest。不可只重贴旧镜像tag。
- API审计修复应与新gateway作为明确版本组合验证。V21只是数据库结构版本，不是镜像兼容性证明；需核对注册/鉴权上下文、租约owner与续租/释放、service credential、安全配置、健康探测及outbox升级行为。
- 工作量：固定产物/构建约0.5人日，镜像启动和配套API/PostgreSQL隔离合同约0.5–1人日，证据/回滚清单约0.5人日，合计1–2人日；若基础镜像/依赖未缓存或架构不符，额外等待和排障另计。
- 可在A前完成，宜与视频设计审阅并行准备；不必等待视频功能才建立V21兼容基线，但视频后续改变gateway时还须重建最终候选镜像。
- 本机Docker CLI存在，default endpoint为本地npipe；docker version实查daemon管道不存在。当前无法直接构建镜像，需另行准备可用本地构建环境。本轮没有启动Docker、构建/推送镜像或部署。

## 结论

审计修复的隔离PostgreSQL阻挡已关闭；本地H2/HTTP/TCP和真实数据库证据均具备，但本修复未部署。现有A目标仍要求VIDEO VERIFIED及可用视频业务，故暂不具备进入A条件。下一顺序为审阅视频声明/存储/队列方案及媒体主路径，同时准备V21 gateway构建环境与版本组合验证；任何迁移、白名单、真实接入和部署均保持待单独授权。
