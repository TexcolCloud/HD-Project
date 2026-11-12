import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { nextTick, reactive } from "vue";
import type { Repair } from "../api/types";
import RepairsPage from "./RepairsPage.vue";

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  replace: vi.fn(),
}));
vi.mock("../api/client", () => ({
  ...mocks,
  request: vi.fn(),
  ApiError: class extends Error {},
}));
vi.mock("vue-router", () => ({
  useRoute: () => route,
  useRouter: () => ({ replace: mocks.replace }),
  onBeforeRouteLeave: vi.fn(),
}));
// The test drives component model callbacks; native DOM input directives are outside this close-chain regression.
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelText: {},
  vModelSelect: {},
}));

import {
  node,
  renderer,
  find,
  text,
  type TestNode as Node,
} from "../test/renderer";

const repair: Repair = {
  id: "repair-1",
  orderNo: "TEST-1",
  reporterStudentId: "student-1",
  reporterName: "测试学生",
  roomId: "room-1",
  roomNo: "302",
  buildingId: "building-1",
  buildingName: "A栋",
  title: "水龙头滴水",
  description: "测试故障描述",
  category: "UTILITIES",
  priority: "NORMAL",
  urgentReason: "",
  contactName: "测试学生",
  contactPhone: "13800000000",
  contactTimeSlot: "",
  status: "PROCESSING",
  handlerUserId: "manager-1",
  handlerName: "测试宿管",
  relatedOrderId: null,
  submittedAt: "2026-11-07T01:00:00Z",
  completedAt: null,
  version: 2,
  allowedActions: ["notes"],
  reportImages: [],
  resultImages: [],
  latestResult: null,
  windowStart: null,
  windowEnd: null,
  windowConfirmed: false,
  estimatedMinutes: 30,
  visitRequired: true,
  waitingReason: "",
};
let unmount: undefined | (() => void);
let route: { query: Record<string, string>; params: { id?: string } };
beforeEach(() => {
  route = reactive({ query: {}, params: { id: "repair-1" } });
});
afterEach(() => {
  unmount?.();
  unmount = undefined;
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

it("快速切换工单后，旧详情即使迟到也不能覆盖当前工单", async () => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  let resolveOld!: (value: Repair) => void;
  const old = new Promise<Repair>((resolve) => {
    resolveOld = resolve;
  });
  const latest = { ...repair, id: "repair-2", title: "当前工单：门锁检修" };
  mocks.get.mockImplementation(async (path: string) => {
    if (path === "/repairs/repair-1") return old;
    if (path === "/repairs/repair-2") return latest;
    if (path.endsWith("/events")) return [];
    return { items: [], total: 0, page: 1, pageSize: 20 };
  });
  const root = node("root");
  const app = renderer.createApp(RepairsPage);
  app.mount(root);
  unmount = () => app.unmount();
  await vi.waitFor(() =>
    expect(mocks.get).toHaveBeenCalledWith(
      "/repairs/repair-1",
      {},
      expect.any(AbortSignal),
    ),
  );
  route.params.id = latest.id;
  await vi.waitFor(() => expect(text(root)).toContain(latest.title));
  resolveOld(repair);
  await old;
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
  expect(text(root)).toContain(latest.title);
  expect(text(root)).not.toContain(repair.title);
});

it("工单有未保存说明时，关闭只确认一次；取消保留输入，确认后关闭", async () => {
  const confirm = vi.fn().mockReturnValue(true);
  vi.stubGlobal("window", {
    confirm,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  mocks.get.mockImplementation(async (path: string) =>
    path === "/repairs/repair-1"
      ? repair
      : path.endsWith("/events")
        ? []
        : {
            items: path === "/repairs" ? [repair] : [],
            total: 1,
            page: 1,
            pageSize: 20,
          },
  );
  const root = node("root");
  const app = renderer.createApp(RepairsPage);
  app.mount(root);
  unmount = () => app.unmount();
  await vi.waitFor(() =>
    expect(
      find(root, (n) => n.tag === "button" && text(n) === "追加进度"),
    ).toBeDefined(),
  );
  const begin = find(
    root,
    (n) => n.tag === "button" && text(n) === "追加进度",
  )!;
  (begin.props.onClick as () => void)();
  await nextTick();
  const input = find(root, (n) => n.tag === "textarea")!;
  (input.props["onUpdate:modelValue"] as (value: string) => void)(
    "仍在等待现场检查",
  );
  await nextTick();
  const close = () => {
    const button = find(
      root,
      (n) => n.tag === "button" && n.props["aria-label"] === "关闭详情",
    )!;
    (button.props.onClick as () => void)();
  };
  confirm.mockReturnValueOnce(false);
  close();
  await nextTick();
  expect(confirm).toHaveBeenCalledTimes(1);
  expect(find(root, (n) => n.tag === "textarea")).toBeDefined();
  expect(mocks.replace).not.toHaveBeenCalled();
  confirm.mockClear();
  close();
  await nextTick();
  expect(confirm).toHaveBeenCalledTimes(1);
  expect(mocks.replace).toHaveBeenCalledWith("/repairs");
  expect(find(root, (n) => n.tag === "dialog")).toBeUndefined();
});
