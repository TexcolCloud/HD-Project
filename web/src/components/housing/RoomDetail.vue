<script setup lang="ts">
import { onMounted, ref } from "vue";
import { get } from "../../api/client";
import type { Model, Room, Residence, Repair } from "../../api/types";
import { dateTime } from "../../ui";
import { useLatestQuery } from "../../composables/useLatestQuery";
import Drawer from "../Drawer.vue";
const props = defineProps<{ room: Room }>();
const emit = defineEmits<{ close: [] }>();
const selectedRoom = ref(props.room),
  history = ref<Residence[]>([]),
  related = ref<Repair[]>([]);
const query = useLatestQuery();
const { busy, error } = query;
onMounted(() =>
  query.run(
    (signal) =>
      Promise.all([
        get<Room>(`/rooms/${props.room.id}`, {}, signal),
        get<Model<"ResidencePage">>(
          "/residences",
          { roomId: props.room.id, pageSize: 100 },
          signal,
        ),
        get<Model<"RepairPage">>(
          "/repairs",
          { roomId: props.room.id, pageSize: 10 },
          signal,
        ),
      ]),
    ([room, residences, repairs]) => {
      selectedRoom.value = room;
      history.value = residences.items;
      related.value = repairs.items;
    },
  ),
);
</script>
<template>
  <Drawer
    :title="selectedRoom.buildingName + ' ' + selectedRoom.roomNo"
    subtitle="房间档案与历史入住"
    @close="emit('close')"
  >
    <p v-if="busy" role="status" class="loading">正在读取房间详情…</p>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <dl class="details">
      <div>
        <dt>当前入住 / 容量</dt>
        <dd>
          {{ selectedRoom.occupiedCount }} / {{ selectedRoom.capacity }} 人
        </dd>
      </div>
      <div>
        <dt>房间状态</dt>
        <dd>{{ selectedRoom.enabled ? "启用" : "停用" }}</dd>
      </div>
      <div>
        <dt>楼层</dt>
        <dd>{{ selectedRoom.floorNo }} 层</dd>
      </div>
      <div>
        <dt>备注</dt>
        <dd>{{ selectedRoom.remark || "无" }}</dd>
      </div>
    </dl>
    <section class="detail-section" style="margin-top: 26px">
      <h3>入住历史</h3>
      <div
        v-for="h in history"
        :key="h.id"
        class="description"
        style="margin-bottom: 10px"
      >
        {{ h.studentName }} · {{ h.studentNo }}
        <br />
        {{ dateTime(h.checkedInAt) }} —
        {{ h.checkedOutAt ? dateTime(h.checkedOutAt) : "当前在住" }}
      </div>
      <p v-if="!history.length" class="muted">暂无入住记录。</p>
    </section>
    <section class="detail-section">
      <h3>关联报修</h3>
      <p v-for="r in related" :key="r.id" style="margin-bottom: 12px">
        <RouterLink :to="'/repairs/' + r.id">{{ r.title }} →</RouterLink>
      </p>
      <p v-if="!related.length" class="muted">暂无报修记录。</p>
    </section>
  </Drawer>
</template>
