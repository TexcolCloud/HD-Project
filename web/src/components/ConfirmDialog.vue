<script setup lang="ts">
import { ref, useId, watch, onBeforeUnmount } from "vue";
import { confirmation, answerConfirm } from "../composables/useConfirm";
import { openModal } from "./modal";
const titleId = useId(),
  messageId = useId();
const dialog = ref<HTMLDialogElement>();
function finish(answer: boolean) {
  dialog.value?.close?.();
  answerConfirm(answer);
}
onBeforeUnmount(() => {
  if (confirmation.value) answerConfirm(false);
});
watch(
  dialog,
  (el, _old, cleanup) => {
    cleanup(openModal(el));
  },
  { flush: "post" },
);
</script>
<template>
  <dialog
    v-if="confirmation"
    ref="dialog"
    class="confirmation-dialog"
    :aria-labelledby="titleId"
    :aria-describedby="messageId"
    @cancel.prevent="finish(false)"
    @click.self="finish(false)"
  >
    <div class="confirmation-content">
      <h2 :id="titleId">{{ confirmation.title }}</h2>
      <p :id="messageId">{{ confirmation.message }}</p>
      <div class="button-row">
        <button type="button" class="btn" autofocus @click="finish(false)">
          {{ confirmation.cancelText || "返回" }}
        </button>
        <button
          type="button"
          class="btn"
          :class="confirmation.danger ? 'danger' : 'primary'"
          @click="finish(true)"
        >
          {{ confirmation.confirmText }}
        </button>
      </div>
    </div>
  </dialog>
</template>
