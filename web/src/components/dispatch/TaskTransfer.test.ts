import { afterEach, expect, it, vi } from "vitest";
import { h, nextTick, reactive } from "vue";
import type { Model, Repair } from "../../api/types";
import TaskTransfer from "./TaskTransfer.vue";
import { find, node, renderer, text } from "../../test/renderer";

const mocks = vi.hoisted(() => ({ board: vi.fn(), takeover: vi.fn() }));
vi.mock("../../api/dispatch", () => ({
  getDispatchBoard: mocks.board,
  takeoverDispatchTask: mocks.takeover,
}));
vi.mock("../../session", () => ({ session: { user: { id: "2" } } }));
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelText: {},
}));
const repair: Repair = {
  id: "1",
  orderNo: "QA1",
  reporterStudentId: "1",
  reporterName: "虚构学生",
  roomId: "1",
  roomNo: "302",
  buildingId: "1",
  buildingName: "A栋",
  title: "水龙头滴水",
  description: "虚构测试",
  category: "UTILITIES",
  priority: "NORMAL",
  urgentReason: "",
  contactName: "虚构学生",
  contactPhone: "13800000000",
  contactTimeSlot: "",
  status: "ACCEPTED",
  handlerUserId: "3",
  handlerName: "原办理人",
  relatedOrderId: null,
  submittedAt: "2026-11-12T00:00:00Z",
  completedAt: null,
  version: 2,
  allowedActions: [],
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
const task: Model<"DispatchTask"> = {
  orderId: "1",
  orderNo: "QA1",
  title: repair.title,
  description: repair.description,
  buildingId: "1",
  buildingName: "A栋",
  roomId: "1",
  roomNo: "302",
  floorNo: 3,
  priority: "NORMAL",
  repairStatus: "ACCEPTED",
  handlerUserId: "3",
  managerUserId: "3",
  state: "ASSIGNED",
  sequence: 0,
  locked: false,
  estimatedArrival: null,
  estimatedFinish: null,
  travelMinutes: 0,
  serviceMinutes: 30,
  windowStart: null,
  windowEnd: null,
  windowConfirmed: false,
  version: 4,
  orderVersion: 2,
  reason: "原办理人离线",
};
const manager: Model<"DispatchManager"> = {
  userId: "2",
  displayName: "接续人",
  online: true,
  acceptingNewTasks: true,
  buildingIds: ["1"],
  positionBuildingId: "1",
  positionBuildingName: "A栋",
  positionFloorNo: 1,
  positionUpdatedAt: null,
  availableUntil: "2099-01-01T00:00:00Z",
  version: 1,
};
const board: Model<"DispatchBoard"> = {
  revision: 1,
  generatedAt: "2026-11-12T00:00:00Z",
  dirty: false,
  onlineCount: 1,
  participatingCount: 1,
  me: manager,
  routes: [
    {
      manager,
      tasks: [],
      travelMinutes: 0,
      waitingMinutes: 0,
      workloadMinutes: 0,
    },
  ],
  unassigned: [
    {
      task,
      reasonCode: "OWNER_UNAVAILABLE",
      reason: "原办理人离线",
      candidateCount: 1,
    },
  ],
  metrics: {
    totalTasks: 1,
    assignedCount: 0,
    unassignedCount: 1,
    travelMinutes: 0,
    baselineTravelMinutes: 0,
    maxWorkloadMinutes: 0,
  },
  changes: [],
};
let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  unmount = undefined;
  vi.clearAllMocks();
});
function mount() {
  const props = reactive({ repair: { ...repair } });
  const root = node("root");
  const updated = vi.fn();
  const app = renderer.createApp({
    render: () => h(TaskTransfer, { ...props, onUpdated: updated }),
  });
  app.mount(root);
  unmount = () => app.unmount();
  return { root, props, updated };
}

it("异常接续保留双版本与必填原因，换工单后旧读取不能打开接续表单", async () => {
  let resolveOld!: (value: Model<"DispatchBoard">) => void;
  mocks.board.mockReturnValueOnce(
    new Promise<Model<"DispatchBoard">>((resolve) => {
      resolveOld = resolve;
    }),
  );
  const { root, props } = mount();
  const entry = find(
    root,
    (n) => n.tag === "button" && text(n).trim() === "核对异常接续",
  );
  expect(entry).toBeDefined();
  (entry!.props.onClick as () => void)();
  await nextTick();
  props.repair = { ...repair, id: "2" };
  await nextTick();
  resolveOld(board);
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(find(root, (n) => n.tag === "form")).toBeUndefined();
  expect(mocks.takeover).not.toHaveBeenCalled();
});

it("核对后显式接续且提交失败保留原因，成功才通知父页面", async () => {
  mocks.board.mockResolvedValue(board);
  mocks.takeover.mockRejectedValueOnce(new Error("原办理人仍在失联宽限内"));
  const { root, updated } = mount();
  (
    find(root, (n) => n.tag === "button" && text(n).trim() === "核对异常接续")!
      .props.onClick as () => void
  )();
  await new Promise((resolve) => setTimeout(resolve, 0));
  const field = find(root, (n) => n.tag === "textarea")!;
  (field.props["onUpdate:modelValue"] as (value: string) => void)(
    "原办理人已停用，核对后接续",
  );
  await nextTick();
  const submit = find(root, (n) => n.tag === "form")!.props
    .onSubmit as (event: { preventDefault(): void }) => Promise<void>;
  await submit({ preventDefault() {} });
  expect(mocks.takeover).toHaveBeenCalledWith("1", {
    expectedOrderVersion: 2,
    expectedTaskVersion: 4,
    reason: "原办理人已停用，核对后接续",
  });
  expect(text(root)).toContain("失联宽限");
  expect(updated).not.toHaveBeenCalled();
  const result = { ...repair, handlerUserId: "2", version: 3 };
  mocks.takeover.mockResolvedValueOnce(result);
  await submit({ preventDefault() {} });
  expect(updated).toHaveBeenCalledWith(result);
});
