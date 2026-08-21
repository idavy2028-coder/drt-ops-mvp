<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { authStore } from "../auth/authStore";
import * as terminalApi from "../api/terminals";
import { listVehicles } from "../api/resources";
import type { TerminalDetail, TerminalSummary, Vehicle } from "../api/types";

type RiskAction = "preset" | "bind" | "activate" | "suspend" | "retire" | "replace" | "rotate" | "disconnect";

const terminals = ref<TerminalSummary[]>([]);
const selected = ref<TerminalDetail | null>(null);
const selectedCode = ref("");
let selectionRequest = 0;
const vehicles = ref<Vehicle[]>([]);
const loading = ref(false);
const error = ref("");
const action = ref<RiskAction | null>(null);
const reason = ref("");
const confirmed = ref(false);
const vehicleId = ref("");
const replacementTerminalCode = ref("");
const preset = ref({ terminalPhone: "", terminalCode: "", manufacturerId: "", model: "", protocolVersion: "JT808_2019", sourceCoordinateSystem: "GCJ02" });
const canManage = computed(() => authStore.has("TERMINAL_MANAGE"));

onMounted(load);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [summaries, fleet] = await Promise.all([terminalApi.listTerminals(), canManage.value ? listVehicles() : Promise.resolve([])]);
    terminals.value = summaries;
    vehicles.value = fleet;
    if (summaries[0]) await select(summaries[0].terminalCode);
  } catch {
    error.value = "终端数据暂时不可用，请稍后重试";
  } finally {
    loading.value = false;
  }
}

async function select(terminalCode: string) {
  const request = ++selectionRequest;
  selectedCode.value = terminalCode;
  selected.value = null;
  action.value = null;
  error.value = "";
  try {
    const detail = await terminalApi.getTerminalDetail(terminalCode);
    if (request === selectionRequest && selectedCode.value === terminalCode) selected.value = detail;
  } catch {
    if (request === selectionRequest) error.value = "终端详情暂时不可用，请稍后重试";
  }
}

function beginAction(next: RiskAction) {
  action.value = next;
  reason.value = "";
  confirmed.value = false;
  vehicleId.value = "";
  replacementTerminalCode.value = "";
}

async function submitAction() {
  if (!action.value || !reason.value.trim() || !confirmed.value) return;
  try {
    if (action.value === "preset") {
      await terminalApi.presetTerminal({ ...preset.value, reason: reason.value.trim() });
      action.value = null;
      terminals.value = await terminalApi.listTerminals();
      if (terminals.value[0]) await select(terminals.value[0].terminalCode);
      return;
    }
    if (!selected.value || selected.value.terminalCode !== selectedCode.value) return;
    const requestedAction = action.value;
    const sourceCode = selectedCode.value;
    const latestSource = await terminalApi.getTerminalDetail(sourceCode);
    if (sourceCode !== selectedCode.value || action.value !== requestedAction) return;
    const input = { expectedVersion: latestSource.version, reason: reason.value.trim() };
    switch (action.value) {
      case "bind":
        if (!vehicleId.value) return;
        await terminalApi.bindTerminal(sourceCode, { ...input, vehicleId: vehicleId.value });
        break;
      case "activate": await terminalApi.activateTerminal(sourceCode, input); break;
      case "suspend": await terminalApi.suspendTerminal(sourceCode, input); break;
      case "retire": await terminalApi.retireTerminal(sourceCode, input); break;
      case "rotate": await terminalApi.rotateTerminalAuthentication(sourceCode, input); break;
      case "disconnect": await terminalApi.disconnectTerminal(sourceCode, input); break;
      case "replace":
        const replacementCode = replacementTerminalCode.value;
        if (!replacementCode || replacementCode === sourceCode) return;
        const latestReplacement = await terminalApi.getTerminalDetail(replacementCode);
        if (sourceCode !== selectedCode.value || replacementCode !== replacementTerminalCode.value || action.value !== requestedAction) {
          action.value = null;
          confirmed.value = false;
          error.value = "换机目标已变化，请重新确认";
          return;
        }
        await terminalApi.replaceTerminal(sourceCode, { ...input, replacementTerminalCode: replacementCode, replacementExpectedVersion: latestReplacement.version });
        break;
    }
    action.value = null;
    await select(sourceCode);
    terminals.value = await terminalApi.listTerminals();
  } catch {
    error.value = "管理操作未完成，请刷新终端状态后重试";
  }
}

