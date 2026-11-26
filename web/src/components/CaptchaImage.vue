<script setup lang="ts">
defineProps<{ src: string; disabled: boolean }>();
defineEmits<{ refresh: [] }>();
</script>
<template>
  <button
    type="button"
    class="captcha-image text-link"
    :disabled="disabled"
    @click="$emit('refresh')"
  >
    <img :src="src" alt="图形验证码，点击换一张" width="160" height="56" />
    <span>换一张</span>
  </button>
</template>
<style scoped>
.captcha-image {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
