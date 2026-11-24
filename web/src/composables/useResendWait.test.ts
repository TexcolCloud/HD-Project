import { effectScope } from "vue";
import { afterEach, expect, it, vi } from "vitest";
import { useResendWait } from "./useResendWait";
afterEach(() => vi.useRealTimers());
it("旧号与新号从各自发送成功时独立计时，页面离开后清理定时器", () => {
  vi.useFakeTimers();
  const scope = effectScope();
  const [old, next] = scope.run(
    () => [useResendWait(), useResendWait()] as const,
  )!;
  old.start();
  vi.advanceTimersByTime(29000);
  expect(old.seconds.value).toBe(31);
  expect(next.seconds.value).toBe(0);
  next.start();
  vi.advanceTimersByTime(31000);
  expect(old.seconds.value).toBe(0);
  expect(next.seconds.value).toBe(29);
  scope.stop();
  expect(vi.getTimerCount()).toBe(0);
  next.start();
  expect(vi.getTimerCount()).toBe(0);
});
