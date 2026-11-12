<script setup lang="ts">
import { computed } from "vue";
import type { Model } from "../api/types";
const props = defineProps<{
  items: Model<"NamedCount">[];
  labels?: Record<string, string>;
  interactive?: boolean;
}>();
defineEmits<{ choose: [name: string] }>();
const max = computed(() => Math.max(1, ...props.items.map((x) => x.value)));
</script>
<template>
  <div class="stat-bars">
    <div v-for="item in items" :key="item.name">
      <div class="stat-bar-label">
        <button
          v-if="interactive"
          type="button"
          class="text-link"
          @click="$emit('choose', item.name)"
        >
          {{ labels?.[item.name] || item.name }}
        </button>
        <span v-else>{{ labels?.[item.name] || item.name }}</span>
        <span class="num">{{ item.value }}</span>
      </div>
      <div class="stat-bar" aria-hidden="true">
        <i :style="{ width: (100 * item.value) / max + '%' }" />
      </div>
    </div>
    <p v-if="!items.length" class="empty-state">所选范围暂无数据。</p>
  </div>
</template>
