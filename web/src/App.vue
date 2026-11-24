<script setup lang="ts">
import { provide, ref, watch, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import { session, clearLocalSession, logout } from "./session";
import { get } from "./api/client";
import type { Model } from "./api/types";
import { errorText, notices, notify } from "./ui";
import Icon from "./components/Icon.vue";
import { useManagerPresence } from "./composables/useManagerPresence";
import { useInboxBadge } from "./composables/useInboxBadge";
import { useLatestQuery } from "./composables/useLatestQuery";
import ConfirmDialog from "./components/ConfirmDialog.vue";
import { activeModal } from "./components/modal";
import NoticeToast from "./components/NoticeToast.vue";
import { confirmHost } from "./composables/useConfirm";
provide(confirmHost, true);
useManagerPresence(() => session.user);
const unreadMessages = useInboxBadge(() => session.user);
const route = useRoute(),
  router = useRouter();
const scopeQuery = useLatestQuery();
const sideNav = ref<HTMLElement>();
watch(
  () => [route.path, sideNav.value],
  () => {
    if (
      typeof window !== "undefined" &&
      window.matchMedia?.("(max-width: 700px)")?.matches
    )
      sideNav.value
        ?.querySelector?.(".active")
        ?.scrollIntoView?.({ inline: "center", block: "nearest" });
  },
  { flush: "post" },
);
const noticeStack = ref<HTMLElement>();
watch(
  () => [noticeStack.value, notices.length, activeModal.value],
  () => {
    if (notices.length) noticeStack.value?.showPopover?.();
    else noticeStack.value?.hidePopover?.();
  },
  { flush: "post" },
);
const buildingNames = ref<string[]>([]);
watch(
  () =>
    session.user && !session.user.mustChangePassword
      ? session.user.id
      : undefined,
  (accountId) => {
    buildingNames.value = [];
    scopeQuery.cancel();
    if (!accountId) return;
    void scopeQuery.run(
      (signal) =>
        get<Model<"BuildingPage">>("/buildings", { pageSize: 100 }, signal),
      (result) => {
        buildingNames.value = result.items.map((building) => building.name);
      },
    );
  },
  { immediate: true },
);
const theme = ref(
  document.documentElement.dataset.theme === "night" ? "night" : "day",
);
const groups = [
  {
    title: "日常值班",
    links: [
      ["/dashboard", "home", "工作台"],
      ["/visitors", "visitor", "访客登记"],
    ],
  },
  {
    title: "住宿档案",
    links: [
      ["/housing/rooms", "building", "宿舍档案"],
      ["/housing/students", "people", "学生入住"],
    ],
  },
  {
    title: "维修与调度",
    links: [
      ["/repairs", "tool", "报修工单"],
      ["/dispatch", "route", "任务调度"],
    ],
  },
  {
    title: "卫生与统计",
    links: [
      ["/hygiene", "shield", "卫生督办"],
      ["/statistics", "chart", "数据统计"],
    ],
  },
];
function active(path: string) {
  return (
    route.path === path ||
    (path === "/repairs" && route.path.startsWith("/repairs/")) ||
    (path === "/dispatch" && route.path === "/mobility") ||
    (path === "/hygiene" && route.path === "/remediations")
  );
}
function applyTheme(value: string) {
  theme.value = value;
  document.documentElement.dataset.theme = value;
  document.documentElement.style.colorScheme =
    value === "night" ? "dark" : "light";
  try {
    localStorage.setItem("dormlink-theme", value);
  } catch {
    /* The current tab can still switch themes when storage is unavailable. */
  }
}
applyTheme(theme.value);
async function signOut() {
  try {
    await logout();
    await router.replace("/login");
  } catch (e) {
    notify(errorText(e), "error");
  }
}
function expired() {
  if (!session.user) return;
  clearLocalSession();
  notify("登录已过期，请重新登录后继续办理", "error");
  void router.replace({ path: "/login", query: { redirect: route.fullPath } });
}
onMounted(() => window.addEventListener("session-expired", expired));
onBeforeUnmount(() => window.removeEventListener("session-expired", expired));
</script>
<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <template v-if="route.meta.standalone">
    <RouterView />
  </template>
  <div v-else class="app-shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/dashboard">寓见</RouterLink>
      <p class="sidebar-note">学生公寓管理</p>
      <nav ref="sideNav" aria-label="管理功能">
        <template v-for="group in groups" :key="group.title">
          <span class="nav-group">{{ group.title }}</span>
          <RouterLink
            v-for="[path, icon, label] in group.links"
            :key="path"
            :to="path"
            :class="{ active: active(path) }"
          >
            <Icon :name="icon" />
            {{ label }}
          </RouterLink>
        </template>
      </nav>
      <div class="sidebar-foot">
        <span v-if="buildingNames.length" :title="buildingNames.join('、')">
          负责楼栋 · {{ buildingNames.join("、") }}
        </span>
        <span v-else>按负责楼栋管理数据</span>
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <div class="breadcrumb">
          公寓服务 / {{ route.meta.title || "工作台" }}
        </div>
        <div class="top-actions">
          <div class="theme-switch" aria-label="界面主题">
            <button :aria-pressed="theme === 'day'" @click="applyTheme('day')">
              <Icon name="sun" />
              日间
            </button>
            <button
              :aria-pressed="theme === 'night'"
              @click="applyTheme('night')"
            >
              <Icon name="moon" />
              夜间
            </button>
          </div>
          <RouterLink
            class="btn"
            to="/messages"
            :class="{ active: route.path === '/messages' }"
            :aria-label="`服务消息，${unreadMessages}条未读`"
          >
            消息
            <span v-if="unreadMessages" class="num">{{ unreadMessages }}</span>
          </RouterLink>
          <div class="profile">
            <span class="avatar">
              {{ session.user?.displayName?.slice(0, 1) }}
            </span>
            <div>
              <strong>{{ session.user?.displayName }}</strong>
              <small>宿管</small>
            </div>
          </div>
          <RouterLink class="btn" to="/account-security">账号安全</RouterLink>
          <button
            class="icon-button"
            title="退出登录"
            aria-label="退出登录"
            @click="signOut"
          >
            <Icon name="logout" />
          </button>
        </div>
      </header>
      <main id="main-content" class="content" tabindex="-1">
        <RouterView />
      </main>
    </div>
  </div>
  <Teleport :to="activeModal || 'body'" :disabled="!activeModal">
    <div ref="noticeStack" popover="manual" class="notice-stack">
      <NoticeToast
        v-for="notice in notices"
        :key="notice.id"
        :notice="notice"
      />
    </div>
  </Teleport>
  <ConfirmDialog />
</template>
