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

const orders = ref<RideOrder[]>([]);
const showCreateDialog = ref(false);
const status = ref("");

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
  try {
    orders.value = await listOrders();
  } catch (error) {
    status.value = error instanceof Error ? error.message : "订单数据加载失败";
  }
}

async function submitOrder(input: CreateRideOrderInput) {
  await createOrder(input);
  showCreateDialog.value = false;
  await loadOrders();
}

async function runDispatch(order: RideOrder) {
  await dispatchOrder(order.id);
  await loadOrders();
}

async function cancel(order: RideOrder) {
  await cancelOrder(order.id, "运营后台取消");
  await loadOrders();
}

async function closeNoShow(order: RideOrder) {
  await markOrderNoShow(order.id, "乘客未上车");
  await loadOrders();
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
        <button class="primary-button" type="button" @click="showCreateDialog = true">录入需求</button>
        <button class="secondary-button" type="button" @click="loadOrders">刷新</button>
      </div>
    </header>

    <OrderCreateDialog
      v-if="showCreateDialog"
      @close="showCreateDialog = false"
      @create="submitOrder"
    />

    <p v-if="status" class="section-copy">状态：{{ status }}</p>

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
            <td><span class="status-pill">{{ order.status }}</span></td>
            <td>{{ formatDateTime(order.estimatedBoardingAt) }}</td>
            <td>{{ formatDateTime(order.requestedDepartureAt) }}</td>
            <td>
              <div class="toolbar">
                <button class="secondary-button" type="button" @click="runDispatch(order)">调度</button>
                <button class="secondary-button" type="button" @click="cancel(order)">取消</button>
                <button class="danger-button" type="button" @click="closeNoShow(order)">异常关闭</button>
              </div>
            </td>
          </tr>
          <tr v-if="orders.length === 0">
            <td colspan="6">暂无订单</td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>
