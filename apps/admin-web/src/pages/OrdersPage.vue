<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
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
import OrderDetailDrawer from "../components/OrderDetailDrawer.vue";
import NoShowConfirmDialog from "../components/NoShowConfirmDialog.vue";
import RecordPagination from "../components/RecordPagination.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { authStore } from "../auth/authStore";
import { userMessage } from "../api/errors";
import { usePageScrollRetention } from "../composables/usePageScrollRetention";
import { formatShanghaiDateTime, shanghaiDateKey } from "../presentation/dateTime";
import { feedbackStore } from "../stores/feedbackStore";

type OrderGroupKey = "today" | "history";

const ORDER_PAGE_SIZE = 8;
const orders = ref<RideOrder[]>([]);
const showCreateDialog = ref(false);
const status = ref("");
const loading = ref(false);
const submitting = ref(false);
const noShowOrder = ref<RideOrder | null>(null);
const noShowSubmitting = ref(false);
const selectedOrder = ref<RideOrder | null>(null);
const detailTrigger = ref<HTMLElement | null>(null);
const activeOrderGroup = ref<OrderGroupKey>("today");
const orderPageByGroup = ref<Record<OrderGroupKey, number>>({ today: 1, history: 1 });

usePageScrollRetention();

function orderCreatedAtValue(order: RideOrder): number {
  const timestamp = order.createdAt ? Date.parse(order.createdAt) : Number.NEGATIVE_INFINITY;
  return Number.isNaN(timestamp) ? Number.NEGATIVE_INFINITY : timestamp;
}

const orderGroups = computed(() => {
  const todayKey = shanghaiDateKey(new Date().toISOString());
  const sorted = [...orders.value].sort((left, right) => orderCreatedAtValue(right) - orderCreatedAtValue(left));
  return {
    today: {
      title: "今日新增",
      empty: "暂无今日新增订单",
      items: sorted.filter((order) => shanghaiDateKey(order.createdAt) === todayKey)
    },
    history: {
      title: "历史订单",
      empty: "暂无历史订单",
      items: sorted.filter((order) => shanghaiDateKey(order.createdAt) !== todayKey)
    }
  } satisfies Record<OrderGroupKey, { title: string; empty: string; items: RideOrder[] }>;
});

const activeGroup = computed(() => orderGroups.value[activeOrderGroup.value]);
const activePage = computed({
  get: () => orderPageByGroup.value[activeOrderGroup.value],
  set: (page: number) => {
    orderPageByGroup.value[activeOrderGroup.value] = page;
  }
});
const pagedOrders = computed(() => {
  const start = (activePage.value - 1) * ORDER_PAGE_SIZE;
  return activeGroup.value.items.slice(start, start + ORDER_PAGE_SIZE);
});

function clampOrderPages(): void {
  (["today", "history"] satisfies OrderGroupKey[]).forEach((key) => {
    const pageCount = Math.max(1, Math.ceil(orderGroups.value[key].items.length / ORDER_PAGE_SIZE));
    orderPageByGroup.value[key] = Math.min(orderPageByGroup.value[key], pageCount);
  });
}

function canDispatch(order: RideOrder) {
  return order.status === "PENDING_DISPATCH";
}

function canCancel(order: RideOrder) {
  return !["UNSERVICEABLE", "CANCELLED", "COMPLETED", "EXCEPTION_CLOSED"].includes(order.status);
}

function canConfirmPassengerCancellation(order: RideOrder) {
  return order.status === "CANCELLED";
}

function openOrderDetails(order: RideOrder, event: MouseEvent): void {
  detailTrigger.value = event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
  selectedOrder.value = order;
}

async function closeOrderDetails(): Promise<void> {
  selectedOrder.value = null;
  await nextTick();
  detailTrigger.value?.focus();
}

