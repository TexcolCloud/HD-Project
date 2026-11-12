import { afterEach, expect, it, vi } from "vitest";
import { effectScope, nextTick, ref } from "vue";
import type { User } from "../api/types";
import type { DispatchManager } from "../api/dispatch";
import { useManagerPresence } from "./useManagerPresence";

const mocks = vi.hoisted(() => ({ heartbeat: vi.fn() }));
vi.mock("../api/dispatch", () => mocks);
const manager: DispatchManager = {
  userId: "m1",
  displayName: "测试宿管",
  online: true,
  acceptingNewTasks: true,
  buildingIds: ["a"],
  positionBuildingId: null,
  positionBuildingName: null,
  positionFloorNo: null,
  positionUpdatedAt: null,
  availableUntil: null,
  version: 1,
};
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

it("仅宿管前台每30秒心跳，隐藏暂停，恢复补发，登出和卸载停止", async () => {
  vi.useFakeTimers();
  const doc = new EventTarget();
  Object.assign(doc, { hidden: false });
  vi.stubGlobal("document", doc);
  mocks.heartbeat.mockResolvedValue(manager);
  const user = ref<Pick<User, "id" | "role"> | null>({
    id: "s1",
    role: "STUDENT",
  });
  const scope = effectScope();
  scope.run(() => useManagerPresence(() => user.value));
  await vi.advanceTimersByTimeAsync(30_000);
  expect(mocks.heartbeat).not.toHaveBeenCalled();
  user.value = { id: "m1", role: "MANAGER" };
  await nextTick();
  expect(mocks.heartbeat).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(30_000);
  expect(mocks.heartbeat).toHaveBeenCalledTimes(2);
  Object.assign(doc, { hidden: true });
  doc.dispatchEvent(new Event("visibilitychange"));
  await vi.advanceTimersByTimeAsync(90_000);
  expect(mocks.heartbeat).toHaveBeenCalledTimes(2);
  Object.assign(doc, { hidden: false });
  doc.dispatchEvent(new Event("visibilitychange"));
  await nextTick();
  expect(mocks.heartbeat).toHaveBeenCalledTimes(3);
  user.value = null;
  await nextTick();
  await vi.advanceTimersByTimeAsync(60_000);
  expect(mocks.heartbeat).toHaveBeenCalledTimes(3);
  scope.stop();
  doc.dispatchEvent(new Event("visibilitychange"));
  await vi.advanceTimersByTimeAsync(60_000);
  expect(mocks.heartbeat).toHaveBeenCalledTimes(3);
});
