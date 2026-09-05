# R1–R6 最终完整回归与全分支复核

日期：2026-09-05（Asia/Shanghai）

结论：**APPROVED，仅限本地 R1–R6 整改与约定回归范围**。独立复核的三个问题全部关闭，当前未关闭 Critical/Important 为 0。完整 Java 默认回归、独立外部迁移、前端及两个既有私有测试入口均达到约定门禁；本结论不代表全部可选测试已执行，也不构成云端部署或真实设备准入。

## 版本与提交

- 分支：`codex/p6-2-composite-onboard-system`。
- 审阅基线：`39494f3e3e459a7c5ac842f73ad99967baf3119b`。
- 最终代码/测试版本：`cfd18b330b79b597ff73115305cdcb056a6f4a1a`。
- R1–R6提交依次为 `b38502d`、`3917c7e`、`5543054`、`a61e8eb`、`14ab97b`、`4518c67`。
- 本轮额外测试合同提交 `cfd18b3`：4文件+65/-13，无生产或迁移文件变更。修复包SHA-256为 `D57027F0B4972F43D3CE0504D601673A33E03F0C1D2218B079B609D083925F87`，提交前后相同，证明测试与复核快照一致。

## 新鲜验证结果

| 门禁 | 实际结果 | 适用边界 |
|---|---|---|
| Java完整reactor | 126测试类全部对应本轮126份XML；1042项中948实际通过、94条件跳过，0 failure/error，exit0 | 未排除首轮失败用例，未开启failure-ignore；跳过不计passed |
| 协议 / gateway / 模拟器 | 48/48、220/220、24/24 | 全部实际执行 |
| API | 750项中656通过、94条件跳过 | 所有99个测试类均有本轮报告 |
| 独立外部迁移 | PostgreSQL17.9 / PostGIS3.6.1；62项中61通过、1独立Docker条件跳过，exit0 | P6Composite59/59；DatabaseMigration2实际通过，包括external路径 |
| 完整前端 | 54文件、317/317，typecheck0、build0；191 modules | 保留>500kB chunk及插件耗时提示，未升级依赖或隐藏提示 |
| 既有私有迁移测试 | 43/43，exit0，stderr空 | 合成测试与既有私密资料只读校验；网络测试只使用本地stub |
| 既有Task12测试 | 6/6，exit0，stderr空 | 纯内存合成测试；不等于四设备真实验收 |

完整 Java 的开始UTC为 `2026-09-05T04:59:40.4612861Z`，结束为 `2026-09-05T05:11:48.7105541Z`。报告仅按本轮之后的XML机械统计，`missingSourceReports=0`。94条条件跳过涉及显式外部数据库、Docker/Testcontainers、容量等测试；其中部分由独立迁移套件补证，但不同门禁有重叠，不累加宣称唯一用例总数。

外部修复后运行UTC为 `04:53:27.8702214Z` 至 `04:57:56.4412202Z`。前端及私有测试在同日执行，后续修复仅涉及Java测试文件，它们所验证的前端与脚本内容未变化。

## 发现的问题与关闭证据

| 问题 | 真实失败证据 | 最小修复与复核 |
|---|---|---|
| E2E协议夹具不一致 | 四个GatewayOperationsFlow用例注册/会话失败 | 明确fixture使用实际2013协议，新增真实session接受2013/拒绝2019断言，生产fail-closed不变 |
| 固定历史lease | 同车双会话测试期望AUTHENTICATED，实际过期CLOSED | 使用runtime UTC即时签发有限180秒lease，并验证期限与独立owner/同终端接管隔离 |
| 当前JPA误测V19-only schema | 外部P6迁移用例有3个缺V21游标列error | 只将三个当前JPA用例按V19→合同fixture→V20→V21准备schema，断言head21；其他V19历史、catalog、约束和回滚测试保留 |

gateway定向16/16及后续完整220/220通过；三个JPA用例在真实PostgreSQL迁移59/59中通过。独立广域审阅及修复后定向复核将三项均裁定为ADDRESSED，新Critical/Important/Minor为0/0/0。

外部首轮另有测试前辅助脚本故障：PowerShell输出管道被后台PostgreSQL继承，导致启动等待EOF；未进入Maven，未当作业务测试失败。仅修复ignored helper的进程等待和安全清理，再用全新实例验证。失败历史未覆盖或删除。

## 冻结边界与清理

- V19/V20相对审阅基线零差异；V21自R1提交冻结后无变更。
- V19 SHA-256：`9E9D50BAA4DD44616AEFF4C4F6412D346F5CF9C2D4CD0704BF39A917D36E5775`。
- V20 SHA-256：`C1FED7C9D4F796F556F8B4E4BCE83E6CB01B54AC3DA1156D1D593A4F04435DD3`。
- V21 SHA-256：`EC6FEC3C8E38B9B4A48054E89220434B4B606F69147C01234E337E9758E33FA3`。
- 最终工作树测试Java/Node进程为0；所有本轮PostgreSQL实例已停止、随机端口关闭、精确data目录及合成密码文件删除；未操作其他数据库、服务或Docker资源。
- 本轮私有测试stdout/stderr未命中配置的敏感模式，仅证明捕获输出范围，不外推源资料中不存在秘密。
- 代码及测试修复精确提交，进度与本报告单独提交；原始日志与SDD过程证据继续保留在ignored本地目录，避免后续恢复重复工作。

## 持续限制和下一阶段

1. Task10异常路径ByteBuf释放时机、instance runner首个失败步骤/原因诊断两项历史Minor，本轮未精确复验；真实窗口前仍需验证。
2. Task11 runner在safe catch外加载library，缺失、ACL不可读、语法错误三类子进程stderr脱敏门禁未关闭。
3. Task12真实验收结果`count==4`及资源cleanup selector/match-count的0匹配、多匹配、越界fail-closed门禁未关闭；既有6项测试不能替代。
4. 历史敏感审计未清洗，本轮未获得历史UPDATE/DELETE/替换授权；未来只读盘点仅限聚合计数、时间和动作。
5. cloud V20全库盘点及云端/真实终端/真实流量准入保持**NO-GO**。附件媒体及完整GB业务不属于此次交付。

后续应先关闭上述实际运维/真实窗口门禁，再申请相应环境的操作授权。本轮没有push、merge、PR、部署或真实设备操作。

独立复核全文见 [最终独立复核记录](final-remediation-review-2026-09-05.md)。实施计划与各次失败/修复记录保留在本工作树的计划SDD目录。
