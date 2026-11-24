import { afterEach, expect, it, vi } from "vitest";
import { effectScope, nextTick, ref } from "vue";
import type { DispatchBoard, DispatchManager } from "../api/dispatch";
import type { User } from "../api/types";
import { useManagerPresence } from "../composables/useManagerPresence";
import { find, node, renderer, text } from "../test/renderer";
import { useDispatchBoard } from "../composables/useDispatchBoard";
import DispatchPage from "./DispatchPage.vue";

const api = vi.hoisted(() => ({
  getDispatchBoard: vi.fn(),
  getDispatchBuildings: vi.fn(),
  heartbeat: vi.fn(),
}));
vi.mock("../api/dispatch", () => api);

const manager: DispatchManager = {
  userId: "manager-1",
  displayName: "演示宿管",
  online: false,
  acceptingNewTasks: true,
  buildingIds: ["building-1"],
  positionBuildingId: null,
  positionBuildingName: null,
  positionFloorNo: null,
  positionUpdatedAt: null,
  availableUntil: null,
  version: 1,
};
const board: DispatchBoard = {
  revision: 1,
  generatedAt: "2026-11-18T08:00:00+08:00",
  dirty: false,
  onlineCount: 0,
  participatingCount: 0,
  me: manager,
  routes: [],
  unassigned: [],
  metrics: {
    totalTasks: 0,
    assignedCount: 0,
    unassignedCount: 0,
    travelMinutes: 0,
    baselineTravelMinutes: 0,
    maxWorkloadMinutes: 0,
  },
  changes: [],
};
let cleanup: (() => void) | undefined;
afterEach(() => {
  cleanup?.();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

it("等待心跳响应时显示正在连接，收到响应才显示在线", async () => {
  const documentEvents = new EventTarget();
  vi.stubGlobal("document", Object.assign(documentEvents, { hidden: false }));
  let reply!: (manager: DispatchManager) => void;
  api.heartbeat.mockImplementation(
    () =>
      new Promise<DispatchManager>((resolve) => {
        reply = resolve;
      }),
  );
  api.getDispatchBoard.mockResolvedValue(board);
  api.getDispatchBuildings.mockResolvedValue([]);
  const root = node("root");
  const app = renderer.createApp(DispatchPage);
  app.component("RouterLink", { template: "<a><slot /></a>" });
  const scope = effectScope();
  const user = ref<Pick<User, "id" | "role"> | null>({
    id: manager.userId,
    role: "MANAGER",
  });
  scope.run(() => useManagerPresence(() => user.value));
  app.mount(root);
  cleanup = () => {
    app.unmount();
    scope.stop();
  };

  const status = () => {
    const area = find(root, (el) => el.props.class === "dispatch-presence");
    const label = area && find(area, (el) => el.tag === "p");
    return label && text(label).trim();
  };
  await vi.waitFor(() => expect(status()).toBeDefined());
  expect(status()).toBe("正在连接…");
  reply({ ...manager, online: true });
  await vi.waitFor(() => expect(status()).toBe("在线"));
  user.value = null;
  await nextTick();
  expect(status()).toBe("尚未连接");
});

it("后台轮询保持按钮可用，写入后的主动刷新优先于迟到的轮询", async () => {
  vi.useFakeTimers();
  const scope = effectScope();
  try {
    vi.stubGlobal(
      "document",
      Object.assign(new EventTarget(), { hidden: false }),
    );
    let complete!: (result: DispatchBoard) => void;
    api.getDispatchBoard
      .mockReset()
      .mockResolvedValueOnce(board)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            complete = resolve;
          }),
      )
      .mockResolvedValueOnce({ ...board, revision: 3 });
    const state = scope.run(() => useDispatchBoard())!;
    expect(state.busy.value).toBe(true);
    await vi.advanceTimersByTimeAsync(5000);
    expect(state.busy.value).toBe(false);
    expect(api.getDispatchBoard).toHaveBeenCalledTimes(2);
    const current = state.refresh();
    expect(state.busy.value).toBe(true);
    await current;
    complete({ ...board, revision: 2 });
    await nextTick();
    expect(state.board.value?.revision).toBe(3);
  } finally {
    scope.stop();
    vi.useRealTimers();
  }
});
