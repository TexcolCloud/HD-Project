import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import type { Student } from "../api/types";
import { node, renderer, find, text } from "../test/renderer";
import StudentsPage from "./StudentsPage.vue";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("vue-router", () => ({ onBeforeRouteLeave: vi.fn() }));
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelText: {},
  vModelSelect: {},
}));
let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

it("入住历史读取期间不将未知状态当成未入住，成功后才显示办理操作", async () => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  const student: Student = {
    id: "student-1",
    userId: "user-1",
    studentNo: "DEMO-01",
    name: "回归学生",
    college: "演示学院",
    major: "演示专业",
    grade: "2026",
    className: "演示班",
    phone: "138****0000",
    status: "ACTIVE",
    roomId: null,
    roomNo: null,
    buildingName: null,
    version: 1,
  };
  let resolveHistory!: (value: {
    items: [];
    total: number;
    page: number;
    pageSize: number;
  }) => void;
  const pending = new Promise<{
    items: [];
    total: number;
    page: number;
    pageSize: number;
  }>((resolve) => {
    resolveHistory = resolve;
  });
  api.get.mockImplementation(async (path: string) =>
    path === "/residences"
      ? pending
      : { items: [student], total: 1, page: 1, pageSize: 20 },
  );
  const root = node("root");
  const app = renderer.createApp(StudentsPage);
  app.mount(root);
  unmount = () => app.unmount();
  const history = () =>
    find(
      root,
      (candidate) =>
        candidate.tag === "button" && text(candidate).trim() === "入住历史",
    );
  await vi.waitFor(() => expect(history()).toBeDefined());
  (history()!.props.onClick as () => void)();
  await nextTick();
  const dialog = find(root, (candidate) => candidate.tag === "dialog")!;
  const checkIn = () =>
    find(
      dialog,
      (candidate) =>
        candidate.tag === "button" && text(candidate).trim() === "办理入住",
    );
  expect(checkIn()).toBeUndefined();
  resolveHistory({ items: [], total: 0, page: 1, pageSize: 100 });
  await pending;
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
  expect(checkIn()).toBeDefined();
});
