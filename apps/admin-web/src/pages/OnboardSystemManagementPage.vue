<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  applyOnboardConfiguration,
  getOnboardSystem,
  listOnboardSystems,
  previewOnboardConfiguration
} from "../api/onboardSystems";
import type {
  OnboardConfigurationInput,
  OnboardDeviceView,
  OnboardOperatingMode,
  OnboardRole,
  OnboardSystemDetail,
  OnboardSystemSummary,
  ProtocolProfiles
} from "../api/types";
import { authStore } from "../auth/authStore";
import { feedbackStore } from "../stores/feedbackStore";

interface EditableDevice {
  deviceAlias: string;
  networkMode: "DIRECT_CELLULAR" | "SHARED_LAN_CLIENT";
  roles: OnboardRole[];
  protocolProfiles: ProtocolProfiles;
}

interface ConfigurationDraft {
  vehicleId: string;
  version: number;
  operatingMode: OnboardOperatingMode;
  devices: EditableDevice[];
  reason: string;
  confirmed: boolean;
}

const roleOptions: readonly OnboardRole[] = [
  "DISPATCH", "LOCATION_PRIMARY", "LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO", "WAN_UPLINK"
];
const systems = ref<OnboardSystemSummary[]>([]);
const selectedVehicleId = ref("");
const detail = ref<OnboardSystemDetail | null>(null);
const draft = ref<ConfigurationDraft | null>(null);
const loading = ref(false);
const applying = ref(false);
const pageError = ref("");
const notice = ref("");
let selectionRequest = 0;

const canManage = computed(() => authStore.has("TERMINAL_MANAGE"));
const canApply = computed(() => Boolean(
  canManage.value
  && draft.value
  && detail.value
  && !applying.value
  && draft.value.vehicleId === selectedVehicleId.value
  && draft.value.version === detail.value.version
  && draft.value.reason.trim()
  && draft.value.confirmed
));

onMounted(loadSystems);

async function loadSystems(): Promise<void> {
  if (applying.value) return;
  loading.value = true;
  pageError.value = "";
  notice.value = "";
  try {
    const page = await listOnboardSystems(0, 20);
    const previousSelection = selectedVehicleId.value;
    systems.value = page.items;
    const nextSelection = page.items.find((system) => system.vehicleId === previousSelection)
      ?? page.items[0];
    if (nextSelection) {
      await selectSystem(nextSelection.vehicleId);
    } else {
      selectedVehicleId.value = "";
      detail.value = null;
    }
  } catch {
    systems.value = [];
    detail.value = null;
    pageError.value = "车载系统数据暂时不可用，请稍后重试";
    feedbackStore.error(pageError.value);
  } finally {
    loading.value = false;
  }
}

async function selectSystem(vehicleId: string): Promise<void> {
  if (applying.value) return;
  const changedSelection = selectedVehicleId.value !== "" && selectedVehicleId.value !== vehicleId;
  if (changedSelection && draft.value) {
    draft.value = null;
    notice.value = "草稿已失效，请重新开始编辑";
  }
  const request = ++selectionRequest;
  selectedVehicleId.value = vehicleId;
  detail.value = null;
  pageError.value = "";
  try {
    const next = await getOnboardSystem(vehicleId);
    if (request === selectionRequest && selectedVehicleId.value === vehicleId) {
      if (draft.value?.vehicleId === vehicleId && draft.value.version !== next.version) {
        draft.value = null;
        notice.value = "配置版本已变化，请重新开始编辑";
      }
      detail.value = next;
      replaceSummaryFromDetail(next);
    }
  } catch {
    if (request === selectionRequest) {
      draft.value = null;
      pageError.value = "车载系统详情暂时不可用";
      feedbackStore.error(pageError.value);
    }
  }
}

function beginEditing(): void {
  if (!detail.value || !canManage.value) return;
  notice.value = "";
  pageError.value = "";
  draft.value = {
    vehicleId: detail.value.vehicleId,
    version: detail.value.version,
    operatingMode: detail.value.operatingMode,
    devices: detail.value.devices.map((device) => ({
      deviceAlias: device.deviceAlias,
      networkMode: device.networkMode,
      roles: [...device.roles],
      protocolProfiles: copyProfiles(device.protocolProfiles)
    })),
    reason: "",
    confirmed: false
  };
}

function cancelEditing(): void {
  draft.value = null;
  notice.value = "";
}

