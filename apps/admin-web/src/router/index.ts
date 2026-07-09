import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import AuditLogsPage from "../pages/AuditLogsPage.vue";
import DashboardPage from "../pages/DashboardPage.vue";
import DispatchWorkbenchPage from "../pages/DispatchWorkbenchPage.vue";
import OrdersPage from "../pages/OrdersPage.vue";
import ResourcesPage from "../pages/ResourcesPage.vue";
import RulesPage from "../pages/RulesPage.vue";
import TasksPage from "../pages/TasksPage.vue";

export const routes: RouteRecordRaw[] = [
  { path: "/", name: "dashboard", component: DashboardPage, meta: { title: "运营看板" } },
  { path: "/dispatch", name: "dispatch", component: DispatchWorkbenchPage, meta: { title: "调度工作台" } },
  { path: "/resources", name: "resources", component: ResourcesPage, meta: { title: "资源配置" } },
  { path: "/rules", name: "rules", component: RulesPage, meta: { title: "规则配置" } },
  { path: "/orders", name: "orders", component: OrdersPage, meta: { title: "订单中心" } },
  { path: "/tasks", name: "tasks", component: TasksPage, meta: { title: "车辆任务" } },
  { path: "/audit-logs", name: "auditLogs", component: AuditLogsPage, meta: { title: "审计日志" } }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

export default router;
