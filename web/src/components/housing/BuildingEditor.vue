<script setup lang="ts">
import { reactive, ref } from "vue";
import { patch, post } from "../../api/client";
import type { Body, Building } from "../../api/types";
import { errorText } from "../../ui";
import Drawer from "../Drawer.vue";
const props = defineProps<{ building?: Building }>();
const emit = defineEmits<{ close: []; saved: [] }>();
const saving = ref(false),
  dirty = ref(false),
  error = ref("");
const buildingForm = reactive<Body<"post_buildings">>({
  code: props.building?.code ?? "",
  name: props.building?.name ?? "",
  floorCount: props.building?.floorCount ?? 6,
  enabled: props.building?.enabled ?? true,
});
async function save() {
  saving.value = true;
  error.value = "";
  try {
    if (props.building)
      await patch(`/buildings/${props.building.id}`, {
        ...buildingForm,
        expectedVersion: props.building.version,
      });
    else await post("/buildings", buildingForm);
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
    :title="building ? '编辑楼栋' : '新建楼栋'"
    :busy="saving"
    :dirty="dirty"
    @close="emit('close')"
  >
    <form class="form" @input="dirty = true" @submit.prevent="save">
      <label>
        楼栋编码 *
        <input
          v-model.trim="buildingForm.code"
          required
          maxlength="20"
          placeholder="例如 A"
        />
      </label>
      <label>
        楼栋名称 *
        <input v-model.trim="buildingForm.name" required maxlength="80" />
      </label>
      <label>
        楼层数 *
        <input
          v-model.number="buildingForm.floorCount"
          type="number"
          min="1"
          max="100"
          required
        />
      </label>
      <label class="checkbox">
        <input v-model="buildingForm.enabled" type="checkbox" />
        启用楼栋
      </label>
      <p class="form-note">
        新建楼栋仅自动授权给当前宿管。停用时服务端校验房间与入住。
      </p>

      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
      <button class="btn primary" :disabled="saving">
        {{ saving ? "正在保存…" : "保存档案" }}
      </button>
    </form>
  </Drawer>
</template>