async function loadOrders() {
  status.value = "";
  loading.value = true;
  try {
    orders.value = await listOrders();
    clampOrderPages();
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
  <section class="page orders-page">
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
    <OrderDetailDrawer
      v-if="selectedOrder"
      :order="selectedOrder"
      @close="closeOrderDetails"
    />

    <p v-if="loading" class="page-state">正在同步订单数据…</p>
    <p v-else-if="status" class="page-state">{{ status }}</p>

    <section class="table-panel order-record-panel">
      <div class="record-toolbar">
        <div class="segmented-control" aria-label="订单分区">
          <button
            type="button"
            :aria-pressed="activeOrderGroup === 'today'"
            @click="activeOrderGroup = 'today'"
          >
            今日新增 {{ orderGroups.today.items.length }}
          </button>
          <button
            type="button"
            :aria-pressed="activeOrderGroup === 'history'"
            @click="activeOrderGroup = 'history'"
          >
            历史订单 {{ orderGroups.history.items.length }}
          </button>
        </div>
        <span class="page-size-hint">每页 {{ ORDER_PAGE_SIZE }} 条</span>
      </div>

      <div class="table-scroll">
        <table class="data-table orders-table">
          <thead>
            <tr>
              <th>订单</th>
              <th>乘客</th>
              <th>联系电话</th>
              <th>上车点 → 下车点</th>
              <th>状态</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in pagedOrders" :key="order.id">
              <td>
                <div class="stacked-cell order-cell">
                  <strong>{{ order.id.slice(0, 8) }}</strong>
                  <span>{{ formatShanghaiDateTime(order.createdAt, "time") }} 创建</span>
                </div>
              </td>
              <td>
                <div class="stacked-cell">
                  <strong>{{ order.passengerName }}</strong>
                  <span>{{ order.passengerCount }} 人</span>
                </div>
              </td>
              <td>
                <a
                  v-if="order.passengerPhone"
                  class="phone-link"
                  :href="`tel:${order.passengerPhone}`"
                  :aria-label="`拨打 ${order.passengerPhone}`"
                >
                  {{ order.passengerPhone }}
                </a>
                <span v-else>--</span>
              </td>
              <td>
                <div class="stacked-cell route-cell" :title="`${order.originAddress || '上车点未填写'} → ${order.destinationAddress || '下车点未填写'}`">
                  <strong>{{ order.originAddress || "上车点未填写" }}</strong>
                  <span>↓ {{ order.destinationAddress || "下车点未填写" }}</span>
                </div>
              </td>
              <td>
                <StatusBadge :code="order.status" />
              </td>
              <td>
                <div class="stacked-cell time-cell">
                  <strong>预约 {{ formatShanghaiDateTime(order.requestedDepartureAt) }}</strong>
                  <span>预计 {{ formatShanghaiDateTime(order.estimatedBoardingAt) }}</span>
                </div>
              </td>
              <td>
                <div class="toolbar row-actions">
                  <button
                    v-if="authStore.has('DISPATCH_EXECUTE') && canDispatch(order)"
                    class="secondary-button"
                    type="button"
                    @click="runDispatch(order)"
                  >
                    调度
                  </button>
                  <button
                    class="secondary-button"
                    type="button"
                    @click="openOrderDetails(order, $event)"
                  >
                    查看详情
                  </button>
                  <template v-if="authStore.has('DISPATCH_EXECUTE')">
                    <button v-if="order.canMarkNoShow" class="danger-button" type="button" @click="openNoShow(order)">乘客未到</button>
                    <span
                      v-else-if="order.status === 'IN_PROGRESS' && order.noShowBlockReason"
                      class="action-hint"
                    >
                      <span>{{ order.noShowBlockReason }}</span>
                      <span v-if="order.noShowEligibleAt">{{ noShowRemaining(order) }}</span>
                    </span>
                    <button
                      class="secondary-button"
                      type="button"
                      :disabled="!canCancel(order)"
                      :aria-describedby="!canCancel(order) ? `cancel-disabled-${order.id}` : undefined"
                      @click="canCancel(order) && cancel(order)"
                    >
                      取消订单
                    </button>
                    <span v-if="!canCancel(order)" :id="`cancel-disabled-${order.id}`" class="sr-only">当前订单状态不可取消</span>
                    <button v-if="canConfirmPassengerCancellation(order)" class="secondary-button" type="button" @click="confirmPassengerCancellation(order)">确认乘客取消</button>
                  </template>
                </div>
              </td>
            </tr>
            <tr v-if="pagedOrders.length === 0">
              <td colspan="7" class="record-group-empty">{{ activeGroup.empty }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <RecordPagination
        v-model:current-page="activePage"
        class="order-pagination"
        :total-items="activeGroup.items.length"
        :page-size="ORDER_PAGE_SIZE"
      />
    </section>
  </section>
</template>

<style scoped>
.orders-page { gap: 12px; }
.order-record-panel { overflow: hidden; }
.record-toolbar { align-items: center; background: #f7f9f7; border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; min-height: 52px; padding: 8px 12px; }
.segmented-control { background: #e9eeeb; border-radius: 8px; display: inline-flex; gap: 4px; padding: 3px; }
.segmented-control button { background: transparent; border: 0; border-radius: 6px; color: #52615a; cursor: pointer; min-height: 32px; padding: 6px 12px; font-size: 13px; font-weight: 900; }
.segmented-control button[aria-pressed="true"] { background: #ffffff; box-shadow: 0 1px 4px rgba(23, 36, 29, 0.12); color: var(--brand); }
.page-size-hint { color: var(--ink-muted); font-size: 12px; font-weight: 800; }
.table-scroll { max-width: 100%; overflow-x: auto; }
.orders-table { min-width: 1080px; }
.orders-table th,
.orders-table td { height: 44px; padding: 6px 12px; vertical-align: middle; }
.stacked-cell { display: grid; gap: 2px; line-height: 1.25; min-width: 0; }
.stacked-cell strong { font-size: 13px; }
.stacked-cell span { color: var(--ink-muted); font-size: 12px; }
.order-cell { min-width: 82px; }
.route-cell { max-width: 220px; }
.route-cell strong,
.route-cell span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.time-cell { min-width: 142px; }
.phone-link { color: #176b7d; font-size: 13px; font-weight: 800; text-decoration: underline; text-decoration-color: rgba(23, 107, 125, 0.35); text-underline-offset: 3px; }
.row-actions { flex-wrap: nowrap; gap: 6px; min-width: 190px; }
.row-actions .primary-button,
.row-actions .secondary-button,
.row-actions .danger-button { min-height: 32px; padding: 5px 9px; font-size: 12px; white-space: nowrap; }
.order-pagination { border-top: 1px solid var(--line); padding: 0 12px; }
.record-group-empty { color: var(--ink-muted); padding: 24px 12px; text-align: center; }
.action-hint { color: var(--ink-muted); display: grid; font-size: 13px; font-weight: 700; gap: 2px; }
.sr-only { height: 1px; margin: -1px; overflow: hidden; padding: 0; position: absolute; width: 1px; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 720px) {
  .record-toolbar { align-items: flex-start; flex-direction: column; }
  .segmented-control { width: 100%; }
  .segmented-control button { flex: 1; }
}
</style>
