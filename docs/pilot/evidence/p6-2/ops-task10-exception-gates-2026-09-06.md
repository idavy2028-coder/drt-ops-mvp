# P6-2 Task10：异常路径门禁验收记录

## 结论

状态：`TASK10_EXCEPTION_GATES_APPROVED_LOCAL`。两个历史异常路径缺口完成 RED→GREEN、三模块矩阵与独立规格/质量复核；未关闭 Critical/Important/Minor 为 0/0/0。

本结论只覆盖本地合成与回环测试，不代表本地隔离演练、云端部署或真实设备验收完成。

## 基线与范围

- 分支：`codex/p6-2-ops-safety-gates`。
- 入口 HEAD：`2c0d274437db5e91a0e30579d03456b5c7e01c1c`（Task12）。
- 仅修改 `ScenarioRunner`、`SimulatedTerminal`、`ScenarioRunnerTest`，新增 `SimulatedTerminalResourceTest`；不改 API/gateway 业务、POM、V19/V20/V21、Task11/12 或真实资料。
- JDK 21.0.10、Maven 3.9.11；TEMP/TMP/java.io.tmpdir 均在工作树 `.tmp`，Maven 串行。

## RED→GREEN

### ByteBuf 所有权

旧代码在 registration/authentication/position 的 body 构造异常时，release 只存在于正常路径。测试使用真实可释放 ByteBuf；首次夹具中的零容量 `EmptyByteBuf` 不可释放，明确作为测试基础设施错误剔除，不冒充产品 RED。

修正夹具后，registration 的三个固定字段超长，以及 2019 authentication/position 的写入失败，真实观察到 `refCnt=1`；最小 try/finally 修复后资源组 5/5。正常发送和断线写失败同样验证当前方法持有的 body 为 `refCnt=0`，异常仍传播，无双重释放。

### 首个失败诊断

原 instance multi runner 只给出泛化失败；report wire 失败保留带 serial 的自由文本，unexpected 路径拼接异常类和 message。新增用例在实现前 29 项中出现6个断言失败；缺 control adapter 的独立mutation用例也先失败。

修复后首次失败只保存 zero-based step、枚举 action 和固定 reason；实例继续抛兼容 `IllegalStateException`，无 cause、不返回部分结果。report 的 FAIL/SKIP 保留，但不输出原始异常正文、合成身份、token 或 body；后续步骤不执行，已打开连接被关闭，runner 仍为 single-use。

历史 RED 的原始 Maven stdout 未单独持久化；实施者在执行时向控制器交接了真实失败数与断言值，并在过程报告中逐项记录。独立复核没有回滚生产修复重跑 RED，明确将历史执行时序证据由控制器承接；没有因此伪称审阅者独立复现 RED。

## 最终新鲜门禁

| 门禁 | 结果 |
| --- | --- |
| Task10 定向 | ScenarioRunner 29 + Resource 5 = 34/34，0 failure/error/skip，exit0 |
| 三模块矩阵 | simulator 34 + gateway runtime 13 + composite API 5 = 52/52，0 failure/error/skip，exit0 |
| 产物新鲜度 | 四个 Surefire XML 均晚于矩阵启动时间，Fresh=true |
| 代码一致性 | 四个文件 SHA 与独立审阅快照一致；working/cached差异一致 |
| 差异检查 | `git diff --cached --check` exit0 |

各门禁包含重叠测试，不相加为唯一总数。三模块命令使用 `-Dsurefire.failIfNoSpecifiedTests=false`，各目标模块均从新鲜 XML 按 suite 名校验，不能用“其他模块无指定测试”替代目标 suite。

## 独立复核

独立审阅完整读取四文件差异包，SHA-256 为 `74FE4B21C867BF14714B045FF7C905BB228199456A883D8966F1C38704AB66EB`；核对 34 个 testcase 名称及控制器三模块矩阵产物。规格 PASS、质量 PASS，C/I/M=0/0/0。

审阅重点确认：public接口和协议字段不变；package-private allocator 只为真实资源测试；首错不会被 SKIP 覆盖；诊断结构不保存原异常；关闭路径保持。审阅者未并行运行 Maven，避免 target 输出竞争。

## 隔离演练交接

本机 Docker 库存只读核对为44容器、16个bind mount、36个volume。Task12门禁规定全库存任何bind均拒绝自动清理，因此后续演练选用新建的 native PostgreSQL 17.9/PostGIS 实例，不改现有Docker资源，也不把native清理写成Task12 Docker清理通过。

最新API需要V21；空库V2的两辆合成demo车会触发V20 dispatch门禁。演练必须在专用新库中先迁到V19，精确将这两条固定合成seed设为不可调度，再原样执行V20/V21；该fixture绝不可用于真实数据库。四个新终端注册后仍为PENDING，必须由真实loopback管理API激活后再鉴权，现有CLI不能跳过这一衔接。

下一步按独立预检报告创建本轮 native 资源、真实loopback API/gateway及四终端接线；任何失败停止后续业务动作并按本轮收据清理。真实云端和真实设备继续NO-GO。
