<script setup lang="ts">
import { ref, reactive, computed, watch, onBeforeUnmount } from "vue";
import type { Repair, Model } from "../../api/types";
import {
  getDispatchBoard,
  saveRepairVisit,
  handoffDispatchTask,
  type DispatchBoard,
} from "../../api/dispatch";
import { session } from "../../session";
import { datetimeInput } from "../../composables/dispatchPlan";
import { dateTime, errorText, isoTime, notify } from "../../ui";

const props = defineProps<{ repair: Repair; disabled?: boolean }>();
const emit = defineEmits<{
  updated: [repair: Repair];
  dirty: [value: boolean];
  busy: [value: boolean];
  editing: [value: boolean];
}>();
const mode = ref<"visit" | "handoff" | "">(""),
  busy = ref(false),
  dirty = ref(false),
  error = ref(""),
  board = ref<DispatchBoard | null>(null),
  targetId = ref(""),
  handoffReason = ref("");
const form = reactive({
  windowStart: "",
  windowEnd: "",
  windowConfirmed: false,
  estimatedMinutes: 30,
  visitRequired: true,
  waitingReason: "",
});
let orderVersion = 0;
const canEdit = computed(
  () =>
    ["SUBMITTED", "ACCEPTED", "PROCESSING"].includes(props.repair.status) &&
    (props.repair.status === "SUBMITTED" ||
      props.repair.handlerUserId === session.user?.id),
);
const task = computed(
  () =>
    board.value?.routes
      .flatMap((route) => route.tasks)
      .find((item) => item.orderId === props.repair.id) ||
    board.value?.unassigned.find(
      (entry) => entry.task.orderId === props.repair.id,
    )?.task,
);
const canHandoff = computed(
  () =>
    props.repair.status === "ACCEPTED" &&
    props.repair.handlerUserId === session.user?.id,
);
const taskAllowsHandoff = computed(
  () =>
    !!task.value &&
    ["ASSIGNED", "BLOCKED"].includes(task.value.state) &&
    !task.value.locked,
);
const targets = computed(
  () =>
    board.value?.routes
      .map((route) => route.manager)
      .filter(
        (manager) =>
          manager.userId !== session.user?.id &&
          manager.online &&
          manager.acceptingNewTasks &&
          !!manager.availableUntil &&
          Date.parse(manager.availableUntil) > Date.now() &&
          manager.buildingIds.includes(props.repair.buildingId),
      ) || [],
);
watch([dirty, busy, mode], () => {
  emit("dirty", dirty.value);
  emit("busy", busy.value);
  emit("editing", !!mode.value);
});
watch(
  () => props.repair.id,
  () => {
    mode.value = "";
    dirty.value = false;
    error.value = "";
    board.value = null;
  },
);
onBeforeUnmount(() => {
  emit("dirty", false);
  emit("busy", false);
  emit("editing", false);
});
function beginVisit() {
  orderVersion = props.repair.version;
  Object.assign(form, {
    windowStart: datetimeInput(props.repair.windowStart),
    windowEnd: datetimeInput(props.repair.windowEnd),
    windowConfirmed: props.repair.windowConfirmed || false,
    estimatedMinutes: props.repair.estimatedMinutes || 30,
    visitRequired: props.repair.visitRequired ?? true,
    waitingReason: props.repair.waitingReason || "",
  });
  dirty.value = false;
  error.value = "";
  mode.value = "visit";
}
async function beginHandoff() {
  error.value = "";
  targetId.value = "";
  handoffReason.value = "";
  dirty.value = false;
  mode.value = "handoff";
  busy.value = true;
  try {
    board.value = await getDispatchBoard();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function cancel() {
  if (dirty.value && !window.confirm("访问安排尚未保存，确定放弃本次修改？"))
    return;
  mode.value = "";
  dirty.value = false;
  error.value = "";
}
async function saveVisit() {
  busy.value = true;
  error.value = "";
  try {
    if (
      (form.windowStart || form.windowEnd || form.windowConfirmed) &&
      (!form.windowStart || !form.windowEnd)
    )
      throw new Error("请完整填写访问开始和结束时间");
    const body: Model<"RepairVisitRequest"> = {
      expectedVersion: orderVersion,
      windowStart: isoTime(form.windowStart) || null,
      windowEnd: isoTime(form.windowEnd) || null,
      windowConfirmed: form.windowConfirmed,
      estimatedMinutes: Number(form.estimatedMinutes),
      visitRequired: form.visitRequired,
      waitingReason: form.waitingReason.trim(),
    };
    const updated = await saveRepairVisit(props.repair.id, body);
    dirty.value = false;
    mode.value = "";
    emit("updated", updated);
    notify("访问安排已保存，剩余路线将自动更新");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function handoff() {
  if (!task.value) {
    error.value = "尚未找到有效路线任务，请刷新工单后重试";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const body: Model<"DispatchHandoffRequest"> = {
      targetManagerId: targetId.value,
      reason: handoffReason.value.trim(),
      expectedOrderVersion: props.repair.version,
      expectedTaskVersion: task.value.version,
    };
    const updated = await handoffDispatchTask(props.repair.id, body);
    dirty.value = false;
    mode.value = "";
    emit("updated", updated);
    notify("办理责任已交接，原因与双方信息已留痕");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="repair-visit-panel detail-section">
    <header>
      <h3>访问安排与交接</h3>
      <RouterLink to="/dispatch" class="text-link">查看任务路线 →</RouterLink>
    </header>
    <dl v-if="!mode" class="details">
      <div>
        <dt>上门安排</dt>
        <dd>
          {{ repair.visitRequired === false ? "暂不安排上门" : "需要上门" }}
        </dd>
      </div>
      <div>
        <dt>预计处理时长</dt>
        <dd>{{ repair.estimatedMinutes || "—" }} 分钟（估算）</dd>
      </div>
      <div
        v-if="repair.windowStart && repair.windowEnd"
        class="dispatch-full-field"
      >
        <dt>
          {{ repair.windowConfirmed ? "双方已确认预约" : "可协商访问时段" }}
        </dt>
        <dd>
          {{ dateTime(repair.windowStart) }} — {{ dateTime(repair.windowEnd) }}
        </dd>
      </div>
      <div v-if="repair.waitingReason" class="dispatch-full-field">
        <dt>等待或暂缓原因</dt>
        <dd>{{ repair.waitingReason }}</dd>
      </div>
    </dl>
    <p v-if="!mode && !repair.windowStart" class="form-note">
      尚未约定结构化上门时段。原“方便联系时段”仅作沟通参考，不作为已确认预约。
    </p>
    <div v-if="!mode" class="button-row">
      <button
        v-if="canEdit"
        type="button"
        class="btn"
        :disabled="disabled"
        @click="beginVisit"
      >
        设置访问安排
      </button>
      <button
        v-if="canHandoff"
        type="button"
        class="btn"
        :disabled="disabled"
        @click="beginHandoff"
      >
        明确交接责任
      </button>
    </div>
    <form
      v-if="mode === 'visit'"
      class="form dispatch-nested-form"
      @input="dirty = true"
      @submit.prevent="saveVisit"
    >
      <label class="checkbox">
        <input v-model="form.visitRequired" type="checkbox" :disabled="busy" />
        需要安排上门
      </label>
      <div class="form-row">
        <label>
          访问开始（北京时间）
          <input
            v-model="form.windowStart"
            type="datetime-local"
            :required="!!form.windowEnd || form.windowConfirmed"
            :disabled="busy"
          />
        </label>
        <label>
          访问结束
          <input
            v-model="form.windowEnd"
            type="datetime-local"
            :min="form.windowStart"
            :required="!!form.windowStart || form.windowConfirmed"
            :disabled="busy"
          />
        </label>
      </div>
      <label class="checkbox">
        <input
          v-model="form.windowConfirmed"
          type="checkbox"
          :disabled="busy"
        />
        已与学生确认此预约时段
      </label>
      <label>
        预计处理时长（分钟） *
        <input
          v-model.number="form.estimatedMinutes"
          type="number"
          min="1"
          max="480"
          step="1"
          required
          :disabled="busy"
        />
        <small>这是工作量估算，不表示维修一定在该时长内完成。</small>
      </label>
      <label>
        等待或暂缓原因 {{ !form.visitRequired ? "*" : "" }}
        <textarea
          v-model="form.waitingReason"
          :required="!form.visitRequired"
          maxlength="500"
          :disabled="busy"
          placeholder="例如：等待配件到货，暂不安排上门"
        />
      </label>
      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
      <div class="button-row">
        <button class="btn primary" :disabled="busy">
          {{ busy ? "正在保存…" : "保存访问安排" }}
        </button>
        <button class="btn" type="button" :disabled="busy" @click="cancel">
          取消设置
        </button>
      </div>
    </form>
    <form
      v-if="mode === 'handoff'"
      class="form dispatch-nested-form"
      @input="dirty = true"
      @submit.prevent="handoff"
    >
      <p class="info-note">
        仅交接已接单且尚未出发的任务。在途或当前处理的任务不能靠重新规划换人，交接会保留原办理人、接收人和原因。
      </p>
      <p v-if="busy" class="loading">正在读取接收人员…</p>
      <p v-if="task && !taskAllowsHandoff" class="error-banner">
        当前任务已出发、已锁定或状态不允许交接，请继续由原办理宿管跟进。
      </p>
      <label>
        接收宿管 *
        <select v-model="targetId" required :disabled="busy">
          <option value="">请选择有楼栋权限的活跃宿管</option>
          <option
            v-for="manager in targets"
            :key="manager.userId"
            :value="manager.userId"
          >
            {{ manager.displayName }}
          </option>
        </select>
        <small>只列出在线、接受新任务且有该楼栋权限的其他宿管。</small>
      </label>
      <label>
        交接原因 *
        <textarea
          v-model.trim="handoffReason"
          required
          minlength="2"
          maxlength="500"
          :disabled="busy"
        />
      </label>
      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
      <p v-if="!busy && !targets.length" class="form-note">
        当前没有满足条件的接收宿管；可取消后稍后重试。
      </p>
      <div class="button-row">
        <button
          class="btn primary"
          :disabled="busy || !taskAllowsHandoff || !targets.length"
        >
          {{ busy ? "正在办理…" : "确认交接办理责任" }}
        </button>
        <button class="btn" type="button" :disabled="busy" @click="cancel">
          取消交接
        </button>
      </div>
    </form>
  </section>
</template>
