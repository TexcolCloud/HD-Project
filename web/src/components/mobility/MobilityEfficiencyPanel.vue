<script setup lang="ts">
import { ref, watch } from "vue";
import { get } from "../../api/client";
import type { Model } from "../../api/types";
import { useLatestQuery } from "../../composables/useLatestQuery";
import { dateTime } from "../../ui";
const props = defineProps<{ refreshKey: number }>();
const query = useLatestQuery();
const { busy, error } = query;
const value = ref<Model<"MobilityEfficiency">>();
const updatedAt = ref("");
async function load() {
  await query.run(
    (signal) =>
      get<Model<"MobilityEfficiency">>("/mobility/efficiency", {}, signal),
    (result) => {
      value.value = result;
      updatedAt.value = new Date().toISOString();
    },
  );
}
watch(
  () => props.refreshKey,
  () => {
    void load();
  },
  { immediate: true },
);
const minutes = (number: number | null | undefined) =>
  number === null || number === undefined
    ? "暂无可比较样本"
    : `${number.toFixed(1)} 分钟`;
</script>
<template>
  <section class="panel efficiency-panel">
    <header class="panel-head">
      <div>
        <h2>本人的通行校准</h2>
        <p>近30天已确认入口之间的通行记录，用实际耗时核对预计耗时。</p>
      </div>
      <button class="btn" :disabled="busy" @click="load">
        {{ busy ? "正在读取…" : "刷新校准记录" }}
      </button>
    </header>
    <p v-if="error" class="error-banner" role="alert">
      {{ error }}。当前结果尚未更新，请重试。
    </p>
    <p v-if="busy && !value" class="loading" role="status">
      正在读取本人通行记录…
    </p>
    <template v-if="value">
      <dl class="efficiency-values">
        <div>
          <dt>有效通行片段</dt>
          <dd class="num">
            {{ value.sampleCount }}
            <small>段</small>
          </dd>
        </div>
        <div>
          <dt>个人校准方向</dt>
          <dd class="num">
            {{ value.personalModelCount }}
            <small>个</small>
          </dd>
        </div>
        <div>
          <dt>平均绝对预测误差</dt>
          <dd :class="{ num: value.meanAbsoluteErrorMinutes !== null }">
            {{ minutes(value.meanAbsoluteErrorMinutes) }}
          </dd>
        </div>
      </dl>
      <p class="efficiency-comparison">
        可比较 {{ value.measuredCount }} 段 · 预计合计
        {{ minutes(value.predictedMinutes) }} · 实际合计
        {{ minutes(value.actualMinutes) }}
      </p>
      <p class="form-note">{{ value.explanation }}</p>
      <small class="efficiency-updated">读取于 {{ dateTime(updatedAt) }}</small>
    </template>
  </section>
</template>
<style scoped>
.efficiency-panel {
  margin-top: 26px;
  padding: 26px;
}
.efficiency-panel .panel-head {
  padding-bottom: 22px;
}
.efficiency-values {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
  margin: 0 0 20px;
}
.efficiency-values dt {
  color: var(--muted);
  font-size: 12px;
  margin-bottom: 10px;
}
.efficiency-values dd {
  margin: 0;
  color: var(--text);
  font-size: 22px;
}
.efficiency-values dd small {
  font-size: 12px;
  color: var(--muted);
}
.efficiency-comparison {
  border-top: 1px solid var(--border);
  padding-top: 18px;
  font-size: 13px;
}
.efficiency-updated {
  color: var(--muted);
  font-size: 11px;
}
@media (max-width: 800px) {
  .efficiency-values {
    grid-template-columns: 1fr;
    gap: 18px;
  }
  .efficiency-panel {
    padding: 22px;
  }
}
</style>
