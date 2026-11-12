import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import type { Visitor } from "../api/types";
import { node, renderer, find, text } from "../test/renderer";
import VisitorsPage from "./VisitorsPage.vue";

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

it("登记离访成功后，晚到的详情查询不能恢复旧的在访状态", async () => {
  vi.stubGlobal("window", {
    confirm: () => true,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
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
  const button = (label: string) =>
    find(
      root,
      (candidate) =>
        candidate.tag === "button" && text(candidate).trim() === label,
    );
  await vi.waitFor(() => expect(button("查看")).toBeDefined());
  (button("查看")!.props.onClick as () => void)();
  await nextTick();
  const dialog = find(root, (candidate) => candidate.tag === "dialog")!;
  const leave = find(
    dialog,
    (candidate) =>
      candidate.tag === "button" && text(candidate).trim() === "登记离访",
  )!;
  (leave.props.onClick as () => void)();
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
