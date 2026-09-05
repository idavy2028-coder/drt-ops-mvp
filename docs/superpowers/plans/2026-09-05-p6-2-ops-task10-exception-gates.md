# P6-2 Task10 异常路径门禁实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development。按测试先行执行；本地复核后继续用户已授权的隔离演练，不插入用户审阅暂停点。

**Goal:** 关闭模拟终端 ByteBuf 异常释放和多设备 runner 首失败诊断两个历史缺口。
**Architecture:** 在现有报文构造资源所有权内使用finally，实例与report多设备路径共享固定安全诊断；不改变协议、业务角色或单次运行规则。
**Tech Stack:** Java21、JUnit5、Netty、Maven、Windows PowerShell。
**Spec:** `docs/pilot/evidence/p6-2/final-remediation-review-2026-09-05.md` 的Task10残余项及已批准 `docs/superpowers/plans/2026-08-29-p6-2-composite-onboard-system.md` Task10。用户本轮明确授权修复后继续隔离演练。

## Global Constraints

- 工作树 `D:\codex-projects\.worktrees\p6-2-composite-onboard-system`，分支 `codex/p6-2-ops-safety-gates`，入口HEAD `2c0d274437db5e91a0e30579d03456b5c7e01c1c`。
- 不改API/gateway生产业务、数据库V19/V20/V21、Task11/12已冻结工具、真实资料；不推送、不部署、不连接真实设备。
- 当前任务只允许合成身份和loopback测试；附件真实链路不启用。
- Maven串行；每个运行使用工作树内TEMP/TMP/java.io.tmpdir，禁止依赖陈旧Surefire结果或把skip算pass。
- 异常消息、cause、stack trace、report不得新增终端身份、车牌、token、原始报文/异常正文泄漏。
- 开发与独立复核完成后，控制器继续本地隔离演练的环境核对和执行；环境操作仅针对本轮新建资源，现有容器/数据库不改变。

### Task 10: 修复两个异常路径并补可重复的RED/GREEN

**Files:**
- Modify: `tools/jt-terminal-simulator/src/main/java/com/idavy/drtops/jtsimulator/SimulatedTerminal.java`
- Modify: `tools/jt-terminal-simulator/src/main/java/com/idavy/drtops/jtsimulator/ScenarioRunner.java`
- Modify: `tools/jt-terminal-simulator/src/test/java/com/idavy/drtops/jtsimulator/ScenarioRunnerTest.java`
- Create: `tools/jt-terminal-simulator/src/test/java/com/idavy/drtops/jtsimulator/SimulatedTerminalResourceTest.java`

**Interfaces:**
- 保持现有public构造器、`sendRegistration/sendAuthentication/sendPosition`、`ScenarioRunner.run(Scenario)`与static `run(Scenario, endpoint)`接口兼容。
- 首个失败诊断包含zero-based step index、固定action和固定reasonCode；实例仍抛IllegalStateException兼容类型，不返回部分成功，不附带原始cause。
- 报告式multi路径同样不输出RuntimeException.getMessage()，保留FAIL/SKIP及现有安全连接别名。不改legacy单终端输出范围。

- [ ] **Step 1: 先核对代码和运行旧基线**

当前 `sendRegistration` 在分配body后调用可抛异常的writeFixed，release只在正常路径；sendAuthentication和sendPosition有相同所有权形状。实例runner调用executeMulti(false)后只检查failed并抛泛化消息，首失败步骤被丢弃；report多设备unexpected路径直接拼原异常正文。

