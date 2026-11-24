<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ApiError, get, post } from "../api/client";
import type { Model, User } from "../api/types";
import { isMobilePhone, mobilePhoneError } from "../phone";
import { clearLocalSession, session } from "../session";
import { errorText, notify } from "../ui";

const props = defineProps<{ activation?: boolean }>();
const router = useRouter();
type Action = "bind" | "change" | "replace" | "password";
const action = ref<Action>();
const phoneMasked = computed(() => session.user?.phoneMasked);
const mode = computed(() => (props.activation ? "activate" : action.value));
const titles = {
  password: "修改登录密码",
  activate: "验证手机号并激活",
  bind: "绑定登录手机号",
  change: "更换登录手机号",
  replace: "生成新恢复码",
};
const phone = ref(""),
  currentPassword = ref(""),
  newPassword = ref(""),
  confirmation = ref(""),
  recoveryCode = ref(""),
  completed = ref(false),
  signedOut = ref(false),
  busy = ref(false),
  phoneError = ref(""),
  error = ref("");
import { useResendWait } from "../composables/useResendWait";
import { copyText } from "../utils/copyText";
const { seconds: oldWait, start: startOldWait } = useResendWait(),
  { seconds: nextWait, start: startNextWait } = useResendWait();
const old = reactive({ id: "", code: "" });
const next = reactive({ id: "", code: "" });
const needsPhone = computed(
  () => mode.value !== "replace" && mode.value !== "password",
);
const purpose = computed<Model<"PhoneChallengeRequest">["purpose"]>(() =>
  mode.value === "activate"
    ? "ACTIVATE"
    : mode.value === "change"
      ? "CHANGE_PHONE_NEW"
      : mode.value === "replace"
        ? "REPLACE_RECOVERY_CODE"
        : "BIND",
);

watch(phone, () => {
  next.id = next.code = phoneError.value = "";
});
watch(
  currentPassword,
  () => {
    error.value = "";
  },
  { flush: "sync" },
);
watch(
  () => session.user?.id,
  (id) => {
    if (id || !signedOut.value) {
      clearForm();
      recoveryCode.value = "";
      completed.value = false;
    }
  },
);

function clearForm() {
  phone.value =
    currentPassword.value =
    newPassword.value =
    confirmation.value =
      "";
  old.id = old.code = next.id = next.code = phoneError.value = error.value = "";
}

function select(nextAction?: Action) {
  clearForm();
  action.value = nextAction;
}

async function act(operation: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  phoneError.value = error.value = "";
  try {
    await operation();
  } catch (cause) {
    if (
      cause instanceof ApiError &&
      cause.status === 401 &&
      cause.code === "UNAUTHORIZED"
    ) {
      signedOut.value = completed.value;
      clearLocalSession();
      if (!completed.value) await router.replace("/login");
      return;
    }
    error.value = errorText(cause);
  } finally {
    busy.value = false;
  }
}

function sendCode(forOld = false) {
  if (forOld ? oldWait.value : nextWait.value) return;
  return act(async () => {
    if (mode.value !== "activate" && !currentPassword.value) {
      error.value = "请输入当前密码后获取验证码";
      return;
    }
    const toNewPhone = !forOld && needsPhone.value;
    if (toNewPhone && !isMobilePhone(phone.value)) {
      phoneError.value = mobilePhoneError;
      return;
    }
    const challenge = forOld ? old : next;
    const result = await post<Model<"PhoneChallengeResult">>(
      "/auth/phone/challenges",
      {
        purpose: forOld ? "CHANGE_PHONE_OLD" : purpose.value,
        phone: toNewPhone ? phone.value.trim() : undefined,
        currentPassword:
          mode.value === "activate" ? undefined : currentPassword.value,
      } satisfies Model<"PhoneChallengeRequest">,
    );
    if (forOld) startOldWait();
    else startNextWait();
    challenge.id = result.challengeId;
    challenge.code = "";
  });
}

