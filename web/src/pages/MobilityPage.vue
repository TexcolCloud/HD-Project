<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  getMobility,
  getTravelModels,
  rollbackTravelModel,
  saveAnchor,
  stopMobility,
  type CampusAnchor,
  type MobilityView,
} from "../api/mobility";
import { getDispatchBuildings } from "../api/dispatch";
import type { Building, Model } from "../api/types";
import { contextSummary } from "../composables/mobilityContext";
import { dateTime, errorText, notify } from "../ui";
import Drawer from "../components/Drawer.vue";

const view = ref<MobilityView | null>(null);
const buildings = ref<Building[]>([]);
const travelModels = ref<Model<"MobilityTravelModel">[]>([]);
const busy = ref(false),
  error = ref(""),
  editing = ref(false),
  formError = ref("");
const anchor = ref<CampusAnchor>();
const name = ref(""),
  buildingId = ref(""),
  floor = ref("");
const latitude = ref(""),
  longitude = ref(""),
  radius = ref(""),
  enabled = ref(true);
const now = ref(Date.now());
const summary = computed(() =>
  contextSummary(view.value?.context ?? null, now.value),
);
const stateLabels = {
  ACTIVE: "工作进行中",
  PAUSED: "已暂停",
  ENDED: "已结束",
  EXPIRED: "已过期",
};
const sourceLabels = {
  MANUAL: "手工确认",
  QR: "扫码确认",
  GPS: "系统定位",
  NONE: "暂无来源",
};
const live = computed(
  () =>
    !!view.value?.session &&
    ["ACTIVE", "PAUSED"].includes(view.value.session.state) &&
    Date.parse(view.value.session.leaseExpiresAt) > now.value,
);
let clock: ReturnType<typeof setInterval> | undefined;
const buildingName = (id: string) =>
  buildings.value.find((item) => item.id === id)?.name ?? "负责楼栋";