function time(value: string | null) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "尚无数据"; }
</script>

<template>
  <section class="terminal-page" aria-label="终端管理">
    <header class="page-header">
      <div><p class="eyebrow">JT 终端安全运营</p><h2>终端管理</h2><p>状态、在线性与安全能力均以已接入的事实为准。</p></div>
      <div class="header-actions"><button v-if="canManage" type="button" @click="beginAction('preset')">预置终端</button><button class="secondary-button" type="button" :disabled="loading" @click="load">刷新</button></div>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="layout">
      <aside class="terminal-list" aria-label="终端列表">
        <button v-for="terminal in terminals" :key="terminal.terminalCode" type="button" :class="{ selected: selectedCode === terminal.terminalCode }" @click="select(terminal.terminalCode)">
          <strong>{{ terminal.terminalCode }}</strong><span>{{ terminal.terminalPhoneMasked }}</span><small>{{ terminal.status }} · {{ terminal.registrationCompleted ? "已注册" : "待注册" }}</small>
        </button>
        <p v-if="!loading && terminals.length === 0">暂无终端</p>
      </aside>
      <article v-if="action === 'preset'" class="detail"><section class="actions"><h4>预置终端</h4><p>填写终端身份与原因后，必须进行第二次确认才会提交。</p><form class="confirmation" @submit.prevent="submitAction"><label>终端手机号<input v-model="preset.terminalPhone" required maxlength="30" /></label><label>业务终端代码<input v-model="preset.terminalCode" required maxlength="80" /></label><label>厂商<input v-model="preset.manufacturerId" required maxlength="80" /></label><label>型号<input v-model="preset.model" required maxlength="120" /></label><label>协议版本<input v-model="preset.protocolVersion" required maxlength="40" /></label><label>坐标系<select v-model="preset.sourceCoordinateSystem"><option value="GCJ02">GCJ02</option><option value="WGS84">WGS84</option></select></label><label>操作原因<textarea v-model="reason" required maxlength="300" /></label><label class="check"><input v-model="confirmed" type="checkbox" /> 我已核对风险与原因，确认执行。</label><button type="submit" :disabled="!confirmed || !reason.trim()">确认预置</button><button class="secondary-button" type="button" @click="action = null">取消</button></form></section></article>
      <article v-else-if="selected" class="detail">
        <div class="status-row"><div><p class="eyebrow">业务终端代码</p><h3>{{ selected.terminalCode }}</h3><p>{{ selected.manufacturerId }} · {{ selected.model }} · {{ selected.terminalPhoneMasked }}</p></div><span class="status" :data-status="selected.onlineStatus">{{ selected.onlineStatus }}</span></div>
        <div class="cards">
          <section><h4>协议与能力</h4><p>{{ selected.protocolVersion }} · {{ selected.sourceCoordinateSystem }}</p><p>主动安全：{{ selected.activeSafetyStandard ?? "尚未配置" }}</p><p>模块：{{ selected.activeSafetyModules.length ? selected.activeSafetyModules.join("、") : "尚未配置" }}</p><p>JT/T 1078：{{ selected.jt1078Enabled ? "支持" : "未启用" }}</p></section>
          <section><h4>链路时间</h4><p>最近注册：{{ time(selected.lastRegisteredAt) }}</p><p>最近鉴权：{{ time(selected.lastAuthenticatedAt) }}</p><p>有效报文：{{ time(selected.lastValidMessageAt) }}</p><p>心跳：{{ time(selected.lastHeartbeatAt) }}</p><p>位置：{{ time(selected.lastLocationAt) }}</p><p>离线判定：{{ time(selected.offlineAt) }}</p></section>
          <section><h4>绑定</h4><p>当前：{{ selected.currentBinding?.plateNumber ?? "尚未绑定" }}</p><ul><li v-for="binding in selected.bindingHistory" :key="`${binding.plateNumber}-${binding.validFrom}`">{{ binding.plateNumber }} · {{ binding.status }} · {{ time(binding.validFrom) }}</li></ul></section>
        </div>
        <section class="audit"><h4>安全审计</h4><p v-if="selected.securityAudits.length === 0">尚无数据</p><table v-else><thead><tr><th>事件</th><th>结果</th><th>原因码</th><th>协议/消息 ID</th><th>发生时间</th></tr></thead><tbody><tr v-for="audit in selected.securityAudits" :key="`${audit.eventType}-${audit.occurredAt}`"><td>{{ audit.eventType }}</td><td>{{ audit.result }}</td><td>{{ audit.reasonCode ?? "—" }}</td><td>{{ audit.protocolVersion ?? "—" }}{{ audit.messageId === null ? "" : ` / ${audit.messageId}` }}</td><td>{{ time(audit.occurredAt) }}</td></tr></tbody></table></section>
        <section v-if="canManage" class="actions"><h4>受控管理操作</h4><p>所有操作须填写原因，并在提交前进行第二次确认；提交前会重新读取最新版本。</p><div class="action-buttons"><button type="button" @click="beginAction('bind')">绑定车辆</button><button type="button" @click="beginAction('activate')">激活终端</button><button type="button" @click="beginAction('replace')">换机</button><button type="button" @click="beginAction('suspend')">暂停终端</button><button type="button" @click="beginAction('retire')">退役终端</button><button type="button" @click="beginAction('rotate')">轮换鉴权</button><button type="button" @click="beginAction('disconnect')">强制断开</button></div>
          <form v-if="action" class="confirmation" @submit.prevent="submitAction"><h5>二次确认：{{ selected.terminalCode }} · {{ action }}</h5><label>操作原因<textarea v-model="reason" required maxlength="300" /></label><label v-if="action === 'bind'">车辆<select v-model="vehicleId" required><option value="">请选择</option><option v-for="vehicle in vehicles" :key="vehicle.id" :value="vehicle.id">{{ vehicle.plateNumber }}</option></select></label><template v-if="action === 'replace'"><label>替换终端<select v-model="replacementTerminalCode" required><option value="">请选择</option><option v-for="terminal in terminals.filter(item => item.terminalCode !== selectedCode)" :key="terminal.terminalCode" :value="terminal.terminalCode">{{ terminal.terminalCode }} · {{ terminal.terminalPhoneMasked }}</option></select></label></template><label class="check"><input v-model="confirmed" type="checkbox" /> 我已核对风险与原因，确认执行。</label><button type="submit" :disabled="!confirmed || !reason.trim()">确认执行</button><button class="secondary-button" type="button" @click="action = null">取消</button></form>
        </section>
      </article>
    </div>
  </section>
