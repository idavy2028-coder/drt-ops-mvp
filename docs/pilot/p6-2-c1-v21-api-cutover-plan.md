# C1：先升级结构至V21，再用匹配API配置

## 方案确认与边界

用户于2026-09-11确认采用C1。顺序为：维护窗口与V19恢复点→独立Flyway V20→验证→V21→验证→匹配API→真实能力核验→配置→全库复核。本文固化执行条件与候选产物，未执行任何云端操作。

这替代此前“必须在V19通过最新API完成配置再迁V20”的安排。最新API配置apply/详情依赖V21 runtime/lease结构，旧云端API又无复合系统管理接口。当前全库dispatchable=0，四个安全监控系统各有有效成员；已查V20各项SQL违规数为0，但角色/能力为空仅是部分条件无适用记录，不代表业务准入完成。

## 已核对的发布依据

| 项目 | 核验结果 |
| --- | --- |
| 本地完整演练SourceHead | c84a75edda5d29557a1fe5b4ea3ee1c6cbd02401 |
| 成功报告 | .superpowers/sdd/2026-09-06-p6-2-local-isolation-rehearsal/execution/5b4dd1917ed44c128bd7fefd3be79af0.json |
| 候选API JAR | apps/api/target/drt-ops-api-0.1.0-SNAPSHOT.jar |
| JAR SHA-256 | d4e9bba42e725a66403ce10fbb551b5ce588e0d4808cdd91a23734bd54b2bf2a |
| 来源比对 | 本次文件摘要等于成功报告API摘要；c84a75e至77783a6的apps/api和libs/jt-protocol无差异 |
| V20 SQL SHA-256 | c1fed7c9d4f796f556f8b4e4bce83e6cb01b54ac3da1156d1d593a4f04435dd3 |
| V21 SQL SHA-256 | ec6fec3c8e38b9b4a48054e89220434b4b606f69147c01234e337e9758e33fa3 |
| 云端旧API | drt-ops-jt-cloud-api:p6-2-preview-consistency-169104a；image ID 88361bb6e503… |
| 云端当前结构 | 最近实查V19；不将本表当作执行时免复核凭据 |

Docker镜像尚未为C1构建或上传。执行前将上述已验证JAR封装为固定tag/不可变image ID，记录镜像归档SHA，并在恢复的数据库上验证镜像启动与接口；镜像构建不能替换为未经比对的旧target或不确定依赖重建产物。

## 执行批次及退出条件

1. **只读冻结清单**：核对目标主机、Flyway19、成功校验、四系统/四成员、全库dispatchable=0、有效终端成员和各V20条件；固定API/GW镜像ID、Compose配置、挂载、凭据来源与恢复方式。SSH失败即停，不自动重试。
2. **进入维护窗口**：保持gateway停止；停止旧API的业务写入，并阻止其他写入者与迁移竞争。V20后旧绑定表会只读，旧API不能继续承担绑定管理。未证明停写则不迁移。
3. **建立当前V19恢复点**：完整备份、SHA及归档读取，独立隔离实例实际恢复并核对V19/四系统/四成员/两演示车false/审计与约束索引。现有pre-v19.dump是V18，不能替代此恢复点。保存私有配置恢复材料；不得把数据库恢复验证与角色/配置恢复自动混为一谈。
4. **独立迁移到V20**：使用冻结原始SQL，通过Flyway target20执行，不由启动API隐式迁移。迁移前全库门禁再次为零违规，记录Flyway版本、审计及原旧绑定行数；迁移后确认V20成功、旧表只读trigger存在、V21未执行。任何失败即停。
5. **独立迁移到V21**：Flyway target21执行，确认新增session lease表、runtime三个字段、vehicle_alarms系统归属列及Flyway21/validate结果；记录审计，不修改原迁移文件、不repair假通过。
6. **启动匹配API**：仅重建已确定目标API容器，保留旧镜像与恢复材料。显式SPRING_FLYWAY_ENABLED=false，将迁移职责留在已完成的独立工具；启动前核对数据库已到21。保留现有用户与密码，不重新bootstrap或轮换用户密码。核对数据库、JWT和gateway服务凭据映射；不输出环境变量原文。
7. **API验收**：宿主端口仍仅映射127.0.0.1:8080；检查健康、登录、权限、系统列表/详情、preview和read-back合同。仅health UP不代表兼容。gateway继续停止。
8. **能力证据与配置**：按下节完成证据核验；缺证据的能力不标VERIFIED，对应角色不伪造放行。通过受控API逐系统preview→apply→read-back，保存版本/审计/结果；再重新检查V20全库合同与业务配置完整性。
9. **结束边界**：只在配置及验收通过后关闭本批；真实设备接入及gateway启动另行明确放行，不能由结构迁移成功自动触发。

