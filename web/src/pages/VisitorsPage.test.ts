import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { onBeforeRouteLeave, useRoute } from "vue-router";
import type { Visitor } from "../api/types";
import { node, renderer, find, text, type TestNode } from "../test/renderer";
import VisitorsPage from "./VisitorsPage.vue";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("vue-router", async () => {
  const { reactive } = await import("vue");
  const route = reactive({ query: {} });
  return { onBeforeRouteLeave: vi.fn(), useRoute: () => route };
});
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelText: {},
  vModelSelect: {},
}));

let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  delete useRoute().query.visitorId;
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

const visitor: Visitor = {
  id: "visitor-1",
  visitorName: "回归访客",
  phone: "138****0000",
  visitedStudentId: "student-1",
  studentName: "演示学生",
  roomId: "room-1",
  roomNo: "302",
  buildingId: "building-1",
  buildingName: "A栋",
  purpose: "探访",
  remark: "",
  arrivedAt: "2026-11-12T01:00:00Z",
  leftAt: null,
  status: "VISITING",
  version: 1,
};
const button = (root: TestNode, label: string) =>
  find(
    root,
    (candidate) =>
      candidate.tag === "button" && text(candidate).trim() === label,
  );
const click = (root: TestNode, label: string) =>
  (button(root, label)!.props.onClick as () => void)();
const confirmation = (root: TestNode) =>
  find(
    root,
    (candidate) =>
      candidate.tag === "dialog" &&
      candidate.props["aria-labelledby"] === "visitor-leave-title",
  );

