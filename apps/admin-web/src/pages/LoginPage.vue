<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { authStore } from "../auth/authStore";

const route = useRoute();
const router = useRouter();
const username = ref("");
const password = ref("");
const submitting = ref(false);
const errorMessage = ref("");

async function submit(): Promise<void> {
  submitting.value = true;
  errorMessage.value = "";
  try {
    await authStore.login(username.value, password.value);
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/";
    await router.replace(redirect);
  } catch {
    errorMessage.value = "用户名或密码不正确";
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-panel" aria-labelledby="login-title">
      <p class="eyebrow">DRT OPS</p>
      <h1 id="login-title">区域动态响应公交</h1>
      <p class="intro">企业运营管理</p>
      <form class="login-form" @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model="username" autocomplete="username" required />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" required />
        </label>
        <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
        <button class="primary-button" type="submit" :disabled="submitting">登录</button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-page { display: grid; min-height: 100vh; place-items: center; background: #18221e; padding: 24px; }
.login-panel { width: min(100%, 420px); border-top: 4px solid #8bd6bc; background: #f8faf8; padding: 36px; }
.eyebrow { margin: 0 0 12px; color: #17634b; font-size: 12px; font-weight: 900; letter-spacing: 0; }
h1 { margin: 0; color: #17201c; font-size: 30px; line-height: 1.2; }
.intro { margin: 10px 0 28px; color: #66736d; }
.login-form { display: grid; gap: 16px; }
label { display: grid; gap: 7px; color: #53615a; font-size: 13px; font-weight: 800; }
input { border: 1px solid #cfd8d3; border-radius: 6px; min-height: 42px; padding: 8px 10px; }
.error { margin: 0; color: #8f2f2f; font-size: 14px; }
.primary-button { width: 100%; }
</style>
