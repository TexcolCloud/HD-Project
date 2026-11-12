<script setup lang="ts">
import { reactive, ref } from "vue";
import { patch, post } from "../../api/client";
import type { Building, Model, Room } from "../../api/types";
import { errorText } from "../../ui";
import Drawer from "../Drawer.vue";
const props = defineProps<{ room?: Room; buildings: Building[] }>();
const emit = defineEmits<{ close: []; saved: [] }>();
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
async function save() {
  saving.value = true;
  error.value = "";
  try {
    if (selectedRoom) await patch(`/rooms/${selectedRoom.id}`, form);
    else await post("/rooms", form);
    dirty.value = false;
    emit("saved");
  } catch (cause) {
    error.value = errorText(cause);
  } finally {
    saving.value = false;
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
            v-model.trim="form.roomNo"
            required
            maxlength="20"
            placeholder="例如 302"
          />
        </label>
        <label>
          楼层 *
          <input
            v-model.number="form.floorNo"
            type="number"
            min="1"
            :max="
              buildings.find((b) => b.id === form.buildingId)?.floorCount || 100
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
    </form>
  </Drawer>
</template>
