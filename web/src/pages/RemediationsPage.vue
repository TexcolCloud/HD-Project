<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from "vue";
import { onBeforeRouteUpdate, useRoute } from "vue-router";
import { get, patch, post } from "../api/client";
import type { Model, Hygiene } from "../api/types";
import { dateTime, errorText, isoTime, notify } from "../ui";
import { useLatestQuery } from "../composables/useLatestQuery";
import Drawer from "../components/Drawer.vue";
import Pagination from "../components/Pagination.vue";
const route = useRoute();
const listQuery = useLatestQuery(),
  detailQuery = useLatestQuery();
const { busy, error } = listQuery;
const items = ref<Model<"Remediation">[]>([]),
  total = ref(0),
  page = ref(1);
const filters = reactive({ status: "", overdueOnly: false });
const selected = ref<Model<"Remediation">>(),
  inspection = ref<Hygiene>();
const residents = ref<Model<"Residence">[]>([]),
  rechecks = ref<Hygiene[]>([]);
const modal = ref<"create" | "detail" | null>(null),
  saving = ref(false),
  formError = ref("");
const editMode = ref<"" | "assign" | "review" | "cancel">("");
const responsibleStudentId = ref(""),
  dueLocal = ref(""),
  issueNote = ref(""),
  reason = ref(""),
  approved = ref(true),
  recheckId = ref("");
