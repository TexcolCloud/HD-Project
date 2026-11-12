<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import Icon from "./Icon.vue";
const props = defineProps<{
  title: string;
  subtitle?: string;
  busy?: boolean;
  dirty?: boolean;
}>();
const emit = defineEmits<{ close: [] }>();
const el = ref<HTMLDialogElement>();
function close() {
  if (props.busy) return;
  if (props.dirty && !window.confirm("当前内容尚未保存，确定放弃本次修改？"))
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
  el.value?.showModal();
  window.addEventListener("beforeunload", unload);
});
onBeforeUnmount(() => window.removeEventListener("beforeunload", unload));
onBeforeRouteLeave(() =>
  props.busy
    ? false
    : props.dirty
      ? window.confirm("当前内容尚未保存，确定离开并放弃本次修改？")
      : true,
);
</script>
<template>
  <dialog
    ref="el"
    class="drawer"
    @cancel.prevent="close"
    @click="
      (e) => {
        if (e.target === el) close();
      }
    "
  >
    <header class="drawer-head">
      <div>
        <h2>{{ title }}</h2>
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
</template>
