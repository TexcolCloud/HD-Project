<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import { get } from "../api/client";
import type { Model, Building, Room } from "../api/types";
import { notify } from "../ui";
import { useLatestQuery } from "../composables/useLatestQuery";
import Icon from "../components/Icon.vue";
import Pagination from "../components/Pagination.vue";
import RoomEditor from "../components/housing/RoomEditor.vue";
import BuildingEditor from "../components/housing/BuildingEditor.vue";
import RoomDetail from "../components/housing/RoomDetail.vue";
const route = useRoute();
const tab = ref<"rooms" | "buildings">("rooms");
const buildings = ref<Building[]>([]),
  items = ref<Room[]>([]),
  page = ref(1),
  total = ref(0);
const filters = reactive({
  q: String(route.query.q || ""),
  buildingId: "",
  status: "",
});
const modal = ref<"room" | "building" | "detail" | null>(null),
  selectedRoom = ref<Room>(),
  selectedBuilding = ref<Building>();
const listQuery = useLatestQuery(),
  buildingQuery = useLatestQuery();
const busy = listQuery.busy;
const error = computed(
  () => listQuery.error.value || buildingQuery.error.value,
);
function load() {
  return listQuery.run(
    (signal) =>
      get<Model<"RoomPage">>(
        "/rooms",
        { ...filters, page: page.value, pageSize: 20 },
        signal,
      ),
    (result) => {
      items.value = result.items;
      total.value = result.total;
    },
  );
}
function loadBuildings() {
  return buildingQuery.run(
    (signal) =>
      get<Model<"BuildingPage">>("/buildings", { pageSize: 100 }, signal),
    (result) => {
      buildings.value = result.items;
    },
  );
}
function refresh() {
  return Promise.all([load(), loadBuildings()]);
}
function search() {
  page.value = 1;
  void load();
}
function turn(value: number) {
  page.value = value;
  void load();
}
function editRoom(room?: Room) {
  selectedRoom.value = room;
  modal.value = "room";
}
function editBuilding(building?: Building) {
  selectedBuilding.value = building;
  modal.value = "building";
}
function detail(room: Room) {
  selectedRoom.value = room;
  modal.value = "detail";
}
async function saved(buildingChanged = false) {
  modal.value = null;
  notify("档案已保存");
  await (buildingChanged ? refresh() : load());
}
onMounted(refresh);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>宿舍档案</h1>
      <p>维护楼栋与房间，让入住名额和服务范围清楚可查。</p>
    </div>
    <div class="page-actions">
      <button class="btn subtle" @click="editBuilding()">
        <Icon name="plus" />
        新建楼栋
      </button>
      <button class="btn primary" @click="editRoom()">
        <Icon name="plus" />
        新建房间
      </button>
    </div>
  </div>
  <div class="tabs">
    <button :class="{ active: tab === 'rooms' }" @click="tab = 'rooms'">
      房间档案
    </button>
    <button :class="{ active: tab === 'buildings' }" @click="tab = 'buildings'">
      楼栋档案
    </button>
  </div>
  <p v-if="error" class="error-banner" role="alert">
    {{ error }}
    <button @click="refresh">重新加载</button>
  </p>
  <section v-if="tab === 'rooms'" class="panel">
    <form class="filters" @submit.prevent="search">
      <label>
        关键词
        <input v-model="filters.q" placeholder="房间号 / 楼栋" />
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
          <option value="ENABLED">启用</option>
          <option value="DISABLED">停用</option>
        </select>
      </label>
      <button class="btn primary" :disabled="busy">查询</button>
      <button
        type="button"
        class="btn"
        @click="
          Object.assign(filters, { q: '', buildingId: '', status: '' });
          search();
        "
      >
        重置
      </button>
    </form>
    <p v-if="busy" class="panel-body loading">正在读取房间档案…</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>楼栋 / 房间</th>
            <th>楼层</th>
            <th>容量 / 当前入住</th>
            <th>状态</th>
            <th>备注</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in items" :key="r.id">
            <td>
              <strong>
                {{ r.buildingName }}
                <span class="num">{{ r.roomNo }}</span>
              </strong>
            </td>
            <td>{{ r.floorNo }} 层</td>
            <td>
              <span class="num">{{ r.occupiedCount }} / {{ r.capacity }}</span>
              人
            </td>
            <td>
              <span class="status" :class="r.enabled ? 'ACTIVE' : 'INACTIVE'">
                {{ r.enabled ? "启用" : "停用" }}
              </span>
            </td>
            <td>{{ r.remark || "—" }}</td>
            <td>
              <div class="cell-actions">
                <button class="text-link" @click="detail(r)">查看</button>
                <button class="text-link" @click="editRoom(r)">编辑</button>
              </div>
            </td>
          </tr>
          <tr v-if="!busy && !items.length">
            <td colspan="6" class="empty-state">
              没有匹配的房间，可调整筛选或新建档案。
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
  <section v-else class="panel">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>楼栋编码</th>
            <th>名称</th>
            <th>楼层数</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in buildings" :key="b.id">
            <td class="num">{{ b.code }}</td>
            <td>{{ b.name }}</td>
            <td>{{ b.floorCount }}</td>
            <td>{{ b.enabled ? "启用" : "停用" }}</td>
            <td>
              <button class="text-link" @click="editBuilding(b)">
                编辑楼栋
              </button>
            </td>
          </tr>
          <tr v-if="!buildings.length">
            <td colspan="5" class="empty-state">
              暂无负责楼栋，请先新建楼栋。
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <RoomEditor
    v-if="modal === 'room'"
    :key="selectedRoom?.id || 'new'"
    :room="selectedRoom"
    :buildings="buildings"
    @close="modal = null"
    @saved="saved()"
  />
  <BuildingEditor
    v-if="modal === 'building'"
    :key="selectedBuilding?.id || 'new'"
    :building="selectedBuilding"
    @close="modal = null"
    @saved="saved(true)"
  />
  <RoomDetail
    v-if="modal === 'detail' && selectedRoom"
    :key="selectedRoom.id"
    :room="selectedRoom"
    @close="modal = null"
  />
</template>
