<script setup lang="ts">
import { ref, watch } from "vue";
import { useRouter } from "vue-router";
import { post } from "../api/client";
import type { Model } from "../api/types";
import { isMobilePhone, mobilePhoneError } from "../phone";
import { clearLocalSession } from "../session";
import { errorText, notify } from "../ui";

import { useResendWait } from "../composables/useResendWait";
const { seconds: wait, start: startWait } = useResendWait();
const showRecovery = ref(false);
const router = useRouter();
const phone = ref(""),
  challengeId = ref(""),
  code = ref(""),
  proof = ref<Model<"PasswordRecoveryVerifyResult">>(),
  recoveryCode = ref(""),
  password = ref(""),
  confirmation = ref(""),
  busy = ref(false),
  error = ref("");

watch(phone, () => {
  challengeId.value = code.value = error.value = "";
});

async function act(operation: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await operation();
  } catch (cause) {
    error.value = errorText(cause);
  } finally {
    busy.value = false;
  }
}

function requestCode() {
  if (wait.value) return;
  return act(async () => {
    if (!isMobilePhone(phone.value)) {
      error.value = mobilePhoneError;
      return;
    }
    const result = await post<Model<"PhoneChallengeResult">>(
      "/auth/password-recovery/challenges",
      {
        phone: phone.value.trim(),
        clientType: "WEB",
      } satisfies Model<"PasswordRecoveryChallengeRequest">,
    );
    startWait();
    challengeId.value = result.challengeId;
    code.value = "";
  });
}

function verify() {
  return act(async () => {
    if (!challengeId.value) return;
    proof.value = await post<Model<"PasswordRecoveryVerifyResult">>(
      "/auth/password-recovery/verify",
      {
        challengeId: challengeId.value,
        code: code.value.trim(),
        clientType: "WEB",
      } satisfies Model<"PasswordRecoveryVerifyRequest">,
    );
    challengeId.value = code.value = phone.value = "";
  });
}

function complete() {
  return act(async () => {
    if (!proof.value) return;
    if (password.value !== confirmation.value) {
      error.value = "两次输入的新密码不一致";
      return;
    }
    if (proof.value.recoveryCodeRequired && !recoveryCode.value.trim()) {
      error.value = "请输入宿管恢复码";
      return;
    }
    await post("/auth/password-recovery/complete", {
      resetToken: proof.value.resetToken,
      newPassword: password.value,
      clientType: "WEB",
      recoveryCode: proof.value.recoveryCodeRequired
        ? recoveryCode.value.trim()
        : undefined,
    } satisfies Model<"PasswordRecoveryCompleteRequest">);
    reset();
    clearLocalSession();
    notify("密码已重设，请使用新密码登录");
    await router.replace("/login");
  });
}
function reset() {
  showRecovery.value = false;
  proof.value = undefined;
  password.value = confirmation.value = recoveryCode.value = error.value = "";
}
</script>

<template>
  <main class="login-page" id="main-content">
    <section class="login-story">
      <RouterLink class="brand" to="/login">寓见</RouterLink>
      <div>
        <h1>找回密码</h1>
        <p>验证账号绑定的手机号后重设密码。</p>
      </div>
      <p class="form-note">
        未绑定、遗失号码、长期收不到短信或找不到恢复码时，请联系学校账号维护人员核验身份。
      </p>
    </section>
    <section class="login-form">
      <form
        @submit.prevent="
          proof ? complete() : challengeId ? verify() : requestCode()
        "
      >
        <h2>{{ proof ? "设置新密码" : "验证绑定号码" }}</h2>
        <template v-if="!proof">
          <label>
            已绑定手机号
            <input
              v-model="phone"
              type="tel"
              inputmode="numeric"
              autocomplete="tel"
              :disabled="busy"
              :aria-invalid="error === mobilePhoneError"
              :aria-describedby="
                error === mobilePhoneError ? 'recovery-phone-error' : undefined
              "
            />
            <small
              v-if="error === mobilePhoneError"
              id="recovery-phone-error"
              class="field-error"
              role="alert"
            >
              {{ error }}
            </small>
          </label>
          <template v-if="challengeId">
            <p class="form-note" role="status">
              如果该号码可以找回密码，验证码将发送到此号码。
            </p>
            <label>
              短信验证码
              <input
                v-model="code"
                required
                inputmode="numeric"
                autocomplete="one-time-code"
                :disabled="busy"
              />
            </label>
            <button
              class="text-link"
              type="button"
              :disabled="busy || wait > 0"
              @click="requestCode"
            >
              {{ wait ? wait + "秒后可重发" : "重新发送验证码" }}
            </button>
          </template>
        </template>
        <template v-else>
          <label v-if="proof.recoveryCodeRequired">
            宿管恢复码
            <input
              v-model="recoveryCode"
              required
              name="recovery-code"
              :type="showRecovery ? 'text' : 'password'"
              autocomplete="off"
              :disabled="busy"
            />
            <small>请输入绑定时保存的恢复码，重设成功后请重新生成。</small>
          </label>
          <button
            v-if="proof.recoveryCodeRequired"
            type="button"
            class="text-link"
            :aria-pressed="showRecovery"
            @click="showRecovery = !showRecovery"
          >
            {{ showRecovery ? "隐藏恢复码" : "显示恢复码" }}
          </button>
          <label>
            新密码
            <input
              v-model="password"
              required
              type="password"
              minlength="10"
              maxlength="72"
              autocomplete="new-password"
              :disabled="busy"
            />
            <small>至少10位字符，建议包含字母、数字或符号。</small>
          </label>
          <label>
            确认新密码
            <input
              v-model="confirmation"
              required
              type="password"
              minlength="10"
              maxlength="72"
              autocomplete="new-password"
              :disabled="busy"
            />
          </label>
          <button
            class="text-link"
            type="button"
            :disabled="busy"
            @click="reset"
          >
            重新验证手机号
          </button>
        </template>
        <p
          v-if="error && error !== mobilePhoneError"
          class="error-banner"
          role="alert"
        >
          {{ error }}
        </p>
        <button
          class="btn primary full"
          type="submit"
          :disabled="busy || (!proof && !challengeId && wait > 0)"
        >
          {{
            busy
              ? "正在处理…"
              : proof
                ? "重设密码并返回登录"
                : challengeId
                  ? "验证短信"
                  : wait
                    ? wait + "秒后可重发"
                    : "发送验证码"
          }}
        </button>
        <RouterLink to="/login">返回登录</RouterLink>
      </form>
    </section>
  </main>
</template>
