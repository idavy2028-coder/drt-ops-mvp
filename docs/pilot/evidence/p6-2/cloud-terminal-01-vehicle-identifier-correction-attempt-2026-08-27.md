# P6-2 terminal-01 车辆标识受控纠正尝试记录（2026-08-27）

## 结论

本次受控纠正**未执行写入**。测试先行补齐了单台 dry-run、精确字段范围和按当前 plan terminalCode 映射门禁；但云端出现不可安全裁决的矛盾：数据库私密哈希比较确认 terminal-01 仅 `vehicleIdentifier` 不同，身份纠正 preview 却返回 `ChangedFields=NONE`，而只读注册验证仍返回 `VEHICLE_IDENTIFIER_MISMATCH`。

因此 dry-run 门禁未通过，流程在备份、Apply 和真实注册复验前停止。gateway 全程保持关闭，TCP 7611 无监听。

## RED/GREEN

- 原私有脚本在 DryRun 提供 Alias 时仍遍历全部 4 台，也没有精确 changed-fields 范围守卫。新增测试首次形成 2 项预期失败。
- GREEN 增加单台 DryRun 选择和 `Test-CloudIdentityCorrectionScope`，可拒绝额外字段、缺失字段及重复/未知字段。
- 真实 dry-run 暴露旧映射依赖 preload manifest vehicleId/车牌反向查找终端，可能把安全别名映射到错误终端。新增 terminalCode 精确映射测试先形成 1 项 RED，再改为按当前 plan terminalCode 大小写敏感唯一匹配，并独立检查活动绑定。
- 私有测试最终全部通过；改动只涉及 Git 忽略的私有纠正脚本和测试，没有修改生产 API 或数据库。

## 云端只读证据

- 首次严格 dry-run 在 `PREVIEW_CORRECTION` 被字段范围守卫阻止，没有状态写入。
- 修复 terminalCode 映射后，strict dry-run 仍被阻止；脱敏事件为 `ChangedFields=NONE|Status=SCOPE_MISMATCH|Version=4`。
- 使用正式 provisioning-plan 在本地计算七个期望字段摘要，服务器在 mode 600 私密文件中导出当前活动绑定行并逐字段哈希比较；terminalCode 摘要唯一匹配 1 条，结果为 `ChangedFields=vehicleIdentifier|BindingStatus=ACTIVE|Version=4`。
- 使用同一 plan 构造 JT808-2019 固定宽度消息头身份和六字段私密注册请求，通过服务凭证调用 `/internal/jt-gateway/registrations/verify`；HTTP 200，但结果为 `Approved=false|ReasonCode=VEHICLE_IDENTIFIER_MISMATCH`。
- 最近一小时公开安全审计 action 只有一次登录失败，没有 `VEHICLE_IDENTIFIER_CORRECTED` 或 `JT_TERMINAL_IDENTITY_CORRECTED`，排除本轮脚本已写入或其他可见纠正操作。
- 本地已部署 API 发布 JAR与当前 target JAR中的 `TerminalManagementService`、`TerminalController` 和 `IdentityCorrectionRequest` 类哈希一致，且均包含车辆标识纠正门禁；不是简单的 API 镜像缺少该功能。

## 未执行事项

- 未生成纠正前数据库备份：流程按顺序在 dry-run 门禁失败后停止。
- 未调用 identity-correction Apply。
- 未修改车辆、终端、绑定、版本或审计事实。
- 未启动 gateway，未开放 7611，未执行真实注册复验。

## 安全终态

- gateway：`created`、Running=false，新 exception-boundary 镜像保持。
- TCP 7611：监听 0、连接 0；API 继续健康。
- 服务器本窗口私密目录已加入注册验证请求/响应和哈希比较导出，共 10 个文件，全部 mode 600；清单 SHA-256 为 `90706462…a002`。公开文档不包含字段值、捕获值或它们的独立摘要。

## 下一门禁

必须先解释并修复“identity-correction preview 返回 NONE、同一 terminalCode 的数据库哈希比较仅车辆标识不同、registration verify 又拒绝车辆标识”这一三方不一致。建议下一轮测试先行增加同一真实数据快照下 `previewIdentityCorrection` 与 `verifyRegistration` 的一致性合同，或在 API 内增加只返回字段名/原因码的只读诊断接口。该一致性门禁通过前，不允许绕过 preview 直接 Apply，也不重复开启真实终端窗口。
