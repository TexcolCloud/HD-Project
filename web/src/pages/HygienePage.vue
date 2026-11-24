<script setup lang="ts">
import ModuleTabs from "../components/ModuleTabs.vue";
import { ref, reactive, computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ApiError, get, post, patch } from "../api/client";
import type { Model, Hygiene, Building, FileAsset } from "../api/types";
import {
  errorText,
  notify,
  localDate,
  dateTime,
  isoTime,
  statusNames,
} from "../ui";
import { allPages } from "../api/allPages";
import Drawer from "../components/Drawer.vue";
import Pagination from "../components/Pagination.vue";
import LookupSelect from "../components/LookupSelect.vue";
import UploadField from "../components/UploadField.vue";
import StatusBadge from "../components/StatusBadge.vue";
import Icon from "../components/Icon.vue";
import { useLatestQuery } from "../composables/useLatestQuery";
const listQuery = useLatestQuery();
const route = useRoute(),
  router = useRouter();
const detailQuery = useLatestQuery();
const comparisonQuery = useLatestQuery();
const { busy: comparing, error: comparisonError } = comparisonQuery;
const comparison = ref<{ record: Hygiene; sameRound: boolean }>();
const { busy, error } = listQuery;
const items = ref<Hygiene[]>([]),
  buildings = ref<Building[]>([]),
  page = ref(1),
  total = ref(0),
  filters = reactive({
    q: "",
    buildingId: "",
    grade: "",
    startDate: "",
    endDate: "",
  });
const selected = ref<Hygiene>(),
  modal = ref<"form" | "detail" | null>(null),
  mode = ref<"create" | "correction" | "recheck">("create"),
  saving = ref(false),
  dirty = ref(false),
  formError = ref(""),
  images = ref<FileAsset[]>([]),
  uploadBlocked = ref(false),
  inspectedLocal = ref(""),
  history = ref<Model<"CorrectionEvent">[]>([]);
const form = reactive<Model<"HygieneRequest">>({
  roomId: "",
  inspectionDate: localDate(),
  round: "ROUTINE",
  floorScore: 25,
  deskScore: 25,
  bedScore: 25,
  bathroomScore: 25,
  issueNote: "",
  remediationNote: "",
  imageIds: [],
});
const nextRoomQuery = useLatestQuery();
const voiding = ref(false),
  voidReason = ref("");
const correctionReason = ref("");
const scores = [
  ["floorScore", "地面"],
  ["deskScore", "桌面"],
  ["bedScore", "床铺"],
  ["bathroomScore", "卫生间"],
] as const;
const score = computed(() =>
  scores.reduce((sum, [key]) => sum + Number(form[key] || 0), 0),
);
const needsRemediation = computed(
  () =>
    modal.value === "form" &&
    mode.value === "create" &&
    form.round === "ROUTINE" &&
    score.value < 60,
);
const createRemediation = ref(false),
  responsibleStudentId = ref(""),
  dueLocal = ref("");
