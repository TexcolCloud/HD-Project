import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import type { Student } from "../api/types";
import { node, renderer, find, text } from "../test/renderer";
import StudentsPage from "./StudentsPage.vue";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("vue-router", () => ({
  onBeforeRouteLeave: vi.fn(),
  useRoute: () => ({ query: {} }),
}));
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

it("新建学生档案时保留粘贴内容，并阻止提交12位手机号", async () => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  api.get.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 });
  const root = node("root");
  const app = renderer.createApp(StudentsPage);
  app.mount(root);
  unmount = () => app.unmount();
  const create = find(
    root,
    (candidate) =>
      candidate.tag === "button" && text(candidate).includes("新建学生档案"),
  )!;
  (create.props.onClick as () => void)();
  await nextTick();
  const dialog = find(root, (candidate) => candidate.tag === "dialog")!;
  const phone = find(dialog, (candidate) => candidate.props.type === "tel")!;
  (phone.props["onUpdate:modelValue"] as (value: string) => void)(
    "138000000001",
  );
  const form = find(dialog, (candidate) => candidate.tag === "form")!;
  (form.props.onSubmit as (event: { preventDefault: () => void }) => void)({
    preventDefault() {},
  });
  await nextTick();
  expect(text(dialog)).toContain("请输入11位手机号");
  expect(phone.props.maxlength).toBeUndefined();
  expect(api.post).not.toHaveBeenCalled();
});

it.each([
  ["调宿", "transfer"],
  ["退宿", "check-out"],
])("行内%s等有效入住返回后才允许按当前版本办理", async (label, path) => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    setTimeout,
  });
  let finish!: (v: unknown) => void;
  const read = new Promise((resolve) => (finish = resolve));
  api.get.mockImplementation(async (url: string) =>
    url === "/residences"
      ? read
      : {
          items:
            url === "/students"
              ? [{ id: "s", roomId: "r", name: "测试", status: "ACTIVE" }]
              : [],
          total: 1,
        },
  );
  const root = node("root"),
    app = renderer.createApp(StudentsPage);
  app.mount(root);
  unmount = () => app.unmount();
  const btn = (name: string) =>
    find(root, (n) => n.tag === "button" && text(n).trim() === name);
  await vi.waitFor(() => expect(btn(label)).toBeDefined());
  (btn(label)!.props.onClick as () => void)();
  await nextTick();
  expect(text(root)).toContain("正在核对当前入住记录");
  expect(btn("确认" + label)?.props.disabled).toBe(true);
  finish({
    items: [
      { id: "old", checkedOutAt: "2025-01-01" },
      { id: "current", version: 7, checkedOutAt: null },
    ],
  });
  await vi.waitFor(() =>
    expect(btn("确认" + label)?.props.disabled).toBe(false),
  );
  const dialog = find(root, (n) => n.tag === "dialog")!;
  (
    find(dialog, (n) => n.tag === "textarea")!.props["onUpdate:modelValue"] as (
      v: string,
    ) => void
  )("学生申请");
  (
    find(dialog, (n) => n.tag === "form")!.props.onSubmit as (e: {
      preventDefault(): void;
    }) => void
  )({ preventDefault() {} });
  await vi.waitFor(() =>
    expect(api.post).toHaveBeenCalledWith(
      "/residences/current/" + path,
      expect.objectContaining({ expectedVersion: 7, reason: "学生申请" }),
    ),
  );
});