async function applyConfiguration(): Promise<void> {
  if (!canApply.value || !draft.value) return;
  const activeDraft = draft.value;
  const capturedVehicleId = activeDraft.vehicleId;
  const capturedVersion = activeDraft.version;
  const capturedSelectionRequest = selectionRequest;
  applying.value = true;
  pageError.value = "";
  notice.value = "";
  let latest: OnboardSystemDetail;
  try {
    latest = await getOnboardSystem(capturedVehicleId);
  } catch {
    if (draft.value === activeDraft) draft.value = null;
    detail.value = null;
    pageError.value = "车载系统详情暂时不可用";
    feedbackStore.error(pageError.value);
    applying.value = false;
    return;
  }

  if (draft.value !== activeDraft || selectedVehicleId.value !== capturedVehicleId) {
    draft.value = null;
    notice.value = "草稿已失效，请重新开始编辑";
    applying.value = false;
    return;
  }
  detail.value = latest;
  if (latest.version !== capturedVersion) {
    draft.value = null;
    notice.value = "配置版本已变化，请重新开始编辑";
    applying.value = false;
    return;
  }

  const input: OnboardConfigurationInput = {
    expectedVersion: latest.version,
    operatingMode: activeDraft.operatingMode,
    devices: activeDraft.devices.map((device) => ({
      deviceAlias: device.deviceAlias,
      networkMode: device.networkMode,
      roles: [...device.roles],
      protocolProfiles: { ...device.protocolProfiles }
    })),
    reason: activeDraft.reason.trim()
  };
  try {
    await previewOnboardConfiguration(capturedVehicleId, input);
    if (draft.value !== activeDraft || selectedVehicleId.value !== capturedVehicleId) {
      draft.value = null;
      notice.value = "草稿已失效，请重新开始编辑";
      applying.value = false;
      return;
    }
    await applyOnboardConfiguration(capturedVehicleId, input);
  } catch {
    pageError.value = "配置未完成，请刷新车载系统状态后重试";
    feedbackStore.error(pageError.value);
    applying.value = false;
    return;
  }

  let refreshed: OnboardSystemDetail;
  try {
    refreshed = await getOnboardSystem(capturedVehicleId);
  } catch {
    failClosedAfterAppliedReload();
    applying.value = false;
    return;
  }
  if (selectionRequest !== capturedSelectionRequest
      || selectedVehicleId.value !== capturedVehicleId) {
    failClosedAfterAppliedReload();
    applying.value = false;
    return;
  }
  detail.value = refreshed;
  replaceSummaryFromDetail(refreshed);
  draft.value = null;
  applying.value = false;
  feedbackStore.success("车载系统期望配置已应用");
}

function failClosedAfterAppliedReload(): void {
  detail.value = null;
  draft.value = null;
  pageError.value = "配置已应用，但最新车载系统详情暂时不可用";
  feedbackStore.error(pageError.value);
}

function replaceSummaryFromDetail(next: OnboardSystemDetail): void {
  systems.value = systems.value.map((system) => system.vehicleId === next.vehicleId
    ? {
        ...system,
        ...next,
        devices: [...next.devices]
      }
    : system);
}

function copyProfiles(profiles: ProtocolProfiles | null): ProtocolProfiles {
  return profiles ? { ...profiles } : {
    transportProfile: "JT808_2019",
    businessProfile: "NONE",
    safetyProfile: "NONE",
    mediaProfile: "NONE",
    activePositionIntervalSeconds: 30,
    idlePositionIntervalSeconds: 60
  };
}

function displayVehicle(system: OnboardSystemSummary): string {
  return `车辆 ${system.vehicleId}`;
}

function deviceType(device: OnboardDeviceView): string {
  if (device.roles.includes("DISPATCH")) return "调度终端";
  if (device.roles.includes("ACTIVE_SAFETY") || device.roles.includes("VIDEO")) return "主动安全记录仪";
  return "车载设备";
}

function deviceLabel(alias: string | null): string {
  if (!alias) return "未接入";
  const device = detail.value?.devices.find((candidate) => candidate.deviceAlias === alias);
  return device ? deviceType(device) : alias;
}

function networkLabel(mode: string): string {
  return mode === "DIRECT_CELLULAR" ? "独立蜂窝网络" : "共享网络客户端";
}

function roleLabel(role: OnboardRole): string {
  return ({
    DISPATCH: "调度业务",
    LOCATION_PRIMARY: "位置主源",
    LOCATION_BACKUP: "位置备源",
    ACTIVE_SAFETY: "主动安全",
    VIDEO: "视频",
    WAN_UPLINK: "广域网出口"
  } as Record<OnboardRole, string>)[role];
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
}
</script>

