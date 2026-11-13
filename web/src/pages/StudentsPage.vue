<script setup lang="ts">
import { ref, reactive, onMounted, watch } from "vue";
import { get, post, patch } from "../api/client";
import type { Model, Student, Residence } from "../api/types";
import { notify, errorText, dateTime, isoTime } from "../ui";
import Drawer from "../components/Drawer.vue";
import Pagination from "../components/Pagination.vue";
import LookupSelect from "../components/LookupSelect.vue";
import Icon from "../components/Icon.vue";
import { useLatestQuery } from "../composables/useLatestQuery";
const listQuery = useLatestQuery();
const historyQuery = useLatestQuery();
const { busy: historyBusy, error: historyError } = historyQuery;
const { busy, error } = listQuery;
const items = ref<Student[]>([]),
  history = ref<Residence[]>([]),
  page = ref(1),
  total = ref(0),
  q = ref(""),
  status = ref("");
const modal = ref<
    "student" | "history" | "checkin" | "transfer" | "checkout" | null
  >(null),
  selected = ref<Student>(),
  current = ref<Residence>(),
  saving = ref(false),
  dirty = ref(false),
  formError = ref("");
const studentForm = reactive<Model<"StudentRequest">>({
  studentNo: "",
  name: "",
  college: "",
  major: "",
  grade: "",
  className: "",
  phone: "",
  status: "ACTIVE",
});
const residenceForm = reactive({
  studentId: "",
  roomId: "",
  checkedInAt: "",
  targetRoomId: "",
  reason: "",
});
async function load() {
  await listQuery.run(
    (signal) =>
      get<Model<"StudentPage">>(
        "/students",
        {
          q: q.value,
          status: status.value,
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
function search() {
  page.value = 1;
  void load();
}
function turn(p: number) {
  page.value = p;
  void load();
}
function edit(s?: Student) {
  historyQuery.cancel();
  selected.value = s;
  Object.assign(studentForm, {
    studentNo: s?.studentNo || "",
    name: s?.name || "",
    college: s?.college || "",
    major: s?.major || "",
    grade: s?.grade || "",
    className: s?.className || "",
    phone: s?.phone || "",
    status: s?.status || "ACTIVE",
    expectedVersion: s?.version,
  });
  dirty.value = false;
  formError.value = "";
  modal.value = "student";
}
async function showHistory(s: Student) {
  selected.value = s;
  history.value = [];
  current.value = undefined;
  formError.value = "";
  modal.value = "history";
  await historyQuery.run(
    (signal) =>
      get<Model<"ResidencePage">>(
        "/residences",
        {
          studentId: s.id,
          pageSize: 100,
        },
        signal,
      ),
    (result) => {
      history.value = result.items;
      current.value = result.items.find((r) => !r.checkedOutAt);
    },
  );
  if (modal.value === "history" && selected.value?.id === s.id)
    formError.value = historyQuery.error.value;
}
function residenceAction(
  action: "checkin" | "transfer" | "checkout",
  s?: Student,
) {
  historyQuery.cancel();
  selected.value = s || selected.value;
  Object.assign(residenceForm, {
    studentId: selected.value?.id || "",
    roomId: "",
    checkedInAt: "",
    targetRoomId: "",
    reason: "",
  });
  dirty.value = false;
  formError.value = "";
  modal.value = action;
}
async function save() {
  saving.value = true;
  formError.value = "";
  try {
    if (modal.value === "student") {
      if (selected.value)
        await patch(`/students/${selected.value.id}`, studentForm);
      else await post("/students", studentForm);
    } else if (modal.value === "checkin") {
      await post("/residences/check-in", {
        studentId: residenceForm.studentId,
        roomId: residenceForm.roomId,
        checkedInAt: isoTime(residenceForm.checkedInAt),
      });
    } else if (current.value) {
      if (modal.value === "transfer")
        await post(`/residences/${current.value.id}/transfer`, {
          targetRoomId: residenceForm.targetRoomId,
          reason: residenceForm.reason,
          expectedVersion: current.value.version,
        });
      else
        await post(`/residences/${current.value.id}/check-out`, {
          reason: residenceForm.reason,
          expectedVersion: current.value.version,
        });
    } else throw new Error("未找到有效入住，请重新打开学生入住记录");
    dirty.value = false;
    modal.value = null;
    notify("学生信息与入住记录已更新");
    await load();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    saving.value = false;
  }
}
watch(
  modal,
  (mode) => {
    if (mode !== "history") historyQuery.cancel();
  },
  { flush: "sync" },
);
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>学生入住</h1>
      <p>维护学生档案，办理入住、调宿与退宿，保留每一段居住历史。</p>
    </div>
    <div class="page-actions">
      <button
        class="btn"
        @click="
          selected = undefined;
          residenceAction('checkin');
        "
      >
        办理入住
      </button>
      <button class="btn primary" @click="edit()">
        <Icon name="plus" />
        新建学生档案
      </button>
    </div>
  </div>
  <p v-if="error" class="error-banner" role="alert">
    {{ error }}
    <button @click="load">重新加载</button>
  </p>
  <section class="panel">
    <form class="filters" @submit.prevent="search">
      <label>
        姓名 / 学号
        <input v-model="q" placeholder="输入姓名或学号" />
      </label>
      <label>
        档案状态
        <select v-model="status">
          <option value="">全部</option>
          <option value="ACTIVE">在校</option>
          <option value="INACTIVE">停用</option>
        </select>
      </label>
      <button class="btn primary" :disabled="busy">查询</button>
      <button
        class="btn"
        type="button"
        @click="
          q = '';
          status = '';
          search();
        "
      >
        重置
      </button>
    </form>
    <p v-if="busy" class="panel-body loading">正在读取学生档案…</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>学生</th>
            <th>院系 / 班级</th>
            <th>联系方式</th>
            <th>当前宿舍</th>
            <th>档案状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in items" :key="s.id">
            <td>
              <strong>{{ s.name }}</strong>
              <small class="num">{{ s.studentNo }}</small>
            </td>
            <td>
              {{ s.college }}
              <small>{{ s.major }} · {{ s.className || s.grade }}</small>
            </td>
            <td>{{ s.phone || "按权限隐藏" }}</td>
            <td>{{ s.roomId ? s.buildingName + " " + s.roomNo : "未入住" }}</td>
            <td>
              <span class="status" :class="s.status">
                {{ s.status === "ACTIVE" ? "在校" : "停用" }}
              </span>
            </td>
            <td>
              <div class="cell-actions">
                <button class="text-link" @click="showHistory(s)">
                  入住历史
                </button>
                <button class="text-link" @click="edit(s)">编辑档案</button>
                <button
                  v-if="!s.roomId && s.status === 'ACTIVE'"
                  class="text-link"
                  @click="residenceAction('checkin', s)"
                >
                  入住
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!busy && !items.length">
            <td colspan="6" class="empty-state">
              没有匹配的学生档案，可调整关键词或新建档案。
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
    v-if="modal === 'student'"
    :title="selected ? '编辑学生档案' : '新建学生档案'"
    :busy="saving"
    :dirty="dirty"
    @close="modal = null"
  >
    <form class="form" @input="dirty = true" @submit.prevent="save">
      <label>
        学号 *
        <input
          v-model.trim="studentForm.studentNo"
          required
          maxlength="40"
          :readonly="!!selected"
        />
      </label>
      <label>
        姓名 *
        <input v-model.trim="studentForm.name" required maxlength="50" />
      </label>
      <p v-if="!selected" class="form-note">
        新账号凭据由学校维护人员核验身份后单独发放，学生首次登录需设置自己的密码。
      </p>
      <label>
        学院 *
        <input v-model.trim="studentForm.college" required maxlength="100" />
      </label>
      <label>
        专业
        <input v-model.trim="studentForm.major" maxlength="100" />
      </label>
      <div class="form-row">
        <label>
          年级
          <input
            v-model.trim="studentForm.grade"
            maxlength="20"
            placeholder="例如 2024"
          />
        </label>
        <label>
          班级
          <input v-model.trim="studentForm.className" maxlength="80" />
        </label>
      </div>
      <label>
        联系电话 *
        <input
          v-model.trim="studentForm.phone"
          type="tel"
          required
          pattern="[0-9+ ]{7,20}"
          maxlength="20"
        />
        <small>联系方式受权限限制；如显示掩码，请由档案创建宿管维护。</small>
      </label>
      <label>
        档案状态
        <select v-model="studentForm.status">
          <option value="ACTIVE">在校</option>
          <option value="INACTIVE">停用</option>
        </select>
      </label>
      <p v-if="formError" class="error-banner" role="alert">{{ formError }}</p>
      <button class="btn primary" :disabled="saving">
        {{ saving ? "正在保存…" : "保存学生档案" }}
      </button>
    </form>
  </Drawer>
  <Drawer
    v-if="modal === 'history' && selected"
    title="学生入住历史"
    :subtitle="selected.studentNo + ' · ' + selected.name"
    @close="modal = null"
  >
    <p v-if="formError" class="error-banner">{{ formError }}</p>
    <div class="info-note">
      当前宿舍：{{
        selected.roomId
          ? selected.buildingName + " " + selected.roomNo
          : "未入住"
      }}。调宿以原入住和目标入住一起完成，历史不会删除。
    </div>
    <p v-if="historyBusy" class="loading" role="status">正在读取入住历史…</p>
    <ol class="timeline">
      <li v-for="r in history" :key="r.id">
        <strong>{{ r.buildingName }} {{ r.roomNo }}</strong>
        <p>
          {{ dateTime(r.checkedInAt) }} —
          {{ r.checkedOutAt ? dateTime(r.checkedOutAt) : "当前在住" }}
        </p>
        <small>{{ r.reason || "办理入住" }}</small>
      </li>
    </ol>
    <p
      v-if="!historyBusy && !historyError && !history.length"
      class="empty-state"
    >
      暂无入住记录。
    </p>
    <template #footer>
      <button
        v-if="current && !historyBusy && !historyError"
        class="btn primary"
        @click="residenceAction('transfer')"
      >
        办理调宿
      </button>
      <button
        v-if="current && !historyBusy && !historyError"
        class="btn danger"
        @click="residenceAction('checkout')"
      >
        办理退宿
      </button>
      <button
        v-if="!current && !historyBusy && !historyError"
        class="btn primary"
        @click="residenceAction('checkin')"
      >
        办理入住
      </button>
    </template>
  </Drawer>
  <Drawer
    v-if="['checkin', 'transfer', 'checkout'].includes(modal || '')"
    :title="
      modal === 'checkin'
        ? '办理入住'
        : modal === 'transfer'
          ? '办理调宿'
          : '办理退宿'
    "
    :busy="saving"
    :dirty="dirty"
    @close="modal = null"
  >
    <form class="form" @input="dirty = true" @submit.prevent="save">
      <template v-if="modal === 'checkin'">
        <LookupSelect
          v-model="residenceForm.studentId"
          kind="student"
          label="学生 *"
          available
          :initial-label="selected?.name"
        />
        <LookupSelect
          v-model="residenceForm.roomId"
          kind="room"
          label="入住房间 *"
          available
        />
        <label>
          实际入住时间
          <input v-model="residenceForm.checkedInAt" type="datetime-local" />
          <small>留空使用办理时间，按北京时间保存。</small>
        </label>
      </template>
      <template v-else>
        <div class="info-note">
          {{ selected?.name }} · 当前入住 {{ current?.buildingName }}
          {{ current?.roomNo }}
        </div>
        <LookupSelect
          v-if="modal === 'transfer'"
          v-model="residenceForm.targetRoomId"
          kind="room"
          label="目标宿舍 *"
          available
        />
        <label>
          {{ modal === "transfer" ? "调宿原因" : "退宿原因" }} *
          <textarea
            v-model.trim="residenceForm.reason"
            required
            minlength="2"
            maxlength="500"
          />
        </label>
        <p class="form-note">
          旧房间的报修记录保留原归属，退宿或调宿不会关闭未结工单。
        </p>
      </template>
      <p v-if="formError" class="error-banner" role="alert">{{ formError }}</p>
      <button class="btn primary" :disabled="saving">
        {{
          saving
            ? "正在办理…"
            : modal === "checkin"
              ? "确认入住"
              : modal === "transfer"
                ? "确认调宿"
                : "确认退宿"
        }}
      </button>
    </form>
  </Drawer>
</template>
