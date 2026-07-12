<script setup lang="ts">
import { computed } from "vue";
import { RouterLink, RouterView, useRouter } from "vue-router";
import { authStore } from "../auth/authStore";

const navItems = [
  { label: "运营看板", to: "/", permission: "METRICS_READ" },
  { label: "调度工作台", to: "/dispatch", permission: "DISPATCH_EXECUTE" },
  { label: "订单中心", to: "/orders", permission: "ORDER_READ" },
  { label: "车辆任务", to: "/tasks", permission: "TASK_READ" },
  { label: "资源配置", to: "/resources", permission: "RESOURCE_MANAGE" },
  { label: "规则配置", to: "/rules", permission: "RULE_MANAGE" },
  { label: "审计日志", to: "/audit-logs", permission: "AUDIT_READ" },
  { label: "用户与权限", to: "/users", permission: "USER_MANAGE" }
];

const visibleNavItems = computed(() => navItems.filter((item) => authStore.has(item.permission as never)));
const router = useRouter();
async function signOut() {
  try {
    await authStore.logout();
  } finally {
    await router.replace("/login");
  }
}

const today = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  weekday: "short"
}).format(new Date());
</script>

<template>
  <div class="ops-shell">
    <aside class="sidebar" aria-label="主导航">
      <div class="brand-block">
        <p class="brand-eyebrow">DRT OPS</p>
        <h1>区域动态响应公交</h1>
      </div>

      <nav class="nav-list">
        <RouterLink v-for="item in visibleNavItems" :key="item.to" :to="item.to" class="nav-link">
          {{ item.label }}
        </RouterLink>
      </nav>
    </aside>

    <div class="workspace">
      <header class="topbar">
        <div>
          <p class="topbar-kicker">企业运营闭环</p>
          <strong>实时调度 · 任务执行 · 审计追踪</strong>
        </div>
        <div class="account-summary"><span>{{ authStore.user?.username }}</span><button class="secondary-button" type="button" @click="signOut">退出</button><time>{{ today }}</time></div>
      </header>

      <main class="content-surface">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.ops-shell {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  min-height: 100vh;
  background:
    linear-gradient(90deg, rgba(23, 32, 28, 0.04) 1px, transparent 1px),
    linear-gradient(180deg, rgba(23, 32, 28, 0.04) 1px, transparent 1px),
    #eef2ef;
  background-size: 32px 32px;
}

.sidebar {
  display: flex;
  flex-direction: column;
  border-right: 1px solid #d6ded9;
  background: #18221e;
  color: #f8faf8;
  padding: 24px 18px;
}

.brand-block {
  border-bottom: 1px solid rgba(248, 250, 248, 0.18);
  padding-bottom: 22px;
}

.brand-eyebrow {
  margin: 0 0 10px;
  color: #8bd6bc;
  font-size: 12px;
  font-weight: 900;
  letter-spacing: 0;
}

.brand-block h1 {
  margin: 0;
  font-size: 22px;
  line-height: 1.25;
}

.nav-list {
  display: grid;
  gap: 6px;
  margin-top: 22px;
}

.nav-link {
  border: 1px solid transparent;
  border-radius: 8px;
  color: #cad6d0;
  padding: 12px 14px;
  font-size: 15px;
  font-weight: 800;
}

.nav-link:hover,
.nav-link.router-link-active {
  border-color: rgba(139, 214, 188, 0.35);
  background: rgba(139, 214, 188, 0.11);
  color: #ffffff;
}

.workspace {
  min-width: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid #d6ded9;
  background: rgba(255, 255, 255, 0.86);
  padding: 18px 28px;
}

.topbar-kicker {
  margin: 0 0 4px;
  color: #68746f;
  font-size: 12px;
  font-weight: 900;
}

.topbar time {
  color: #4d5b54;
  font-size: 14px;
  font-weight: 800;
}
.account-summary { display: flex; align-items: center; gap: 12px; color: #4d5b54; font-size: 14px; font-weight: 800; }

.content-surface {
  padding: 28px;
}

@media (max-width: 900px) {
  .ops-shell {
    grid-template-columns: 1fr;
  }

  .sidebar {
    border-right: 0;
    padding: 18px;
  }

  .nav-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .content-surface {
    padding: 18px;
  }
}
</style>
