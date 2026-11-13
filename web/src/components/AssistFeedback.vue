<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { Model } from "../api/types";
import { get, post } from "../api/client";
import { useLatestQuery } from "../composables/useLatestQuery";
import { dateTime, errorText, notify } from "../ui";

const props = defineProps<{ repairId: string; suggestionId?: string }>();
const history = ref<Model<"AssistFeedback">[]>([]);
const read = useLatestQuery();
const selected = ref("");
const decision = ref<Model<"AssistFeedbackRequest">["decision"]>("ADOPTED");
const reason = ref(""),
  outcome = ref(""),
  busy = ref(false),
  error = ref("");
let generation = 0,
  disposed = false;
const labels: Record<string, string> = {
  ADOPTED: "采用",
  MODIFIED: "修改后采用",
  IGNORED: "未采用",
};
const choices = computed(() => {
  const options = new Map<string, string>();
  if (props.suggestionId) options.set(props.suggestionId, "当前生成的建议");
  for (const item of history.value) {
    if (item.canFollowUp && !options.has(item.suggestionId))
      options.set(item.suggestionId, `历史建议 · ${dateTime(item.createdAt)}`);
  }
  return [...options].map(([id, label]) => ({ id, label }));
});
async function load() {
  const id = props.repairId;
  await read.run(
    (signal) =>
      get<Model<"AssistFeedbackList">>(
        `/ai/manager/repairs/${encodeURIComponent(id)}/feedback`,
        {},
        signal,
      ),
    (result) => {
      history.value = result.items;
      if (!selected.value) selected.value = choices.value[0]?.id || "";
    },
  );
}
watch(
  () => props.repairId,
  () => {
    generation++;
    read.cancel();
    history.value = [];
    selected.value = props.suggestionId || "";
    reason.value = "";
    outcome.value = "";
    error.value = "";
    busy.value = false;
    void load();
  },
  { immediate: true },
);
watch(
  () => props.suggestionId,
  (id) => {
    if (id && !busy.value) {
      selected.value = id;
      reason.value = "";
      outcome.value = "";
    }
  },
);
function followUp(item: Model<"AssistFeedback">) {
  if (busy.value || !item.canFollowUp) return;
  selected.value = item.suggestionId;
  decision.value = item.decision;
  reason.value = item.reason;
  outcome.value = item.outcome;
  error.value = "";
}
async function save() {
  if (busy.value || !selected.value) return;
  if (!reason.value.trim()) {
    error.value = "请填写采用、修改或未采用的原因";
    return;
  }
  const current = generation,
    id = props.repairId;
  const body: Model<"AssistFeedbackRequest"> = {
    suggestionId: selected.value,
    decision: decision.value,
    reason: reason.value.trim(),
    outcome: outcome.value.trim(),
  };
  busy.value = true;
  error.value = "";
  try {
    await post<Model<"AssistFeedback">>(
      `/ai/manager/repairs/${encodeURIComponent(id)}/feedback`,
      body,
    );
    if (disposed || current !== generation) return;
    reason.value = "";
    outcome.value = "";
    notify("反馈已留存，可稍后继续补充办理结果");
    await load();
  } catch (cause) {
    if (!disposed && current === generation) error.value = errorText(cause);
  } finally {
    if (!disposed && current === generation) busy.value = false;
  }
}
onBeforeUnmount(() => {
  disposed = true;
  generation++;
});
</script>

<template>
  <section class="assist-feedback" aria-label="AI建议反馈">
    <header>
      <h3>建议反馈与办理结果</h3>
      <button
        class="text-link"
        type="button"
        :disabled="read.busy.value || busy"
        @click="load"
      >
        刷新记录
      </button>
    </header>
    <p class="muted">
      记录人工判断及后续结果；反馈会追加保存。下方展示最近50条，工单仍按正常流程办理。
    </p>
    <form
      v-if="choices.length"
      class="manager-assist-form"
      @submit.prevent="save"
    >
      <label>
        针对的建议
        <select v-model="selected" :disabled="busy">
          <option v-for="item in choices" :key="item.id" :value="item.id">
            {{ item.label }}
          </option>
        </select>
      </label>
      <label>
        人工判断
        <select v-model="decision" :disabled="busy">
          <option value="ADOPTED">采用</option>
          <option value="MODIFIED">修改后采用</option>
          <option value="IGNORED">未采用</option>
        </select>
      </label>
      <label>
        判断原因
        <textarea
          v-model="reason"
          maxlength="1000"
          required
          :disabled="busy"
          placeholder="哪些建议有帮助，哪些需要调整？"
        />
      </label>
      <label>
        办理结果（可稍后补充）
        <textarea
          v-model="outcome"
          maxlength="1000"
          :disabled="busy"
          placeholder="记录实际核查与处理结果"
        />
      </label>
      <button class="btn" :disabled="busy || !reason.trim()">
        {{ busy ? "正在保存…" : "留存反馈" }}
      </button>
    </form>
    <p v-else-if="!read.busy.value" class="muted">
      生成本单研判后可留存反馈；已有记录可继续查看。
    </p>
    <p v-if="error || read.error.value" class="error-banner" role="alert">
      {{ error || read.error.value }}
    </p>
    <p v-if="read.busy.value" class="muted">正在读取历史反馈…</p>
    <ol v-if="history.length" class="assist-feedback-history">
      <li v-for="item in history" :key="item.id">
        <div>
          <strong>{{ labels[item.decision] || item.decision }}</strong>
          <small>
            {{ item.operatorName }} · {{ dateTime(item.createdAt) }}
          </small>
        </div>
        <p>{{ item.reason }}</p>
        <p v-if="item.outcome">办理结果：{{ item.outcome }}</p>
        <button
          v-if="item.canFollowUp"
          class="text-link"
          type="button"
          :disabled="busy"
          @click="followUp(item)"
        >
          补充办理结果
        </button>
      </li>
    </ol>
    <p v-else-if="!read.busy.value && !read.error.value" class="muted">
      暂无反馈记录。
    </p>
  </section>
</template>
