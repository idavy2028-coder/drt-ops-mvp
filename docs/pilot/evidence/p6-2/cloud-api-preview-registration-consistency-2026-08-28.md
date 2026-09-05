# P6-2 云端 API preview / registration-verify 一致性复验

日期：2026-08-28

范围：terminal-01 单台只读 dry-run；不执行身份纠正 Apply，不启动 gateway。

## 结论

本轮一致性门禁通过：

- identity-correction preview 仅返回 `vehicleIdentifier`，状态为 `PLANNED`，终端版本为 4；
- 内部只读 registration-verify 返回 HTTP 200、`approved=false`、`VEHICLE_IDENTIFIER_MISMATCH`；
- 两条路径对同一真实资料的结论一致：唯一差异为绑定车辆标识；
- 本轮未执行 Apply，未新增终端或车辆标识纠正审计，gateway 保持停止，TCP 7611 无监听。

本结论仅批准进入车辆标识受控纠正的下一门禁，不代表真实终端注册、鉴权、位置或报警链路已经通过。

## 根因与测试先行修复

最初云端现象为 preview 返回 `NONE`，但 registration-verify 返回车辆标识不一致。API 本地合同修复并部署后，该现象仍可复现，证明不能把问题简单归因于 Java 比较逻辑。

私密编码诊断最终确认：Windows PowerShell 5.1 对 UTF-8 无 BOM 私密文件使用默认系统代码页读取，造成非 ASCII 车辆标识被错误解码。脱敏长度证据如下：

- 默认读取：字符数 8、UTF-8 字节数 12；
- 明确 UTF-8：字符数 8、UTF-8 字节数 10；
- 默认读取值与旧 preview 请求一致；
- 明确 UTF-8 值与真实 0x0100 私密捕获及 registration-verify 请求一致。

修复按 RED/GREEN 执行：

- RED：显式 UTF-8 JSON/CSV 读取函数缺失，2 项预期失败；
- GREEN：新增严格 UTF-8 无 BOM JSON/CSV 读取，非法字节 fail-closed；identity-correction 的 intake、secret JSON 和 preload manifest 全部改用该入口；
- identity-correction 私有测试 20/20；preload 私有测试 79/79。

第一次复验还遇到 intake CSV 被可写方式占用，读取门禁以 `UTF8_INPUT_FILE_INVALID` 停止。关闭占用程序、确认文件可读后再次执行，preview dry-run 通过。该次失败未登录成功、未调用 preview、未写业务数据。

## API 修复与 V18

独立 GREEN 复核在部署前闭环了三类一致性问题：

1. preview/apply 与 registration-verify 共用固定宽度 BCD 手机号等价规则；只修车辆业务身份时保留后台规范手机号和协议值，终端聚合版本推进一次。
2. 应用层按双方各自协议的持久化 canonical identity 做语义冲突检查。
3. V18 新增 `terminal_phone_identity` 非空唯一约束，作为并发最终门禁；已知唯一约束安全映射为 HTTP 409，未知完整性错误原样抛出。

验证证据：

- JT 协议 48、gateway 141、API 500、模拟器 11，共 700 项，失败 0、错误 0、条件跳过 44；
- 真实临时 PostgreSQL 迁移测试 3/3：V0→V18、V17→V18 回填、已有语义重复时事务回滚且不留下 V18 列或 Flyway 记录；
- 一次全 reactor 冷启动期间 gateway burst 时序失败；原用例独立复跑及随后 gateway 141/141 全量均通过，未修改 gateway 代码或放宽断言。

## 发布与部署证据

- 发布：`p6-2-preview-consistency-169104a`；
- 源码清单：240/240，摘要 `169104acdedaf98df5ae21cd42f08fa5e3443653af2b73b04db816f826e02fb6`；
- API JAR SHA-256：`72cce99b1a4f22fa8590b2b24f90fb0dbf3cf538dd388a5032dbbfa36ca717cc`；
- 镜像 ID：`sha256:88361bb6e503240ed28ea3472608fd9bee464481aa14c1aec5a9c11ee0cafd11`；
- 离线归档 SHA-256：`794faf51b77f4ec794e6db82e42ac9be97e3838ab7bea190131285c522f530b5`；
- 发布 SHA 8/8、归档单条目、私密高敏扫描命中 0；唯一 4 字符 ASCII notes 命中按低熵通用备注裁决为假阳性；
- 云端上传目录权限 700，发布文件权限 600，上传载荷 SHA 5/5；
- 部署前 V17 数据库备份 234,873 字节、201 个归档条目，校验通过；
- Compose dry-run 仅包含 API，gateway 计划数 0；
- 部署后 API health `UP`、restart 0、Flyway V18、终端手机号规范化身份缺失 0、重复组 0。

运行容器内 `/app/app.jar` SHA 与发布 JAR一致，API 无文件挂载覆盖。

## 最终只读门禁

- dry-run 私密状态文件：`identity-correction-preview-consistency-r1-20260828.json`；
- 状态文件 SHA-256：`d6ede5cf05b8163c7e3ec8a3c2de1e2a527c5d35a2560439efa098926004fe8d`；
- 项目数 1，安全别名 terminal-01，模式 `DRY_RUN`，唯一变化字段 `vehicleIdentifier`，版本 4；
- 数据库与请求均为 8 字符，但 UTF-8 字节数分别为 12 与 10；精确、去空白和 NFC 比较均不相等，无首尾空白，无其他车辆占用请求标识；
- registration-verify：HTTP 200、`approved=false`、`VEHICLE_IDENTIFIER_MISMATCH`；
- 终端 4、PENDING 4、注册 0、鉴权 0、最大版本 4；
- 当日 `JT_TERMINAL_IDENTITY_CORRECTED` 0、`VEHICLE_IDENTIFIER_CORRECTED` 0；
- API 使用新镜像，gateway `created|false|0`，TCP 7611 监听 0。

## 下一门禁

等待明确指令后，才可执行：当前车辆标识私密备份 → terminal-01 单台 Apply → 只读 registration-verify `APPROVED` → gateway 单台真实注册复验。不得绕过单台验证，也不得提前放开 terminal-02～04。