async function refresh() {
  busy.value = true;
  error.value = "";
  try {
    const [current, models] = await Promise.all([
      getMobility(),
      getTravelModels(),
    ]);
    view.value = current;
    travelModels.value = models.items;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function rollback(model: Model<"MobilityTravelModel">) {
  busy.value = true;
  error.value = "";
  try {
    const updated = await rollbackTravelModel(model);
    travelModels.value = travelModels.value.map((item) =>
      item.fromBuildingId === updated.fromBuildingId &&
      item.toBuildingId === updated.toBuildingId
        ? updated
        : item,
    );
    notify("已停用此方向的学习估算，路线恢复人工通行基线");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function stop(action: "pause" | "end") {
  const session = view.value?.session;
  if (!session) return;
  busy.value = true;
  error.value = "";
  try {
    view.value = await stopMobility(session, action);
    notify(action === "pause" ? "现场位置采集已暂停" : "本次现场工作已结束");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function edit(item?: CampusAnchor) {
  anchor.value = item;
  name.value = item?.name ?? "";
  buildingId.value = item?.buildingId ?? buildings.value[0]?.id ?? "";
  floor.value = item?.floorNo?.toString() ?? "";
  latitude.value = item?.latitude?.toString() ?? "";
  longitude.value = item?.longitude?.toString() ?? "";
  radius.value = item?.radiusMeters?.toString() ?? "";
  enabled.value = item?.enabled ?? true;
  formError.value = "";
  editing.value = true;
}
async function save() {
  if (
    !!latitude.value !== !!longitude.value ||
    (!!radius.value && !latitude.value)
  ) {
    formError.value = "经纬度需要同时填写；设置识别半径前请先填写实测坐标。";
    return;
  }
  const body: Model<"CampusAnchorRequest"> = {
    buildingId: buildingId.value,
    name: name.value.trim(),
    floorNo: floor.value === "" ? null : Number(floor.value),
    latitude: latitude.value === "" ? null : Number(latitude.value),
    longitude: longitude.value === "" ? null : Number(longitude.value),
    radiusMeters: radius.value === "" ? null : Number(radius.value),
    enabled: enabled.value,
    expectedVersion: anchor.value?.version,
  };
  busy.value = true;
  formError.value = "";
  try {
    await saveAnchor(body, anchor.value?.id);
    editing.value = false;
    notify("现场地点已保存");
    await refresh();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  clock = setInterval(() => {
    now.value = Date.now();
  }, 1000);
  await refresh();
  try {
    buildings.value = await getDispatchBuildings();
  } catch (e) {
    error.value = errorText(e);
  }
});
onBeforeUnmount(() => clearInterval(clock));
</script>

<template>
  <div class="page-heading">
    <div>
      <p class="section-note">宿管现场服务</p>
      <h1>现场位置</h1>
      <p>在鸿蒙端开启本次工作，确认位置后为剩余路线提供依据。</p>
    </div>
    <div class="page-actions">
      <button class="btn" :disabled="busy" @click="refresh">刷新状态</button
      ><button
        class="btn primary"
        :disabled="busy || !buildings.length"
        @click="edit()"
      >
        登记现场地点
      </button>
    </div>
  </div>
  <p v-if="error" class="error-banner" role="alert">
    {{ error }}<br />当前数据尚未更新，请重新读取后操作。
  </p>
  <p v-if="!view && busy" class="loading" role="status">正在读取现场状态…</p>
  <template v-if="view">
    <section class="mobility-overview panel">
      <div class="mobility-place">
        <p class="section-note">本次出行位置</p>
        <h2>{{ summary.place }}</h2>
        <p :class="summary.fresh ? 'mobility-valid' : 'muted'">
          {{ summary.fresh ? "在有效期内" : "当前位置待确认" }} ·
          {{ view.context ? sourceLabels[view.context.source] : "暂无来源" }}
        </p>
        <small v-if="view.context?.observedAt"
          >观测于 {{ dateTime(view.context.observedAt) }}</small
        >
      </div>
      <dl class="mobility-facts">
        <div>
          <dt>移动状态</dt>
          <dd>{{ summary.speed }}</dd>
        </div>
        <div>
          <dt>定位精度</dt>
          <dd>
            {{
              summary.fresh &&
              view.context?.accuracyMeters !== null &&
              view.context?.accuracyMeters !== undefined
                ? "约 " + view.context.accuracyMeters.toFixed(0) + " 米"
                : "暂无可靠精度"
            }}
          </dd>
        </div>
        <div>
          <dt>本次工作</dt>
          <dd>
            {{
              view.session
                ? !live && ["ACTIVE", "PAUSED"].includes(view.session.state)
                  ? "已过期，等待重开"
                  : stateLabels[view.session.state]
                : "尚未开启"
            }}
          </dd>
        </div>
      </dl>
      <div class="mobility-control">
        <p>
          {{
            view.context?.explanation ||
            "定位不可用时，可在鸿蒙端手工确认楼栋与楼层。"
          }}
        </p>
        <div class="button-row">
          <button
            v-if="live && view.session?.state === 'ACTIVE'"
            class="btn"
            :disabled="busy || !!error"
            @click="stop('pause')"
          >
            暂停采集</button
          ><button
            v-if="live"
            class="btn"
            :disabled="busy || !!error"
            @click="stop('end')"
          >
            结束本次工作</button
          ><RouterLink class="text-link" to="/dispatch"
            >查看任务路线 →</RouterLink
          >
        </div>
        <small
          >移动端主动开启后才采集。同账号只保留一个有效来源；暂停或结束不改变工单状态。</small
        >
      </div>
    </section>
    <section class="panel mobility-anchors">
      <header class="panel-head">
        <div>
          <h2>现场地点</h2>
          <p>登记楼栋入口或楼层，供移动端选择和扫码确认。仅显示负责楼栋。</p>
        </div>
        <span class="muted">{{ view.anchors.length }} 处</span>
      </header>
      <div v-if="view.anchors.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>地点</th>
              <th>楼栋与楼层</th>
              <th>定位依据</th>
              <th>状态</th>
              <th><span class="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in view.anchors" :key="item.id">
              <td>
                <strong>{{ item.name }}</strong>
                <details class="mobility-code">
                  <summary>查看扫码内容</summary>
                  <code>{{ "dormlink-anchor:" + item.scanCode }}</code
                  ><small>静态码需在移动端预览并确认，仅用于地点校正。</small>
                </details>
              </td>
              <td>
                {{ item.buildingName }} ·
                {{
                  item.floorNo === null ? "入口／楼层未知" : item.floorNo + "层"
                }}
              </td>
              <td>
                {{
                  item.latitude === null ? "人工／扫码确认" : "已登记实测坐标"
                }}
              </td>
              <td>{{ item.enabled ? "可用" : "已停用" }}</td>
              <td>
                <button
                  class="text-link"
                  :disabled="busy || !!error"
                  @click="edit(item)"
                >
                  编辑
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">
        <p>还没有登记现场地点。</p>
        <p>先登记楼栋或楼层；没有实测坐标，也可以用于人工和扫码确认。</p>
        <button
          class="btn"
          :disabled="busy || !buildings.length"
          @click="edit()"
        >
          登记第一个地点
        </button>
      </div>
    </section>
    <section class="panel mobility-anchors mobility-models">
      <header class="panel-head">
        <div>
          <h2>通行耗时校准</h2>
          <p>
            仅采用已确认入口之间的出行片段；至少 5 段、3
            个独立工作会话才形成估算。
          </p>
        </div>
      </header>
      <div v-if="travelModels.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>方向</th>
              <th>学习估算</th>
              <th>采用依据</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="model in travelModels"
              :key="model.fromBuildingId + ':' + model.toBuildingId"
            >
              <td>
                {{ buildingName(model.fromBuildingId) }} →
                {{ buildingName(model.toBuildingId)
                }}<small class="mobility-model-time">{{
                  dateTime(model.updatedAt)
                }}</small>
              </td>
              <td>{{ model.minutes }} 分钟</td>
              <td>
                {{ model.sampleCount }} 段 · {{ model.sessionCount }} 次工作
              </td>
              <td>{{ model.enabled ? "已启用" : "已恢复人工基线" }}</td>
              <td>
                <button
                  v-if="model.enabled"
                  class="text-link"
                  :disabled="busy || !!error"
                  @click="rollback(model)"
                >
                  恢复人工基线</button
                ><span v-else class="muted">已停用自动更新</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">
        尚无达到采用门槛的出行记录，路线继续使用人工配置的通行时间。
      </p>
    </section>
    <p class="form-note mobility-note">
      系统定位不能确定房间或楼层；过期、精度不足或无权限时使用已确认地点与原通行时间。学生房间继续由入住档案确定。
    </p>
  </template>
  <Drawer
    v-if="editing"
    :title="anchor ? '编辑现场地点' : '登记现场地点'"
    :busy="busy"
    dirty
    @close="editing = false"
  >
    <form class="form" @submit.prevent="save">
      <label
        >地点名称 *<input
          v-model.trim="name"
          required
          maxlength="80"
          placeholder="例如：A栋南入口"
      /></label>
      <div class="form-row">
        <label
          >楼栋 *<select v-model="buildingId" required>
            <option
              v-for="building in buildings"
              :key="building.id"
              :value="building.id"
            >
              {{ building.name }}
            </option>
          </select></label
        ><label
          >楼层<input
            v-model="floor"
            type="number"
            min="1"
            :max="buildings.find((b) => b.id === buildingId)?.floorCount"
            step="1"
            placeholder="入口可留空"
        /></label>
      </div>
      <details>
        <summary>实测坐标（可选）</summary>
        <p class="form-note">
          仅填写已核验的地点坐标，留空时继续使用人工和扫码确认。
        </p>
        <div class="form-row">
          <label
            >纬度<input
              v-model="latitude"
              type="number"
              min="-90"
              max="90"
              step="any" /></label
          ><label
            >经度<input
              v-model="longitude"
              type="number"
              min="-180"
              max="180"
              step="any"
          /></label>
        </div>
        <label
          >识别半径（米）<input
            v-model="radius"
            type="number"
            min="5"
            max="500"
            step="any"
        /></label>
      </details>
      <label
        >可用状态<select v-model="enabled">
          <option :value="true">可用</option>
          <option :value="false">停用</option>
        </select></label
      >
      <p v-if="formError" class="error-banner" role="alert">{{ formError }}</p>
      <button class="btn primary" :disabled="busy">
        {{ busy ? "正在保存…" : "保存地点" }}
      </button>
    </form>
  </Drawer>
</template>

<style scoped>
.mobility-overview {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: 30px;
  padding: 28px;
  margin-bottom: 26px;
}
.mobility-place h2 {
  font:
    600 28px/1.6 "Dorm Serif",
    serif;
  margin: 8px 0 12px;
}
.mobility-place p {
  margin-bottom: 8px;
}
.mobility-place small,
.mobility-control small {
  color: var(--muted);
  font-size: 12px;
}
.mobility-valid {
  color: var(--success);
}
.mobility-facts {
  display: grid;
  gap: 18px;
  margin: 0;
}
.mobility-facts > div {
  display: flex;
  justify-content: space-between;
  border-bottom: 1px solid var(--border);
  padding-bottom: 12px;
  gap: 20px;
}
.mobility-facts dt {
  color: var(--muted);
  font-size: 13px;
}
.mobility-facts dd {
  margin: 0;
  font-size: 14px;
}
.mobility-control {
  grid-column: 1/-1;
  border-top: 1px solid var(--border);
  padding-top: 20px;
  display: grid;
  gap: 14px;
}
.mobility-control p {
  margin: 0;
  font-size: 14px;
}
.mobility-code {
  font-size: 12px;
  margin-top: 7px;
  color: var(--muted);
}
.mobility-code code {
  display: block;
  overflow-wrap: anywhere;
  max-width: 340px;
  margin: 12px 0;
  color: var(--text);
}
.mobility-code small {
  display: block;
  font-size: 11px;
  max-width: 340px;
}
.mobility-note {
  margin-top: 24px;
}
.mobility-anchors .empty-state p {
  margin: 10px 0 18px;
}
.mobility-anchors .panel-head {
  padding-bottom: 22px;
}
.mobility-models {
  margin-top: 26px;
}
.mobility-model-time {
  display: block;
  margin-top: 6px;
  color: var(--muted);
  font-size: 11px;
}
@media (max-width: 800px) {
  .mobility-overview {
    grid-template-columns: 1fr;
    padding: 22px;
    gap: 24px;
  }
  .mobility-place h2 {
    font-size: 24px;
  }
}
</style>
