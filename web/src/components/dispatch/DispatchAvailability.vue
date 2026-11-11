<script setup lang="ts">
import { ref, reactive } from "vue";
import { saveDispatchSettings, type DispatchManager } from "../../api/dispatch";
import type { Building, Model } from "../../api/types";
import {
  datetimeInput,
  dispatchPositionFields,
} from "../../composables/dispatchPlan";
import { errorText, isoTime } from "../../ui";
import Drawer from "../Drawer.vue";
const props = defineProps<{
  manager: DispatchManager;
  buildings: Building[];
}>();
const emit = defineEmits<{ close: []; saved: [] }>();
const suggestedUntil =
  props.manager.availableUntil &&
  Date.parse(props.manager.availableUntil) > Date.now()
    ? props.manager.availableUntil
    : new Date(Date.now() + 4 * 3600_000).toISOString();
const minimumUntil = datetimeInput(new Date().toISOString()),
  maximumUntil = datetimeInput(
    new Date(Date.now() + 7 * 86400_000).toISOString(),
  );
const form = reactive({
  acceptingNewTasks: props.manager.acceptingNewTasks,
  availableUntil: datetimeInput(suggestedUntil),
  buildingId: props.manager.positionBuildingId || "",
  floorNo: props.manager.positionFloorNo ?? "",
});
const version = props.manager.version,
  busy = ref(false),
  error = ref(""),
  dirty = ref(false),
  positionChanged = ref(false);
async function save() {
  busy.value = true;
  error.value = "";
  try {
    const body: Model<"DispatchSettingsRequest"> = {
      acceptingNewTasks: form.acceptingNewTasks,
      availableUntil: isoTime(form.availableUntil) || null,
      ...dispatchPositionFields(
        form.buildingId,
        form.floorNo,
        positionChanged.value,
      ),
      expectedVersion: version,
    };
    await saveDispatchSettings(body);
    dirty.value = false;
    emit("saved");
    emit("close");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <Drawer
    title="本次接单与位置"
    :busy="busy"
    :dirty="dirty"
    @close="$emit('close')"
    ><form class="form" @input="dirty = true" @submit.prevent="save">
      <p class="info-note">
        接单意愿按账号共享。暂停后不分配新任务，已经接下的工单仍由你负责。
      </p>
      <label class="checkbox"
        ><input
          v-model="form.acceptingNewTasks"
          type="checkbox"
        />接受新任务</label
      ><label
        >本次可用到 {{ form.acceptingNewTasks ? "*" : ""
        }}<input
          v-model="form.availableUntil"
          type="datetime-local"
          :required="form.acceptingNewTasks"
          :min="minimumUntil"
          :max="maximumUntil"
        /><small
          >按北京时间填写未来7天内的截止时刻。接受新任务时必须设置有效工作时段；预填时间只有保存后生效。</small
        ></label
      ><label
        >当前所在楼栋<select
          v-model="form.buildingId"
          @change="
            form.floorNo = '';
            positionChanged = true;
          "
        >
          <option value="">暂未确认位置</option>
          <option v-for="b in buildings" :key="b.id" :value="b.id">
            {{ b.name }}
          </option>
        </select></label
      ><label v-if="form.buildingId"
        >当前楼层 *<input
          v-model.number="form.floorNo"
          type="number"
          min="1"
          @input="positionChanged = true"
          :max="
            buildings.find((b) => b.id === form.buildingId)?.floorCount || 100
          "
          :required="positionChanged"
          placeholder="楼层待确认"
      /></label>
      <p class="form-note">
        修改楼栋时请重新确认楼层。仅调整接单时段会保留位置来源；未知或过期位置需要本人确认后再用于路线。
      </p>
      <p v-if="error" class="error-banner" role="alert">
        {{ error }}<br /><small
          >如设置已被其他端修改，请关闭后重新打开；当前填写内容仍保留。</small
        >
      </p>
      <button class="btn primary" :disabled="busy">
        {{ busy ? "正在保存…" : "保存本次设置" }}
      </button>
    </form></Drawer
  >
</template>
