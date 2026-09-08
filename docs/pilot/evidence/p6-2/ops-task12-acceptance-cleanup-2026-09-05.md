# P6-2 Task12：验收数量与资源清理保护

## 当前结论

状态：`TASK12_ACCEPTANCE_CLEANUP_APPROVED_LOCAL`。实现、最终复验及独立规格/质量复核通过，未关闭 Critical/Important/Minor 为 0/0/0；本文不是云端上线或真实设备验收凭据。

本轮仅关闭运维脚本中“四台物理终端数量/映射校验”和“一次性资源清理选择器”的实现缺口。真实设备验收、云端 V20 全库只读盘点、部署和流量窗口不在本轮范围。

## 基线与范围

- 分支：`codex/p6-2-ops-safety-gates`。
- 入口 HEAD：`16e174214ff87bc5a90736a34eb3bdc1818a3f04`（Task11）。
- 新阶段基线：`master@ecbaf15a128c6dc6d965e409f2748f5da8d7f5d2`。
- Task11 已收口；不重写其证据，不修改已冻结 runner。
- Task12 旧通用 seed helper 和6项测试保留。旧测试名称中的 four-way 并非四条真实验收结果证据。

## 保护边界

1. 四项门禁按物理终端计数和逐项映射校验，同车双设备可以共享车辆及车载系统。
2. 清理默认只读，必须使用创建时记录的本轮 receipt，禁止根据现场名字猜测或重新认领。
3. 本地 endpoint、RunId、owner label、完整容器 ID、卷创建时间共同校验；运行中、共享、歧义、漂移或命令失败均停止。
4. 测试使用合成输入和进程 fixture，不调用真实 Docker 删除，不访问云端或真实资料。
5. 验收输入真实性依赖上游采集；receipt 依赖本机受控保存。Docker 卷无原子 compare-and-delete，最终删除存在本机并发管理员竞态边界，使用时必须独占维护。

## 测试与复核

| 门禁 | 实施侧证据 | 解释 |
| --- | --- | --- |
| 数量/映射 | 首组18/18 GREEN | 恰好4项、唯一物理ID、逐项映射、合法同车双设备 |
| 清理编排 | 新32项先RED，随后50/50 GREEN | 零/多匹配、共享、身份/状态漂移、部分删除与不重试 |
| 原生适配器/入口 | 新12项先RED，随后62/62 GREEN | 假Docker进程、JSON、固定参数、超时及安全出口 |
| 边界修正 | 69项中4个行为RED；72项中3个行为RED；74项中3个行为RED | Guid/Schema/时间/JSON、默认只读、BOM、bind、大小写及WinPS数组返回 |
| 初审前定向 | 74/74，0 failure/skip，exit0 | 未覆盖初审随后发现的两个反例 |
| 冻结兼容基线 | 旧private 6/6，0 failure/skip，exit0 | 旧seed helper合同未变；不是四台真实验收 |
| WinPS5.1语法 | 新4个ps1均0错误，exit0 | 文件保留UTF-8 BOM |

初始新库缺失仅为bootstrap RED（0测试、1加载失败），不是74项行为证明。一轮无BOM兼容错误曾造成69项仅35通过，另记为解析/兼容失败，未冒充有效产品RED；修复后新鲜完整组74/74。过程报告保存在本计划SDD目录。

控制器在同一初审快照上完整复验：74/74，exit0，stderr为0字节；5个代码/测试文件的工作树SHA与审阅快照逐项一致。冻结文件哈希漂移0，系统Temp的Task12 fixture目录计数0。

独立初审 C/I/M=0/2/1：标签键大小写漏检；bind引用卷底层目录的共享消费者未识别；缺少坏库parse失败的入口行为测试。前两项均用WinPS5.1纯内存反例证明可以错误调用2次模拟删除，未操作真实Docker。因此原74/74不能替代缺失反例，当时没有宣称收口，后续按下述两轮修复闭环。

修复裁定：标签键/值严格Ordinal；全库存有任何bind均拒绝自动清理，以避免将表面不同但可能是别名的路径判为不共享。这会保守阻断无关bind，须人工只读核对和单独清理流程，不提供自动绕过。

### 独立复核闭环

1. Fix1：补标签键及阶段漂移、共享bind/未知挂载库存的具体行为RED；随后I1 5/5、I2 12/12、完整92/92。早期两项dictionary fixture形状错误单独披露，不计为产品RED。独立复核确认I1/I2 ADDRESSED。
2. M1首版坏库测试虽然2/2通过，但只设fake日志变量、未接好fake PATH且receipt不存在，零日志不证明后续调用被阻断；复核仍保留Minor，未直接放行。
3. Fix2仅修测试：增加正控制先得到2/2 RED；有效合成receipt、显式fake PATH、正控制4次库存调用→精确重置日志→破坏库→零调用。两个focused用例2/2 GREEN，产品代码未改。
4. 最终定向复核：M1 ADDRESSED，规格PASS、质量PASS，C/I/M=0/0/0，无新增问题。初审、两轮定向报告及包保留在本计划SDD目录，可从暂停点追溯，不重复旧任务。

### 最终新鲜门禁

| 门禁 | 最终结果 |
| --- | --- |
| 控制器完整Task12（Fix2后） | 92/92，failure=0，skip=0，exit0，stderr=0字节 |
| 旧private兼容基线 | 6/6，exit0；Fix2未改产品或旧库，不再重复执行 |
| WinPS5.1.19041.6328 Parser | 4个执行脚本，errors=0；保留UTF-8 BOM |
| 审阅/测试一致性 | Fix2测试SHA一致，Fix1产品SHA未漂移 |
| 冻结边界 | Task11入口及旧Task12库/测试哈希漂移0；业务/迁移零变更 |
| 临时工件 | 最终完整组结束后Task12 fixture目录0 |

最终测试文件SHA-256：`F2D222849CE2900289B7B656133D89131CE8A46B30FFA4FCBA02042F5A8CE28F`。
定向复核包SHA-256：`7920BF398CD61330F6B2308AD67E4550FB403AF53E35651461C5EF099AFF3E8E`。
各测试组有重叠，不相加为唯一测试总数。完整命令为 `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\tools\ops-safety\tests\task12-safety.tests.ps1`，未指定过滤器。

## 交付与提交范围

版本化入口、两个库、测试/假进程fixture、手册链接、新手册、本报告、实施计划及 `progress.md` 共10个文件随本次本地提交固化；提交的父节点为本报告所列入口HEAD。仅作本地交付，不push、不merge、不部署。过程工件继续Git ignored，真实资料未纳入提交。

## 不变项

- 旧 Task12 library SHA-256：`DE1D346C04FCD7D77D13B616C7B6B6128CA6FE1F1BC7AC5848D9FB0912828B24`。
- 旧 Task12 tests SHA-256：`ED9A69D257FDD2994A1880FD918046816D9939E213B2AF0057B01550502E3CDE`。
- 业务代码、V19/V20/V21、真实资料、云端资源均禁止变更。

## 下一阶段

Task10 异常路径门禁及独立隔离演练仍需各自完成。任何云端/真实流量动作继续单独授权；Task12 门禁通过不能替代这些步骤。