<template>
  <section class="onboard-page" aria-label="车载系统管理">
    <header class="page-header">
      <div>
        <p class="page-kicker">复合车载系统 · 运行事实与期望配置</p>
        <h2 class="page-title">车载系统</h2>
        <p class="page-subtitle">以车辆为单位查看独立设备状态、当前权威位置源和广域网出口；所有配置变更均重新核对版本。</p>
      </div>
      <div class="header-actions">
        <a class="secondary-button" href="/terminals/devices">物理设备操作</a>
        <button class="secondary-button" type="button" :disabled="loading || applying" @click="loadSystems">刷新</button>
      </div>
    </header>

    <p v-if="pageError" class="message error-message" role="alert">{{ pageError }}</p>
    <p v-else-if="notice" class="message notice-message" role="alert">{{ notice }}</p>

    <div class="system-layout">
      <aside class="system-list" aria-label="车载系统列表">
        <div class="list-heading"><strong>车辆</strong><span>{{ systems.length }} 套系统</span></div>
        <button
          v-for="system in systems"
          :key="system.vehicleId"
          type="button"
          :disabled="applying"
          :class="{ selected: selectedVehicleId === system.vehicleId }"
          :aria-label="`${displayVehicle(system)} · ${system.devices.length} 台设备`"
          @click="selectSystem(system.vehicleId)"
        >
          <strong>{{ displayVehicle(system) }}</strong>
          <span>{{ system.operatingMode === "DISPATCH_SERVICE" ? "调度服务" : "安全监控" }}</span>
          <small>v{{ system.version }} · {{ system.devices.length }} 台设备</small>
        </button>
        <p v-if="!loading && systems.length === 0">暂无已安装车载系统</p>
      </aside>

      <main v-if="detail" class="system-detail">
        <section class="detail-heading">
          <div>
            <p class="eyebrow">车辆运行单元</p>
            <h3>{{ detail.vehicleId }}</h3>
            <p>配置版本 v{{ detail.version }} · {{ detail.operatingMode === "DISPATCH_SERVICE" ? "调度服务" : "安全监控" }}</p>
          </div>
          <div class="heading-actions">
            <span class="overall-status" :data-status="detail.readiness.overallStatus">整体：{{ detail.readiness.overallStatus }}</span>
            <button v-if="canManage && !draft" class="primary-button" type="button" @click="beginEditing">编辑期望配置</button>
          </div>
        </section>

        <section class="readiness-grid" aria-label="就绪度">
          <div><span>连接</span><strong>连接：{{ detail.readiness.connectivity }}</strong></div>
          <div><span>调度</span><strong>调度：{{ detail.readiness.dispatch }}</strong></div>
          <div><span>位置</span><strong>位置：{{ detail.readiness.location }}</strong></div>
          <div><span>主动安全</span><strong>主动安全：{{ detail.readiness.activeSafety }}</strong></div>
          <div><span>视频</span><strong>视频：{{ detail.readiness.video }}</strong></div>
        </section>

        <section class="source-strip">
          <div><span>位置主源</span><strong>位置主源：{{ deviceLabel(detail.activeLocationDeviceAlias) }}</strong></div>
          <div><span>广域网出口</span><strong>广域网：{{ deviceLabel(detail.wanDeviceAlias) }}</strong></div>
          <div><span>物理设备</span><strong>已安装 {{ detail.devices.length }} 台</strong></div>
        </section>

        <section class="devices-panel">
          <div class="section-heading"><div><p class="eyebrow">独立故障域</p><h4>物理设备</h4></div><span>展开查看事实与协议</span></div>
          <details v-for="device in detail.devices" :key="device.deviceAlias" class="device-panel" open>
            <summary>
              <div><strong>{{ deviceType(device) }}</strong><code>{{ device.deviceAlias }}</code></div>
              <div class="device-summary"><span>{{ networkLabel(device.networkMode) }}</span><span>{{ device.terminalStatus }}</span></div>
            </summary>
            <div class="device-facts">
              <dl>
                <div><dt>当前会话</dt><dd>{{ device.currentlyAuthenticated ? "当前：在线且已鉴权" : "当前：离线" }}</dd></div>
                <div><dt>最近注册</dt><dd>{{ time(device.lastRegisteredAt) }}</dd></div>
                <div><dt>最近成功鉴权（历史）</dt><dd>{{ time(device.lastAuthenticatedAt) }}</dd></div>
                <div><dt>会话最近有效消息</dt><dd>{{ time(device.sessionLastValidMessageAt) }}</dd></div>
                <div><dt>会话到期时间</dt><dd>{{ time(device.sessionExpiresAt) }}</dd></div>
                <div><dt>最近可见</dt><dd>{{ time(device.lastSeenAt) }}</dd></div>
              </dl>
              <div><h5>当前角色</h5><p>{{ device.roles.map(roleLabel).join(" · ") || "—" }}</p></div>
              <div><h5>已验证能力</h5><p>{{ device.verifiedCapabilities.join(" · ") || "—" }}</p></div>
              <div><h5>协议档案</h5><p v-if="device.protocolProfiles">{{ device.protocolProfiles.transportProfile }} · {{ device.protocolProfiles.businessProfile }} · {{ device.protocolProfiles.safetyProfile }} · {{ device.protocolProfiles.mediaProfile }}</p><p v-else>—</p></div>
            </div>
          </details>
        </section>

        <form v-if="draft" class="configuration-panel" @submit.prevent="applyConfiguration">
          <div class="section-heading"><div><p class="eyebrow">受控期望状态</p><h4>编辑配置</h4></div><span>基于 v{{ draft.version }}</span></div>
          <label class="field">运行模式
            <select v-model="draft.operatingMode">
              <option value="DISPATCH_SERVICE">调度服务</option>
              <option value="SAFETY_MONITOR_ONLY">安全监控</option>
            </select>
          </label>
          <fieldset v-for="device in draft.devices" :key="device.deviceAlias" class="device-editor">
            <legend>{{ device.deviceAlias }}</legend>
            <label class="field">网络模式
              <select v-model="device.networkMode" :aria-label="`${device.deviceAlias} 网络模式`">
                <option value="DIRECT_CELLULAR">独立蜂窝网络</option>
                <option value="SHARED_LAN_CLIENT">共享网络客户端</option>
              </select>
            </label>
            <div class="roles-editor">
              <label v-for="role in roleOptions" :key="role">
                <input v-model="device.roles" type="checkbox" :value="role" :aria-label="`${device.deviceAlias} 角色 ${role}`" />
                {{ roleLabel(role) }}
              </label>
            </div>
            <div class="profiles-editor">
              <label>传输协议<select v-model="device.protocolProfiles.transportProfile"><option>JT808_2019</option><option>JT808_2013</option></select></label>
              <label>调度协议<select v-model="device.protocolProfiles.businessProfile"><option>GBT28787_2023</option><option>VENDOR_DISPATCH</option><option>NONE</option></select></label>
              <label>主动安全协议<select v-model="device.protocolProfiles.safetyProfile"><option>GBT28787_2023</option><option>JSATL12_2017</option><option>NONE</option></select></label>
              <label>媒体协议<select v-model="device.protocolProfiles.mediaProfile"><option>JT1078_2016</option><option>NONE</option></select></label>
            </div>
          </fieldset>
          <label class="field">操作原因<textarea v-model="draft.reason" maxlength="300" required /></label>
          <label class="confirm-check"><input v-model="draft.confirmed" type="checkbox" /> 我已核对当前车辆、版本和设备角色，确认应用。</label>
          <div class="form-actions">
            <button class="primary-button" type="submit" :disabled="!canApply">{{ applying ? "正在核对…" : "应用配置" }}</button>
            <button class="secondary-button" type="button" :disabled="applying" @click="cancelEditing">取消</button>
          </div>
          <p>提交顺序：重新读取详情 → 比对车辆与版本 → 预检 → 应用。浏览器仅提交设备别名。</p>
        </form>
      </main>
    </div>
  </section>
