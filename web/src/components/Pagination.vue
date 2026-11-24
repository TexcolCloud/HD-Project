<script setup lang="ts">
defineProps<{
  page: number;
  pageSize: number;
  total: number;
  busy?: boolean;
}>();
defineEmits<{ change: [page: number] }>();
</script>
<template>
  <div v-if="total > 0" class="pagination">
    <span>
      共 {{ total }} 条 · 第 {{ page }} /
      {{ Math.max(1, Math.ceil(total / pageSize)) }} 页
    </span>
    <div>
      <button :disabled="busy || page <= 1" @click="$emit('change', page - 1)">
        上一页
      </button>
      <select
        v-if="total > pageSize"
        aria-label="跳转页码"
        :value="page"
        :disabled="busy"
        @change="
          $emit('change', Number(($event.target as HTMLSelectElement).value))
        "
      >
        <option v-for="n in Math.ceil(total / pageSize)" :key="n" :value="n">
          第 {{ n }} 页
        </option>
      </select>
      <button
        :disabled="busy || page * pageSize >= total"
        @click="$emit('change', page + 1)"
      >
        下一页
      </button>
    </div>
  </div>
</template>
