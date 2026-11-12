<script setup lang="ts">
import { ref, reactive, onMounted, watch } from "vue";
import { get, post, patch, ApiError } from "../api/client";
import type { Model, Visitor, Building } from "../api/types";
import { errorText, notify, dateTime, isoTime } from "../ui";
import Drawer from "../components/Drawer.vue";
import Pagination from "../components/Pagination.vue";
import StatusBadge from "../components/StatusBadge.vue";
import LookupSelect from "../components/LookupSelect.vue";
import Icon from "../components/Icon.vue";
import { useLatestQuery } from "../composables/useLatestQuery";
const listQuery = useLatestQuery();
const detailQuery = useLatestQuery();
let contactRevision = 0;
const { busy, error } = listQuery;
const items = ref<Visitor[]>([]),
  buildings = ref<Building[]>([]),
  page = ref(1),
  total = ref(0),
  filters = reactive({
    q: "",
    buildingId: "",
    status: "",
    startDate: "",
    endDate: "",
  });
const selected = ref<Visitor>(),
  modal = ref<"create" | "detail" | "correction" | null>(null),
  saving = ref(false),
  formError = ref(""),
  dirty = ref(false),
  arrivedLocal = ref(""),
  leftLocal = ref(""),
  correctionReason = ref(""),
  clearPhone = ref("");
