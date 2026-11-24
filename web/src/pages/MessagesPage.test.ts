import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { node, renderer, find, text } from "../test/renderer";
import MessagesPage from "./MessagesPage.vue";
import type { Model } from "../api/types";
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("vue-router", () => ({ onBeforeRouteLeave: vi.fn() }));
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelCheckbox: {},
}));
let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

it("消息已读操作使用当前版本，并展示后端确认结果", async () => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  const message: Model<"ServiceMessage"> = {
    id: "1",
    kind: "REPAIR_CHANGED",
    title: "办理进度已更新",
    body: "请查看相关工单",
    businessType: "REPAIR",
    businessId: "23",
    createdAt: "2026-11-12T01:00:00Z",
    readAt: null,
    version: 2,
  };
  api.get.mockImplementation(async (path: string) =>
    path.endsWith("summary")
      ? { unreadCount: 1, deliveryPending: 0, deliveryFailed: 0 }
      : { items: [message], total: 1, page: 1, pageSize: 20 },
  );
  api.post.mockResolvedValue({
    ...message,
    version: 3,
    readAt: "2026-11-12T02:00:00Z",
  });
  const root = node("root"),
    app = renderer.createApp(MessagesPage);
  app.mount(root);
  unmount = () => app.unmount();
  const button = (label: string) =>
    find(
      root,
      (candidate) =>
        candidate.tag === "button" && text(candidate).trim() === label,
    );
  await vi.waitFor(() => expect(button("查看消息")).toBeDefined());
  (button("查看消息")!.props.onClick as () => void)();
  await nextTick();
  await vi.waitFor(() =>
    expect(api.post).toHaveBeenCalledWith("/messages/1/read", {
      expectedVersion: 2,
    }),
  );
  await vi.waitFor(() => expect(text(root)).toContain("已读于"));
  expect(button("标记已读")).toBeUndefined();
});

it("本页批量已读跳过已读记录，按版本写入后仅刷新一次", async () => {
  const items = [
    { id: "a", title: "一", body: "正文", readAt: null, version: 1 },
    {
      id: "b",
      title: "二",
      body: "正文",
      readAt: "2026-11-12T01:00Z",
      version: 3,
    },
    { id: "c", title: "三", body: "正文", readAt: null, version: 2 },
  ].map((m) => ({ ...m, createdAt: "2026-11-12T01:00Z" }));
  api.get.mockImplementation(async (path) =>
    path.endsWith("summary") ? { unreadCount: 2 } : { items, total: 3 },
  );
  api.post.mockImplementation(async (path) => ({
    ...items.find((m) => path.includes("/" + m.id + "/")),
    readAt: "2026-11-12T02:00Z",
  }));
  const root = node("root"),
    app = renderer.createApp(MessagesPage);
  app.mount(root);
  unmount = () => app.unmount();
  const batch = () =>
    find(
      root,
      (n) => n.tag === "button" && text(n).trim() === "本页未读标为已读",
    );
  await vi.waitFor(() => expect(batch()?.props.disabled).toBe(false));
  await (batch()!.props.onClick as () => Promise<void>)();
  expect(api.post.mock.calls).toEqual([
    ["/messages/a/read", { expectedVersion: 1 }],
    ["/messages/c/read", { expectedVersion: 2 }],
  ]);
  expect(api.get.mock.calls.filter(([p]) => p === "/messages")).toHaveLength(2);
});