Maven位于 `C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.4\plugins\maven\lib\maven3\bin\mvn.cmd`；JDK位于 `C:\Program Files\Java\jdk-21.0.10`。先确认版本，然后仅执行旧ScenarioRunnerTest基线。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$taskTemp = Join-Path $PWD '.tmp/task10-exception-gates'
New-Item -ItemType Directory -Path $taskTemp -Force | Out-Null
$env:TEMP = $taskTemp
$env:TMP = $taskTemp
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.4\plugins\maven\lib\maven3\bin\mvn.cmd' -q -pl tools/jt-terminal-simulator -am '-Dtest=ScenarioRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Djava.io.tmpdir=$taskTemp" test
```

如果Maven因沙箱读取repo/settings失败，按工具机制申请升级该明确命令，不扫描或打印凭据。测试失败先分类，环境失败不算产品RED。

- [ ] **Step 2: 写资源释放反例并证明真实RED**

允许增加最小package-private body分配依赖，例如 `IntFunction<ByteBuf>`，public构造器默认仍使用Unpooled.buffer；只用于真实资源分配，不增加仅测试调用的cleanup API或新依赖。

```java
// 捕获的是真实ByteBuf；故障发生在当前业务方法内部，不靠GC或日志猜泄漏。
ByteBuf allocated = Unpooled.buffer(0, 0);
assertThrows(IndexOutOfBoundsException.class, terminal::sendPosition);
assertEquals(0, allocated.refCnt());
```

先写测试；如初始因缺构造器编译失败，只做行为保持的最小分配依赖接线，再运行并记录真实`refCnt=1`断言RED，随后才能修释放。覆盖超长manufacturer/model/terminalCode触发的registration验证失败，2019 authentication写入失败、position写入失败及成功/断线写失败的释放。使用真实小容量ByteBuf故障，不引入Mockito或变更POM。异常退出后所有本方法持有body须refCnt=0，不双重释放，异常仍传递；不修改协议字段和默认值。

- [ ] **Step 3: 写首失败与脱敏反例并证明RED**

```java
IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.run(scenario));
assertTrue(failure.getMessage().contains("step=1"));
assertTrue(failure.getMessage().contains("action=REGISTER"));
assertTrue(failure.getMessage().contains("reason="));
assertFalse(failure.getMessage().contains("TASK10_SYNTHETIC_SECRET"));
assertNull(failure.getCause());
```

按实际场景给出字面预期：REGISTER无连接、连接失败/注册拒绝、control adapter抛带合成敏感sentinel的RuntimeException。固定原因至少区分缺连接、缺control adapter、wire步骤失败和意外异常；不能简单照抄getMessage。用后续动作计数/平台记录证明首失败后不再执行，首失败位置不被后续skip覆盖；已打开连接被关闭，实例再次调用仍single-use拒绝。static多设备report保持相同失败位置与安全原因，不输出cause/token/body/identity。尽量复用现有FakePlatform和控制回调，不扩建框架。

- [ ] **Step 4: 最小GREEN实现**

```java
ByteBuf body = bodyAllocator.apply(initialCapacity);
try {
    // 原构造和拷贝逻辑不改变。
} finally {
    body.release();
}
```

首失败只捕获一次，使用固定安全码，不持久保存原异常；实例异常和multi report消费同一安全诊断。代码安全关键处添加简短中文注释；不要重构不相关路径。

- [ ] **Step 5: 定向完整组与自检**

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.4\plugins\maven\lib\maven3\bin\mvn.cmd' -q -pl tools/jt-terminal-simulator -am '-Dtest=ScenarioRunnerTest,SimulatedTerminalResourceTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Djava.io.tmpdir=$taskTemp" test
```

记录本次XML实际测试数/failure/error/skip和命令exit；自检看真实资源副作用、首错保真、敏感sentinel不外泄。不要反复跑无关Java/前端全套。当前实现者独占Maven；控制器只并行做只读环境准备。

- [ ] **Step 6: 独立复核与连续交接**

实施者不stage/commit，报告写本计划SDD的task-10-report.md；控制器打包diff，独立规格/质量复核，必要fix/scoped re-review。通过后由控制器做三模块矩阵及本地隔离演练，补独立资源/迁移/四条结果门禁证据并统一本地提交；不等待额外用户审阅。

## 后续隔离演练边界

使用全新合成四物理终端及独立数据目录/数据库；不得复用真实private manifest。Docker若可安全使用，遵守Task12 receipt/label/数量/无bind门禁；若当前库存或daemon条件不允许，不能停止现有资源、放宽Task12或重启Docker来过门禁。可使用已安装本地PG17的新实例及独立sentinel/PID/端口/目录保护，具体执行步骤由控制器在只读环境核对后固化到独立演练简报。无论哪种路径，真实云端/真实设备验收继续NO-GO。
