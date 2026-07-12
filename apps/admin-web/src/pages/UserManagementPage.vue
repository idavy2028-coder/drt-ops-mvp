<script setup lang="ts">
import { onMounted, ref } from "vue";
import { createUser, listUsers, resetPassword, setUserEnabled, updateUserRoles } from "../api/users";
import type { UserAccount } from "../api/types";

const users = ref<UserAccount[]>([]);
const status = ref("");
const showCreate = ref(false);
const roleCodes = ["SYSTEM_ADMIN", "DISPATCHER", "OPERATOR", "AUDITOR"];
const newUser = ref({ username: "", displayName: "", temporaryPassword: "", roles: ["OPERATOR"] as string[] });
const selectedRoles = ref<Record<string, string>>({});

async function load() { try { users.value = await listUsers(); selectedRoles.value = Object.fromEntries(users.value.map((user) => [user.id, user.roles[0] ?? "OPERATOR"])); } catch (error) { status.value = error instanceof Error ? error.message : "用户数据加载失败"; } }
async function toggle(user: UserAccount) { await setUserEnabled(user.id, !user.enabled); await load(); }
async function reset(user: UserAccount) { await resetPassword(user.id, "Temp123!"); status.value = `${user.username} 的临时密码已重置`; }
async function create() { await createUser(newUser.value); showCreate.value = false; newUser.value = { username: "", displayName: "", temporaryPassword: "", roles: ["OPERATOR"] }; await load(); }
async function saveRoles(user: UserAccount) { await updateUserRoles(user.id, [selectedRoles.value[user.id]]); await load(); }
onMounted(() => { void load(); });
</script>

<template>
  <section class="page"><header class="page-header"><div><p class="page-kicker">USERS</p><h2 class="page-title">用户与权限</h2><p class="page-subtitle">维护企业管理端账号、预置角色和账号状态。</p></div><div class="toolbar"><button class="primary-button" type="button" @click="showCreate = !showCreate">新建用户</button><button class="secondary-button" type="button" @click="load">刷新</button></div></header>
    <form v-if="showCreate" class="work-panel form-grid" @submit.prevent="create"><label class="field"><span>用户名</span><input v-model="newUser.username" required /></label><label class="field"><span>姓名</span><input v-model="newUser.displayName" required /></label><label class="field"><span>临时密码</span><input v-model="newUser.temporaryPassword" type="password" required /></label><label class="field"><span>初始角色</span><select v-model="newUser.roles[0]"><option v-for="role in roleCodes" :key="role">{{ role }}</option></select></label><div class="toolbar"><button class="primary-button" type="submit">创建</button></div></form><p v-if="status" class="section-copy">{{ status }}</p>
    <section class="table-panel"><table class="data-table"><thead><tr><th>用户名</th><th>姓名</th><th>角色</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="user in users" :key="user.id"><td>{{ user.username }}</td><td>{{ user.displayName }}</td><td><select v-model="selectedRoles[user.id]"><option v-for="role in roleCodes" :key="role">{{ role }}</option></select></td><td><span class="status-pill">{{ user.enabled ? '启用' : '停用' }}</span></td><td><div class="toolbar"><button class="secondary-button" type="button" @click="saveRoles(user)">保存角色</button><button class="secondary-button" type="button" @click="reset(user)">重置密码</button><button class="danger-button" type="button" @click="toggle(user)">{{ user.enabled ? '停用' : '启用' }}</button></div></td></tr><tr v-if="users.length === 0"><td colspan="5">暂无用户</td></tr></tbody></table></section>
  </section>
</template>
