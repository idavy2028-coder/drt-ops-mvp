# C1 第2批准备：四系统待审阅差异清单

核验日期：2026-09-11（北京时间）；文档生成时间约10:40。状态：**准备盘点完成，能力与网络证据不足，暂不具备配置写入准入条件**。

## 范围与证据口径

本轮仅通过SSH只读查询云端、读取本地私密资料及已有观察器结果。未启动gateway、未开放7611、未执行迁移、未修改manifest或设备配置，也未调用能力验证/配置preview/apply接口。此处“提交审阅”指交付清单，不代表Git提交、推送或授权下一批执行。

使用数据质量核验方法，严格区分资料声明、迁移默认值、真实设备观测和云端VERIFIED事实。私密身份仅在进程内比较，输出安全别名和布尔结果。系统通过已有预录入manifest的车辆ID关联，随后比较成员的terminal_code/terminal_phone与对应私密资料，四台均匹配；未按数据库列表顺序猜测映射。CSV原terminal_alias不等于安全别名，通过唯一private_terminal_file_ref关联，四台均各匹配一行。

## 1. 云端当前状态（本轮实查）

| 安全别名 | 系统状态/模式 | 系统版本 | 有效成员数/成员版本 | 终端状态/版本 | 有效角色 | 能力事实 | 有效协议档案 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| terminal-01 | ACTIVE / SAFETY_MONITOR_ONLY | 0 | 1 / 0 | ACTIVE / 13 | 0 | 0 | 0 |
| terminal-02 | ACTIVE / SAFETY_MONITOR_ONLY | 0 | 1 / 0 | PENDING / 3 | 0 | 0 | 0 |
| terminal-03 | ACTIVE / SAFETY_MONITOR_ONLY | 0 | 1 / 0 | PENDING / 3 | 0 | 0 | 0 |
| terminal-04 | ACTIVE / SAFETY_MONITOR_ONLY | 0 | 1 / 0 | PENDING / 3 | 0 | 0 | 0 |

- 全表数量：系统4、成员4、角色0、能力0、协议档案0。不是仅筛选VERIFIED后得到0，而是能力表本身为空。
- 四台旧终端档案均声明JT808_2019、T/JSATL12-2017、ADAS/DMS及jt1078_enabled=true；这些旧字段不能替代新模型能力事实或协议档案。
- terminal-01最近注册时间2026-08-28 22:27:03.780284 UTC，最近鉴权时间为空；其余三台注册/鉴权时间均为空。ACTIVE状态不等于当前在线或鉴权成功。
- API为running/healthy；gateway为exited，残留Health字段为unhealthy（停止容器的历史健康值），7611监听0。Flyway最新21/success=true。本轮未重复验证管理员登录或查询API接口；以上配置来自一致性只读数据库查询。
- 第一次查询事务已完成COMMIT（事务为READ ONLY），随后shell末尾CR空行报错；已输出的SQL结果完整，不把shell退出码1写成全脚本成功。第二次身份/事件核对READ ONLY、ROLLBACK、退出码0。

## 2. 逐设备能力与网络证据结论

| 设备 | 定位 | ADAS | DMS | 视频/JT1078 | 实际联网方式 |
| --- | --- | --- | --- | --- | --- |
| terminal-01 | 云端定位事件0；观察器有注册成功但无鉴权成功，尚不能证明定位链路 | 云端告警0；仅模块声明 | 云端告警0；仅模块声明 | CSV标SUPPORTED、旧档案开启；未取得可核验流媒体验收记录 | 待核实SIM所在设备与上行路径 |
| terminal-02 | 云端定位事件0，无注册/鉴权时间 | 云端告警0；仅模块声明 | 云端告警0；仅模块声明 | 同上，仅声明 | 待核实SIM所在设备与上行路径 |
| terminal-03 | 云端定位事件0，无注册/鉴权时间 | 云端告警0；仅模块声明 | 云端告警0；仅模块声明 | 同上，仅声明 | 待核实SIM所在设备与上行路径 |
| terminal-04 | 云端定位事件0，无注册/鉴权时间 | 云端告警0；仅模块声明 | 云端告警0；仅模块声明 | 同上，仅声明 | 待核实SIM所在设备与上行路径 |

本轮检查cloud-evidence目录内19份observer*.json：9 FAIL、6 TIMEOUT、4 RUNNING，无成功收口结果；其中5份出现terminal-01 registered=true/authenticated=false。RUNNING为保存的历史文件状态，不表示观察器当前仍运行。另发现acceptance-evidence与acceptance-r2-evidence中的terminal-01…04-simulator.log，属于模拟证据，不用于认证真实设备。

范围限制：此结论基于当前授权目录中的资料、观察器JSON与云端事件计数，不等于断言设备不支持这些能力；未穷尽旧照片、移动盘原始日志、全部历史私密捕获和厂商材料，也未重新播放媒体或触发真实测试。已有原始证据如能补足设备/固件/时间/结果的关联，可供后续核验，而无需伪造新测试。

**重要网络风险：**四成员的DIRECT_CELLULAR均带有V19回填原因`Legacy active terminal binding V19 backfill`，原V19 SQL将该值固定回填。CSV的network_reachability=YES、nat_firewall_check=PASSED不能证明SIM在本设备；CSV无专门SIM位置字段。历史业务描述允许调度终端共享网络给记录仪，因此不能仅据默认值分配WAN_UPLINK。四台均保持“实际网络未核实”。

