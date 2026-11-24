<script setup lang="ts">
defineProps<{ domain: "dispatch" | "hygiene" }>();
const tabs = {
  dispatch: [
    ["/dispatch", "任务路线"],
    ["/mobility", "现场位置与校准"],
  ],
  hygiene: [
    ["/hygiene", "卫生巡检"],
    ["/remediations", "卫生整改"],
  ],
};
</script>
<template>
  <nav
    class="tabs"
    :aria-label="domain === 'dispatch' ? '任务调度' : '卫生督办'"
  >
    <RouterLink v-for="[path, label] in tabs[domain]" :key="path" :to="path">
      {{ label }}
    </RouterLink>
  </nav>
</template>
