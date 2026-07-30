<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { authStore } from "../auth/authStore";

const router = useRouter();
const currentPassword = ref("");
const newPassword = ref("");
const confirmedPassword = ref("");
const submitting = ref(false);
const errorMessage = ref("");

async function submit(): Promise<void> {
  errorMessage.value = "";
  if (newPassword.value.length < 12) {
    errorMessage.value = "新密码至少需要 12 个字符";
    return;
  }
  if (newPassword.value !== confirmedPassword.value) {
    errorMessage.value = "两次输入的新密码不一致";
    return;
  }
  submitting.value = true;
  try {
    await authStore.changePassword(currentPassword.value, newPassword.value);
    await router.replace({ name: "login", query: { passwordChanged: "true" } });
  } catch {
    errorMessage.value = "当前密码不正确或新密码不符合要求";
  } finally {
    submitting.value = false;
  }
}

async function logout(): Promise<void> {
  await authStore.logout();
  await router.replace({ name: "login" });
}
</script>

<template>
  <main class="password-page">
    <section class="password-panel" aria-labelledby="password-title">
      <p class="eyebrow">首次登录安全校验</p>
      <h1 id="password-title">修改临时密码</h1>
      <p class="intro">完成改密后，旧会话会立即失效，请使用新密码重新登录。</p>
      <form class="password-form" @submit.prevent="submit">
        <label>
          <span>当前密码</span>
          <input v-model="currentPassword" type="password" autocomplete="current-password" required />
        </label>
        <label>
          <span>新密码</span>
          <input v-model="newPassword" type="password" autocomplete="new-password" minlength="12" required />
        </label>
        <label>
          <span>确认新密码</span>
          <input v-model="confirmedPassword" type="password" autocomplete="new-password" minlength="12" required />
        </label>
        <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
        <button class="primary-button" type="submit" :disabled="submitting">修改密码</button>
        <button class="secondary-button" type="button" :disabled="submitting" @click="logout">退出登录</button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.password-page { display: grid; min-height: 100vh; place-items: center; background: #18221e; padding: 24px; }
.password-panel { width: min(100%, 460px); border-top: 4px solid #8bd6bc; background: #f8faf8; padding: 36px; }
.eyebrow { margin: 0 0 12px; color: #17634b; font-size: 12px; font-weight: 900; }
h1 { margin: 0; color: #17201c; font-size: 30px; line-height: 1.2; }
.intro { margin: 10px 0 28px; color: #66736d; line-height: 1.6; }
.password-form { display: grid; gap: 16px; }
label { display: grid; gap: 7px; color: #53615a; font-size: 13px; font-weight: 800; }
input { border: 1px solid #cfd8d3; border-radius: 6px; min-height: 42px; padding: 8px 10px; }
.error { margin: 0; color: #8f2f2f; font-size: 14px; }
.primary-button, .secondary-button { width: 100%; }
</style>
