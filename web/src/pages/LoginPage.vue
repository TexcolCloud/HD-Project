<script setup lang="ts">
import { ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { login, session } from "../session";
import { errorText, notify } from "../ui";
import Icon from "../components/Icon.vue";
import { isMobilePhone, mobilePhoneError } from "../phone";
const username = ref(""),
  password = ref(""),
  busy = ref(false),
  error = ref(""),
  mode = ref<"USERNAME" | "PHONE" | "ACTIVATE">("USERNAME");
const router = useRouter(),
  route = useRoute();
function selectMode(next: typeof mode.value) {
  if (mode.value === next) return;
  if (mode.value === "ACTIVATE" || next === "ACTIVATE") password.value = "";
  mode.value = next;
  error.value = "";
}
async function submit() {
  if (busy.value) return;
  error.value = "";
  const identifier = username.value.trim();
  if (mode.value === "PHONE" && !isMobilePhone(identifier)) {
    error.value = mobilePhoneError;
    return;
  }
  busy.value = true;
  try {
    const activating = mode.value === "ACTIVATE";
    await login(
      identifier,
      password.value,
      mode.value === "PHONE" ? "PHONE" : "USERNAME",
    );
    password.value = "";
    if (activating && session.user?.mustChangePassword) {
      await router.replace("/activate-phone");
      return;
    }
    if (activating) notify("账号可以正常登录，请在账号安全中管理登录手机号");
    const target =
      typeof route.query.redirect === "string" &&
      route.query.redirect.startsWith("/") &&
      !route.query.redirect.startsWith("//")
        ? route.query.redirect
        : "/dashboard";
    await router.replace(target);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main id="main-content" class="login-page">
    <section class="login-story">
      <a class="brand" href="/">寓见</a>
      <div>
        <h1>
          学生公寓
          <br />
          综合服务平台
        </h1>
      </div>
      <figure>
        <img
          width="1200"
          height="801"
          decoding="async"
          src="/assets/images/campus-courtyard.jpg"
          alt="庭院树影与暖色建筑，校园生活氛围示意"
        />
        <figcaption>
          校园生活氛围示意 ·
          <a href="/assets/images/SOURCES.md" target="_blank">图片来源与许可</a>
        </figcaption>
      </figure>
    </section>
    <section class="login-form">
      <form @submit.prevent="submit">
        <h2>{{ mode === "ACTIVATE" ? "宿管注册 / 激活" : "宿管登录" }}</h2>
        <p class="muted">
          {{
            mode === "ACTIVATE"
              ? "使用学校发放的账号和临时密码激活。"
              : "学生请使用鸿蒙端登录。"
          }}
        </p>
        <div
          v-if="mode !== 'ACTIVATE'"
          class="theme-switch"
          aria-label="登录方式"
        >
          <button
            type="button"
            :aria-pressed="mode === 'USERNAME'"
            :disabled="busy"
            @click="selectMode('USERNAME')"
          >
            账号
          </button>
          <button
            type="button"
            :aria-pressed="mode === 'PHONE'"
            :disabled="busy"
            @click="selectMode('PHONE')"
          >
            手机号
          </button>
        </div>
        <label v-if="mode === 'PHONE'">
          手机号
          <input
            v-model="username"
            name="username"
            type="tel"
            inputmode="numeric"
            autocomplete="username"
            placeholder="请输入11位已绑定手机号"
            :aria-invalid="error === mobilePhoneError"
            :aria-describedby="
              error === mobilePhoneError ? 'login-phone-error' : undefined
            "
            @input="error = ''"
          />
          <small
            v-if="error === mobilePhoneError"
            id="login-phone-error"
            class="field-error"
            role="alert"
          >
            {{ error }}
          </small>
        </label>
        <label v-else>
          学校账号
          <input
            v-model="username"
            name="username"
            required
            autocomplete="username"
            maxlength="80"
            placeholder="请输入宿管账号"
          />
        </label>
        <label>
          {{ mode === "ACTIVATE" ? "临时密码" : "密码" }}
          <input
            v-model="password"
            name="password"
            type="password"
            required
            autocomplete="current-password"
            maxlength="128"
            placeholder="请输入登录密码"
          />
        </label>
        <div
          v-if="error && error !== mobilePhoneError"
          class="error-banner"
          role="alert"
        >
          {{ error }}
        </div>
        <button class="btn primary full" type="submit" :disabled="busy">
          {{
            busy
              ? "正在验证…"
              : mode === "ACTIVATE"
                ? "验证并继续"
                : "登录管理端"
          }}
          <Icon name="arrow" />
        </button>
        <div class="button-row login-links">
          <button
            v-if="mode === 'ACTIVATE'"
            type="button"
            class="text-link"
            :disabled="busy"
            @click="selectMode('USERNAME')"
          >
            返回登录
          </button>
          <button
            v-else
            type="button"
            class="text-link"
            :disabled="busy"
            @click="selectMode('ACTIVATE')"
          >
            注册 / 激活
          </button>
          <RouterLink to="/password-recovery">忘记密码</RouterLink>
        </div>
        <p class="form-note">
          未收到初始账号或需要人工恢复，请联系学校账号维护人员核验身份。
        </p>
      </form>
    </section>
  </main>
</template>
