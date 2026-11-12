<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { Model, Repair } from "../../api/types";
import { getDispatchBoard, takeoverDispatchTask } from "../../api/dispatch";
import { session } from "../../session";
import { useLatestQuery } from "../../composables/useLatestQuery";
import { errorText, notify } from "../../ui";

const props = defineProps<{ repair: Repair; disabled?: boolean }>();
const emit = defineEmits<{
  updated: [repair: Repair];
  dirty: [value: boolean];
  busy: [value: boolean];
  editing: [value: boolean];
}>();
const query = useLatestQuery();
const { busy: loading, error: readError } = query;
const board = ref<Model<"DispatchBoard">>();
const editing = ref(false),
  saving = ref(false),
  reason = ref(""),
  error = ref("");
let generation = 0;
const eligibleOrder = computed(
  () =>
    ["ACCEPTED", "PROCESSING"].includes(props.repair.status) &&
    !!props.repair.handlerUserId &&
    props.repair.handlerUserId !== session.user?.id,
);
const task = computed(
  () =>
    board.value?.routes
      .flatMap((route) => route.tasks)
      .find((item) => item.orderId === props.repair.id) ??
    board.value?.unassigned.find(
      (entry) => entry.task.orderId === props.repair.id,
    )?.task,
);
const ownerOnline = computed(
  () =>
    board.value?.routes.some(
      (route) =>
        route.manager.userId === props.repair.handlerUserId &&
        route.manager.online &&
        route.manager.buildingIds.includes(props.repair.buildingId),
    ) ?? false,
);
const canSubmit = computed(() => {
  const me = board.value?.me;
  return (
    !!task.value &&
    task.value.orderVersion === props.repair.version &&
    !ownerOnline.value &&
    !!me?.online &&
    me.acceptingNewTasks &&
    me.buildingIds.includes(props.repair.buildingId) &&
    !!me.availableUntil &&
    Date.parse(me.availableUntil) > Date.now()
  );
});
watch([editing, saving, loading, reason], () => {
  emit("editing", editing.value);
  emit("busy", saving.value || loading.value);
  emit("dirty", editing.value && !!reason.value.trim());
});
function reset() {
  generation++;
  query.cancel();
  board.value = undefined;
  editing.value = false;
  saving.value = false;
  reason.value = "";
  error.value = "";
}
watch(() => [props.repair.id, props.repair.version], reset);
onBeforeUnmount(() => {
  reset();
  emit("busy", false);
  emit("editing", false);
  emit("dirty", false);
});
async function readTask() {
  board.value = undefined;
  await query.run(
    (signal) => getDispatchBoard(signal),
    (value) => {
      board.value = value;
    },
  );
}
function begin() {
  editing.value = true;
  reason.value = "";
  error.value = "";
  void readTask();
}
function cancel() {
  if (reason.value.trim() && !window.confirm("接续原因尚未提交，确定放弃？"))
    return;
  reset();
}
async function submit() {
  if (!task.value || !canSubmit.value || !reason.value.trim() || saving.value)
    return;
  const epoch = generation;
  const orderId = props.repair.id;
  const body: Model<"DispatchTakeoverRequest"> = {
    expectedOrderVersion: props.repair.version,
    expectedTaskVersion: task.value.version,
    reason: reason.value.trim(),
  };
  saving.value = true;
  error.value = "";
  try {
    const updated = await takeoverDispatchTask(orderId, body);
    if (epoch !== generation) return;
    reset();
    emit("updated", updated);
    notify("办理责任已接续，请在任务路线核对现场并恢复办理");
  } catch (cause) {
    if (epoch === generation) error.value = errorText(cause);
  } finally {
    if (epoch === generation) saving.value = false;
  }
}
</script>
<template>
  <section v-if="eligibleOrder" class="detail-section">
    <h3>异常接续</h3>
    <p class="form-note">
      原办理人停用、失去本楼栋权限，或失联超过宽限时，可由有资格的宿管明确接续。原因与原办理记录一并保留。
    </p>
    <button
      v-if="!editing"
      type="button"
      class="btn"
      :disabled="disabled"
      @click="begin"
    >
      核对异常接续
    </button>
    <form v-else class="form dispatch-nested-form" @submit.prevent="submit">
      <p v-if="loading" class="loading" role="status">
        正在核对任务及当前接单资格…
      </p>
      <p v-if="readError" class="error-banner" role="alert">{{ readError }}</p>
      <p v-if="board && ownerOnline" class="info-note">
        原办理人仍在线且具有本楼栋权限，请联系本人明确交接。
      </p>
      <p v-else-if="board && !canSubmit" class="info-note">
        任务版本或本人接单资格已变化，请刷新工单，并在任务调度中核对可用时段与接单状态。
      </p>
      <label>
        接续原因 *
        <textarea
          v-model="reason"
          required
          maxlength="500"
          :disabled="loading || saving"
          placeholder="说明原办理人无法继续的原因，以及本次接续安排"
        />
      </label>
      <p class="form-note">
        服务端会再次核对原办理人状态及10分钟失联宽限。接续后任务暂停等待现场核对，不沿用他人的到场事实。
      </p>
      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
      <div class="button-row">
        <button
          class="btn primary"
          :disabled="loading || saving || !canSubmit || !reason.trim()"
        >
          {{ saving ? "正在接续…" : "确认由我接续" }}
        </button>
        <button
          v-if="readError"
          class="btn"
          type="button"
          :disabled="saving || loading"
          @click="readTask"
        >
          重新核对
        </button>
        <button
          class="btn"
          type="button"
          :disabled="saving || loading"
          @click="cancel"
        >
          取消接续
        </button>
      </div>
    </form>
  </section>
</template>
