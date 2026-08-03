<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  cancelOrder,
  confirmCancellationReason,
  createOrder,
  dispatchOrder,
  listOrders,
  markOrderNoShow,
  type CreateRideOrderInput
} from "../api/orders";
import type { RideOrder } from "../api/types";
import OrderCreateDialog from "../components/OrderCreateDialog.vue";
import NoShowConfirmDialog from "../components/NoShowConfirmDialog.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { authStore } from "../auth/authStore";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";

const orders = ref<RideOrder[]>([]);
const showCreateDialog = ref(false);
const status = ref("");
const loading = ref(false);
const submitting = ref(false);
const noShowOrder = ref<RideOrder | null>(null);
const noShowSubmitting = ref(false);
const expandedFailureOrderId = ref<string | null>(null);

function canDispatch(order: RideOrder) {
  return order.status === "PENDING_DISPATCH";
}

function canCancel(order: RideOrder) {
  return !["UNSERVICEABLE", "CANCELLED", "COMPLETED", "EXCEPTION_CLOSED"].includes(order.status);
}

function canConfirmPassengerCancellation(order: RideOrder) {
  return order.status === "CANCELLED";
}

function toggleFailureDetails(orderId: string): void {
  expandedFailureOrderId.value = expandedFailureOrderId.value === orderId ? null : orderId;
}

