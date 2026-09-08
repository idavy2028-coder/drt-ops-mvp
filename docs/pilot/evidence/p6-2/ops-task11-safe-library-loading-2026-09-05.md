# P6-2 Task11 运维入口安全加载验收

日期：2026-09-05。

结论：**APPROVED，仅限 Task11 library 安全加载门禁**。独立复核发现的 I1/I2/M1 均已关闭，当前 Critical/Important/Minor 为0/0/0。Task12、Task10和后续隔离演练仍需分别完成。

## 基线与实现

- 分支：`codex/p6-2-ops-safety-gates`，基线为合并后的 `master@ecbaf15a128c6dc6d965e409f2748f5da8d7f5d2`。
- 版本化通用入口：`tools/ops-safety/Invoke-CloudOnboardSystemMigration.ps1`。入口需与既有 `cloud-onboard-system-migration-lib.ps1` 放在同一部署目录；真实library/manifest/identity不纳入此Git提交。
- 现有私有入口在原hash守卫和备份后已同步。参数与业务逻辑不变；唯一新增行为是独立捕获library加载失败。
- library缺失、真实NTFS拒读或语法错误时，出口不依赖library formatter/hash/helper，不访问manifest或进入迁移逻辑；stdout恰好为下列一行，stderr为空，exit=1：

```text
alias=batch phase=<已验证的Mode> step=load-library httpStatus=0 version=0 warnings=LIBRARY_LOAD_FAILED fileSha256=NONE
```

Mode覆盖DryRun、ApplyV19、ContractCheck，不包含任意异常原文、路径或源码片段。

## RED→GREEN 证据

| 验证 | 原runner | 最终runner |
|---|---|---|
| 三类失败×三Mode | 9项均明确WRONG_STDOUT_COUNT失败 | 9/9通过 |
| 合法合成library×三Mode | 3/3通过 | 3/3通过 |
| 独立harness自证 | 3/3通过 | 3/3通过 |
| 既有业务回归的隔离副本 | 单独执行 | 42/42通过 |

黑盒测试以原生Windows PowerShell子进程运行；ACL用同一明确Path绑定探针证明READ_OK/0→仅UnauthorizedAccessException得到READ_DENIED/23→恢复后READ_OK/0。三个fixture逐一记录拒读和恢复，不能以任意非零退出或某一次恢复成功替代。

harness额外验证真实慢进程超时回收、输出捕获后注入异常仍回收，以及manifest后验读取失败被明确标为基础设施错误。主等待30秒、输出等待另30秒、每次终止确认5秒，均有界；恢复或进程退出未确认时不删除现场，不按进程名批量终止其他进程。

## 隔离业务回归与读取边界

既有43项中的current_private_manifest会读取真实资料；非loopback用例还会在旧业务catch中为默认manifest计算hash。因此只筛选42项并在原目录运行，不足以宣称零真实资料读取。本次早期运行存在该checksum读取路径，未改写或输出真实内容，不作为最终隔离证据。

最终将runner、业务library和原tests按相同SHA复制到唯一临时suite，放置相邻合成manifest，用原TestNamePattern排除current_private_manifest，执行42/42。输出stderr为空、敏感模式命中0、合成manifest hash不变；仅使用自身loopback stub，未访问外网或执行真实Apply。副本与进程在证据采集后安全清理。

## 工件与回退

- 原runner及备份SHA-256：`1672021E0A9F6E40FF50D5B6DFD158D071C26C5B3568D5C9E15117123DC7A16B`。
- 通用/私有runner同步SHA-256：`8C99D07F66F0A95E78FCBFAC82F29F2C1585B1C58DD607E9672217A07613C4C4`。
- 业务library保持：`5EBF0FBB2DAD0F512110F6A9628CD4A5E6F6A7E96B304AFE163C7C79F0A83E3E`。
- 独立测试脚本SHA-256：`22BEE46DC2BC01DF31717A15C4B08E6EF8774988C0CD94785AE0B8802169A165`。
- 原入口备份留在ignored私有目录，可用于受控回退；真实资料未修改。过程RED、GREEN、隔离回归和独立复核保存在本计划SDD目录。

复验入口：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/ops-safety/tests/runner-library-loading.tests.ps1
```

本轮无Java业务/迁移改动，未重复已验证的无关Java或前端回归；未推送、部署、启动gateway、开放7611或接入真实终端。下一项为Task12验收数量与资源清理门禁。