const form = reactive<Model<"VisitorRequest">>({
  visitorName: "",
  phone: "",
  visitedStudentId: "",
  purpose: "",
  remark: "",
});
async function load() {
  await listQuery.run(
    (signal) =>
      get<Model<"VisitorPage">>(
        "/visitors",
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
function create() {
  detailQuery.cancel();
  selected.value = undefined;
  Object.assign(form, {
    visitorName: "",
    phone: "",
    visitedStudentId: "",
    purpose: "",
    remark: "",
    confirmDuplicate: false,
  });
  arrivedLocal.value = "";
  leftLocal.value = "";
  dirty.value = false;
  formError.value = "";
  modal.value = "create";
}
async function detail(v: Visitor) {
  contactRevision++;
  selected.value = v;
  clearPhone.value = "";
  formError.value = "";
  modal.value = "detail";
  await detailQuery.run(
    (signal) => get<Visitor>(`/visitors/${v.id}`, {}, signal),
    (r) => {
      selected.value = r;
    },
  );
  if (modal.value === "detail" && selected.value?.id === v.id)
    formError.value = detailQuery.error.value;
}
function correction() {
  detailQuery.cancel();
  if (!selected.value) return;
  Object.assign(form, {
    visitorName: selected.value.visitorName,
    phone: "",
    visitedStudentId: selected.value.visitedStudentId,
    purpose: selected.value.purpose,
    remark: selected.value.remark,
  });
  arrivedLocal.value = "";
  leftLocal.value = "";
  correctionReason.value = "";
  dirty.value = false;
  formError.value = "";
  modal.value = "correction";
}
async function save() {
  saving.value = true;
  formError.value = "";
  try {
    if (modal.value === "create") {
      await post("/visitors", {
        ...form,
        arrivedAt: isoTime(arrivedLocal.value),
      });
    } else if (selected.value) {
      await patch(`/visitors/${selected.value.id}`, {
        visitorName: form.visitorName,
        phone: form.phone || undefined,
        purpose: form.purpose,
        remark: form.remark,
        arrivedAt: isoTime(arrivedLocal.value),
        leftAt: isoTime(leftLocal.value),
        correctionReason: correctionReason.value,
        expectedVersion: selected.value.version,
      });
    }
    dirty.value = false;
    modal.value = null;
    notify("访客记录已保存");
    await load();
  } catch (e) {
    if (
      e instanceof ApiError &&
      e.code === "VISITOR_DUPLICATE_CONFIRMATION_REQUIRED" &&
      window.confirm("该联系电话已有在访登记。请确认是本次新的来访，是否继续？")
    ) {
      form.confirmDuplicate = true;
      saving.value = false;
      await save();
      return;
    }
    formError.value = errorText(e);
  } finally {
    saving.value = false;
  }
}
async function leave(v: Visitor) {
  if (
    !window.confirm(
      `确认 ${v.visitorName} 已离开 ${v.buildingName} ${v.roomNo}？将以当前时间记录离访。`,
    )
  )
    return;
  if (selected.value?.id === v.id) detailQuery.cancel();
  saving.value = true;
  try {
    const updated = await post<Visitor>(`/visitors/${v.id}/leave`, {
      expectedVersion: v.version,
    });
    if (selected.value?.id === v.id) selected.value = updated;
    notify("离访时间已记录");
    await load();
  } catch (e) {
    notify(errorText(e), "error");
  } finally {
    saving.value = false;
  }
}
async function reveal() {
  if (!selected.value) return;
  const id = selected.value.id;
  const revision = contactRevision;
  try {
    const contact = (
      await post<Model<"ContactResult">>(`/visitors/${id}/reveal-contact`)
    ).phone;
    if (
      revision === contactRevision &&
      modal.value === "detail" &&
      selected.value?.id === id
    )
      clearPhone.value = contact;
  } catch (e) {
    if (
      revision === contactRevision &&
      modal.value === "detail" &&
      selected.value?.id === id
    )
      formError.value = errorText(e);
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
    if (mode !== "detail") {
      detailQuery.cancel();
      contactRevision++;
    }
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
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>访客登记</h1>
      <p>记录到访与离开，联系方式按需查看，宿舍归属保留登记时快照。</p>
    </div>
    <button class="btn primary" @click="create">
      <Icon name="plus" />
      登记来访
    </button>
  </div>
  <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  <section class="panel">
    <form class="filters" @submit.prevent="search">
      <label>
        访客 / 联系方式
        <input v-model="filters.q" placeholder="姓名、电话或房号" />
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
        来访状态
        <select v-model="filters.status">
          <option value="">全部</option>
          <option value="VISITING">在访</option>
          <option value="LEFT">已离访</option>
        </select>
      </label>
      <label>
        入访开始日期
        <input v-model="filters.startDate" type="date" />
      </label>
      <label>
        截止日期
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
            status: '',
            startDate: '',
            endDate: '',
          });
          search();
        "
      >
        重置
      </button>
    </form>
    <p v-if="busy" class="panel-body loading">正在读取访客记录…</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>访客</th>
            <th>被访学生 / 宿舍</th>
            <th>事由</th>
            <th>入访 / 离访</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in items" :key="v.id">
            <td>
              <strong>{{ v.visitorName }}</strong>
              <small>{{ v.phone }}</small>
            </td>
            <td>
              {{ v.studentName }}
              <small>{{ v.buildingName }} {{ v.roomNo }}</small>
            </td>
            <td>{{ v.purpose }}</td>
            <td>
              {{ dateTime(v.arrivedAt) }}
              <small>{{ v.leftAt ? dateTime(v.leftAt) : "尚未离访" }}</small>
            </td>
            <td><StatusBadge :status="v.status" /></td>
            <td>
              <div class="cell-actions">
                <button class="text-link" @click="detail(v)">查看</button>
                <button
                  v-if="v.status === 'VISITING'"
                  class="text-link"
                  :disabled="saving"
                  @click="leave(v)"
                >
                  登记离访
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!busy && !items.length">
            <td colspan="6" class="empty-state">暂无匹配的访客记录。</td>
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
    v-if="modal === 'create' || modal === 'correction'"
    :title="modal === 'create' ? '登记来访' : '更正访客记录'"
    :busy="saving"
    :dirty="dirty"
    @close="modal = null"
  >
    <form class="form" @input="dirty = true" @submit.prevent="save">
      <label>
        访客姓名 *
        <input v-model.trim="form.visitorName" required maxlength="60" />
      </label>
      <label>
        联系电话 {{ modal === "create" ? "*" : "（留空保留）" }}
        <input
          v-model.trim="form.phone"
          type="tel"
          :required="modal === 'create'"
          pattern="[0-9+ ]{7,20}"
          maxlength="20"
        />
      </label>
      <LookupSelect
        v-if="modal === 'create'"
        v-model="form.visitedStudentId"
        kind="student"
        label="被访学生 *"
        require-residence
      />
      <div v-else class="info-note">
        被访学生：{{ selected?.studentName }} · {{ selected?.buildingName }}
        {{ selected?.roomNo }}
      </div>
      <label>
        来访事由 *
        <input v-model.trim="form.purpose" required maxlength="200" />
      </label>
      <label>
        入访时间
        <input v-model="arrivedLocal" type="datetime-local" />
        <small>
          {{
            modal === "create" ? "留空使用当前时间。" : "留空保留原入访时间。"
          }}
        </small>
      </label>
      <label v-if="modal === 'correction' && selected?.leftAt">
        离访时间
        <input v-model="leftLocal" type="datetime-local" />
        <small>留空保留原离访时间。</small>
      </label>
      <label>
        备注
        <textarea v-model.trim="form.remark" maxlength="500" />
      </label>
      <label v-if="modal === 'correction'">
        更正原因 *
        <textarea
          v-model.trim="correctionReason"
          required
          minlength="2"
          maxlength="500"
        />
      </label>
      <p class="form-note">
        不采集身份证号、证件照片或人脸。联系方式查看会记录操作审计。
      </p>
      <p v-if="formError" class="error-banner" role="alert">{{ formError }}</p>
      <button class="btn primary" :disabled="saving">
        {{
          saving
            ? "正在保存…"
            : modal === "create"
              ? "保存来访登记"
              : "保存更正并留痕"
        }}
      </button>
    </form>
  </Drawer>
  <Drawer
    v-if="modal === 'detail' && selected"
    title="访客登记详情"
    :subtitle="selected.buildingName + ' ' + selected.roomNo"
    :busy="saving"
    @close="modal = null"
  >
    <p v-if="formError" class="error-banner">{{ formError }}</p>
    <StatusBadge :status="selected.status" />
    <dl class="details">
      <div>
        <dt>访客姓名</dt>
        <dd>{{ selected.visitorName }}</dd>
      </div>
      <div>
        <dt>联系电话</dt>
        <dd>
          {{ clearPhone || selected.phone }}
          <br />
          <button v-if="!clearPhone" class="text-link" @click="reveal">
            按需查看明文
          </button>
        </dd>
      </div>
      <div>
        <dt>被访学生</dt>
        <dd>{{ selected.studentName }}</dd>
      </div>
      <div>
        <dt>来访事由</dt>
        <dd>{{ selected.purpose }}</dd>
      </div>
      <div>
        <dt>入访时间</dt>
        <dd>{{ dateTime(selected.arrivedAt) }}</dd>
      </div>
      <div>
        <dt>离访时间</dt>
        <dd>{{ dateTime(selected.leftAt) }}</dd>
      </div>
      <div>
        <dt>备注</dt>
        <dd>{{ selected.remark || "无" }}</dd>
      </div>
    </dl>
    <template #footer>
      <button
        v-if="selected.status === 'VISITING'"
        class="btn primary"
        :disabled="saving"
        @click="leave(selected)"
      >
        登记离访
      </button>
      <button class="btn" @click="correction">更正记录</button>
    </template>
  </Drawer>
</template>