</template>

<style scoped>
.onboard-page { display:grid; gap:18px; color:var(--ink); }.header-actions,.heading-actions,.form-actions { display:flex; align-items:center; gap:10px; }.system-layout { display:grid; grid-template-columns:250px minmax(0,1fr); gap:14px; }.system-list,.system-detail { min-width:0; }.system-list { display:grid; align-content:start; gap:7px; border:1px solid var(--line); background:#f8f8f4; padding:10px; }.list-heading { display:flex; justify-content:space-between; padding:5px 3px 9px; color:var(--ink-muted); font-size:12px; }.system-list button { display:grid; gap:4px; width:100%; border:1px solid #d5ddd8; border-radius:6px; background:#fff; padding:11px 12px; color:var(--ink); text-align:left; cursor:pointer; }.system-list button.selected { border-color:#17634b; background:#eaf3ed; box-shadow:inset 3px 0 0 #17634b; }.system-list button span,.system-list small { color:var(--ink-muted); }.system-detail { display:grid; gap:12px; }.detail-heading,.readiness-grid,.source-strip,.devices-panel,.configuration-panel { border:1px solid var(--line); border-radius:8px; background:var(--surface); }.detail-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; padding:16px; }.detail-heading h3 { margin:0; font-size:22px; }.detail-heading p:last-child { margin:6px 0 0; color:var(--ink-muted); }.eyebrow { margin:0 0 5px; color:#39755d; font-size:11px; font-weight:900; letter-spacing:.05em; }.overall-status { border-radius:999px; padding:6px 10px; background:#e5eee8; color:#17634b; font-size:12px; font-weight:900; }.overall-status[data-status="DEGRADED"] { background:#fff0cc; color:#7c5a09; }.overall-status[data-status="OFFLINE"] { background:#f7dfdb; color:#99362f; }.readiness-grid { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); overflow:hidden; }.readiness-grid div { display:grid; gap:5px; padding:12px; border-right:1px solid var(--line); }.readiness-grid div:last-child { border-right:0; }.readiness-grid span,.source-strip span { color:var(--ink-muted); font-size:11px; font-weight:800; }.readiness-grid strong,.source-strip strong { font-size:13px; }.source-strip { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); padding:12px 16px; }.source-strip div { display:grid; gap:4px; }.devices-panel,.configuration-panel { padding:14px; }.section-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; margin-bottom:10px; }.section-heading h4 { margin:0; }.section-heading > span { color:var(--ink-muted); font-size:12px; }.device-panel { border-top:1px solid var(--line); }.device-panel summary { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:11px 2px; cursor:pointer; }.device-panel summary > div:first-child { display:flex; align-items:center; gap:10px; }.device-panel code { color:#17634b; font-size:12px; }.device-summary { display:flex; gap:12px; color:var(--ink-muted); font-size:12px; }.device-facts { display:grid; grid-template-columns:1.3fr repeat(3,1fr); gap:12px; padding:2px 2px 14px; }.device-facts dl { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:7px; margin:0; }.device-facts dl div { border-left:2px solid #d4dfd8; padding-left:8px; }.device-facts dt,.device-facts h5 { margin:0 0 3px; color:var(--ink-muted); font-size:11px; }.device-facts dd,.device-facts p { margin:0; font-size:12px; line-height:1.5; }.configuration-panel { display:grid; gap:12px; border-color:#9fbbab; background:#fbfcfa; }.field,.profiles-editor label { display:grid; gap:5px; color:#33423a; font-size:12px; font-weight:800; }.field select,.field textarea,.profiles-editor select { min-height:36px; border:1px solid #aebdb4; border-radius:5px; background:#fff; padding:7px 9px; }.field textarea { min-height:74px; resize:vertical; }.device-editor { display:grid; gap:10px; border:1px solid var(--line); padding:10px; }.device-editor legend { padding:0 6px; color:#17634b; font-family:monospace; font-size:12px; }.roles-editor { display:flex; flex-wrap:wrap; gap:8px 14px; }.roles-editor label,.confirm-check { display:flex; align-items:center; gap:6px; font-size:12px; }.profiles-editor { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; }.configuration-panel > p { margin:0; color:var(--ink-muted); font-size:12px; }.message { margin:0; border-left:4px solid; padding:10px 12px; font-weight:800; }.error-message { border-color:#a7352e; background:#faece9; color:#8e302a; }.notice-message { border-color:#b27b13; background:#fff5db; color:#76520c; }button:disabled { cursor:not-allowed; opacity:.55; }@media (max-width:1050px) { .readiness-grid { grid-template-columns:repeat(3,1fr); }.device-facts { grid-template-columns:repeat(2,1fr); }.profiles-editor { grid-template-columns:repeat(2,1fr); } }@media (max-width:760px) { .system-layout,.readiness-grid,.source-strip,.device-facts,.profiles-editor { grid-template-columns:1fr; }.page-header,.detail-heading { align-items:flex-start; flex-direction:column; }.device-panel summary { align-items:flex-start; flex-direction:column; } }
</style>
