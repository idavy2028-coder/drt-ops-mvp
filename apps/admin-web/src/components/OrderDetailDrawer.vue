<script setup lang="ts">
import { nextTick, onMounted, ref } from "vue";
import type { RideOrder } from "../api/types";
import { formatShanghaiDateTime } from "../presentation/dateTime";
import StatusBadge from "./StatusBadge.vue";

defineProps<{
  order: RideOrder;
}>();

const emit = defineEmits<{
  close: [];
}>();

const closeButton = ref<HTMLButtonElement | null>(null);

onMounted(() => {
  void nextTick(() => closeButton.value?.focus());
});
</script>

<template>
  <Teleport to="body">
    <div
      class="order-drawer-overlay"
      @click.self="emit('close')"
      @keydown.esc="emit('close')"
    >
      <aside
        class="order-detail-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="order-detail-title"
      >
        <header class="drawer-header">
          <div>
            <p class="drawer-kicker">ORDER DETAIL</p>
            <h2 id="order-detail-title">订单详情</h2>
          </div>
          <button
            ref="closeButton"
            type="button"
            class="drawer-close"
            aria-label="关闭订单详情"
            @click="emit('close')"
          >
            ×
          </button>
        </header>

        <div class="drawer-content">
          <section class="drawer-section" aria-labelledby="order-summary-title">
            <div class="section-heading-row">
              <h3 id="order-summary-title">订单概况</h3>
              <StatusBadge :code="order.status" />
            </div>
            <dl class="detail-list">
              <div class="detail-wide">
                <dt>完整订单编号</dt>
                <dd class="mono-value">{{ order.id }}</dd>
              </div>
              <div>
                <dt>乘客</dt>
                <dd>{{ order.passengerName }} · {{ order.passengerCount }} 人</dd>
              </div>
              <div>
                <dt>联系电话</dt>
                <dd>
                  <a
                    v-if="order.passengerPhone"
                    :href="`tel:${order.passengerPhone}`"
                    :aria-label="`拨打 ${order.passengerPhone}`"
                  >
                    {{ order.passengerPhone }}
                  </a>
                  <span v-else>--</span>
                </dd>
              </div>
              <div>
                <dt>请求类型</dt>
                <dd>{{ order.requestType || "--" }}</dd>
              </div>
            </dl>
          </section>

          <section class="drawer-section" aria-labelledby="order-route-title">
            <h3 id="order-route-title">行程</h3>
            <div class="route-detail">
              <div><span class="route-marker route-origin">上</span><strong>{{ order.originAddress || "上车点未填写" }}</strong></div>
              <div><span class="route-marker route-destination">下</span><strong>{{ order.destinationAddress || "下车点未填写" }}</strong></div>
            </div>
          </section>

          <section class="drawer-section" aria-labelledby="order-time-title">
            <h3 id="order-time-title">时间</h3>
            <dl class="detail-list">
              <div><dt>创建时间</dt><dd>{{ formatShanghaiDateTime(order.createdAt) }}</dd></div>
              <div><dt>预约出发</dt><dd>{{ formatShanghaiDateTime(order.requestedDepartureAt) }}</dd></div>
              <div><dt>预计上车</dt><dd>{{ formatShanghaiDateTime(order.estimatedBoardingAt) }}</dd></div>
              <div><dt>预计到达</dt><dd>{{ formatShanghaiDateTime(order.estimatedArrivalAt) }}</dd></div>
            </dl>
          </section>

          <section
            v-if="order.dispatchFailure"
            class="drawer-section failure-section"
            aria-labelledby="dispatch-failure-title"
          >
            <h3 id="dispatch-failure-title">不可服务诊断</h3>
            <p class="failure-summary">{{ order.dispatchFailure.summary }}</p>
            <div class="failure-grid">
              <span>候选方案数：{{ order.dispatchFailure.candidateCount }}</span>
              <span v-if="order.dispatchFailure.maxWaitMinutes !== undefined">最大等候：{{ order.dispatchFailure.maxWaitMinutes }} 分钟</span>
              <span v-if="order.dispatchFailure.maxDetourMinutes !== undefined">最大绕行：{{ order.dispatchFailure.maxDetourMinutes }} 分钟</span>
              <span v-if="order.dispatchFailure.rejectedReasons.length">拒绝原因：{{ order.dispatchFailure.rejectedReasons.join("、") }}</span>
              <span v-if="order.dispatchFailure.mapProvider">路线服务：{{ order.dispatchFailure.mapProvider }}{{ order.dispatchFailure.mapDegraded ? "（降级）" : "（正常）" }}</span>
              <span v-if="order.dispatchFailure.vehicleToPickupDistanceMeters">车辆至上车点：{{ order.dispatchFailure.vehicleToPickupDistanceMeters }} 米 / {{ Math.ceil((order.dispatchFailure.vehicleToPickupDurationSeconds ?? 0) / 60) }} 分钟</span>
              <span v-if="order.dispatchFailure.pickupToDestinationDistanceMeters">起终点路线：{{ order.dispatchFailure.pickupToDestinationDistanceMeters }} 米 / {{ Math.ceil((order.dispatchFailure.pickupToDestinationDurationSeconds ?? 0) / 60) }} 分钟</span>
              <span class="diagnostic-code">诊断代码：{{ order.dispatchFailure.code }}</span>
            </div>
          </section>
        </div>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped>