## 3. 目标与当前差异（四系统共同适用）

| 配置项 | 当前 | manifest目标 | 本轮审阅建议/前置条件 |
| --- | --- | --- | --- |
| operatingMode | SAFETY_MONITOR_ONLY | SAFETY_MONITOR_ONLY | 无差异，保持；不增加DISPATCH |
| JT808_LOCATION能力 | 无事实 | 需要该能力 | 缺真实定位帧、鉴权链路及平台落库/质量证据，不标VERIFIED |
| ADAS能力 | 无事实 | 需要该能力 | 需对应设备安全场景/静态回放的告警解析与落库证据，不进行危险实车触发 |
| DMS能力 | 无事实 | 需要该能力 | 同上，独立核验DMS，不能由ADAS通过推定 |
| VIDEO能力 | 无事实 | 需要该能力 | 需设备视频能力及实际链路证据；不能由jt1078_enabled或附件能力代替 |
| LOCATION_PRIMARY角色 | 无 | 单成员承担 | 待JT808_LOCATION VERIFIED后规划 |
| ACTIVE_SAFETY角色 | 无 | 单成员承担 | 至少满足角色要求的ADAS或DMS VERIFIED；两项声明须分别有证据 |
| VIDEO角色 | 无 | 单成员承担 | 待VIDEO VERIFIED，不自动等同于JT1078_MEDIA已核验 |
| WAN_UPLINK角色 | 无 | 单成员承担 | 仅实证DIRECT_CELLULAR才规划；共享网络客户端不得承担 |
| networkMode | DIRECT_CELLULAR回填 | DIRECT_CELLULAR | 默认值与目标文字相同但证据缺失；不得视为已核验无差异 |
| transportProfile | 无新档案 | JT808_2019 | 与旧档案/CSV声明一致；需对应当前设备注册/帧版本证据 |
| businessProfile | 无新档案 | NONE | 当前安全监控用途保持NONE，不引入调度能力 |
| safetyProfile | 无新档案 | JSATL12_2017 | 旧档案T/JSATL12-2017为不同表示；协议枚举映射不等于实测通过 |
| mediaProfile | 无新档案 | JT1078_2016 | 需设备媒体协议证据，不能仅以SUPPORTED认定 |
| 活跃/空闲上报间隔 | 无新协议档案 | 10 / 10秒 | CSV与manifest均为10/10；目前无事件可验证实际频率，待设备配置或连续上报时间证据 |

资料中materialsStatus=VERIFIED、CSV CONFIRMED仅为资料确认状态，不等于onboard_device_capabilities.status=VERIFIED。未改变任何声明，也未自动降级/升级云端配置。

## 4. 证据引用与完整性

| 引用 | 实际来源与用途 | 摘要/结果 |
| --- | --- | --- |
| E1 | 当前工作树私有onboard-system-migration-manifest.json：四台目标配置 | SHA-256 `3d93e67918898a486c2ec3b7b27b92e38572154ebf43b1f904534e2346896364` |
| E2 | jt-gateway-deployment工作树私有real-terminal-intake.csv：模块、协议、频率声明 | SHA-256 `e752f193be0ced330e91f383e3de06f208aa019bf5fac2a26cf78aba466143c9` |
| E3 | E1引用的四份secret.json：仅用于身份匹配与摘要核对 | 四份均存在且SHA与manifest一致；云端成员身份匹配4/4 |
| E4 | cloud-evidence/observer-vehicle-corrected-20260828T222048583Z.json | SHA-256 `804a79cb2015bc8a4893cd6d52116009135be999ebf0c83a465208879e8e29cd`；FAIL、注册1、鉴权0 |
| E5 | 同目录19份observer*.json状态检查 | 无成功收口；不是当前流量或当前固件证明 |
| E6 | 本轮云端只读事务：系统/成员/角色/能力/档案及设备事件计数 | 见第1、2节；身份值不写入报告 |
| E7 | V19__add_composite_onboard_system_model.sql末尾成员回填 | DIRECT_CELLULAR为固定默认值，不是设备观测 |

私密原文不复制到报告。当前映射锚点为预录入manifest车辆ID与私密身份双重匹配；未来配置前仍应重新读取，不能复用过期版本。

## 5. 待审阅决策与后续执行条件

1. **证据准入：暂不放行apply。** 按terminal-01…04提供或定位每台设备当前固件、核验日期/核验人、定位/ADAS/DMS/视频结果及私密证据引用；同时确认SIM所在物理设备、是否共享LAN、上行设备身份。缺项逐台阻断，不用一台结果覆盖四台。
2. 如实际为SHARED_LAN_CLIENT，先审阅该系统是否缺少真正WAN设备成员；不能为了通过门禁伪装DIRECT_CELLULAR，亦不在本轮新增设备。
3. 后续经授权才通过受控API记录能力事实；新能力的expectedVersion按接口新记录合同处理，不使用终端版本13/3代替。四系统当前expectedVersion均0，但apply前须重新读取；成员版本也均0，不能混用。
4. 证据齐备后逐系统执行preview→审阅差异→apply→read-back，保留reason/evidenceRef/expectedVersion及审计；遇冲突或结果不确定立即停止，不能盲重试。此次未调用preview，不能写成dry-run通过。
5. gateway及真实流量仍需独立授权窗口。第2批配置准备不改变第1批验收结论，也不意味着真实业务或双设备故障切换已验收。
