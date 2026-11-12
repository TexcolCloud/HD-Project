import { afterEach, expect, it, vi } from "vitest";
import { effectScope, nextTick, ref } from "vue";
import type { User } from "../api/types";
import { useInboxBadge } from "./useInboxBadge";
const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock("../api/client", () => api);
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

it("换号后旧消息数量不回填，页面隐藏及卸载时停止轮询", async () => {
  vi.useFakeTimers();
  const document = Object.assign(new EventTarget(), { hidden: false });
  vi.stubGlobal("document", document);
  let finish: (value: { unreadCount: number }) => void = () => {};
  api.get
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValue({ unreadCount: 2 });
  const user = ref<Pick<User, "id" | "role" | "mustChangePassword"> | null>({
    id: "A",
    role: "MANAGER",
  });
  const scope = effectScope();
  const badge = scope.run(() => useInboxBadge(() => user.value))!;
  user.value = { id: "B", role: "MANAGER" };
  await nextTick();
  await nextTick();
  finish({ unreadCount: 99 });
  await nextTick();
  expect(badge.value).toBe(2);
  document.hidden = true;
  document.dispatchEvent(new Event("visibilitychange"));
  const calls = api.get.mock.calls.length;
  await vi.advanceTimersByTimeAsync(90000);
  expect(api.get).toHaveBeenCalledTimes(calls);
  scope.stop();
});