function submit() {
  return act(async () => {
    const selected = mode.value;
    if (selected === "password") {
      if (newPassword.value !== confirmation.value) {
        error.value = "两次输入的新密码不一致";
        return;
      }
      await post("/auth/change-password", {
        oldPassword: currentPassword.value,
        newPassword: newPassword.value,
      });
      clearForm();
      clearLocalSession();
      notify("密码已修改，请使用新密码登录");
      await router.replace("/login");
      return;
    }
    if (
      !selected ||
      !next.id ||
      !next.code.trim() ||
      (selected === "change" && (!old.id || !old.code.trim()))
    ) {
      error.value = "请获取并填写所需的短信验证码";
      return;
    }
    const code = { challengeId: next.id, code: next.code.trim() };
    let result: Model<"AccountSecurityResult">;
    if (selected === "activate") {
      if (newPassword.value !== confirmation.value) {
        error.value = "两次输入的新密码不一致";
        return;
      }
      result = await post("/auth/activate", {
        ...code,
        newPassword: newPassword.value,
      } satisfies Model<"ActivateRequest">);
    } else if (selected === "change") {
      result = await post("/auth/phone/change", {
        oldChallengeId: old.id,
        oldCode: old.code.trim(),
        newChallengeId: next.id,
        newCode: next.code.trim(),
      } satisfies Model<"PhoneChangeRequest">);
    } else {
      result = await post(
        selected === "bind"
          ? "/auth/phone/bind"
          : "/auth/recovery-code/replace",
        code satisfies Model<"PhoneCodeRequest">,
      );
    }
    recoveryCode.value = result.recoveryCode ?? "";
    completed.value = true;
    signedOut.value = selected === "activate" || selected === "change";
    clearForm();
    if (signedOut.value) {
      clearLocalSession();
    } else {
      const accountId = session.user?.id;
      const user = await get<User>("/auth/me");
      if (session.user?.id === accountId) session.user = user;
    }
  });
}

async function acknowledge() {
  if (busy.value) return;
  recoveryCode.value = "";
  completed.value = false;
  select();
  if (signedOut.value) await router.replace("/login");
}
</script>

