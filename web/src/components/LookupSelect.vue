<script setup lang="ts">
import { computed, nextTick, onScopeDispose, ref, useId, watch } from "vue";
import { get } from "../api/client";
import type { Room, Student, Model } from "../api/types";
import { useLatestQuery } from "../composables/useLatestQuery";
const props = defineProps<{
  modelValue: string;
  kind: "room" | "student";
  label: string;
  available?: boolean;
  requireResidence?: boolean;
  initialLabel?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const query = useLatestQuery();
const selectionQuery = useLatestQuery();
const { busy: loading, error } = query;
const listId = useId();
const input = ref<HTMLInputElement>();
const open = ref(false),
  active = ref(-1),
  search = ref(""),
  chosen = ref<Room | Student>();
const items = ref<(Room | Student)[]>([]);
const occupancy = computed(() =>
  props.kind === "room"
    ? props.available
      ? "AVAILABLE"
      : undefined
    : props.requireResidence
      ? "ASSIGNED"
      : props.available
        ? "UNASSIGNED"
        : undefined,
);
function labelFor(item: Room | Student) {
  return "studentNo" in item
    ? `${item.studentNo} · ${item.name}${item.roomNo ? " · " + item.buildingName + " " + item.roomNo : " · 未入住"}`
    : `${item.buildingName} ${item.roomNo} · ${item.occupiedCount}/${item.capacity}人${!item.enabled ? " · 已停用" : ""}`;
}
const selectedLabel = computed(() => {
  if (!props.modelValue) return "";
  const item =
    chosen.value?.id === props.modelValue
      ? chosen.value
      : items.value.find((item) => item.id === props.modelValue);
  return item ? labelFor(item) : props.initialLabel || "当前已选记录";
});
async function load() {
  const path = props.kind === "room" ? "/rooms" : "/students";
  await query.run(
    (signal) =>
      get<Model<"RoomPage"> | Model<"StudentPage">>(
        path,
        { q: search.value, pageSize: 30, occupancy: occupancy.value },
        signal,
      ),
    (result) => {
      items.value = result.items.slice(0, 30);
    },
  );
}
let timer: ReturnType<typeof setTimeout>;
function show() {
  if (open.value || props.modelValue) return;
  open.value = true;
  void load();
}
function close() {
  open.value = false;
  active.value = -1;
  clearTimeout(timer);
  query.cancel();
}
function filter(event: Event) {
  search.value = (event.target as HTMLInputElement).value;
  open.value = true;
  items.value = [];
  active.value = -1;
  query.cancel();
  clearTimeout(timer);
  timer = setTimeout(load, 250);
}
function select(item: Room | Student) {
  chosen.value = item;
  emit("update:modelValue", item.id);
  search.value = "";
  close();
}
async function clear() {
  chosen.value = undefined;
  emit("update:modelValue", "");
  search.value = "";
  await nextTick();
  input.value?.focus?.();
  show();
}
function move(step: number) {
  show();
  const count = items.value.length;
  if (!count || !open.value) return;
  active.value =
    active.value < 0
      ? step > 0
        ? 0
        : count - 1
      : (active.value + step + count) % count;
  void nextTick(() =>
    globalThis.document
      ?.getElementById?.(listId + "-" + active.value)
      ?.scrollIntoView?.({ block: "nearest" }),
  );
}
function keydown(event: KeyboardEvent) {
  if (
    props.modelValue &&
    (event.key === "Backspace" || event.key === "Delete")
  ) {
    event.preventDefault();
    void clear();
  } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    event.preventDefault();
    move(event.key === "ArrowDown" ? 1 : -1);
  } else if (open.value && event.key === "Escape") {
    event.preventDefault();
    event.stopPropagation();
    close();
  } else if (open.value && event.key === "Enter") {
    event.preventDefault();
    const item = items.value[active.value];
    if (item) select(item);
  }
}
watch(
  () => [props.modelValue, input.value],
  () => {
    input.value?.setCustomValidity?.(
      props.modelValue ? "" : "请选择" + props.label,
    );
  },
  { flush: "post" },
);
watch(
  () => [props.kind, occupancy.value],
  () => {
    items.value = [];
    if (open.value) void load();
  },
);
watch(
  () => [props.modelValue, props.kind, props.initialLabel],
  async () => {
    selectionQuery.cancel();
    selectionQuery.error.value = "";
    const id = props.modelValue;
    if (!id || chosen.value?.id === id || props.initialLabel) return;
    await selectionQuery.run(
      (signal) =>
        get<Room | Student>(
          (props.kind === "room" ? "/rooms/" : "/students/") +
            encodeURIComponent(id),
          {},
          signal,
        ),
      (item) => {
        chosen.value = item;
      },
    );
  },
  { immediate: true },
);
onScopeDispose(() => clearTimeout(timer));
</script>
<template>
  <div class="lookup-field">
    <label :for="listId + '-input'">{{ label }}</label>
    <div class="lookup-control">
      <input
        :id="listId + '-input'"
        ref="input"
        role="combobox"
        autocomplete="off"
        aria-autocomplete="list"
        required
        :readonly="!!modelValue"
        :aria-expanded="open"
        :aria-controls="listId"
        :aria-activedescendant="
          open && active >= 0 ? listId + '-' + active : undefined
        "
        :value="modelValue ? selectedLabel : search"
        :placeholder="
          kind === 'room' ? '输入房号或楼栋搜索' : '输入姓名、学号或房号搜索'
        "
        @focus="show"
        @click="show"
        @input.stop="filter"
        @blur="close"
        @keydown="keydown"
      />
      <button v-if="modelValue" type="button" class="text-link" @click="clear">
        更换
      </button>
      <div v-if="open" class="lookup-popup">
        <ul :id="listId" role="listbox" :aria-label="label">
          <li
            v-for="(item, i) in items"
            :id="listId + '-' + i"
            :key="item.id"
            role="option"
            :aria-selected="i === active"
            @mousedown.prevent
            @click="select(item)"
          >
            {{ labelFor(item) }}
          </li>
        </ul>
        <small v-if="error" role="alert" class="field-error">{{ error }}</small>
        <small v-else-if="loading || !items.length">
          {{ loading ? "正在查找…" : "没有匹配的记录" }}
        </small>
      </div>
    </div>
    <small v-if="selectionQuery.error.value" class="field-error">
      {{ selectionQuery.error.value }}
    </small>
  </div>
</template>
<style scoped>
.lookup-field {
  display: grid;
  gap: 6px;
}
.lookup-control {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
}
.lookup-control input {
  min-width: 0;
  flex: 1;
}
.lookup-popup {
  position: absolute;
  inset: 100% 0 auto;
  z-index: 2;
  max-height: 15rem;
  overflow: auto;
  background: var(--surface);
  color: var(--text);
  border: 1px solid var(--border-strong);
  border-radius: 4px;
}
.lookup-popup ul {
  margin: 0;
  padding: 0;
  list-style: none;
}
.lookup-popup li,
.lookup-popup small {
  display: block;
  padding: 8px 12px;
}
.lookup-popup li {
  cursor: pointer;
}
.lookup-popup li:hover,
.lookup-popup li[aria-selected="true"] {
  background: var(--soft);
}
</style>