const labels: Record<Model<"RemediationStatus">, string> = {
  OPEN: "待整改",
  SUBMITTED: "待复核",
  CLOSED: "已验收",
  CANCELLED: "已取消",
};
const currentOpen = computed(
  () => selected.value && ["OPEN", "SUBMITTED"].includes(selected.value.status),
);
const validRechecks = computed(() =>
  rechecks.value.filter(
    (row) =>
      row.relatedInspectionId === selected.value?.inspectionId &&
      row.round.startsWith("RECHECK") &&
      row.totalScore >= 60 &&
      !row.voidedAt,
  ),
);
let disposed = false;
async function load() {
  await listQuery.run(
    (signal) =>
      get<Model<"RemediationPage">>(
        "/hygiene-remediations",
        {
          status: filters.status,
          overdueOnly: filters.overdueOnly ? "true" : undefined,
          page: page.value,
          pageSize: 20,
        },
        signal,
      ),
    (result) => {
      items.value = result.items;
      total.value = result.total;
    },
  );
}
function search() {
  page.value = 1;
  void load();
}
function resetForms() {
  editMode.value = "";
  reason.value = "";
  recheckId.value = "";
  approved.value = true;
  formError.value = "";
}
function localTime(value: string) {
  return new Date(new Date(value).getTime() + 8 * 3600000)
    .toISOString()
    .slice(0, 16);
}
async function context(roomId: string, signal: AbortSignal) {
  const [people, checks] = await Promise.all([
    get<Model<"ResidencePage">>(
      "/residences",
      { roomId, status: "CURRENT", pageSize: 100 },
      signal,
    ),
    get<Model<"HygienePage">>(
      "/hygiene-inspections",
      { roomId, pageSize: 100 },
      signal,
    ),
  ]);
  return { residents: people.items, rechecks: checks.items };
}
async function detail(id: string) {
  if (saving.value) return;
  resetForms();
  selected.value = undefined;
  modal.value = "detail";
  await detailQuery.run(
    async (signal) => {
      const task = await get<Model<"Remediation">>(
        `/hygiene-remediations/${encodeURIComponent(id)}`,
        {},
        signal,
      );
      return { task, ...(await context(task.roomId, signal)) };
    },
    (result) => {
      selected.value = result.task;
      residents.value = result.residents;
      rechecks.value = result.rechecks;
    },
  );
}
async function create(inspectionId: string) {
  if (saving.value) return;
  resetForms();
  selected.value = undefined;
  inspection.value = undefined;
  modal.value = "create";
  await detailQuery.run(
    async (signal) => {
      const source = await get<Hygiene>(
        `/hygiene-inspections/${encodeURIComponent(inspectionId)}`,
        {},
        signal,
      );
      const [room, tasks] = await Promise.all([
        context(source.roomId, signal),
        get<Model<"RemediationPage">>(
          "/hygiene-remediations",
          { inspectionId: source.id, pageSize: 1 },
          signal,
        ),
      ]);
      return {
        source,
        ...room,
        existing: tasks.items.find((task) => task.inspectionId === source.id),
      };
    },
    (result) => {
      inspection.value = result.source;
      residents.value = result.residents;
      if (result.existing) {
        selected.value = result.existing;
        rechecks.value = result.rechecks;
        modal.value = "detail";
        return;
      }
      responsibleStudentId.value = result.residents[0]?.studentId || "";
      dueLocal.value = localTime(
        new Date(Date.now() + 2 * 86400000).toISOString(),
      );
      issueNote.value =
        result.source.issueNote || result.source.remediationNote;
    },
  );
}
function edit(mode: "assign" | "review" | "cancel") {
  if (!selected.value || saving.value) return;
  resetForms();
  editMode.value = mode;
  responsibleStudentId.value = selected.value.responsibleStudentId;
  dueLocal.value = localTime(selected.value.dueAt);
}
async function save() {
  if (saving.value) return;
  const task = selected.value,
    source = inspection.value,
    mode = editMode.value;
  const dueAt = isoTime(dueLocal.value);
  formError.value = "";
  if ((modal.value === "create" || mode === "assign") && !dueAt) {
    formError.value = "请填写整改期限";
    return;
  }
  saving.value = true;
  detailQuery.cancel();
  try {
    let result: Model<"Remediation">;
    if (modal.value === "create" && source) {
      result = await post<Model<"Remediation">>("/hygiene-remediations", {
        inspectionId: source.id,
        responsibleStudentId: responsibleStudentId.value,
        dueAt: dueAt!,
        issueNote: issueNote.value,
      } satisfies Model<"RemediationRequest">);
    } else if (task && mode === "assign") {
      result = await patch<Model<"Remediation">>(
        `/hygiene-remediations/${task.id}`,
        {
          expectedVersion: task.version,
          responsibleStudentId: responsibleStudentId.value,
          dueAt: dueAt!,
          reason: reason.value,
        } satisfies Model<"RemediationAssignmentRequest">,
      );
    } else if (task && mode === "cancel") {
      result = await post<Model<"Remediation">>(
        `/hygiene-remediations/${task.id}/cancel`,
        {
          expectedVersion: task.version,
          reason: reason.value,
        } satisfies Model<"RemediationCancelRequest">,
      );
    } else if (task && mode === "review") {
      result = await post<Model<"Remediation">>(
        `/hygiene-remediations/${task.id}/review`,
        {
          expectedVersion: task.version,
          approved: approved.value,
          reviewNote: reason.value,
          relatedRecheckId: approved.value ? recheckId.value : undefined,
        } satisfies Model<"RemediationReviewRequest">,
      );
    } else {
      return;
    }
    if (disposed) return;
    selected.value = result;
    modal.value = "detail";
    resetForms();
    notify("整改任务已更新，处理记录已保留");
    await load();
  } catch (cause) {
    if (!disposed) formError.value = errorText(cause);
  } finally {
    if (!disposed) saving.value = false;
  }
}
function close() {
  detailQuery.cancel();
  modal.value = null;
  selected.value = undefined;
  resetForms();
}
onBeforeRouteUpdate(() => {
  if (saving.value) return false;
  if (modal.value === "create" || editMode.value)
    return window.confirm("整改内容尚未保存，确定切换并放弃本次修改？");
  return true;
});
watch(
  () => [route.query.taskId, route.query.inspectionId],
  ([taskId, inspectionId]) => {
    if (typeof taskId === "string" && taskId) void detail(taskId);
    else if (typeof inspectionId === "string" && inspectionId)
      void create(inspectionId);
    else close();
  },
  { immediate: true },
);
onMounted(load);
onScopeDispose(() => {
  disposed = true;
});
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>卫生整改</h1>
      <p>明确责任与期限，跟进反馈，再以现场复查完成验收。</p>
    </div>
    <RouterLink to="/hygiene" class="btn primary">
      从巡检记录安排整改
    </RouterLink>
  </div>
  <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  <section class="panel">
    <form class="filters" @submit.prevent="search">
      <label>
        任务状态
        <select v-model="filters.status">
          <option value="">全部状态</option>
          <option
            v-for="(label, status) in labels"
            :key="status"
            :value="status"
          >
            {{ label }}
          </option>
        </select>
      </label>
      <label>
        跟进范围
        <select v-model="filters.overdueOnly">
          <option :value="false">全部期限</option>
          <option :value="true">仅看逾期</option>
        </select>
      </label>
      <button class="btn" :disabled="busy || saving">查询</button>
    </form>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>宿舍与问题</th>
            <th>责任学生</th>
            <th>截止时间</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in items" :key="task.id">
            <td>
              <strong>{{ task.buildingName }} {{ task.roomNo }}</strong>
              <small class="muted">{{ task.issueNote }}</small>
            </td>
            <td>{{ task.responsibleName }}</td>
            <td>
              {{ dateTime(task.dueAt) }}
              <small v-if="task.overdue" class="field-error">
                已超过约定期限
              </small>
            </td>
            <td>{{ labels[task.status] }}</td>
            <td>
              <button
                class="text-link"
                :disabled="saving"
                @click="detail(task.id)"
              >
                查看整改
              </button>
            </td>
          </tr>
          <tr v-if="!items.length">
            <td colspan="5" class="empty-state">
              {{ busy ? "正在读取整改任务…" : "当前条件下没有整改任务" }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Pagination
      :page="page"
      :total="total"
      :page-size="20"
      :busy="busy || saving"
      @change="
        (value) => {
          page = value;
          load();
        }
      "
    />
  </section>
  <Drawer
    v-if="modal"
    :title="modal === 'create' ? '安排卫生整改' : '整改任务详情'"
    :busy="saving"
    :dirty="modal === 'create' || !!editMode"
    @close="close"
  >
    <p v-if="detailQuery.busy.value" class="muted">正在读取任务及宿舍信息…</p>
    <p v-if="detailQuery.error.value" class="error-banner" role="alert">
      {{ detailQuery.error.value }}
    </p>
    <template v-if="modal === 'create' && inspection">
      <p class="section-note">
        {{ inspection.buildingName }} {{ inspection.roomNo }} ·
        {{ inspection.totalScore }}分 · {{ inspection.inspectionDate }}
      </p>
      <form class="form" @submit.prevent="save">
        <label>
          整改问题
          <textarea v-model.trim="issueNote" required maxlength="500" />
        </label>
        <label>
          责任学生
          <select v-model="responsibleStudentId" required>
            <option value="">请选择本宿舍在住学生</option>
            <option
              v-for="resident in residents"
              :key="resident.id"
              :value="resident.studentId"
            >
              {{ resident.studentName }} · {{ resident.studentNo }}
            </option>
          </select>
        </label>
        <label>
          截止时间
          <input v-model="dueLocal" type="datetime-local" required />
        </label>
        <p class="form-note">
          学生将收到整改任务，可在鸿蒙端提交说明和图片。截止时间应在未来90天内。
        </p>
        <p v-if="formError" class="error-banner" role="alert">
          {{ formError }}
        </p>
        <button class="btn primary" :disabled="saving || !residents.length">
          安排整改并通知学生
        </button>
      </form>
    </template>
    <template v-if="modal === 'detail' && selected">
      <dl class="details">
        <div>
          <dt>宿舍</dt>
          <dd>{{ selected.buildingName }} {{ selected.roomNo }}</dd>
        </div>
        <div>
          <dt>状态</dt>
          <dd>
            {{ labels[selected.status]
            }}{{ selected.overdue ? " · 已逾期" : "" }}
          </dd>
        </div>
        <div>
          <dt>责任学生</dt>
          <dd>{{ selected.responsibleName }}</dd>
        </div>
        <div>
          <dt>截止时间</dt>
          <dd>{{ dateTime(selected.dueAt) }}</dd>
        </div>
      </dl>
      <section class="detail-section">
        <h3>整改问题</h3>
        <p class="description">{{ selected.issueNote }}</p>
        <RouterLink
          :to="
            '/hygiene?inspectionId=' + encodeURIComponent(selected.inspectionId)
          "
          class="text-link"
        >
          查看原巡检
        </RouterLink>
      </section>
      <section class="detail-section">
        <h3>学生反馈</h3>
        <p class="description">
          {{ selected.feedbackNote || "尚未提交整改反馈" }}
        </p>
        <small v-if="selected.submittedAt" class="muted">
          提交于 {{ dateTime(selected.submittedAt) }}
        </small>
        <div v-if="selected.evidenceImages.length" class="image-grid">
          <a
            v-for="image in selected.evidenceImages"
            :key="image.id"
            :href="image.url"
            target="_blank"
            rel="noopener"
          >
            <img :src="image.thumbnailUrl" :alt="image.originalName" />
          </a>
        </div>
      </section>
      <section v-if="selected.reviewNote" class="detail-section">
        <h3>复核与办理说明</h3>
        <p class="description">{{ selected.reviewNote }}</p>
        <RouterLink
          v-if="selected.relatedRecheckId"
          :to="
            '/hygiene?inspectionId=' +
            encodeURIComponent(selected.relatedRecheckId)
          "
          class="text-link"
        >
          查看验收复查
        </RouterLink>
      </section>
      <div v-if="currentOpen && !editMode" class="cell-actions">
        <button class="btn" :disabled="saving" @click="edit('assign')">
          调整责任与期限
        </button>
        <button
          v-if="selected.status === 'SUBMITTED'"
          class="btn primary"
          :disabled="saving"
          @click="edit('review')"
        >
          复核学生反馈
        </button>
        <button class="btn danger" :disabled="saving" @click="edit('cancel')">
          取消整改
        </button>
      </div>
      <form v-if="editMode" class="form detail-section" @submit.prevent="save">
        <h3>
          {{
            editMode === "assign"
              ? "调整责任与期限"
              : editMode === "review"
                ? "复核整改反馈"
                : "取消整改任务"
          }}
        </h3>
        <template v-if="editMode === 'assign'">
          <label>
            责任学生
            <select v-model="responsibleStudentId" required>
              <option
                v-for="resident in residents"
                :key="resident.id"
                :value="resident.studentId"
              >
                {{ resident.studentName }} · {{ resident.studentNo }}
              </option>
            </select>
          </label>
          <label>
            截止时间
            <input v-model="dueLocal" type="datetime-local" required />
          </label>
          <p class="form-note">
            调整后任务重新进入待整改，旧反馈和调整原因保留在审计记录中。
          </p>
        </template>
        <template v-if="editMode === 'review'">
          <label>
            复核结果
            <select v-model="approved">
              <option :value="true">验收通过</option>
              <option :value="false">退回继续整改</option>
            </select>
          </label>
          <label v-if="approved">
            关联现场复查
            <select v-model="recheckId" required>
              <option value="">请选择与原巡检关联的合格复查</option>
              <option
                v-for="check in validRechecks"
                :key="check.id"
                :value="check.id"
              >
                {{ check.inspectionDate }} · {{ check.totalScore }}分 ·
                {{ check.round }}
              </option>
            </select>
          </label>
          <RouterLink
            :to="{
              path: '/hygiene',
              query: {
                recheckOf: selected.inspectionId,
                returnTask: selected.id,
              },
            }"
            class="text-link"
          >
            录入现场复查
          </RouterLink>
          <p class="form-note">
            只有与原巡检关联、同宿舍且合格的有效复查才能作为验收依据。
          </p>
        </template>
        <label>
          {{ editMode === "review" ? "复核意见" : "办理原因" }}
          <textarea v-model.trim="reason" required maxlength="1000" />
        </label>
        <div class="form-row">
          <button class="btn primary" :disabled="saving">
            {{ saving ? "正在保存…" : "保存并通知相关人员" }}
          </button>
          <button
            class="btn"
            type="button"
            :disabled="saving"
            @click="resetForms"
          >
            放弃本次编辑
          </button>
        </div>
      </form>
      <p v-if="formError" class="error-banner" role="alert">{{ formError }}</p>
    </template>
  </Drawer>
</template>
