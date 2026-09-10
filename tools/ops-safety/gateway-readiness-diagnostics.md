# GW 演练门禁与安全诊断

GW 启动后先检查 `/actuator/health/liveness`（HTTP 200 且 status=UP），并通过现有完整进程归属校验、管理端口及设备 TCP 端口的唯一回环监听证明。只有三者成立才执行 WIRE。WIRE 成功完成注册/认证交互后，独立 `GW_READINESS` 阶段严格检查 `/actuator/health/readiness`，通过后才进入 TASK12。

不改变 GW 的已认证交互 readiness 条件。两个探测门禁各使用原有 120 秒等待上限；单请求 3 秒、读取超时 1 秒、响应体读取预算 3 秒、响应体最多 64 KiB，原统一运行时限不变。不以 liveness 代替最终 readiness。

## 报告字段

每次门禁只占用一条 Evidence，反复更新最后一次观测，不累计全部响应或原始日志：

- Phase：`GW_LIVENESS` / `GW_READINESS`（API/PG 沿用同一探测消费者时也记录各自门禁）。
- Status：PASS / FAIL；HttpStatus 为整数，0 表示该次未取得 HTTP 响应。
- ExceptionKind：NONE、GUARD、LISTENER_GUARD、NETWORK、BODY_LIMIT、BODY_TIMEOUT、RESPONSE_INVALID、HTTP_STATUS、HEALTH_NOT_UP、PROCESS_EXITED。
- Health：仅顶层及固定 GW 组件状态枚举，tcpListening / bufferWritable 布尔值，operationsApiStatus / RegistryStatus / IngressStatus / ProbeStatus 枚举，以及有限的 operationsApiOperation 固定值。未知字段、未知枚举和错误类型直接省略。不会保存路径、URL、凭据、业务身份、原始异常或响应体。
- StartupExceptionKind：NONE、JAVA_EXCEPTION、APPLICATION_START_FAILED、DATABASE、PORT_BIND。仅 GW 输出流启用分类；每次读取 2048 字符，跨块最多保留 256 字符后缀，仅在内存中匹配固定类别。NONE 表示未识别到类别，不证明启动无异常；类别也不等于经过根因核验的诊断。
- Attempts：观测次数。每条投影大小由固定键、短枚举和布尔值限定，HTTP 合同验证其序列化小于 2048 字符。

若 GW 启动入口失败，另存 `GW_START`、原 `REHEARSAL_RESOURCE_START_FAILED`、固定 ExceptionKind（WIN32 / ACCESS_DENIED / STARTUP_GUARD_FAILED）及已观测的 StartupExceptionKind。最多查看八层异常链，只取类别，不保存 message 或 stack trace。原 ticket 保留和清理规则不变。

完整演练结果由现有运行器保存至 ignored SDD execution 目录。新增诊断不授权修改 GW 安全规则、绕过归属证明或重跑历史实例。