<template>
  <div
    :id="activation ? 'main-content' : undefined"
    :class="{ 'login-page': activation }"
  >
    <section v-if="activation" class="login-story">
      <RouterLink class="brand" to="/activate-phone">寓见</RouterLink>
      <div>
        <h1>激活宿管账号</h1>
        <p>验证手机号并设置登录密码。</p>
      </div>
      <p class="form-note">长期无法收码时，请联系学校账号维护人员核验身份。</p>
    </section>
    <div v-else class="page-heading"><h1>账号安全</h1></div>
    <section :class="activation ? 'login-form' : 'panel account-panel'">
      <div v-if="completed" class="form" aria-live="polite">
        <h2>{{ recoveryCode ? "保存宿管恢复码" : "设置已完成" }}</h2>
        <template v-if="recoveryCode">
          <p class="form-note">
            恢复码仅展示此次。请离线妥善保存，找回密码时需同时提供短信验证码和恢复码。
          </p>
          <code class="recovery-code">{{ recoveryCode }}</code>
          <button
            type="button"
            class="btn"
            @click="copyText(recoveryCode, '恢复码已复制')"
          >
            复制恢复码
          </button>
        </template>
        <p v-if="signedOut" class="form-note">请使用更新后的凭据重新登录。</p>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <button
          class="btn primary"
          type="button"
          :disabled="busy"
          @click="acknowledge"
        >
          {{
            signedOut
              ? recoveryCode
                ? "我已保存，重新登录"
                : "返回登录"
              : recoveryCode
                ? "我已保存"
                : "完成"
          }}
        </button>
      </div>
      <div v-else-if="!mode" class="form">
        <dl class="details">
          <div>
            <dt>登录手机号</dt>
            <dd>{{ phoneMasked || "未绑定" }}</dd>
          </div>
          <div v-if="phoneMasked">
            <dt>宿管恢复码</dt>
            <dd>
              {{ session.user?.recoveryCodeConfigured ? "已生成" : "待生成" }}
            </dd>
          </div>
        </dl>
        <div class="button-row">
          <button
            v-if="!phoneMasked"
            class="btn primary"
            type="button"
            @click="select('bind')"
          >
            绑定手机号
          </button>
          <template v-else>
            <button class="btn" type="button" @click="select('change')">
              更换手机号
            </button>
            <button class="btn" type="button" @click="select('replace')">
              生成新恢复码
            </button>
          </template>
          <button class="btn" type="button" @click="select('password')">
            修改登录密码
          </button>
        </div>
        <p class="form-note">
          遗失原号码或需要人工恢复时，请联系学校账号维护人员核验身份。
        </p>
      </div>
      <form v-else class="form" @submit.prevent="submit">
        <h2>{{ titles[mode] }}</h2>
        <p v-if="mode === 'change'" class="form-note">
          请分别验证原号码 {{ phoneMasked }} 和新号码。换绑后需要重新登录。
        </p>
        <p v-if="mode === 'replace'" class="form-note">
          验证绑定号码 {{ phoneMasked }} 后生成新恢复码，原码随即失效。
        </p>
        <label v-if="!activation">
          当前密码 *
          <input
            v-model="currentPassword"
            type="password"
            required
            autocomplete="current-password"
            :disabled="busy"
          />
        </label>
        <template v-if="mode === 'change'">
          <button
            type="button"
            class="btn"
            :disabled="busy || oldWait > 0"
            @click="sendCode(true)"
          >
            {{
              oldWait
                ? oldWait + "秒后可重发"
                : old.id
                  ? "重新发送旧号验证码"
                  : "向旧号发送验证码"
            }}
          </button>
          <label v-if="old.id">
            旧号验证码
            <input
              v-model="old.code"
              required
              inputmode="numeric"
              autocomplete="one-time-code"
              :disabled="busy"
            />
          </label>
        </template>
        <label v-if="needsPhone">
          {{ mode === "change" ? "新手机号" : "手机号" }}
          <input
            v-model="phone"
            type="tel"
            inputmode="numeric"
            autocomplete="tel"
            :disabled="busy"
            :aria-invalid="!!phoneError"
            :aria-describedby="phoneError ? 'account-phone-error' : undefined"
          />
          <small
            v-if="phoneError"
            id="account-phone-error"
            class="field-error"
            role="alert"
          >
            {{ phoneError }}
          </small>
        </label>
        <button
          v-if="mode !== 'password'"
          type="button"
          class="btn"
          :disabled="busy || nextWait > 0"
          @click="sendCode()"
        >
          {{
            nextWait
              ? nextWait + "秒后可重发"
              : next.id
                ? "重新发送验证码"
                : mode === "change"
                  ? "向新号发送验证码"
                  : "发送验证码"
          }}
        </button>
        <p v-if="old.id || next.id" class="form-note" role="status">
          请在5分钟内输入短信验证码。60秒后可以重新发送。
        </p>
        <label v-if="next.id">
          {{ mode === "change" ? "新号验证码" : "短信验证码" }}
          <input
            v-model="next.code"
            required
            inputmode="numeric"
            :autocomplete="mode === 'change' ? 'off' : 'one-time-code'"
            :disabled="busy"
          />
        </label>
        <template v-if="(activation && next.id) || mode === 'password'">
          <label>
            新密码 *
            <input
              v-model="newPassword"
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
            确认新密码 *
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
        </template>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <button
          v-if="next.id || mode === 'password'"
          class="btn primary"
          type="submit"
          :disabled="busy || (mode === 'change' && !old.id)"
        >
          {{ busy ? "正在验证…" : titles[mode] }}
        </button>
        <RouterLink v-if="activation" class="text-link" to="/change-password">
          暂时无法收码，先设置密码
        </RouterLink>
        <button
          v-else
          type="button"
          class="text-link"
          :disabled="busy"
          @click="select()"
        >
          取消
        </button>
      </form>
    </section>
  </div>
</template>