</template>

<style scoped>
.terminal-page { display: grid; gap: 20px; color: #21312a; }.page-header,.status-row { display:flex; justify-content:space-between; gap:20px; align-items:flex-start; }.page-header h2,.status-row h3 { margin:0; }.page-header p:last-child,.detail p { color:#66756d; }.eyebrow { margin:0 0 6px; color:#39755d; font-size:12px; font-weight:900; letter-spacing:.06em; }.layout { display:grid; grid-template-columns:260px minmax(0,1fr); gap:18px; }.terminal-list { display:grid; align-content:start; gap:8px; }.terminal-list button { display:grid; gap:4px; text-align:left; border:1px solid #d8e1dc; background:#fff; padding:13px; border-radius:8px; }.terminal-list button.selected { border-color:#39755d; background:#eff7f1; }.terminal-list small { color:#66756d; }.detail,.cards section,.audit,.actions { border:1px solid #d8e1dc; background:#fff; border-radius:10px; padding:18px; }.detail { display:grid; gap:18px; }.status { border-radius:999px; padding:7px 10px; font-weight:900; background:#e9edf0; }.status[data-status="ONLINE"] { color:#156b43; background:#ddf4e7; }.status[data-status="OFFLINE"] { color:#9b3a2d; background:#fae3df; }.cards { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; }.cards section { padding:14px; }.cards h4,.audit h4,.actions h4,.confirmation h5 { margin:0 0 10px; }.cards p { margin:8px 0; }.cards ul { margin:0; padding-left:18px; }table { width:100%; border-collapse:collapse; }th,td { padding:9px; text-align:left; border-bottom:1px solid #e6ece8; font-size:13px; }.action-buttons { display:flex; flex-wrap:wrap; gap:8px; }.confirmation { display:grid; gap:10px; margin-top:14px; max-width:520px; padding:14px; border:1px solid #b8c9bf; }.confirmation label { display:grid; gap:5px; font-weight:800; }.confirmation textarea,.confirmation input,.confirmation select { padding:8px; border:1px solid #aebdb4; border-radius:4px; }.confirmation .check { display:flex; align-items:center; gap:8px; }.error { color:#a93226; font-weight:800; }button { cursor:pointer; }@media (max-width:900px) { .layout,.cards { grid-template-columns:1fr; } }
</style>
