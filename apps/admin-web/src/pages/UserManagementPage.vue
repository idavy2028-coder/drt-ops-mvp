<script setup lang="ts">
import { onMounted, ref } from "vue";
import { createUser, listUsers, resetPassword, setUserEnabled, updateUserRoles } from "../api/users";
import type { UserAccount } from "../api/types";
import StatusBadge from "../components/StatusBadge.vue";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";

const users = ref<UserAccount[]>([]);
const status = ref("");
const showCreate = ref(false);
const loading = ref(false);
const submitting = ref(false);
const roleCodes = ["SYSTEM_ADMIN", "DISPATCHER", "OPERATOR", "AUDITOR"];
const newUser = ref({ username: "", displayName: "", temporaryPassword: "", roles: ["OPERATOR"] as string[] });
const selectedRoles = ref<Record<string, string>>({});
const resetPasswords = ref<Record<string, string>>({});

function roleLabel(role: string) {
  return ({ SYSTEM_ADMIN: "系统管理员", DISPATCHER: "调度员", OPERATOR: "运营专员", AUDITOR: "审计员" } as Record<string, string>)[role] ?? role;
}

async function load() {
  status.value = "";
  loading.value = true;
  try {
    users.value = await listUsers();
    selectedRoles.value = Object.fromEntries(users.value.map((user) => [user.id, user.roles[0] ?? "OPERATOR"]));
  } catch (error) {
    status.value = userMessage(error, "用户数据加载失败");
  } finally {
    loading.value = false;
  }
}

async function runAction(action: () => Promise<unknown>, successMessage: string, fallback: string) {
  submitting.value = true;
  try {
    await action();
    feedbackStore.success(successMessage);
    await load();
  } catch (error) {
    status.value = userMessage(error, fallback);
    feedbackStore.error(status.value);
  } finally {
    submitting.value = false;
  }
}

async function toggle(user: UserAccount) {
  await runAction(() => setUserEnabled(user.id, !user.enabled), `${user.username} 已${user.enabled ? "停用" : "启用"}`, "账号状态更新失败");
}

async function reset(user: UserAccount) {
  const temporaryPassword = resetPasswords.value[user.id]?.trim();
  if (!temporaryPassword) {
    status.value = `请输入 ${user.username} 的新临时密码`;
    feedbackStore.info(status.value);
    return;
  }
  await runAction(async () => {
    await resetPassword(user.id, temporaryPassword);
    resetPasswords.value[user.id] = "";
  }, `${user.username} 的临时密码已重置`, "密码重置失败");
}

async function create() {
  await runAction(async () => {
    await createUser(newUser.value);
    showCreate.value = false;
    newUser.value = { username: "", displayName: "", temporaryPassword: "", roles: ["OPERATOR"] };
  }, "用户已创建", "用户创建失败");
}

async function saveRoles(user: UserAccount) {
  await runAction(() => updateUserRoles(user.id, [selectedRoles.value[user.id]]), `${user.username} 的角色已更新`, "角色更新失败");
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">USERS</p>
        <h2 class="page-title">用户与权限</h2>
        <p class="page-subtitle">维护企业运营账号、岗位权限和账号生命周期，所有高风险操作都会记录审计日志。</p>
      </div>
      <div class="toolbar">
        <button class="primary-button" type="button" :disabled="submitting" @click="showCreate = !showCreate">新建用户</button>
        <button class="secondary-button" type="button" :disabled="loading || submitting" @click="load">{{ loading ? "同步中" : "刷新" }}</button>
      </div>
    </header>

    <form v-if="showCreate" class="work-panel form-grid" @submit.prevent="create">
      <label class="field"><span>用户名</span><input v-model="newUser.username" required :disabled="submitting" /></label>
      <label class="field"><span>姓名</span><input v-model="newUser.displayName" required :disabled="submitting" /></label>
      <label class="field"><span>临时密码</span><input v-model="newUser.temporaryPassword" type="password" required :disabled="submitting" /></label>
      <label class="field"><span>初始角色</span><select v-model="newUser.roles[0]" :disabled="submitting"><option v-for="role in roleCodes" :key="role" :value="role">{{ roleLabel(role) }}</option></select></label>
      <div class="toolbar"><button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? "正在创建" : "创建用户" }}</button></div>
    </form>

    <p v-if="loading" class="page-state">正在同步用户与权限配置…</p>
    <p v-else-if="status" class="page-state">{{ status }}</p>

    <section class="table-panel">
      <table class="data-table">
        <thead><tr><th>用户名</th><th>姓名</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td>{{ user.username }}</td>
            <td>{{ user.displayName }}</td>
            <td><select v-model="selectedRoles[user.id]" :disabled="submitting"><option v-for="role in roleCodes" :key="role" :value="role">{{ roleLabel(role) }}</option></select></td>
            <td><StatusBadge :code='user.enabled ? "ENABLED" : "DISABLED"' /></td>
            <td>
              <div class="toolbar">
                <button class="secondary-button" type="button" :disabled="submitting" @click="saveRoles(user)">保存角色</button>
                <input v-model="resetPasswords[user.id]" :aria-label="`${user.username} 新临时密码`" class="reset-password-input" type="password" autocomplete="new-password" placeholder="新临时密码" :disabled="submitting" />
                <button class="secondary-button" type="button" :disabled="submitting" @click="reset(user)">重置密码</button>
                <button class="danger-button" type="button" :disabled="submitting" @click="toggle(user)">{{ user.enabled ? "停用" : "启用" }}</button>
              </div>
            </td>
          </tr>
          <tr v-if="users.length === 0"><td colspan="5">暂无用户，可创建运营专员、调度员或审计员账号。</td></tr>
        </tbody>
      </table>
    </section>
  </section>
</template>

<style scoped>
.reset-password-input { border: 1px solid #cfd8d3; border-radius: 6px; min-height: 38px; padding: 8px 10px; width: 150px; }
</style>