const residentQuery = useLatestQuery();
const residents = ref<Model<"Residence">[]>([]);
const incomplete = ref<{ inspectionId: string; message: string }>();
function setDue(days: number) {
  dueLocal.value = localDate(new Date(Date.now() + days * 86400000)) + "T18:00";
}
watch(needsRemediation, (enabled) => {
  createRemediation.value = enabled;
  if (enabled) setDue(2);
});
watch(
  () => needsRemediation.value && createRemediation.value && form.roomId,
  async (roomId) => {
    residentQuery.cancel();
    residents.value = [];
    responsibleStudentId.value = "";
    residentQuery.error.value = "";
    if (!roomId) return;
    await residentQuery.run(
      (signal) =>
        get<Model<"ResidencePage">>(
          "/residences",
          { roomId, status: "CURRENT", pageSize: 100 },
          signal,
        ),
      (result) => {
        residents.value = result.items;
      },
    );
  },
);
const assignmentBlocked = computed(
  () =>
    needsRemediation.value &&
    createRemediation.value &&
    (residentQuery.busy.value ||
      !!residentQuery.error.value ||
      (residents.value.length > 0 &&
        (!responsibleStudentId.value || !dueLocal.value))),
);
const retainedImages = computed(() =>
  mode.value === "correction" ? (selected.value?.images ?? []) : [],
);
const grade = computed(() =>
  score.value >= 90
    ? "优秀"
    : score.value >= 80
      ? "良好"
      : score.value >= 60
        ? "合格"
        : "待整改",
);
async function load() {
  await listQuery.run(
    (signal) =>
      get<Model<"HygienePage">>(
        "/hygiene-inspections",
        {
          ...filters,
          page: page.value,
          pageSize: 20,
        },
        signal,
      ),
    (r) => {
      items.value = r.items;
      total.value = r.total;
    },
  );
}
function edit(
  h?: Hygiene,
  next: "create" | "correction" | "recheck" = "create",
) {
  detailQuery.cancel();
  selected.value = h;
  mode.value = next;
  Object.assign(form, {
    roomId: h?.roomId || "",
    inspectionDate: next === "correction" ? h?.inspectionDate : localDate(),
    round:
      next === "recheck"
        ? "RECHECK"
        : next === "correction"
          ? h?.round
          : "ROUTINE",
    floorScore: next === "correction" ? h?.floorScore : 25,
    deskScore: next === "correction" ? h?.deskScore : 25,
    bedScore: next === "correction" ? h?.bedScore : 25,
    bathroomScore: next === "correction" ? h?.bathroomScore : 25,
    issueNote: next === "correction" ? h?.issueNote : "",
    remediationNote: next === "correction" ? h?.remediationNote : "",
    relatedInspectionId: next === "recheck" ? h?.id : h?.relatedInspectionId,
    imageIds: [],
    expectedVersion: next === "correction" ? h?.version : undefined,
  });
  images.value = [];
  inspectedLocal.value = "";
  correctionReason.value = "";
  formError.value = "";
  dirty.value = false;
  uploadBlocked.value = false;
  modal.value = "form";
}
async function loadComparison() {
  const { roomId, inspectionDate, round } = form;
  comparison.value = undefined;
  comparisonQuery.cancel();
  comparisonError.value = "";
  if (
    modal.value !== "form" ||
    mode.value === "correction" ||
    !roomId ||
    !inspectionDate ||
    inspectionDate > localDate()
  )
    return;
  await comparisonQuery.run(
    async (signal) => {
      const same = await get<Model<"HygienePage">>(
        "/hygiene-inspections",
        { roomId, inspectionDate, round, pageSize: 1 },
        signal,
      );
      if (same.items[0]) return { record: same.items[0], sameRound: true };
      if (signal.aborted) return;
      const endDate = new Date(Date.parse(inspectionDate) - 86_400_000)
        .toISOString()
        .slice(0, 10);
      const previous = await get<Model<"HygienePage">>(
        "/hygiene-inspections",
        { roomId, endDate, pageSize: 1 },
        signal,
      );
      if (previous.items[0])
        return { record: previous.items[0], sameRound: false };
    },
    (result) => {
      comparison.value = result;
    },
  );
}
watch(
  () => [modal.value, mode.value, form.roomId, form.inspectionDate, form.round],
  loadComparison,
);
function useComparison() {
  const match = comparison.value;
  if (!match || saving.value) return;
  if (match.sameRound) {
    if (
      dirty.value &&
      !window.confirm("更正已有评分将放弃本次未保存的录入，是否继续？")
    )
      return;
    edit(match.record, "correction");
    return;
  }
  for (const [key] of scores) form[key] = match.record[key];
  dirty.value = true;
}
async function detail(h: Hygiene | string) {
  const id = typeof h === "string" ? h : h.id;
  selected.value = typeof h === "string" ? undefined : h;
  history.value = [];
  voiding.value = false;
  voidReason.value = "";
  modal.value = "detail";
  formError.value = "";
  await detailQuery.run(
    (signal) =>
      Promise.all([
        get<Hygiene>(
          `/hygiene-inspections/${encodeURIComponent(id)}`,
          {},
          signal,
        ),
        get<Model<"CorrectionEvent">[]>(
          `/hygiene-inspections/${encodeURIComponent(id)}/history`,
          {},
          signal,
        ),
      ]),
    ([r, events]) => {
      selected.value = r;
      history.value = events;
    },
  );
  if (modal.value === "detail" && selected.value?.id === id)
    formError.value = detailQuery.error.value;
}
async function advanceRoom(roomId: string) {
  const date = form.inspectionDate,
    inspected = inspectedLocal.value;
  edit();
  createRemediation.value = false;
  responsibleStudentId.value = dueLocal.value = "";
  form.inspectionDate = date;
  inspectedLocal.value = inspected;
  await nextRoomQuery.run(
    async (signal) => {
      const room = await get<Model<"Room">>(
        "/rooms/" + encodeURIComponent(roomId),
        {},
        signal,
      );
      const rooms = await allPages(
        (page) =>
          get<Model<"RoomPage">>(
            "/rooms",
            {
              buildingId: room.buildingId,
              floorNo: room.floorNo,
              status: "ENABLED",
              page,
              pageSize: 100,
            },
            signal,
          ),
        signal,
      );
      const order = new Intl.Collator("zh-CN", { numeric: true });
      return rooms
        .filter(
          (r) =>
            r.enabled &&
            r.buildingId === room.buildingId &&
            r.floorNo === room.floorNo,
        )
        .sort((a, b) => order.compare(a.roomNo, b.roomNo))
        .find((r) => order.compare(r.roomNo, room.roomNo) > 0);
    },
    (next) => {
      form.roomId = next?.id || "";
      if (!next) notify("本楼层已录至末尾，请选择下一宿舍");
    },
  );
  if (nextRoomQuery.error.value)
    formError.value =
      "评分已保存，请手动选择下一宿舍。" + nextRoomQuery.error.value;
}
function fullScore() {
  for (const [key] of scores) form[key] = 25;
  dirty.value = true;
}
async function save(event?: SubmitEvent) {
  const continueNext =
    mode.value === "create" &&
    form.round === "ROUTINE" &&
    event?.submitter?.getAttribute("value") === "next";
  if (
    saving.value ||
    !form.roomId ||
    uploadBlocked.value ||
    assignmentBlocked.value ||
    comparison.value?.sameRound
  )
    return;
  saving.value = true;
  formError.value = "";
  try {
    const body: Model<"HygieneRequest"> = {
      ...form,
      imageIds: images.value.map((i) => i.id),
      inspectedAt:
        isoTime(inspectedLocal.value) ||
        (mode.value === "correction" ? selected.value?.inspectedAt : undefined),
    };
    if (mode.value === "correction" && selected.value)
      await patch(`/hygiene-inspections/${selected.value.id}`, {
        ...body,
        correctionReason: correctionReason.value.trim(),
      });
    else {
      const assignment =
        needsRemediation.value &&
        createRemediation.value &&
        residents.value.length
          ? {
              responsibleStudentId: responsibleStudentId.value,
              dueAt: isoTime(dueLocal.value)!,
              issueNote: form.issueNote || form.remediationNote || "",
            }
          : undefined;
      const saved = await post<Hygiene>("/hygiene-inspections", body);
      if (assignment) {
        try {
          await post("/hygiene-remediations", {
            inspectionId: saved.id,
            ...assignment,
          } satisfies Model<"RemediationRequest">);
          notify("整改任务已安排");
        } catch (cause) {
          incomplete.value = {
            inspectionId: saved.id,
            message: "巡检已保存，整改任务待核对。" + errorText(cause),
          };
        }
      }
    }
    dirty.value = false;
    if (continueNext) await advanceRoom(body.roomId);
    else modal.value = null;
    notify(
      mode.value === "correction"
        ? "评分已修正，原成绩与原因已留痕"
        : "卫生评分已保存",
    );
    await load();
    if (
      mode.value === "recheck" &&
      typeof route.query.returnTask === "string"
    ) {
      await router.push({
        path: "/remediations",
        query: { taskId: route.query.returnTask },
      });
    }
  } catch (e) {
    formError.value = errorText(e);
    if (
      e instanceof ApiError &&
      e.status === 409 &&
      mode.value !== "correction"
    )
      await loadComparison();
  } finally {
    saving.value = false;
  }
}
async function voidRecord() {
  const reason = voidReason.value.trim();
  if (
    !selected.value ||
    saving.value ||
    reason.length < 2 ||
    reason.length > 500
  )
    return;
  saving.value = true;
  try {
    await post(`/hygiene-inspections/${selected.value.id}/void`, {
      expectedVersion: selected.value.version,
      reason: reason.trim(),
    });
    modal.value = null;
    notify("巡检已作废并保留记录");
    await load();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    saving.value = false;
  }
}
function search() {
  page.value = 1;
  void load();
}
function turn(p: number) {
  page.value = p;
  void load();
}
watch(
  modal,
  (mode) => {
    if (mode !== "detail") detailQuery.cancel();
  },
  { flush: "sync" },
);
onMounted(async () => {
  try {
    buildings.value = (
      await get<Model<"BuildingPage">>("/buildings", { pageSize: 100 })
    ).items;
  } catch (e) {
    error.value = errorText(e);
  }
  await load();
});
watch(
  () => [route.query.inspectionId, route.query.recheckOf, route.query.action],
  async ([id, recheckOf, action]) => {
    if (action === "create" && !id && !recheckOf) {
      edit();
      const query = { ...route.query };
      delete query.action;
      await router.replace({ path: route.path, query });
      return;
    }
    if (typeof id === "string" && id) await detail(id);
    else if (typeof recheckOf === "string" && recheckOf) {
      await detail(recheckOf);
      if (
        route.query.recheckOf === recheckOf &&
        selected.value?.id === recheckOf &&
        !detailQuery.error.value
      )
        edit(selected.value, "recheck");
    }
  },
  { immediate: true },
);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>卫生巡检</h1>
    </div>
    <button class="btn primary" @click="edit()">
      <Icon name="plus" />
      录入卫生评分
    </button>
  </div>
  <ModuleTabs domain="hygiene" />
  <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  <p
    v-if="detailQuery.error.value && !selected"
    class="error-banner"
    role="alert"
  >
    {{ detailQuery.error.value }}
  </p>
  <p v-if="incomplete && modal !== 'form'" class="error-banner" role="alert">
    {{ incomplete.message }}
    <RouterLink
      :to="{
        path: '/remediations',
        query: { inspectionId: incomplete.inspectionId },
      }"
    >
      查看或补建整改任务
    </RouterLink>
  </p>
  <section class="panel">
    <form class="filters" @submit.prevent="search">
      <label>
        房号 / 关键词
        <input v-model="filters.q" placeholder="房号、楼栋或检查人" />
      </label>
      <label>
        楼栋
        <select v-model="filters.buildingId">
          <option value="">全部负责楼栋</option>
          <option v-for="b in buildings" :key="b.id" :value="b.id">
            {{ b.name }}
          </option>
        </select>
      </label>
      <label>
        评分等级
        <select v-model="filters.grade">
          <option value="">全部</option>
          <option
            v-for="g in ['EXCELLENT', 'GOOD', 'PASS', 'RECTIFY']"
            :key="g"
            :value="g"
          >
            {{ statusNames[g] }}
          </option>
        </select>
      </label>
      <label>
        开始日期
        <input v-model="filters.startDate" type="date" />
      </label>
      <label>
        结束日期
        <input v-model="filters.endDate" type="date" :min="filters.startDate" />
      </label>
      <button class="btn primary" :disabled="busy">查询</button>
      <button
        class="btn"
        type="button"
        @click="
          Object.assign(filters, {
            q: '',
            buildingId: '',
            grade: '',
            startDate: '',
            endDate: '',
          });
          search();
        "
      >
        重置
      </button>
    </form>
    <p v-if="busy" class="panel-body loading">正在读取卫生记录…</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>宿舍</th>
            <th>日期 / 轮次</th>
            <th>得分</th>
            <th>等级</th>
            <th>检查人</th>
            <th>问题记录</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="h in items" :key="h.id">
            <td>{{ h.buildingName }} {{ h.roomNo }}</td>
            <td>
              {{ h.inspectionDate }}
              <small>{{ statusNames[h.round] || h.round }}</small>
            </td>
            <td class="num">{{ h.totalScore }} / 100</td>
            <td><StatusBadge :status="h.grade" /></td>
            <td>{{ h.inspectorName }}</td>
            <td>{{ h.issueNote || "—" }}</td>
            <td>
              <button class="text-link" @click="detail(h)">查看与更正</button>
            </td>
          </tr>
          <tr v-if="!busy && !items.length">
            <td colspan="7" class="empty-state">
              暂无匹配的卫生记录，可调整筛选或录入评分。
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Pagination
      :page="page"
      :page-size="20"
      :total="total"
      :busy="busy"
      @change="turn"
    />
  </section>
  <Drawer
    v-if="modal === 'form'"
    :title="
      mode === 'correction'
        ? '更正卫生评分'
        : mode === 'recheck'
          ? '录入整改复查'
          : '录入卫生评分'
    "
    :busy="saving"
    :dirty="dirty"
    @close="modal = null"
  >
    <p v-if="incomplete" class="error-banner" role="alert">
      {{ incomplete.message }}
      <RouterLink
        :to="{
          path: '/remediations',
          query: { inspectionId: incomplete.inspectionId },
        }"
      >
        查看或补建整改任务
      </RouterLink>
    </p>
    <form class="form" @input="dirty = true" @submit.prevent="save">
      <fieldset class="form-fields" :disabled="saving">
        <div class="score-total">
          <span>本次总分 / {{ grade }}</span>
          <strong>{{ score }}</strong>
          <button type="button" class="btn" @click="fullScore">一键满分</button>
        </div>
        <label v-if="selected && mode !== 'create'">
          检查房间
          <span>{{ selected.buildingName }} {{ selected.roomNo }}</span>
        </label>
        <LookupSelect
          v-else
          v-model="form.roomId"
          kind="room"
          label="检查房间 *"
          @update:model-value="dirty = true"
        />
        <div class="form-row">
          <label>
            检查日期 *
            <input
              v-model="form.inspectionDate"
              type="date"
              required
              :max="localDate()"
            />
          </label>
          <label>
            巡检轮次
            <select v-model="form.round">
              <option value="ROUTINE">日常巡检</option>
              <option value="RECHECK">整改复查</option>
              <option value="RECHECK2">第二次复查</option>
              <option value="RECHECK3">第三次复查</option>
            </select>
          </label>
        </div>
        <label>
          实际检查时刻
          <input
            v-model="inspectedLocal"
            type="datetime-local"
            :required="
              mode !== 'correction' && form.inspectionDate < localDate()
            "
          />
          <small>
            补录时请填写实际检查时刻；留空使用当前时间，更正则保留原检查时间。
          </small>
        </label>
        <section v-if="comparison" class="inspection-comparison">
          <h3>{{ comparison.sameRound ? "已有同轮评分" : "最近一次巡检" }}</h3>
          <p class="form-note">
            {{ comparison.record.buildingName }}
            {{ comparison.record.roomNo }} ·
            {{ comparison.record.inspectionDate }} ·
            {{ statusNames[comparison.record.round] }}
          </p>
          <p class="form-note">
            检查时刻 {{ dateTime(comparison.record.inspectedAt) }}
          </p>
          <dl class="details comparison-scores">
            <div v-for="[key, label] in scores" :key="key">
              <dt>{{ label }}</dt>
              <dd class="num">{{ comparison.record[key] }} / 25</dd>
            </div>
          </dl>
          <div class="comparison-action">
            <span>
              总分
              <strong class="num">{{ comparison.record.totalScore }}</strong>
              / 100
            </span>
            <button
              class="btn"
              type="button"
              :disabled="saving"
              @click="useComparison"
            >
              {{ comparison.sameRound ? "更正评分" : "带入四项分数" }}
            </button>
          </div>
        </section>
        <p v-else-if="comparing" class="form-note" role="status">
          正在查询该房间的历史评分…
        </p>
        <div v-else-if="comparisonError" class="error-banner" role="alert">
          未能查询历史评分。{{ comparisonError }}
          <button type="button" class="text-link" @click="loadComparison">
            重新查询
          </button>
        </div>
        <div>
          <div v-for="[key, label] in scores" :key="key" class="score-row">
            <label :for="key">
              {{ label }}
              <small>满分25分</small>
            </label>
            <input
              :id="key"
              v-model.number="form[key]"
              type="number"
              min="0"
              max="25"
              step="1"
              required
            />
            <div class="button-row">
              <button
                v-for="n in [25, 22, 20, 15]"
                :key="n"
                type="button"
                class="btn"
                :aria-label="label + n + '分'"
                @click="
                  form[key] = n;
                  dirty = true;
                "
              >
                {{ n }}
              </button>
            </div>
          </div>
        </div>
        <label>
          问题与扣分说明 {{ score < 60 ? "*" : "" }}
          <textarea
            v-model.trim="form.issueNote"
            :required="score < 60"
            maxlength="1000"
          />
        </label>
        <label>
          整改建议
          <textarea v-model.trim="form.remediationNote" maxlength="1000" />
        </label>
        <section v-if="needsRemediation" class="detail-section">
          <label class="checkbox">
            <input v-model="createRemediation" type="checkbox" />
            同步安排卫生整改
          </label>
          <template v-if="createRemediation && form.roomId">
            <p v-if="residentQuery.busy.value" role="status">
              正在读取在住学生…
            </p>
            <p v-else-if="residentQuery.error.value" class="field-error">
              {{ residentQuery.error.value }}，可关闭同步安排后保存巡检。
            </p>
            <p v-else-if="!residents.length" class="form-note">
              该宿舍暂无在住学生，无法指派整改。可仅保存巡检记录。
            </p>
            <template v-else>
              <label>
                责任学生 *
                <select v-model="responsibleStudentId" required>
                  <option value="">请选择在住学生</option>
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
                整改截止时间 *
                <input v-model="dueLocal" type="datetime-local" required />
              </label>
              <div class="button-row">
                <button
                  v-for="days in [2, 3, 7]"
                  :key="days"
                  type="button"
                  class="btn"
                  @click="setDue(days)"
                >
                  +{{ days }}天
                </button>
              </div>
            </template>
          </template>
        </section>
        <label v-if="mode === 'correction'">
          更正原因 *
          <textarea
            v-model.trim="correctionReason"
            required
            minlength="2"
            maxlength="500"
          />
        </label>
        <section v-if="retainedImages.length" class="detail-section">
          <h3>已留存的现场图片</h3>
          <div class="image-grid">
            <a
              v-for="image in retainedImages"
              :key="image.id"
              :href="image.url"
              target="_blank"
              rel="noopener"
            >
              <img :src="image.thumbnailUrl" :alt="image.originalName" />
            </a>
          </div>
        </section>
        <UploadField
          v-if="retainedImages.length < 6"
          v-model="images"
          usage="INSPECTION"
          :limit="6 - retainedImages.length"
          :disabled="saving"
          @blocked="uploadBlocked = $event"
        />
        <p v-if="formError" class="error-banner" role="alert">
          {{ formError }}
        </p>
        <button
          class="btn primary"
          :disabled="
            saving ||
            uploadBlocked ||
            comparison?.sameRound ||
            assignmentBlocked
          "
        >
          {{
            saving
              ? "正在保存…"
              : mode === "correction"
                ? "保存更正并留痕"
                : "保存卫生评分"
          }}
        </button>
        <button
          v-if="mode === 'create' && form.round === 'ROUTINE'"
          type="submit"
          value="next"
          class="btn"
          :disabled="
            saving ||
            uploadBlocked ||
            comparison?.sameRound ||
            assignmentBlocked
          "
        >
          保存并录入下一间
        </button>
      </fieldset>
    </form>
  </Drawer>
  <Drawer
    v-if="modal === 'detail' && selected"
    :dirty="voiding && !!voidReason.trim()"
    :title="selected.buildingName + ' ' + selected.roomNo + ' 卫生记录'"
    :subtitle="
      selected.inspectionDate +
      ' · ' +
      (statusNames[selected.round] || selected.round)
    "
    :busy="saving"
    @close="modal = null"
  >
    <p v-if="formError" class="error-banner">{{ formError }}</p>
    <div class="score-total">
      <StatusBadge :status="selected.grade" />
      <strong>{{ selected.totalScore }}</strong>
    </div>
    <div v-for="[key, label] in scores" :key="key" class="score-row">
      <span>{{ label }}</span>
      <span class="num">{{ selected[key] }} / 25</span>
    </div>
    <section class="detail-section" style="margin-top: 24px">
      <h3>问题与整改建议</h3>
      <p class="description">
        {{ selected.issueNote || "无问题记录" }}
        <br />
        {{ selected.remediationNote || "无补充整改建议" }}
      </p>
    </section>
    <section v-if="selected.images.length" class="detail-section">
      <h3>现场图片</h3>
      <div class="image-grid">
        <a
          v-for="file in selected.images"
          :key="file.id"
          :href="file.url"
          target="_blank"
        >
          <img :src="file.thumbnailUrl" :alt="file.originalName" />
        </a>
      </div>
    </section>
    <section class="detail-section">
      <h3>修正与操作历史</h3>
      <ol class="timeline">
        <li v-for="h in history" :key="h.id">
          <strong>
            {{
              h.action === "HYGIENE_CORRECT" || h.action === "CORRECT"
                ? "更正评分"
                : h.action === "VOID"
                  ? "作废记录"
                  : h.action
            }}
          </strong>
          <p>{{ h.note }}</p>
          <small>{{ dateTime(h.createdAt) }} · {{ h.operatorName }}</small>
          <details v-if="h.changes">
            <summary>查看变更明细</summary>
            <pre class="changes-json">{{ h.changes }}</pre>
          </details>
        </li>
      </ol>
      <p v-if="!history.length" class="muted">当前记录尚无更正。</p>
    </section>
    <form v-if="voiding" class="form" @submit.prevent="voidRecord">
      <label>
        作废原因 *
        <textarea
          v-model.trim="voidReason"
          required
          minlength="2"
          maxlength="500"
          autofocus
        />
      </label>
      <div class="button-row">
        <button class="btn danger" :disabled="saving || voidReason.length < 2">
          确认作废
        </button>
        <button
          class="btn"
          type="button"
          :disabled="saving"
          @click="
            voiding = false;
            voidReason = '';
          "
        >
          取消
        </button>
      </div>
    </form>
    <template v-if="!voiding" #footer>
      <RouterLink
        v-if="!selected.voidedAt && selected.grade === 'RECTIFY'"
        class="btn"
        :to="{ path: '/remediations', query: { inspectionId: selected.id } }"
      >
        安排整改
      </RouterLink>
      <button class="btn primary" @click="edit(selected, 'correction')">
        更正评分
      </button>
      <button class="btn" @click="edit(selected, 'recheck')">录入复查</button>
      <button
        v-if="!selected.voidedAt"
        class="btn danger"
        :disabled="saving"
        @click="voiding = true"
      >
        作废记录
      </button>
    </template>
  </Drawer>
</template>
