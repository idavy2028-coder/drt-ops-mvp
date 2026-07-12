import { createRouter, createWebHistory, type RouterHistory, type RouteRecordRaw } from "vue-router";
import { authStore } from "../auth/authStore";
import type { PermissionCode } from "../auth/permissions";
import AuditLogsPage from "../pages/AuditLogsPage.vue";
import DashboardPage from "../pages/DashboardPage.vue";
import DispatchWorkbenchPage from "../pages/DispatchWorkbenchPage.vue";
import OrdersPage from "../pages/OrdersPage.vue";
import ResourcesPage from "../pages/ResourcesPage.vue";
import RulesPage from "../pages/RulesPage.vue";
import TasksPage from "../pages/TasksPage.vue";
import LoginPage from "../pages/LoginPage.vue";

export const routes: RouteRecordRaw[] = [
  { path: "/login", name: "login", component: LoginPage, meta: { public: true } },
  { path: "/", name: "dashboard", component: DashboardPage, meta: { title: "运营看板", permission: "METRICS_READ" } },
  { path: "/dispatch", name: "dispatch", component: DispatchWorkbenchPage, meta: { title: "调度工作台", permission: "DISPATCH_EXECUTE" } },
  { path: "/resources", name: "resources", component: ResourcesPage, meta: { title: "资源配置", permission: "RESOURCE_MANAGE" } },
  { path: "/rules", name: "rules", component: RulesPage, meta: { title: "规则配置", permission: "RULE_MANAGE" } },
  { path: "/orders", name: "orders", component: OrdersPage, meta: { title: "订单中心", permission: "ORDER_READ" } },
  { path: "/tasks", name: "tasks", component: TasksPage, meta: { title: "车辆任务", permission: "TASK_READ" } },
  { path: "/audit-logs", name: "auditLogs", component: AuditLogsPage, meta: { title: "审计日志", permission: "AUDIT_READ" } }
];

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({ history, routes });
  router.beforeEach(async (to) => {
    if (to.meta.public === true) {
      return true;
    }
    if (!(await authStore.restore())) {
      return { name: "login", query: { redirect: to.fullPath } };
    }
    const requiredPermission = to.meta.permission as PermissionCode | undefined;
    if (requiredPermission === undefined || authStore.has(requiredPermission)) {
      return true;
    }
    const fallback = routes.find((route) => {
      const permission = route.meta?.permission as PermissionCode | undefined;
      return route.name !== to.name && permission !== undefined && authStore.has(permission);
    });
    return fallback === undefined ? { name: "login" } : { name: fallback.name as string };
  });
  return router;
}

export default createAppRouter();