function formatDateTime(value?: string) {
  if (!value) {
    return "--";
  }
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

async function loadOrders() {
  status.value = "";
  loading.value = true;
  try {
    orders.value = await listOrders();
  } catch (error) {
    status.value = userMessage(error, "订单数据加载失败");
  } finally {
    loading.value = false;
  }
}

async function submitOrder(input: CreateRideOrderInput) {
  submitting.value = true;
  try {
    await createOrder(input);
    showCreateDialog.value = false;
    feedbackStore.success("需求已录入，等待调度处理");
    await loadOrders();
  } catch (error) {
    const message = userMessage(error, "需求录入失败");
    status.value = message;
    feedbackStore.error(message);
  } finally {
    submitting.value = false;
  }
}

async function runDispatch(order: RideOrder) {
  try {
    await dispatchOrder(order.id);
    feedbackStore.success("调度评估已提交");
    await loadOrders();
  } catch (error) {
    const message = userMessage(error, "调度操作失败");
    status.value = message;
    feedbackStore.error(message);
  }
}

async function cancel(order: RideOrder) {
  try {
    await cancelOrder(order.id, "运营后台取消");
    feedbackStore.success("订单已取消");
    await loadOrders();
  } catch (error) {
    const message = userMessage(error, "订单取消失败");
    status.value = message;
    feedbackStore.error(message);
  }
}

function openNoShow(order: RideOrder): void {
  noShowOrder.value = order;
}

async function confirmPassengerCancellation(order: RideOrder) {
  try {
    await confirmCancellationReason(order.id, "乘客取消");
    feedbackStore.success("已确认取消原因为乘客取消");
    await loadOrders();
  } catch (error) {
    feedbackStore.error(userMessage(error, "取消原因确认失败"));
  }
}

function noShowRemaining(order: RideOrder): string {
  const remaining = Math.max(0, 5 * 60 - order.noShowWaitedSeconds);
  return `剩余 ${Math.floor(remaining / 60)} 分 ${remaining % 60} 秒`;
}

async function closeNoShow(value: { reason: string; idempotencyKey: string }) {
  if (!noShowOrder.value) {
    return;
  }
  noShowSubmitting.value = true;
  try {
    await markOrderNoShow(noShowOrder.value.id, value.reason, value.idempotencyKey);
    noShowOrder.value = null;
    feedbackStore.success("订单已按乘客未到关闭");
    await loadOrders();
  } catch (error) {
    const message = userMessage(error, "异常关闭失败");
    status.value = message;
    feedbackStore.error(message);
    await loadOrders();
  } finally {
    noShowSubmitting.value = false;
  }
}

onMounted(() => {
  void loadOrders();
});
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">ORDERS</p>
        <h2 class="page-title">订单中心</h2>
        <p class="page-subtitle">承接乘客需求录入、虚拟站点匹配、调度状态和异常关闭。</p>
      </div>
      <div class="toolbar">
        <button v-if="authStore.has('ORDER_CREATE')" class="primary-button" type="button" @click="showCreateDialog = true">录入需求</button>
        <button class="secondary-button" type="button" :disabled="loading" @click="loadOrders">{{ loading ? "同步中" : "刷新" }}</button>
      </div>
    </header>

    <OrderCreateDialog
      v-if="showCreateDialog"
      :submitting="submitting"
      :submit-error="status"
      @close="showCreateDialog = false"
      @create="submitOrder"
    />
    <NoShowConfirmDialog
      v-if="noShowOrder"
      :order="noShowOrder"
      :submitting="noShowSubmitting"
      @close="noShowOrder = null"
      @confirm="closeNoShow"
    />

    <p v-if="loading" class="page-state">正在同步订单数据…</p>
    <p v-else-if="status" class="page-state">{{ status }}</p>

    <section class="table-panel">
      <table class="data-table">
        <thead>
          <tr>
            <th>订单</th>
            <th>乘客</th>
            <th>订单状态</th>
            <th>预计上车时间</th>
            <th>预约时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="order in orders" :key="order.id">
            <td>{{ order.id.slice(0, 8) }}</td>
            <td>{{ order.passengerName }} · {{ order.passengerCount }}人</td>
            <td>
              <StatusBadge :code="order.status" />
              <div v-if="order.status === 'UNSERVICEABLE' && order.dispatchFailure" class="dispatch-failure-summary">
                <span>{{ order.dispatchFailure.summary }}</span>
                <button
                  class="link-button"
                  type="button"
                  :aria-expanded="expandedFailureOrderId === order.id"
                  aria-label="查看不可服务原因"
                  @click="toggleFailureDetails(order.id)"
                >
                  {{ expandedFailureOrderId === order.id ? "收起原因" : "查看原因" }}
                </button>
                <div v-if="expandedFailureOrderId === order.id" class="dispatch-failure-details">
                  <span>候选方案数：{{ order.dispatchFailure.candidateCount }}</span>
                  <span v-if="order.dispatchFailure.maxWaitMinutes !== undefined">最大等候：{{ order.dispatchFailure.maxWaitMinutes }} 分钟</span>
                  <span v-if="order.dispatchFailure.maxDetourMinutes !== undefined">最大绕行：{{ order.dispatchFailure.maxDetourMinutes }} 分钟</span>
                  <span v-if="order.dispatchFailure.rejectedReasons.length">拒绝原因：{{ order.dispatchFailure.rejectedReasons.join("、") }}</span>
                  <span v-if="order.dispatchFailure.mapProvider">路线服务：{{ order.dispatchFailure.mapProvider }}{{ order.dispatchFailure.mapDegraded ? "（降级）" : "（正常）" }}</span>
                  <span v-if="order.dispatchFailure.pickupToDestinationDistanceMeters">起终点路线：{{ order.dispatchFailure.pickupToDestinationDistanceMeters }} 米 / {{ Math.ceil((order.dispatchFailure.pickupToDestinationDurationSeconds ?? 0) / 60) }} 分钟</span>
                  <span class="dispatch-failure-code">诊断代码：{{ order.dispatchFailure.code }}</span>
                </div>
              </div>
            </td>
            <td>{{ formatDateTime(order.estimatedBoardingAt) }}</td>
            <td>{{ formatDateTime(order.requestedDepartureAt) }}</td>
            <td>
              <div class="toolbar">
                <template v-if="authStore.has('DISPATCH_EXECUTE')">
                  <button v-if="canDispatch(order)" class="secondary-button" type="button" @click="runDispatch(order)">调度</button>
                   <button v-if="canCancel(order)" class="secondary-button" type="button" @click="cancel(order)">取消</button>
                   <button v-if="canConfirmPassengerCancellation(order)" class="secondary-button" type="button" @click="confirmPassengerCancellation(order)">确认乘客取消</button>
                  <button v-if="order.canMarkNoShow" class="danger-button" type="button" @click="openNoShow(order)">乘客未到</button>
                  <span
                    v-else-if="order.status === 'IN_PROGRESS' && order.noShowBlockReason"
                    class="action-hint"
                  >
                    <span>{{ order.noShowBlockReason }}</span>
                    <span v-if="order.noShowEligibleAt">{{ noShowRemaining(order) }}</span>
                  </span>
                   <span v-if="!canCancel(order) && !canConfirmPassengerCancellation(order)" class="action-hint">无需操作</span>
                </template>
              </div>
            </td>
          </tr>
          <tr v-if="orders.length === 0">
            <td colspan="6">暂无订单，可由运营人员录入一条即时或预约需求。</td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>

<style scoped>
.action-hint { color: var(--ink-muted); display: grid; font-size: 13px; font-weight: 700; gap: 2px; }
.dispatch-failure-summary { color: #9b2c2c; display: grid; font-size: 12px; gap: 4px; margin-top: 6px; max-width: 280px; }
.dispatch-failure-details { background: #fff7ed; border-left: 3px solid #f59e0b; color: var(--ink); display: grid; gap: 3px; padding: 8px 10px; }
.dispatch-failure-code { color: var(--ink-muted); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.link-button { background: none; border: 0; color: var(--accent-strong, #006b5b); cursor: pointer; font-size: 12px; font-weight: 800; padding: 0; text-align: left; }
</style>
