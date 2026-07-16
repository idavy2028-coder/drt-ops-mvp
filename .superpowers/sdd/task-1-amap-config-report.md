# Task 1 高德配置与地图能力抽象报告

## 状态

已完成最小可用实现，并按 TDD 完成红灯与绿灯验证。未访问外部账号，未提交真实高德 Key。

## 变更内容

- 后端新增 `AmapProperties`，绑定 `drt.map.amap.*` 配置，默认关闭，缺少 Web Service Key 时返回降级状态。
- 后端新增 `AmapClientConfig`，注册带基础 URL 和超时配置的高德 WebClient，Key 缺失不影响应用上下文启动。
- 后端新增 `MapProviderStatus` 和 `MapProviderException`，统一表达地图提供方可用性、降级原因和 GCJ-02 坐标系。
- 前端新增 `loadAmap(): Promise<AmapRuntime>`，读取 `VITE_AMAP_ENABLED`、`VITE_AMAP_JS_API_KEY`、`VITE_AMAP_SECURITY_JS_CODE`；禁用、缺 Key、脚本加载失败均返回降级结果，不向页面抛出崩溃异常。
- 前端新增 `AmapRuntime` 类型，并在 API 类型中补充通用 `MapProviderStatus`。
- `application.yml` 增加默认禁用的高德配置占位。
- `README.md` 增加本机 Key 配置说明、真实 Key 禁止提交、优先免费/基础配额、不得擅自启用付费服务，以及 GCJ-02 说明。

## TDD 记录

- 先新增后端测试 `AmapPropertiesTest` 和前端测试 `amap-loader.test.ts`。
- 红灯验证：
  - `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=AmapPropertiesTest test` 失败，原因为缺少 `AmapProperties` 和 `MapProviderStatus`。
  - `npm.cmd --prefix apps/admin-web run test -- amap-loader` 失败，原因为缺少 `./amapLoader`。
- 随后实现生产代码并重新运行验证。

## 验证结果

- `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=AmapPropertiesTest test`：通过，退出码 0。
- `npm.cmd --prefix apps/admin-web run test -- amap-loader`：通过，1 个测试文件、3 个用例通过，退出码 0。
- `npm.cmd --prefix apps/admin-web run typecheck`：通过，退出码 0。

## 风险与后续

- 当前只建立配置、状态与 loader 抽象，尚未接入真实高德 Web Service 查询、地理编码或前端地图控件。
- loader 使用高德 JS API v2.0 和 `AMap.PlaceSearch,AMap.Geocoder` 插件；后续真实页面接入时需要按实际功能补插件清单。
- 本地工作树中保留控制器新增的未跟踪计划文档 `docs/superpowers/plans/2026-07-16-amap-virtual-stop-pilot-preparation.md`，本任务未修改也不会纳入提交。
