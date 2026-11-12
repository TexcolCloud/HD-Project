<script setup lang="ts">
import { ref, reactive, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { get, post } from "../api/client";
import type {
  Model,
  Repair,
  RepairEvent,
  Building,
  FileAsset,
} from "../api/types";
import {
  dateTime,
  errorText,
  notify,
  statusNames,
  categoryNames,
  actionNames,
} from "../ui";
import Drawer from "../components/Drawer.vue";
import Pagination from "../components/Pagination.vue";
import StatusBadge from "../components/StatusBadge.vue";
import UploadField from "../components/UploadField.vue";
import RepairVisitPanel from "../components/dispatch/RepairVisitPanel.vue";
import ManagerAssist from "../components/ManagerAssist.vue";
import { useLatestQuery } from "../composables/useLatestQuery";
const listQuery = useLatestQuery();
const detailQuery = useLatestQuery();
const { busy, error } = listQuery;
const { busy: detailBusy, error: detailError } = detailQuery;
const route = useRoute(),
  router = useRouter(),
  items = ref<Repair[]>([]),
  buildings = ref<Building[]>([]),
  page = ref(1),
  total = ref(0);
const filters = reactive({
  q: String(route.query.q || ""),
  buildingId: String(route.query.buildingId || ""),
  status: String(route.query.status || ""),
  category: String(route.query.category || ""),
  startDate: String(route.query.startDate || ""),
  endDate: String(route.query.endDate || ""),
});
const selected = ref<Repair>(),
  events = ref<RepairEvent[]>([]),
  action = ref(""),
  note = ref(""),
  saving = ref(false),
  actionError = ref(""),
  images = ref<FileAsset[]>([]),
  uploadBlocked = ref(false),
  visitDirty = ref(false),
  visitBusy = ref(false),
  visitEditing = ref(false);
async function load() {
  await listQuery.run(
    (signal) =>
      get<Model<"RepairPage">>(
        "/repairs",
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
async function detail(id: string) {
  action.value = "";
  selected.value = undefined;
  events.value = [];
  await detailQuery.run(
    (signal) =>
      Promise.all([
        get<Repair>(`/repairs/${id}`, {}, signal),
        get<RepairEvent[]>(`/repairs/${id}/events`, {}, signal),
      ]),
    ([r, e]) => {
      selected.value = r;
      events.value = e;
    },
  );
}
function open(id: string) {
  void router.replace("/repairs/" + id);
  if (String(route.params.id) === id) void detail(id);
}
function close() {
  detailQuery.cancel();
  selected.value = undefined;
  action.value = "";
  void router.replace("/repairs");
}
function begin(name: string) {
  action.value = name;
  note.value = "";
  actionError.value = "";
  images.value = [];
  uploadBlocked.value = false;
}
async function submitAction() {
  if (!selected.value) return;
  saving.value = true;
  actionError.value = "";
  const body: Model<"ActionRequest"> = {
    expectedVersion: selected.value.version,
  };
  if (["reject", "rework"].includes(action.value))
    body.reason = note.value.trim();
  else if (action.value === "finish") {
    body.resultDescription = note.value.trim();
    body.resultImageIds = images.value.map((i) => i.id);
  } else if (action.value === "notes" || note.value.trim())
    body.note = note.value.trim();
  try {
    const r = await post<Repair>(
      `/repairs/${selected.value.id}/${action.value}`,
      body,
    );
    selected.value = r;
    action.value = "";
    note.value = "";
    notify("工单办理记录已保存");
    events.value = await get<RepairEvent[]>(`/repairs/${r.id}/events`);
    await load();
  } catch (e) {
    if (!action.value)
      detailError.value = "办理已保存，但进度刷新失败：" + errorText(e);
    else actionError.value = errorText(e);
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
onMounted(async () => {
  try {
    buildings.value = (
      await get<Model<"BuildingPage">>("/buildings", { pageSize: 100 })
    ).items;
  } catch (e) {
    error.value = errorText(e);
  }
  await load();
  if (route.params.id) await detail(String(route.params.id));
});
watch(
  () => route.params.id,
  (id) => {
    if (id) void detail(String(id));
    else {
      detailQuery.cancel();
      selected.value = undefined;
    }
  },
);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>报修工单</h1>
      <p>从受理到确认，跟进负责楼栋内的每一次报修。</p>
    </div>
    <button class="btn" :disabled="busy" @click="load">刷新列表</button>
  </div>
  <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  <section class="panel">
    <form class="filters" @submit.prevent="search">
      <label>
        关键词
        <input v-model="filters.q" placeholder="工单号 / 房号 / 问题" />
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
        状态
        <select v-model="filters.status">
          <option value="">全部状态</option>
          <option
            v-for="status in [
              'SUBMITTED',
              'ACCEPTED',
              'PROCESSING',
              'PENDING_CONFIRMATION',
              'COMPLETED',
              'CANCELLED',
              'REJECTED',
            ]"
            :key="status"
            :value="status"
          >
            {{ statusNames[status] }}
          </option>
        </select>
      </label>
      <label>
        类型
        <select v-model="filters.category">
          <option value="">全部类型</option>
          <option
            v-for="(label, code) in categoryNames"
            :key="code"
            :value="code"
          >
            {{ label }}
          </option>
        </select>
      </label>
      <label>
        提交开始日期
        <input v-model="filters.startDate" type="date" />
      </label>
      <label>
        截止日期
        <input v-model="filters.endDate" type="date" :min="filters.startDate" />
      </label>
      <button class="btn primary" :disabled="busy">查询</button>
      <button
        type="button"
        class="btn"
        @click="
          Object.assign(filters, {
            q: '',
            buildingId: '',
            status: '',
            category: '',
            startDate: '',
            endDate: '',
          });
          search();
        "
      >
        重置
      </button>
    </form>
    <p v-if="busy" class="panel-body loading">正在读取工单…</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>报修事项</th>
            <th>宿舍 / 提交学生</th>
            <th>类别 / 紧急程度</th>
            <th>状态</th>
            <th>办理宿管</th>
            <th>提交时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in items" :key="r.id">
            <td>
              <strong>{{ r.title }}</strong>
              <small class="num">{{ r.orderNo }}</small>
            </td>
            <td>
              {{ r.buildingName }} {{ r.roomNo }}
              <small>{{ r.reporterName }}</small>
            </td>
            <td>
              {{ categoryNames[r.category] }}
              <small>{{ r.priority === "URGENT" ? "较急" : "一般" }}</small>
            </td>
            <td><StatusBadge :status="r.status" /></td>
            <td>{{ r.handlerName || "尚未接单" }}</td>
            <td>{{ dateTime(r.submittedAt) }}</td>
            <td>
              <button class="text-link" @click="open(r.id)">查看并处理</button>
            </td>
          </tr>
          <tr v-if="!busy && !items.length">
            <td colspan="7" class="empty-state">
              没有匹配的工单，请调整筛选条件。
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
  <p v-if="detailError && !selected" class="error-banner">{{ detailError }}</p>
  <p v-if="detailBusy && !selected" class="loading">正在读取工单详情…</p>
  <Drawer
    v-if="selected"
    title="报修工单详情"
    :subtitle="selected.orderNo"
    :busy="saving || visitBusy"
    :dirty="(!!action && !!note) || visitDirty"
    @close="close"
  >
    <p v-if="detailError" class="error-banner">{{ detailError }}</p>
    <section class="detail-section">
      <StatusBadge :status="selected.status" />
      <h3 class="detail-title">{{ selected.title }}</h3>
      <dl class="details">
        <div>
          <dt>报修宿舍</dt>
          <dd>{{ selected.buildingName }} {{ selected.roomNo }}</dd>
        </div>
        <div>
          <dt>报修学生</dt>
          <dd>{{ selected.reporterName }}</dd>
        </div>
        <div>
          <dt>故障分类</dt>
          <dd>{{ categoryNames[selected.category] }}</dd>
        </div>
        <div>
          <dt>紧急程度</dt>
          <dd>{{ selected.priority === "URGENT" ? "较急" : "一般" }}</dd>
        </div>
        <div>
          <dt>联系电话</dt>
          <dd>{{ selected.contactName }} · {{ selected.contactPhone }}</dd>
        </div>
        <div>
          <dt>方便联系时段</dt>
          <dd>{{ selected.contactTimeSlot || "未指定" }}</dd>
        </div>
        <div>
          <dt>办理宿管</dt>
          <dd>{{ selected.handlerName || "尚未接单" }}</dd>
        </div>
        <div>
          <dt>提交时间</dt>
          <dd>{{ dateTime(selected.submittedAt) }}</dd>
        </div>
      </dl>
    </section>
    <RepairVisitPanel
      :repair="selected"
      :disabled="!!action || saving"
      @updated="
        selected = $event;
        load();
      "
      @dirty="visitDirty = $event"
      @busy="visitBusy = $event"
      @editing="visitEditing = $event"
    />
    <section class="detail-section">
      <h3>故障描述</h3>
      <p class="description">{{ selected.description }}</p>
    </section>
    <section v-if="selected.reportImages.length" class="detail-section">
      <h3>学生故障图片</h3>
      <div class="image-grid">
        <a
          v-for="file in selected.reportImages"
          :key="file.id"
          :href="file.url"
          target="_blank"
        >
          <img :src="file.thumbnailUrl" :alt="file.originalName" />
        </a>
      </div>
    </section>
    <section v-if="selected.latestResult" class="detail-section">
      <h3>最近处理结果</h3>
      <p class="description">{{ selected.latestResult }}</p>
      <div class="image-grid" style="margin-top: 12px">
        <a
          v-for="file in selected.resultImages"
          :key="file.id"
          :href="file.url"
          target="_blank"
        >
          <img :src="file.thumbnailUrl" :alt="file.originalName" />
        </a>
      </div>
    </section>
    <section class="detail-section">
      <h3>处理进度</h3>
      <ol class="timeline">
        <li v-for="event in events" :key="event.id">
          <strong>
            {{
              actionNames[event.action] ||
              statusNames[event.toStatus] ||
              event.action
            }}
          </strong>
          <p v-if="event.note">{{ event.note }}</p>
          <small>
            {{ dateTime(event.createdAt) }} · {{ event.operatorName }}
          </small>
        </li>
      </ol>
      <p v-if="!events.length" class="muted">暂无进度记录。</p>
    </section>
    <ManagerAssist
      :repair-id="selected.id"
      :context-version="selected.version"
      :image-count="selected.reportImages.length"
    />
    <section v-if="action" class="detail-section">
      <h3>{{ actionNames[action] }}</h3>
      <form class="form" @submit.prevent="submitAction">
        <label>
          {{
            action === "finish"
              ? "处理结果"
              : action === "reject" || action === "rework"
                ? "具体原因"
                : "处理说明"
          }}
          {{
            ["finish", "reject", "rework", "notes"].includes(action) ? "*" : ""
          }}
          <textarea
            v-model="note"
            :required="['finish', 'reject', 'rework', 'notes'].includes(action)"
            :minlength="
              ['finish', 'reject', 'rework', 'notes'].includes(action) ? 2 : 0
            "
            maxlength="1000"
          />
        </label>
        <UploadField
          v-if="action === 'finish'"
          v-model="images"
          usage="RESULT"
          :disabled="saving"
          @blocked="uploadBlocked = $event"
        />
        <p v-if="action === 'finish'" class="form-note">
          提交完工后进入待确认，由原报修学生确认。没有解决时可填写原因返修。
        </p>
        <p v-if="actionError" class="error-banner" role="alert">
          {{ actionError }}
        </p>
        <div class="button-row">
          <button class="btn primary" :disabled="saving || uploadBlocked">
            {{ saving ? "正在保存…" : "确认" + actionNames[action] }}
          </button>
          <button
            class="btn"
            type="button"
            :disabled="saving"
            @click="
              action = '';
              note = '';
            "
          >
            取消操作
          </button>
        </div>
      </form>
    </section>
    <template #footer>
      <template v-if="!action">
        <button
          v-for="name in selected.allowedActions.filter((a) =>
            ['accept', 'reject', 'start', 'finish', 'notes', 'rework'].includes(
              a,
            ),
          )"
          :key="name"
          class="btn"
          :class="name === 'reject' ? 'danger' : 'primary'"
          :disabled="visitEditing || visitBusy"
          @click="begin(name)"
        >
          {{ actionNames[name] }}
        </button>
        <button
          class="btn"
          :disabled="visitEditing || visitBusy"
          @click="detail(selected.id)"
        >
          刷新详情
        </button>
      </template>
    </template>
  </Drawer>
</template>
