<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { get } from "../api/client";
import type { Model, Building } from "../api/types";
import { errorText, localDate, statusNames, categoryNames } from "../ui";
import DataBars from "../components/DataBars.vue";
import { useLatestQuery } from "../composables/useLatestQuery";
const query = useLatestQuery();
const { busy, error } = query;
const router = useRouter(),
  buildings = ref<Building[]>([]),
  overview = ref<Model<"Overview">>(),
  repairs = ref<Model<"RepairStatistics">>(),
  hygiene = ref<Model<"HygieneStatistics">>(),
  visitors = ref<Model<"VisitorStatistics">>();
const filters = reactive({
  buildingId: "",
  startDate: localDate(new Date(Date.now() - 29 * 86400000)),
  endDate: localDate(),
});
const applied = ref({ ...filters });
const dates = computed(() =>
  [
    ...new Set([
      ...(repairs.value?.trend.map((x) => x.date) || []),
      ...(hygiene.value?.trend.map((x) => x.date) || []),
      ...(visitors.value?.trend.map((x) => x.date) || []),
    ]),
  ]
    .sort()
    .reverse(),
);
async function load() {
  const requested = { ...filters };
  await query.run(
    (signal) =>
      Promise.all([
        get<Model<"Overview">>(
          "/statistics/overview",
          {
            buildingId: requested.buildingId,
          },
          signal,
        ),
        get<Model<"RepairStatistics">>(
          "/statistics/repairs",
          requested,
          signal,
        ),
        get<Model<"HygieneStatistics">>(
          "/statistics/hygiene",
          requested,
          signal,
        ),
        get<Model<"VisitorStatistics">>(
          "/statistics/visitors",
          requested,
          signal,
        ),
      ]),
    ([o, r, h, v]) => {
      overview.value = o;
      repairs.value = r;
      hygiene.value = h;
      visitors.value = v;
      applied.value = requested;
    },
  );
}
function drill(status: string) {
  void router.push({ path: "/repairs", query: { ...applied.value, status } });
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
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>数据统计</h1>
      <p>在相同楼栋和时间范围内，查看入住、报修、卫生与来访。</p>
    </div>
  </div>
  <section class="panel" style="margin-bottom: 24px">
    <form class="filters" style="padding-bottom: 18px" @submit.prevent="load">
      <label>
        统计楼栋
        <select v-model="filters.buildingId">
          <option value="">全部负责楼栋</option>
          <option v-for="b in buildings" :key="b.id" :value="b.id">
            {{ b.name }}
          </option>
        </select>
      </label>
      <label>
        开始日期
        <input v-model="filters.startDate" type="date" required />
      </label>
      <label>
        结束日期
        <input
          v-model="filters.endDate"
          type="date"
          :min="filters.startDate"
          required
        />
      </label>
      <button class="btn primary" :disabled="busy">
        {{ busy ? "正在统计…" : "更新统计" }}
      </button>
    </form>
  </section>
  <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  <template v-if="overview && repairs && hygiene && visitors">
    <div class="summary-strip">
      <div>
        <span>当前入住率</span>
        <strong class="num">
          {{
            overview.capacity ? overview.occupancyRate.toFixed(1) + "%" : "—"
          }}
        </strong>
        <p>
          {{ overview.occupiedCount }} 人 / {{ overview.capacity }} 个可用名额
        </p>
      </div>
      <div>
        <span>区间工单完结率</span>
        <strong class="num">
          {{
            repairs.total
              ? ((100 * repairs.completed) / repairs.total).toFixed(1) + "%"
              : "—"
          }}
        </strong>
        <p>{{ repairs.completed }} 单完成 / {{ repairs.total }} 单提交</p>
      </div>
      <div>
        <span>有效巡检均分</span>
        <strong class="num">{{ hygiene.average?.toFixed(1) ?? "—" }}</strong>
        <p>按所选区间内有效巡检记录平均</p>
      </div>
      <div>
        <span>区间登记访客</span>
        <strong class="num">
          {{ visitors.total }}
          <small>人次</small>
        </strong>
        <p>当前在访 {{ visitors.visitingCount }} 人</p>
      </div>
    </div>
    <p class="form-note">
      统计区间 {{ applied.startDate }} 至
      {{
        applied.endDate
      }}（北京时间）。当前入住与在访为实时存量；其余按区间发生记录汇总。
    </p>
    <div class="stat-grid">
      <section class="panel">
        <header class="panel-head">
          <div>
            <h2>工单状态分布</h2>
            <p>所选区间提交的工单 · 点击状态查询明细</p>
          </div>
        </header>
        <DataBars
          :items="repairs.byStatus"
          :labels="statusNames"
          interactive
          @choose="drill"
        />
      </section>
      <section class="panel">
        <header class="panel-head">
          <div>
            <h2>报修类型分布</h2>
            <p>所选区间提交的工单 · 单位：单</p>
          </div>
        </header>
        <DataBars :items="repairs.byCategory" :labels="categoryNames" />
      </section>
      <section class="panel">
        <header class="panel-head">
          <div>
            <h2>卫生等级分布</h2>
            <p>有效巡检记录 · 单位：条</p>
          </div>
        </header>
        <DataBars :items="hygiene.byGrade" :labels="statusNames" />
      </section>
      <section class="panel">
        <header class="panel-head"><h2>办理与环境概况</h2></header>
        <dl class="details panel-body">
          <div>
            <dt>已完成工单平均耗时</dt>
            <dd class="num">
              {{
                repairs.averageHours === null
                  ? "暂无已完成记录"
                  : repairs.averageHours.toFixed(1) + " 小时"
              }}
            </dd>
          </div>
          <div>
            <dt>最新成绩待整改房间</dt>
            <dd>{{ hygiene.rectifyRoomCount }} 间</dd>
          </div>
          <div>
            <dt>当前可用房间</dt>
            <dd>{{ overview.roomCount }} 间</dd>
          </div>
          <div>
            <dt>剩余入住名额</dt>
            <dd>{{ overview.availableCount }} 人</dd>
          </div>
        </dl>
        <p class="form-note panel-body">
          耗时仅统计已完成单的提交至学生确认时间；待整改按各房间最新有效检查确定。
        </p>
      </section>
      <section class="panel" style="grid-column: 1/-1">
        <header class="panel-head">
          <div>
            <h2>每日记录趋势</h2>
            <p>报修按提交日期，来访按入访日期，卫生为当日有效巡检均分</p>
          </div>
        </header>
        <div class="stat-trend table-wrap">
          <table>
            <thead>
              <tr>
                <th>日期</th>
                <th>新增报修 / 单</th>
                <th>巡检平均分 / 分</th>
                <th>来访 / 人次</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="date in dates" :key="date">
                <td class="num">{{ date }}</td>
                <td class="num">
                  {{ repairs.trend.find((x) => x.date === date)?.count || 0 }}
                </td>
                <td class="num">
                  {{
                    hygiene.trend
                      .find((x) => x.date === date)
                      ?.average.toFixed(1) ?? "—"
                  }}
                </td>
                <td class="num">
                  {{ visitors.trend.find((x) => x.date === date)?.count || 0 }}
                </td>
              </tr>
              <tr v-if="!dates.length">
                <td colspan="4" class="empty-state">所选时间范围暂无记录。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </template>
</template>