it("在访超时显示状态，取消离访后保留登记", async () => {
  const confirm = vi.fn(() => false);
  vi.stubGlobal("window", {
    confirm,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  const overdue = { ...visitor, overdue: true };
  api.get.mockImplementation(async (path: string) =>
    path === "/visitors/visitor-1"
      ? overdue
      : { items: path === "/visitors" ? [overdue] : [], total: 1 },
  );
  const root = node("root");
  const app = renderer.createApp(VisitorsPage);
  app.mount(root);
  unmount = () => app.unmount();
  await vi.waitFor(() => expect(text(root)).toContain("超24小时未离访"));
  click(root, "查看");
  await nextTick();
  const dialog = find(root, (candidate) => candidate.tag === "dialog")!;
  expect(text(dialog)).toContain("超24小时未离访");
  click(dialog, "登记离访");
  await nextTick();
  expect(confirmation(root)).toBeDefined();
  expect(text(confirmation(root)!)).toContain("回归访客");
  expect(text(confirmation(root)!)).toContain("A栋 302");
  expect(confirm).not.toHaveBeenCalled();
  click(confirmation(root)!, "返回");
  await nextTick();
  expect(confirmation(root)).toBeUndefined();
  expect(api.post).not.toHaveBeenCalled();
  expect(text(dialog)).toContain("超24小时未离访");

  click(dialog, "登记离访");
  await nextTick();
  const preventDefault = vi.fn();
  (
    confirmation(root)!.props.onCancel as (event: {
      preventDefault: () => void;
    }) => void
  )({ preventDefault });
  await nextTick();
  expect(preventDefault).toHaveBeenCalledOnce();
  expect(confirmation(root)).toBeUndefined();
  expect(api.post).not.toHaveBeenCalled();
});

it("补登记离访将实际离开时刻从北京时间换算并携带当前版本", async () => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  api.get.mockImplementation(async (path: string) => ({
    items: path === "/visitors" ? [visitor] : [],
    total: 1,
  }));
  api.post.mockResolvedValue({ ...visitor, status: "LEFT", version: 2 });
  const root = node("root"),
    app = renderer.createApp(VisitorsPage);
  app.mount(root);
  unmount = () => app.unmount();
  await vi.waitFor(() => expect(button(root, "登记离访")).toBeDefined());
  click(root, "登记离访");
  await nextTick();
  const dialog = confirmation(root)!;
  const field = find(dialog, (n) => n.props.type === "datetime-local")!;
  (field.props["onUpdate:modelValue"] as (v: string) => void)(
    "2026-11-12T10:15",
  );
  click(dialog, "确认离访");
  expect(api.post).toHaveBeenCalledWith("/visitors/visitor-1/leave", {
    expectedVersion: 1,
    leftAt: "2026-11-12T02:15:00.000Z",
  });
});

it("登记离访成功后，晚到的详情查询不能恢复旧的在访状态", async () => {
  vi.stubGlobal("window", {
    confirm: () => true,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  let resolveDetail!: (value: Visitor) => void;
  const pending = new Promise<Visitor>((resolve) => {
    resolveDetail = resolve;
  });
  api.get.mockImplementation(async (path: string) =>
    path === "/visitors/visitor-1"
      ? pending
      : {
          items: path === "/visitors" ? [visitor] : [],
          total: 1,
          page: 1,
          pageSize: 20,
        },
  );
  api.post.mockResolvedValue({
    ...visitor,
    status: "LEFT",
    leftAt: "2026-11-12T02:00:00Z",
    version: 2,
  });
  const root = node("root");
  const app = renderer.createApp(VisitorsPage);
  app.mount(root);
  unmount = () => app.unmount();
  await vi.waitFor(() => expect(button(root, "查看")).toBeDefined());
  click(root, "查看");
  await nextTick();
  const dialog = find(root, (candidate) => candidate.tag === "dialog")!;
  click(dialog, "登记离访");
  await nextTick();
  expect(api.post).not.toHaveBeenCalled();
  const confirm = button(confirmation(root)!, "确认离访")!.props
    .onClick as () => void;
  confirm();
  confirm();
  expect(api.post).toHaveBeenCalledExactlyOnceWith(
    "/visitors/visitor-1/leave",
    { expectedVersion: 1 },
  );
  await vi.waitFor(() => expect(text(dialog)).toContain("已离访"));
  resolveDetail(visitor);
  await pending;
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
  expect(text(dialog)).toContain("已离访");
  expect(
    find(
      dialog,
      (candidate) =>
        candidate.tag === "button" && text(candidate).trim() === "登记离访",
    ),
  ).toBeUndefined();
});

it("切换访客记录时作废已打开的离访确认", async () => {
  vi.stubGlobal("window", {
    confirm: vi.fn(() => false),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  const another = { ...visitor, id: "visitor-2", visitorName: "另一位访客" };
  api.get.mockImplementation(async (path: string) =>
    path === "/visitors/visitor-2"
      ? another
      : { items: path === "/visitors" ? [visitor] : [], total: 1 },
  );
  const root = node("root");
  const app = renderer.createApp(VisitorsPage);
  app.mount(root);
  unmount = () => app.unmount();
  await vi.waitFor(() => expect(button(root, "登记离访")).toBeDefined());
  click(root, "登记离访");
  await nextTick();
  const staleConfirm = button(confirmation(root)!, "确认离访")!.props
    .onClick as () => void;
  useRoute().query.visitorId = another.id;
  await vi.waitFor(() => expect(text(root)).toContain(another.visitorName));
  expect(confirmation(root)).toBeUndefined();
  staleConfirm();
  expect(api.post).not.toHaveBeenCalled();
});

it("离开访客页面时撤销尚未确认的操作", async () => {
  api.get.mockImplementation(async (path: string) => ({
    items: path === "/visitors" ? [visitor] : [],
    total: 1,
  }));
  const root = node("root");
  const app = renderer.createApp(VisitorsPage);
  app.mount(root);
  unmount = () => app.unmount();
  await vi.waitFor(() => expect(button(root, "登记离访")).toBeDefined());
  click(root, "登记离访");
  await nextTick();
  const staleConfirm = button(confirmation(root)!, "确认离访")!.props
    .onClick as () => void;
  const leavePage = vi.mocked(onBeforeRouteLeave).mock
    .calls[0]![0] as () => void;
  leavePage();
  await nextTick();
  expect(confirmation(root)).toBeUndefined();
  staleConfirm();
  expect(api.post).not.toHaveBeenCalled();
});
