<script setup lang="ts">
import { useConfirm, confirmHost } from "../composables/useConfirm";
import ConfirmDialog from "./ConfirmDialog.vue";
import {
  inject,
  provide,
  useId,
  ref,
  onMounted,
  onUnmounted,
  onBeforeUnmount,
} from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { openModal } from "./modal";
import Icon from "./Icon.vue";

const askConfirm = useConfirm();
const props = defineProps<{
  title: string;
  subtitle?: string;
  busy?: boolean;
  dirty?: boolean;
}>();
const emit = defineEmits<{ close: [] }>();
const el = ref<HTMLDialogElement>();
const parentHost = inject(confirmHost, false);
provide(confirmHost, true);
const titleId = useId();
let unregisterModal = () => {};
let previousFocus: HTMLElement | null;
async function close() {
  if (props.busy) return;
  if (
    props.dirty &&
    !(await askConfirm({
      title: "放弃未保存内容？",
      message: "关闭后，本次修改将丢失。",
      confirmText: "放弃修改",
      cancelText: "继续编辑",
      danger: true,
    }))
  )
    return;
  emit("close");
}
function unload(event: BeforeUnloadEvent) {
  if (props.dirty) {
    event.preventDefault();
    event.returnValue = "";
  }
}
onMounted(() => {
  previousFocus = globalThis.document?.activeElement as HTMLElement | null;
  unregisterModal = openModal(el.value);
  window.addEventListener("beforeunload", unload);
});
onBeforeUnmount(() => {
  unregisterModal();
  window.removeEventListener("beforeunload", unload);
});
onUnmounted(() => {
  if (globalThis.document?.activeElement === globalThis.document?.body)
    previousFocus?.focus?.();
});
onBeforeRouteLeave(() =>
  props.busy
    ? false
    : props.dirty
      ? askConfirm({
          title: "离开并放弃修改？",
          message: "离开此页后，本次修改将丢失。",
          confirmText: "离开并放弃",
          cancelText: "留在此页",
          danger: true,
        })
      : true,
);
</script>
<template>
  <dialog
    ref="el"
    class="drawer"
    :aria-labelledby="titleId"
    @cancel.prevent="close"
    @click="
      (e) => {
        if (e.target === el) close();
      }
    "
  >
    <header class="drawer-head">
      <div>
        <h2 :id="titleId">{{ title }}</h2>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
      <button
        class="icon-button"
        type="button"
        aria-label="关闭详情"
        :disabled="busy"
        @click="close"
      >
        <Icon name="close" />
      </button>
    </header>
    <div class="drawer-content"><slot /></div>
    <footer v-if="$slots.footer" class="drawer-footer">
      <slot name="footer" />
    </footer>
  </dialog>
  <ConfirmDialog v-if="!parentHost" />
</template>
