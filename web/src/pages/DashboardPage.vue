<script setup lang="ts">
import { computed, ref, onMounted, watch } from "vue";
import { allPages } from "../api/allPages";
import { get, post } from "../api/client";
import type {
  Model,
  Building,
  Repair,
  Room,
  Visitor,
  Hygiene,
} from "../api/types";
import { dateTime, errorText, notify } from "../ui";
import Icon from "../components/Icon.vue";
import StatusBadge from "../components/StatusBadge.vue";
import ManagerAssist from "../components/ManagerAssist.vue";
import { useLatestQuery } from "../composables/useLatestQuery";
const query = useLatestQuery();
const { busy, error } = query;
const buildings = ref<Building[]>([]),
  buildingId = ref(""),
  overview = ref<Model<"Overview">>(),
  repairs = ref<Repair[]>([]),
  rooms = ref<Room[]>([]),
  visitors = ref<Visitor[]>([]),
  hygiene = ref<Hygiene[]>([]),
  floor = ref(1);
const floorRooms = computed(() =>
  rooms.value.filter((r) => r.floorNo === floor.value),
);
const floorBuildingId = computed(() => {
  if (buildingId.value) return buildingId.value;
  const buildings = new Set(floorRooms.value.map((room) => room.buildingId));
  return buildings.size === 1 ? floorRooms.value[0]?.buildingId : undefined;
});
const accepting = ref("");
function rank(r: Repair) {
  return (r.status === "SUBMITTED" ? 0 : 2) + (r.priority === "URGENT" ? 0 : 1);
}
async function accept(order: Repair) {
  if (accepting.value) return;
  accepting.value = order.id;
  try {
    await post("/repairs/" + order.id + "/accept", {
      expectedVersion: order.version,
    });
    notify("已接单");
    await load();
  } catch (cause) {
    notify(errorText(cause), "error");
  } finally {
    accepting.value = "";
  }
}
async function load() {
  const q = { buildingId: buildingId.value };
  await query.run(
    (signal) =>
      Promise.all([
        get<Model<"Overview">>("/statistics/overview", q, signal),
        Promise.all(
          ["SUBMITTED", "ACCEPTED", "PROCESSING"].map((status) =>
            allPages(
              (page) =>
                get<Model<"RepairPage">>(
                  "/repairs",
                  { ...q, status, page, pageSize: 100 },
                  signal,
                ),
              signal,
            ),
          ),
        ),
        allPages(
          (page) =>
            get<Model<"RoomPage">>(
              "/rooms",
              { ...q, page, pageSize: 100 },
              signal,
            ),
          signal,
        ),
        get<Model<"VisitorPage">>(
          "/visitors",
          {
            ...q,
            status: "VISITING",
            pageSize: 3,
          },
          signal,
        ),
        get<Model<"HygienePage">>(
          "/hygiene-inspections",
          { ...q, pageSize: 3 },
          signal,
        ),
      ]),
    ([o, r, ro, v, h]) => {
      overview.value = o;
      repairs.value = r
        .flat()
        .sort(
          (a, b) =>
            rank(a) - rank(b) ||
            Date.parse(b.submittedAt) - Date.parse(a.submittedAt),
        )
        .slice(0, 6);
      rooms.value = ro;
      visitors.value = v.items;
      hygiene.value = h.items;
      if (ro.length) floor.value = Math.min(...ro.map((x) => x.floorNo));
    },
  );
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
});
watch(buildingId, load);
</script>
<template>
  <div class="page-heading dashboard-lead">
    <h1>工作台</h1>
    <div class="lead-actions">
      <label class="inline-label">
        数据范围
        <select v-model="buildingId" aria-label="工作台楼栋">
          <option value="">全部负责楼栋</option>
          <option v-for="b in buildings" :key="b.id" :value="b.id">
            {{ b.name }}
          </option>
        </select>
      </label>
      <RouterLink class="btn primary" to="/visitors?action=create">
        <Icon name="plus" />
        登记访客
      </RouterLink>
      <RouterLink class="btn" to="/hygiene?action=create">
        <Icon name="plus" />
        录入评分
      </RouterLink>
      <RouterLink class="btn" to="/housing/students?action=checkin">
        <Icon name="plus" />
        办理入住
      </RouterLink>
    </div>
  </div>
  <div v-if="error" class="error-banner" role="alert">
    {{ error }}
    <button @click="load">重新加载</button>
  </div>
  <p v-if="!overview && busy" class="loading" role="status">
    正在读取本栋数据…
  </p>
  <template v-if="overview">
    <div class="summary-strip" :aria-busy="busy">
      <div>
        <span>当前入住 / 总容量</span>
        <strong class="num">
          {{ overview.occupiedCount }}
          <small>/ {{ overview.capacity }} 人</small>
        </strong>
        <p>
          入住率
          {{
            overview.capacity
              ? overview.occupancyRate.toFixed(1) + "%"
              : "暂无可用容量"
          }}
        </p>
      </div>
      <div>
        <span>未结报修</span>
        <strong class="num">
          {{ overview.openRepairs }}
          <small>单</small>
        </strong>
        <RouterLink class="text-link" to="/repairs?status=PENDING_CONFIRMATION">
          其中 {{ overview.pendingConfirmation }} 单待学生确认
        </RouterLink>
      </div>
      <div>
        <span>当前在访</span>
        <strong class="num">
          {{ overview.visitingCount }}
          <small>人</small>
        </strong>
        <p>今日登记 {{ overview.todayVisitors }} 人次</p>
      </div>
      <div>
        <span>有效巡检平均分</span>
        <strong class="num">
          {{ overview.hygieneAverage?.toFixed(1) ?? "—" }}
          <small>/ 100</small>
        </strong>
        <p>{{ overview.rectifyRoomCount }} 间宿舍最新评分待整改</p>
      </div>
    </div>
    <div class="dashboard-grid">
      <div class="main-column">
        <section class="panel task-panel">
          <header class="panel-head">
            <div>
              <h2>待跟进报修</h2>
            </div>
            <RouterLink to="/repairs" class="text-link">全部工单 →</RouterLink>
          </header>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>报修事项</th>
                  <th>宿舍</th>
                  <th>状态 / 办理宿管</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in repairs" :key="r.id">
                  <td>
                    <strong>{{ r.title }}</strong>
                    <small>
                      {{ r.priority === "URGENT" ? "较急" : "一般" }} ·
                      {{ r.orderNo }} · {{ dateTime(r.submittedAt) }}
                    </small>
                  </td>
                  <td>{{ r.buildingName }} {{ r.roomNo }}</td>
                  <td>
                    <StatusBadge :status="r.status" />
                    <small>{{ r.handlerName || "尚未接单" }}</small>
                  </td>
                  <td>
                    <button
                      v-if="
                        r.status === 'SUBMITTED' &&
                        r.allowedActions.includes('accept')
                      "
                      class="text-link"
                      :disabled="!!accepting"
                      @click="accept(r)"
                    >
                      接单
                    </button>
                    <RouterLink :to="'/repairs/' + r.id" class="text-link">
                      查看详情
                    </RouterLink>
                  </td>
                </tr>
                <tr v-if="!repairs.length">
                  <td colspan="4" class="empty-state">
                    当前没有需要跟进的工单。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <section class="panel">
          <header class="panel-head">
            <div>
              <h2>楼层房间概览</h2>
            </div>
            <RouterLink to="/housing/rooms" class="text-link">
              宿舍档案 →
            </RouterLink>
          </header>
          <div class="floor-tabs">
            <button
              v-for="f in [...new Set(rooms.map((r) => r.floorNo))].sort(
                (a, b) => a - b,
              )"
              :key="f"
              :class="{ active: floor === f }"
              @click="floor = f"
            >
              {{ f }}F
            </button>
          </div>
          <div class="room-grid">
            <RouterLink
              v-for="r in floorRooms.slice(0, 20)"
              :key="r.id"
              :to="{ path: '/housing/rooms', query: { roomId: r.id } }"
              :class="{ vacant: r.occupiedCount === 0, disabled: !r.enabled }"
            >
              <strong class="num">{{ r.roomNo }}</strong>
              <small>
                {{ r.buildingName }} · {{ r.occupiedCount }}/{{ r.capacity }} 人
                {{ !r.enabled ? " · 停用" : "" }}
              </small>
            </RouterLink>
            <RouterLink
              v-if="floorRooms.length > 20"
              class="text-link"
              :to="{
                path: '/housing/rooms',
                query: { buildingId: floorBuildingId },
              }"
            >
              查看全部 {{ floorRooms.length }} 间 →
            </RouterLink>
            <p v-if="!rooms.length" class="empty-state">
              尚无宿舍档案，请先建立楼栋和房间。
            </p>
          </div>
        </section>
      </div>
      <aside class="aside-column">
        <section class="panel">
          <header class="panel-head">
            <h2>楼内访客</h2>
            <RouterLink to="/visitors" class="text-link">全部 →</RouterLink>
          </header>
          <div class="compact-list">
            <div v-for="v in visitors" :key="v.id">
              <span>
                <strong>{{ v.visitorName }}</strong>
                <small>
                  {{ v.buildingName }} {{ v.roomNo }} ·
                  {{ dateTime(v.arrivedAt) }}
                </small>
              </span>
              <strong v-if="v.overdue" class="field-error">
                超24小时未离访
              </strong>
              <StatusBadge :status="v.status" />
              <RouterLink
                class="text-link"
                :to="{ path: '/visitors', query: { visitorId: v.id } }"
              >
                详情 / 离访
              </RouterLink>
            </div>
            <p v-if="!visitors.length" class="empty-state">
              目前没有在访人员。
            </p>
          </div>
        </section>
        <section class="panel">
          <header class="panel-head">
            <h2>最近卫生巡检</h2>
            <RouterLink to="/hygiene" class="text-link">查看 →</RouterLink>
          </header>
          <div class="compact-list">
            <div v-for="h in hygiene" :key="h.id">
              <span>
                <strong>{{ h.buildingName }} {{ h.roomNo }}</strong>
                <small>{{ h.inspectionDate }} · {{ h.inspectorName }}</small>
              </span>
              <span class="score-number num">{{ h.totalScore }}</span>
            </div>
            <p v-if="!hygiene.length" class="empty-state">尚无有效巡检记录。</p>
          </div>
        </section>
        <ManagerAssist />
      </aside>
    </div>
  </template>
</template>
