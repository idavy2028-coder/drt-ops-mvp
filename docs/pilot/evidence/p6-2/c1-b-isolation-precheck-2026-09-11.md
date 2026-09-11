# C1 B阶段隔离预检（2026-09-11）

结论：现有隔离演练通过，但B阶段完整范围尚未覆盖，暂不具备进入A阶段条件。缺口属于验证证据不足，不是已证实产品接口失败。

运行HEAD=4caa715d330ae7c2d07147e20995428046c60995，run=f6ad8000b8de470182641aabb5006e87。运行前工作树干净，apps/packages/模拟器源码与部署来源c84a75e无差异。运行前API摘要匹配部署d4e9bba42e725a66403ce10fbb551b5ce588e0d4808cdd91a23734bd54b2bf2a。既有运行器fresh构建，本次实际API摘要593b07ca2529a9eba02726082946f01f772a9e4976df60b5bc9bcea87c66d5bb，gateway摘要dbe6ae942f224a15169e047dfc8bb001573a167d554625e1446e8d801b5e6c2b；不声称与部署二进制逐字节一致。

JDK21.0.10、PostgreSQL17.9/PostGIS、Maven、模拟器依赖实际运行通过。codex非管理员Windows PowerShell5.1；沙箱CIM拒绝访问，沙箱外普通用户CIM可用。新建composite_onboard/composite_live隔离库，端口51556–51559仅回环；gateway使用本轮H2 outbox。未连接真实设备或云端业务数据库，未修改manifest/云端配置，未启动云端gateway。

| 项目 | 实测结果 |
| --- | --- |
| 环境、V21迁移及validate | PASS；API/GW健康HTTP200/UP |
| 数据库合同 | 59/59，失败/错误/跳过均0 |
| 注册、鉴权、心跳 | PASS，4模拟终端 |
| 定位、ADAS/DMS上报 | 未执行；现有Wire仅0x0100/0x0102/0x0002，safetyProfile=NONE |
| 视频声明 | 部分覆盖；合成VIDEO能力核验及JT1078_2016配置read-back，无终端视频能力帧或媒体实测 |
| 能力核验接口 | PASS；合成JT808_LOCATION/VIDEO/VENDOR_DISPATCH，不代表真实设备能力 |
| preview→apply→read-back | PASS；3系统4成员，3次preview前后各10表全行摘要不变；鉴权后核对角色/能力/协议/成员 |
| session lease建立 | PASS；4条独立连接、gateway身份、有效期限和token版本一致 |
| 续租接口 | 未显式验证expires_at前后推进，不能由心跳推定 |
| 释放接口 | 证据不足；LEASE_RELEASE仅检查活跃租约归零，过期也满足，未断言released_at或接口响应 |

现有运行器18阶段PASS，Task12 ACCEPTED=4；结果PASS/COMPLETE/REHEARSAL_COMPLETE/Retained=false，不覆盖上述未测项。

GW/API/PG/TOOLS均STOPPED，STORAGE=REMOVED；独立CIM及监听复核：本轮资源进程0、四端口监听0，本轮目录不存在。历史目录未清理。

执行报告：.superpowers/sdd/2026-09-06-p6-2-local-isolation-rehearsal/execution/f6ad8000b8de470182641aabb5006e87.json。SHA256=a1a3604fae3962763430f391b4301fb5e0127cac782eb55078b77059d89c121c。

下一步需补充隔离定位/ADAS/DMS落库场景、明确视频声明口径、续租期限推进及释放接口/released_at证据后重验B。真实能力与网络证据缺口不能由合成测试替代。本轮未改产品或运行器代码，未commit/push。
