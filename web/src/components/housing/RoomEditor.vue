<script setup lang="ts">
import { reactive, ref, nextTick, watch } from "vue";
import { patch, post } from "../../api/client";
import type { Building, Model, Room } from "../../api/types";
import { errorText } from "../../ui";
import Drawer from "../Drawer.vue";
const props = defineProps<{ room?: Room; buildings: Building[] }>();
const emit = defineEmits<{ close: []; saved: [keepOpen: boolean] }>();
const saving = ref(false),
  dirty = ref(false),
  error = ref("");
const selectedRoom = props.room;
const form = reactive<Model<"RoomRequest">>({
  buildingId: selectedRoom?.buildingId ?? props.buildings[0]?.id ?? "",
  roomNo: selectedRoom?.roomNo ?? "",
  floorNo: selectedRoom?.floorNo ?? 1,
  capacity: selectedRoom?.capacity ?? 4,
  enabled: selectedRoom?.enabled ?? true,
  remark: selectedRoom?.remark ?? "",
  expectedVersion: selectedRoom?.version,
});
const roomInput = ref<HTMLInputElement>();
const floorEdited = ref(false);
function inferFloor() {
  if (selectedRoom || floorEdited.value) return;
  const digits = /^(\d{3,4})(?!\d)/.exec(form.roomNo)?.[1];
  const floor = digits ? Number(digits.slice(0, -2)) : 0;
  const building = props.buildings.find((b) => b.id === form.buildingId);
  if (building && floor >= 1 && floor <= building.floorCount)
    form.floorNo = floor;
}
watch(() => form.buildingId, inferFloor);
async function save(event: SubmitEvent) {
  if (saving.value) return;
  const keepOpen =
    !selectedRoom && event?.submitter?.getAttribute("value") === "continue";
  saving.value = true;
  error.value = "";
  try {
    if (selectedRoom) await patch(`/rooms/${selectedRoom.id}`, form);
    else await post("/rooms", form);
    dirty.value = false;
    if (keepOpen) {
      form.roomNo = /\d+$/.test(form.roomNo)
        ? form.roomNo.replace(/\d+$/, (digits) =>
            (BigInt(digits) + BigInt(1))
              .toString()
              .padStart(digits.length, "0"),
          )
        : "";
      form.remark = "";
    }
    emit("saved", keepOpen);
  } catch (cause) {
    error.value = errorText(cause);
  } finally {
    saving.value = false;
  }
  if (keepOpen && !error.value) {
    await nextTick();
    roomInput.value?.focus?.();
    roomInput.value?.select?.();
  }
}
</script>
<template>
  <Drawer
    :title="room ? '编辑房间' : '新建房间'"
    :busy="saving"
    :dirty="dirty"
    @close="emit('close')"
  >
    <form class="form" @input="dirty = true" @submit.prevent="save">
      <fieldset class="form-fields" :disabled="saving">
        <label>
          所属楼栋 *
          <select v-model="form.buildingId" required :disabled="!!selectedRoom">
            <option value="">请选择楼栋</option>
            <option v-for="b in buildings" :key="b.id" :value="b.id">
              {{ b.name }}
            </option>
          </select>
        </label>
        <div class="form-row">
          <label>
            房间号 *
            <input
              ref="roomInput"
              v-model.trim="form.roomNo"
              @input="inferFloor"
              required
              maxlength="20"
              placeholder="例如 302"
            />
          </label>
          <label>
            楼层 *
            <input
              v-model.number="form.floorNo"
              @input="floorEdited = true"
              type="number"
              min="1"
              :max="
                buildings.find((b) => b.id === form.buildingId)?.floorCount ||
                100
              "
              required
            />
          </label>
        </div>
        <label>
          房间容量 *
          <input
            v-model.number="form.capacity"
            type="number"
            :min="Math.max(1, selectedRoom?.occupiedCount || 0)"
            max="20"
            required
          />
          <small>容量不得小于当前在住人数；已有入住的房间不能停用。</small>
        </label>
        <label class="checkbox">
          <input
            v-model="form.enabled"
            type="checkbox"
            :disabled="!!selectedRoom?.occupiedCount"
          />
          启用房间
        </label>
        <label>
          备注
          <textarea v-model.trim="form.remark" maxlength="500" />
        </label>

        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <button class="btn primary" :disabled="saving">
          {{ saving ? "正在保存…" : "保存档案" }}
        </button>
        <button
          v-if="!selectedRoom"
          class="btn"
          type="submit"
          value="continue"
          :disabled="saving"
        >
          保存并继续新建
        </button>
      </fieldset>
    </form>
  </Drawer>
</template>
