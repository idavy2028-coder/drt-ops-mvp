# P6-2 Task11 运维入口安全加载实施计划

> **执行者：** 使用 superpowers:subagent-driven-development 和 test-driven-development。用户已确认方向和执行方式，直接完成 RED→GREEN、独立复核与本地提交，不再重复请求选项。

**目标：** runner 的 library 缺失、读取权限拒绝或语法错误均只输出固定安全失败记录，退出1，不泄露路径/源文本/凭据，不执行后续迁移动作。

**架构：** 保持迁移业务库及已有参数合同不变，在 runner 内为 library 加载设置独立的最小失败出口。该出口不调用 library 中的任何函数，也不读取 manifest、不计算其hash、不解析异常原文。新增可版本化runner及独立黑盒子进程测试；现有私有入口在GREEN后按内容指纹同步。

**技术：** Windows PowerShell 5.1、原生子进程stdout/stderr捕获、NTFS文件ACL、合成library/manifest。

**依据：** 用户确认的“上线前运维安全门禁收口”方向；`docs/pilot/evidence/p6-2/final-remediation-review-2026-09-05.md` 中Task11残余项。本计划先交付Task11；Task12数量/清理保护、Task10异常路径及本地演练按阶段顺序继续，不能由Task11通过推定已完成。

## 全局约束

- 分支 `codex/p6-2-ops-safety-gates`，基线 `ecbaf15a128c6dc6d965e409f2748f5da8d7f5d2`；使用现有隔离工作树，保留已合并分支和历史证据，不改变其他工作树。
- 本任务不启动gateway、不开7611、不调用云端、不写真实manifest/身份文件、不读取真实令牌、不改Java业务/数据库迁移。
- 路径和原始错误只存在合成测试的受限临时输出中；报告仅计数、固定状态码和非敏感工件hash。
- ACL只施加于本任务创建的单个合成library文件，测试必须证明拒读确实生效，finally恢复原ACL后才精确清理；不能更改工作树、用户目录或真实library的ACL。
- 子进程隐藏窗口；stdout/stderr并行读取，等待有界；若超时只终止本测试创建的子进程，不按进程名批量停止。
- 新tracked脚本只包含通用逻辑和合成测试，真实private资料及54730字节业务library继续忽略。不得force-add私密文件。
- 本地提交精确限定文件；本轮无push/merge/deploy授权。

## 文件与归属

- 新增 `tools/ops-safety/Invoke-CloudOnboardSystemMigration.ps1`：从当前已审阅私有runner机械复制作为通用入口，保留Mode/ApiBaseUri/ManifestPath/PrivateSourceRoot/GatewayStopped/ApiTokenEnvironmentVariable合同，唯一业务差异为安全library加载。
- 新增 `tools/ops-safety/tests/runner-library-loading.tests.ps1`：不依赖真实library/manifest的黑盒测试入口，Windows PowerShell 5.1执行，输出总数/通过/失败及固定失败码。
- 修改ignored现有 `.private/cloud-deployment/p6-2-cloud-7fa38d0/Invoke-CloudOnboardSystemMigration.ps1`：在新入口GREEN后同步完全相同内容；先核对入口初始hash并保留备份。业务library和真实资料不变。
- 更新 `progress.md` 与本计划；本计划SDD目录保存RED/GREEN、复核、源工件hash和恢复入口。

### Task 1：关闭 Task11 library 加载门禁

**消费：** 现有runner的3个Mode及相邻固定文件名 `cloud-onboard-system-migration-lib.ps1`。

**输出：** 加载失败时stdout恰好一条记录：

```text
alias=batch phase=<Mode> step=load-library httpStatus=0 version=0 warnings=LIBRARY_LOAD_FAILED fileSha256=NONE
```

`<Mode>`仅为参数绑定已校验的DryRun/ApplyV19/ContractCheck之一。stderr必须为空，exit=1；不拼接异常Message、Exception、InvocationInfo、library路径或manifest hash。正常加载继续既有逻辑。

- [ ] **步骤1：复制无行为修改的旧runner到tracked通用路径并记录hash。** 不复制library、manifest或identity。先确认基线runner与通用runner正文一致。
- [ ] **步骤2：编写独立合成黑盒测试。** 每种Mode覆盖missing、NTFS拒读、syntax三类，至少9个负例；另对每种Mode使用有效合成library，证明正常路径可继续。

测试矩阵及准确断言：

