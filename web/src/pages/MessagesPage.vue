<script setup lang="ts">
import { ref, onMounted, onScopeDispose } from "vue";
import { get, post } from "../api/client";
import type { Model } from "../api/types";
import { dateTime, errorText } from "../ui";
import { messageDestination } from "../composables/serviceLinks";
import { useLatestQuery } from "../composables/useLatestQuery";
import Pagination from "../components/Pagination.vue";
import Drawer from "../components/Drawer.vue";
const query = useLatestQuery();
const { busy, error } = query;
const items = ref<Model<"ServiceMessage">[]>([]),
  summary = ref<Model<"ServiceMessageSummary">>();
const selected = ref<Model<"ServiceMessage">>(),
  page = ref(1),
  total = ref(0),
  unreadOnly = ref(false);
const saving = ref(false),
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
    },
  );
}
function search() {
  page.value = 1;
  void load();
}
function detail(message: Model<"ServiceMessage">) {
  selected.value = message;
  actionError.value = "";
}
async function read() {
  const message = selected.value;
  if (!message || message.readAt || saving.value) return;
  query.cancel();
  saving.value = true;
  actionError.value = "";
  try {
    const updated = await post<Model<"ServiceMessage">>(
      `/messages/${message.id}/read`,
      {
        expectedVersion: message.version,
      } satisfies Model<"ServiceMessageReadRequest">,
    );
    if (disposed) return;
    selected.value = updated;
    await load();
  } catch (cause) {
    if (!disposed) actionError.value = errorText(cause);
  } finally {
    if (!disposed) saving.value = false;
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
      <p>把工单变化、预约与待办跟进，留在同一份收件箱。</p>
    </div>
    <button class="btn" :disabled="busy || saving" @click="load">
      刷新消息
    </button>
  </div>
  <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>我的收件箱</h2>
        <p class="muted">
          {{ summary?.unreadCount ?? 0 }} 条未读 · 页面打开时每30秒同步
        </p>
      </div>
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
          <tr v-for="message in items" :key="message.id">
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
              <small class="muted">{{ message.body }}</small>
            </td>
            <td>{{ dateTime(message.createdAt) }}</td>
            <td>
              <button
                class="text-link"
                :disabled="saving"
                @click="detail(message)"
              >
                查看消息
              </button>
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
  <p class="form-note">
    这里只显示当前账号及授权范围内的消息。应用关闭后的消息可在下次打开时同步查看。
  </p>
  <Drawer
    v-if="selected"
    :title="selected.title"
    :subtitle="dateTime(selected.createdAt)"
    :busy="saving"
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
        @click="read"
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
