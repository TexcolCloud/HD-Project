import { createRouter, createWebHistory } from "vue-router";
import { accountDestination, restoreSession, session } from "./session";
import LoginPage from "./pages/LoginPage.vue";
import DashboardPage from "./pages/DashboardPage.vue";
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", component: LoginPage, meta: { title: "登录" } },
    {
      path: "/change-password",
      component: () => import("./pages/ChangePasswordPage.vue"),
      meta: { title: "设置登录密码" },
    },
    {
      path: "/messages",
      component: () => import("./pages/MessagesPage.vue"),
      meta: { title: "服务消息" },
    },
    {
      path: "/remediations",
      component: () => import("./pages/RemediationsPage.vue"),
      meta: { title: "卫生整改" },
    },
    { path: "/", redirect: "/dashboard" },
    { path: "/dashboard", component: DashboardPage, meta: { title: "工作台" } },
    {
      path: "/housing/rooms",
      component: () => import("./pages/RoomsPage.vue"),
      meta: { title: "宿舍档案" },
    },
    {
      path: "/housing/students",
      component: () => import("./pages/StudentsPage.vue"),
      meta: { title: "学生入住" },
    },
    { path: "/housing/residences", redirect: "/housing/students" },
    {
      path: "/repairs/:id?",
      component: () => import("./pages/RepairsPage.vue"),
      meta: { title: "报修工单" },
    },
    {
      path: "/dispatch",
      component: () => import("./pages/DispatchPage.vue"),
      meta: { title: "任务调度" },
    },
    {
      path: "/mobility",
      component: () => import("./pages/MobilityPage.vue"),
      meta: { title: "现场位置" },
    },
    {
      path: "/hygiene",
      component: () => import("./pages/HygienePage.vue"),
      meta: { title: "卫生巡检" },
    },
    {
      path: "/visitors",
      component: () => import("./pages/VisitorsPage.vue"),
      meta: { title: "访客登记" },
    },
    {
      path: "/statistics",
      component: () => import("./pages/StatsPage.vue"),
      meta: { title: "数据统计" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/dashboard" },
  ],
});
router.beforeEach(async (to) => {
  await restoreSession();
  if (to.path === "/login")
    return session.user?.role === "MANAGER"
      ? accountDestination(session.user, "/dashboard") || "/dashboard"
      : true;
  if (session.user?.role !== "MANAGER")
    return { path: "/login", query: { redirect: to.fullPath } };
  const destination = accountDestination(session.user, to.path);
  if (destination) return destination;
  return true;
});
