<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { clearCsrf, post } from "../api/client";
import type { Model } from "../api/types";
import { logout, session } from "../session";
import { errorText, notify } from "../ui";
const router = useRouter();
const oldPassword = ref(""),
  password = ref(""),
  confirmation = ref(""),
  busy = ref(false),
  error = ref("");
async function save() {
  error.value = "";
  if (password.value !== confirmation.value) {
    error.value = "两次输入的新密码不一致";
    return;
  }
  if (password.value === oldPassword.value) {
    error.value = "新密码不能与临时密码相同";
    return;
  }
  busy.value = true;
  try {
    await post("/auth/change-password", {
      oldPassword: oldPassword.value,
      newPassword: password.value,
    } satisfies Model<"PasswordRequest">);
    session.user = null;
    clearCsrf();
    oldPassword.value = password.value = confirmation.value = "";
    notify("密码已设置，请使用新密码登录");
    await router.replace("/login");
  } catch (cause) {
    error.value = errorText(cause);
  } finally {
    busy.value = false;
  }
}
async function leave() {
  busy.value = true;
  try {
    await logout();
    await router.replace("/login");
  } catch (cause) {
    error.value = errorText(cause);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="login-page">
    <section class="login-story">
      <a class="brand" href="/">
        寓见
        <span>DormLink</span>
      </a>
      <div>
        <p class="section-note">首次使用 · 账号保护</p>
        <h1>
          从自己的密码，
          <br />
          开始本次值班。
        </h1>
        <p>临时凭据仅用于首次设置。完成后，原有登录会话会失效。</p>
      </div>
      <p class="form-note">
        遗失登录密码时，请联系学校账号维护人员核验身份并重新签发临时凭据。
      </p>
    </section>
    <section class="login-form">
      <form @submit.prevent="save">
        <p class="section-note">{{ session.user?.displayName }}</p>
        <h2>设置登录密码</h2>
        <p class="muted">请先完成密码设置，再进入服务工作台。</p>
        <label>
          临时密码
          <input
            v-model="oldPassword"
            type="password"
            required
            autocomplete="current-password"
            maxlength="128"
          />
        </label>
        <label>
          新密码
          <input
            v-model="password"
            type="password"
            required
            minlength="10"
            maxlength="72"
            autocomplete="new-password"
          />
          <small>至少10位，UTF-8长度不超过72字节。</small>
        </label>
        <label>
          确认新密码
          <input
            v-model="confirmation"
            type="password"
            required
            autocomplete="new-password"
            maxlength="72"
          />
        </label>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <button class="btn primary full" :disabled="busy">
          {{ busy ? "正在设置…" : "设置密码并重新登录" }}
        </button>
        <button class="btn full" type="button" :disabled="busy" @click="leave">
          退出当前账号
        </button>
      </form>
    </section>
  </main>
</template>
