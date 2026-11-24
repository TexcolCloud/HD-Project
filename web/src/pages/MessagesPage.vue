<script setup lang="ts">
import { isRowClick } from "../utils/tableRow";
import { computed, ref, onMounted, onScopeDispose } from "vue";
import { get, post } from "../api/client";
import type { Model } from "../api/types";
import { dateTime, errorText } from "../ui";
import { messageDestination } from "../composables/serviceLinks";
import { useLatestQuery } from "../composables/useLatestQuery";
import Pagination from "../components/Pagination.vue";
import Drawer from "../components/Drawer.vue";
import { syncInboxBadge } from "../composables/useInboxBadge";
const query = useLatestQuery();
const { busy, error } = query;
const items = ref<Model<"ServiceMessage">[]>([]),
  summary = ref<Model<"ServiceMessageSummary">>();
const selected = ref<Model<"ServiceMessage">>(),
  page = ref(1),
  total = ref(0),
  unreadOnly = ref(false);
const pending = ref(new Set<string>()),
  batch = ref(false);
const saving = computed(() => batch.value || pending.value.size > 0),
  actionError = ref("");
let disposed = false,
  timer: ReturnType<typeof setInterval>;
async function load() {
  await query.run(
    (signal) =>
      Promise.all([
        get<Model<"ServiceMessagePage">>(
          "/messages",
          {
            page: page.value,
            pageSize: 20,
            unreadOnly: unreadOnly.value ? "true" : undefined,
          },
          signal,
        ),
        get<Model<"ServiceMessageSummary">>("/messages/summary", {}, signal),
      ]),
    ([list, counts]) => {
      items.value = list.items;
      total.value = list.total;
      summary.value = counts;
      syncInboxBadge(counts.unreadCount);
    },
  );
}
function search() {
  page.value = 1;
  void load();
}
type Message = Model<"ServiceMessage">;
function detail(message: Message) {
  selected.value = message;
  actionError.value = "";
  void read(message);
}
async function read(message: Message) {
  if (message.readAt || pending.value.has(message.id)) return;
  pending.value.add(message.id);
  query.cancel();
  try {
    const updated = await post<Message>("/messages/" + message.id + "/read", {
      expectedVersion: message.version,
    } satisfies Model<"ServiceMessageReadRequest">);
    syncInboxBadge();
    if (disposed) return;
    if (selected.value?.id === message.id) selected.value = updated;
    if (
      unreadOnly.value &&
      items.value.some((item) => item.id === message.id)
    ) {
      items.value = items.value.filter((item) => item.id !== message.id);
      total.value = Math.max(0, total.value - 1);
      page.value = Math.min(
        page.value,
        Math.max(1, Math.ceil(total.value / 20)),
      );
    } else {
      items.value = items.value.map((item) =>
        item.id === message.id ? updated : item,
      );
    }
  } catch (cause) {
    if (!disposed) actionError.value = errorText(cause);
  } finally {
    pending.value.delete(message.id);
    if (!disposed && !saving.value) await load();
  }
}
async function readPage() {
  if (saving.value) return;
  batch.value = true;
  actionError.value = "";
  try {
    for (const message of items.value.filter((item) => !item.readAt)) {
      if (disposed) break;
      await read(message);
    }
  } finally {
    batch.value = false;
    if (!disposed) await load();
  }
}
onMounted(() => {
  void load();
  timer = setInterval(() => {
    if (!saving.value) void load();
  }, 30000);
});
onScopeDispose(() => {
  disposed = true;
  clearInterval(timer);
});
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>服务消息</h1>
    </div>
    <button class="btn" :disabled="busy || saving" @click="load">
      刷新消息
    </button>
  </div>
  <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  <p v-if="!selected && actionError" class="error-banner" role="alert">
    {{ actionError }}
  </p>
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>我的收件箱</h2>
        <p class="muted">{{ summary?.unreadCount ?? 0 }} 条未读</p>
      </div>
      <button
        type="button"
        class="btn"
        :disabled="busy || saving || !items.some((item) => !item.readAt)"
        @click="readPage"
      >
        本页未读标为已读
      </button>
      <label>
        <input v-model="unreadOnly" type="checkbox" @change="search" />
        只看未读
      </label>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>状态</th>
            <th>事项</th>
            <th>时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="message in items"
            :key="message.id"
            class="open-row"
            @click="isRowClick($event) && detail(message)"
          >
            <td>
              <span
                class="status"
                :class="message.readAt ? 'COMPLETED' : 'PENDING_CONFIRMATION'"
              >
                {{ message.readAt ? "已读" : "未读" }}
              </span>
            </td>
            <td>
              <strong>{{ message.title }}</strong>
              <small class="muted cell-clamp" :title="message.body">
                {{ message.body }}
              </small>
            </td>
            <td>{{ dateTime(message.createdAt) }}</td>
            <td>
              <button class="text-link" @click="detail(message)">
                查看消息
              </button>
              <RouterLink
                v-if="messageDestination(message)"
                class="text-link"
                :to="messageDestination(message)!"
                @click="read(message)"
              >
                查看相关记录
              </RouterLink>
            </td>
          </tr>
          <tr v-if="!items.length">
            <td colspan="4" class="empty-state">
              {{ busy ? "正在同步消息…" : "当前没有待查看的服务消息" }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Pagination
      :page="page"
      :total="total"
      :page-size="20"
      :busy="busy || saving"
      @change="
        (value) => {
          page = value;
          load();
        }
      "
    />
  </section>
  <Drawer
    v-if="selected"
    :title="selected.title"
    :subtitle="dateTime(selected.createdAt)"
    @close="selected = undefined"
  >
    <p class="description">{{ selected.body }}</p>
    <p class="muted">
      {{
        selected.readAt ? "已读于 " + dateTime(selected.readAt) : "尚未标记已读"
      }}
    </p>
    <p v-if="actionError" class="error-banner" role="alert">
      {{ actionError }}
    </p>
    <template #footer>
      <button
        v-if="!selected.readAt"
        class="btn primary"
        :disabled="saving"
        @click="read(selected)"
      >
        标记已读
      </button>
      <RouterLink
        v-if="messageDestination(selected)"
        class="btn"
        :to="messageDestination(selected)!"
      >
        查看相关记录
      </RouterLink>
    </template>
  </Drawer>
</template>