.order-drawer-overlay { align-items: stretch; background: rgba(15, 23, 20, 0.36); display: flex; inset: 0; justify-content: flex-end; position: fixed; z-index: 80; }
.order-detail-drawer { background: #f8faf8; box-shadow: -18px 0 44px rgba(15, 23, 20, 0.2); display: flex; flex-direction: column; height: 100%; max-width: calc(100vw - 24px); width: 460px; }
.drawer-header { align-items: center; background: #ffffff; border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; padding: 16px 18px; }
.drawer-header h2 { font-size: 22px; margin: 0; }
.drawer-kicker { color: var(--ink-muted); font-size: 11px; font-weight: 900; margin: 0 0 4px; }
.drawer-close { align-items: center; background: #f2f5f3; border: 1px solid var(--line); border-radius: 8px; color: #314039; cursor: pointer; display: inline-flex; font-size: 24px; height: 36px; justify-content: center; line-height: 1; width: 36px; }
.drawer-content { display: grid; gap: 12px; overflow-y: auto; padding: 16px; }
.drawer-section { background: #ffffff; border: 1px solid var(--line); border-radius: 8px; padding: 16px; }
.drawer-section h3 { font-size: 15px; margin: 0 0 12px; }
.section-heading-row { align-items: center; display: flex; justify-content: space-between; }
.section-heading-row h3 { margin: 0; }
.detail-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 16px; margin: 14px 0 0; }
.detail-list div { min-width: 0; }
.detail-wide { grid-column: 1 / -1; }
.detail-list dt { color: var(--ink-muted); font-size: 12px; font-weight: 800; }
.detail-list dd { color: var(--ink); font-size: 13px; font-weight: 800; margin: 4px 0 0; overflow-wrap: anywhere; }
.detail-list a { color: #176b7d; text-decoration: underline; text-underline-offset: 3px; }
.mono-value,
.diagnostic-code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.route-detail { display: grid; gap: 10px; }
.route-detail div { align-items: center; display: grid; gap: 10px; grid-template-columns: 28px minmax(0, 1fr); }
.route-detail strong { font-size: 13px; overflow-wrap: anywhere; }
.route-marker { align-items: center; border-radius: 999px; display: inline-flex; font-size: 11px; font-weight: 900; height: 28px; justify-content: center; width: 28px; }
.route-origin { background: #e7f0f7; color: #235f7d; }
.route-destination { background: #e6f4eb; color: #17643f; }
.failure-section { border-color: #f0c9c3; }
.failure-summary { color: #9b2c2c; font-size: 13px; font-weight: 900; margin: 0 0 10px; }
.failure-grid { background: #fff7ed; border-left: 3px solid #f59e0b; display: grid; font-size: 12px; gap: 6px; padding: 10px 12px; }
.diagnostic-code { color: var(--ink-muted); overflow-wrap: anywhere; }
@media (max-width: 540px) {
  .order-detail-drawer { max-width: none; width: calc(100vw - 12px); }
  .detail-list { grid-template-columns: 1fr; }
  .detail-wide { grid-column: auto; }
}
</style>
