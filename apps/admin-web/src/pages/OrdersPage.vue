<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  cancelOrder,
  createOrder,
  dispatchOrder,
  listOrders,
  markOrderNoShow,
  type CreateRideOrderInput
} from "../api/orders";
import type { RideOrder } from "../api/types";
import OrderCreateDialog from "../components/OrderCreateDialog.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { authStore } from "../auth/authStore";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";

const orders = ref<RideOrder[]>([]);
const showCreateDialog = ref(false);
const status = ref("");
const loading = ref(false);
const submitting = ref(false);

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

async function closeNoShow(order: RideOrder) {
  try {
    await markOrderNoShow(order.id, "乘客未上车");
    feedbackStore.success("订单已按乘客未到关闭");
    await loadOrders();
  } catch (error) {
    const message = userMessage(error, "异常关闭失败");
    status.value = message;
    feedbackStore.error(message);
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
      @close="showCreateDialog = false"
      @create="submitOrder"
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
            <td><StatusBadge :code="order.status" /></td>
            <td>{{ formatDateTime(order.estimatedBoardingAt) }}</td>
            <td>{{ formatDateTime(order.requestedDepartureAt) }}</td>
            <td>
              <div class="toolbar">
                <template v-if="authStore.has('DISPATCH_EXECUTE')"><button class="secondary-button" type="button" @click="runDispatch(order)">调度</button><button class="secondary-button" type="button" @click="cancel(order)">取消</button><button class="danger-button" type="button" @click="closeNoShow(order)">异常关闭</button></template>
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
