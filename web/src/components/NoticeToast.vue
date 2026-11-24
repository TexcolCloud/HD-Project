<script setup lang="ts">
import { dismissNotice, pauseNotice, resumeNotice, type Notice } from "../ui";
defineProps<{ notice: Notice }>();
</script>
<template>
  <div
    class="toast"
    :class="notice.kind"
    :role="notice.kind === 'error' ? 'alert' : 'status'"
    @mouseenter="pauseNotice(notice.id, 'pointer')"
    @mouseleave="resumeNotice(notice.id, 'pointer')"
    @focusin="pauseNotice(notice.id, 'focus')"
    @focusout="resumeNotice(notice.id, 'focus')"
  >
    <span>{{ notice.message }}</span>
    <button
      type="button"
      class="toast-close"
      aria-label="关闭提示"
      @click="dismissNotice(notice.id)"
    >
      ×
    </button>
  </div>
</template>