## 四系统目标清单（待证据核验后执行）

四个系统仍为SAFETY_MONITOR_ONLY，各当前一个成员；现有manifest未改。

| 项目 | 期望值及证据要求 |
| --- | --- |
| LOCATION_PRIMARY | 需真实JT808_LOCATION VERIFIED证据 |
| ACTIVE_SAFETY | 按实证核验ADAS/DMS；角色最低匹配其中一个，声明两项时分别提供证据 |
| VIDEO | 需VIDEO VERIFIED证据 |
| WAN_UPLINK | 仅设备实际DIRECT_CELLULAR时分配；共享网络设备不得凭V19默认值冒充直连 |
| transportProfile | JT808_2019；执行前逐设备与终端注册档案核对 |
| businessProfile | NONE；当前无需DISPATCH角色 |
| safetyProfile | 现有manifest为JSATL12_2017，需真实协议证据 |
| mediaProfile | 现有manifest为JT1078_2016，需真实协议证据 |
| 上报间隔 | activePositionIntervalSeconds=10、idlePositionIntervalSeconds=10；来自实际manifest，不沿用本地合成演练10/60 |

证据至少关联设备/固件/协议/核验时间/核验人及可追溯测试材料。私密身份保留在私有manifest或材料中；公开报告只用安全别名。不得以manifest声明或历史/canned合成测试直接认证真实设备能力。

## 受控API调用合同

- 操作者具备TERMINAL_READ和TERMINAL_MANAGE；在新API读取当前系统UUID、成员及version，不能猜版本或按列表顺序绑定。
- POST /api/terminals/{terminalCode}/capability-verifications：capability、reason、evidenceRef、expectedVersion。新能力事实expectedVersion=null；已有事实用能力版本，不能用系统/终端版本。未验证不调用此写接口。
- GET /api/onboard-systems/{vehicleId}获取system version。
- POST /api/onboard-systems/{vehicleId}/configuration/preview与/configuration：expectedVersion、operatingMode、完整devices列表、roles、networkMode、protocolProfiles、reason。每个设备使用terminalCode或deviceAlias其中一种选择器。
- preview保存安全changedFields和数据库业务无写入证明；不把HTTP200当作无写入证据。apply成功后GET读回全部期望配置和新version；遇409、网络不确定或返回不符合契约即停并人工核对，不自动重试写入。
- 审计核对DEVICE_CAPABILITY_VERIFIED、ONBOARD_SYSTEM_CONFIGURATION_CHANGED及操作者/原因/新旧版本。manifest作为目标及续跑记录；不直接向配置表插入数据。

## 回滚与当前未完成项

- V20前失败：保持业务入口关闭，根据失败阶段复核；不自动repair。
- V20/V21提交后：不能仅把旧API启动回来，因为旧绑定写入已被V20冻结。需将当前V19备份恢复到新实例并验证，再按独立批准的切换过程配套恢复旧API/配置；原卷不覆盖恢复。
- 配置纠正通过新expectedVersion的受控API请求追加历史，不删除能力/角色/成员历史。
- 当前状态：C1_DESIGN_APPROVED。发布镜像、当前V19恢复验证、云端迁移/部署、真实能力证据核验、配置应用均尚未执行；只读评估不等同于本批执行完成。
