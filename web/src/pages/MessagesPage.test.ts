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
  (button("标记已读")!.props.onClick as () => void)();
  await vi.waitFor(() =>
    expect(api.post).toHaveBeenCalledWith("/messages/1/read", {
      expectedVersion: 2,
    }),
  );
  await vi.waitFor(() => expect(text(root)).toContain("已读于"));
  expect(button("标记已读")).toBeUndefined();
});