| 输入 | 设置方法 | 必须验证 |
|---|---|---|
| 缺失library | 新临时目录只有runner，无相邻library | exit1、stdout固定行、stderr空、manifest字节不变、无后续执行标记 |
| ACL拒读 | 写合成library，仅对当前测试用户SID增加ReadData拒绝；独立探针确认读取失败 | 与missing相同；原DACL在finally恢复，未生效不能skip或计pass |
| library语法错误 | 合成文件包含未闭合字符串与唯一合成泄漏标记 | 与missing相同；stdout/stderr均不得出现标记、路径或源码片段 |
| 正常library | 提供同名合成函数实现，Invoke-OnboardMigration只写测试执行标记、无网络 | exit0、成功执行标记存在、stderr空；使用唯一合成token环境变量名 |

每个子进程必须显式传入合成ManifestPath/PrivateSourceRoot与numeric loopback URI，并将环境令牌变量名设置为本用例随机测试名，不能读取默认真实变量。用包含空格的合成路径检验原生参数引用。读取两个输出流后按整行精确比较；失败报告不可打印捕获的原文。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/ops-safety/tests/runner-library-loading.tests.ps1
```

- [ ] **步骤3：运行RED并保存准确失败原因。** 原runner在catch前dot-source，负例应缺安全行并泄露原始stderr；成功例需能运行，避免把测试基础设施错误当RED。
- [ ] **步骤4：实现最小安全出口。** 保留现有业务try/catch；将加载调用置于独立try，catch只使用PowerShell/.NET内建能力输出固定记录。

```powershell
$libraryPath = Join-Path $PSScriptRoot 'cloud-onboard-system-migration-lib.ps1'
try {
    . $libraryPath
} catch {
    [Console]::Out.WriteLine(
        ('alias=batch phase={0} step=load-library httpStatus=0 version=0 warnings=LIBRARY_LOAD_FAILED fileSha256=NONE' -f $Mode))
    exit 1
}
```

不能简单把dot-source移入旧catch却仍调用未加载的Format-OnboardMigrationResult。无需修改业务library或新增全局错误过滤器。

- [ ] **步骤5：运行同一黑盒GREEN。** 负例9/9和正常模式3/3实际通过，ACL必须是真实NTFS拒读；解析检查通过，子进程/ACL/临时目录清理可验证。
- [ ] **步骤6：同步私有入口并在隔离副本运行既有合成业务回归。** 先比较私有runner仍为初始hash，保存原始备份，再同步通用runner；核对两个入口SHA-256一致。原suite的非loopback用例也会经旧catch为默认manifest算hash，因此仅排除current_private_manifest还不足以证明零真实资料读取。必须把runner/library/tests原样复制到本轮唯一临时目录（SHA与源相同），创建相邻合成manifest；用现有TestNamePattern排除current_private_manifest，准确记录42项合成测试。

```powershell
$Task11SuiteRoot = Join-Path $PWD ('.tmp/task11-synthetic-business-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Task11SuiteRoot | Out-Null
foreach ($name in @('Invoke-CloudOnboardSystemMigration.ps1', 'cloud-onboard-system-migration-lib.ps1', 'cloud-onboard-system-migration-tests.ps1')) {
    Copy-Item -LiteralPath (Join-Path '.private/cloud-deployment/p6-2-cloud-7fa38d0' $name) -Destination (Join-Path $Task11SuiteRoot $name)
}
[IO.File]::WriteAllText((Join-Path $Task11SuiteRoot 'onboard-system-migration-manifest.json'), '{"synthetic":"TASK11"}', [Text.UTF8Encoding]::new($false))
powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Task11SuiteRoot 'cloud-onboard-system-migration-tests.ps1') -TestNamePattern '^(?!current_private_manifest_is_safe_and_valid$).+'
```

这些测试只向自己的loopback stub发请求；不得运行真实Apply或读取真实manifest。结果/脚本SHA保存后，校验完整临时目录路径及本轮GUID再精确清理。如果源脚本运行前已经漂移，保留他人改动并报告。

- [ ] **步骤7：独立复核与提交。** 审阅者检查三种失败的原生子进程证据、不会使用缺失library函数、原业务路径/参数不变、ACL恢复和范围白名单。重要问题清零后精确本地提交通用runner、测试、计划与进度；private镜像不进入Git。

## 完成标准

执行结果：Task11实现与独立复核已通过。最终加载矩阵12/12、独立harness自证3/3、隔离业务回归42/42；I1/I2/M1均关闭。新增harness自证用于证明超时/辅助异常回收与基础设施失败分类；不改变原三Mode业务合同。详细证据见 `docs/pilot/evidence/p6-2/ops-task11-safe-library-loading-2026-09-05.md`。

- 三类失败×三Mode实际GREEN，正常路径和既有合成业务回归通过。
- 私有入口与tracked入口hash一致、原始backup可恢复，真实library/manifest/identity未修改。
- 独立复核APPROVED；报告区分Task11完成与其他阶段门禁仍待完成。
- 保留流程证据，不清理旧分支的SDD目录。
