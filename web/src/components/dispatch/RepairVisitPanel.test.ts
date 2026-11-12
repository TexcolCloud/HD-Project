import { afterEach, expect, it, vi } from "vitest";
import { nextTick, reactive, h } from "vue";
import type { Repair } from "../../api/types";
import type { DispatchBoard, DispatchTask } from "../../api/dispatch";
import RepairVisitPanel from "./RepairVisitPanel.vue";

const mocks = vi.hoisted(() => ({ board: vi.fn(), handoff: vi.fn() }));
vi.mock("../../api/dispatch", () => ({
  getDispatchBoard: mocks.board,
  handoffDispatchTask: mocks.handoff,
  saveRepairVisit: vi.fn(),
}));
vi.mock("../../session", () => ({ session: { user: { id: "manager-1" } } }));
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
} from "../../test/renderer";

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
afterEach(() => {
  unmount?.();
  unmount = undefined;
  vi.clearAllMocks();
});
function mount(value: Repair) {
  const props = reactive({ repair: value });
  const root = node("root");
  const app = renderer.createApp({ render: () => h(RepairVisitPanel, props) });
  app.component("RouterLink", { render: () => null });
  app.mount(root);
  unmount = () => app.unmount();
  return { root, props };
}
const button = (root: Node, label: string) =>
  find(root, (n) => n.tag === "button" && text(n).trim() === label);

it("访问安排仅在待受理、本人已接单和处理中显示，待确认及终态不再提供无效操作", async () => {
  const { root, props } = mount({ ...repair, status: "PENDING_CONFIRMATION" });
  expect(button(root, "设置访问安排")).toBeUndefined();
  for (const status of ["SUBMITTED", "ACCEPTED", "PROCESSING"] as const) {
    props.repair = { ...repair, status };
    await nextTick();
    expect(button(root, "设置访问安排")).toBeDefined();
  }
  props.repair = { ...repair, status: "ACCEPTED", handlerUserId: "manager-2" };
  await nextTick();
  expect(button(root, "设置访问安排")).toBeUndefined();
  for (const status of ["COMPLETED", "CANCELLED", "REJECTED"] as const) {
    props.repair = { ...repair, status };
    await nextTick();
    expect(button(root, "设置访问安排")).toBeUndefined();
  }
});

it("本人已接单的未锁定ASSIGNED或BLOCKED任务可交接，提交保留双版本；在途、现场及锁定任务禁用", async () => {
  const task: DispatchTask = {
    orderId: repair.id,
    orderNo: repair.orderNo,
    title: repair.title,
    description: repair.description,
    buildingId: repair.buildingId,
    buildingName: repair.buildingName,
    roomId: repair.roomId,
    roomNo: repair.roomNo,
    floorNo: 3,
    priority: "NORMAL",
    repairStatus: "ACCEPTED",
    handlerUserId: "manager-1",
    managerUserId: "manager-1",
    state: "BLOCKED",
    sequence: 1,
    locked: false,
    estimatedArrival: null,
    estimatedFinish: null,
    travelMinutes: 0,
    serviceMinutes: 30,
    windowStart: null,
    windowEnd: null,
    windowConfirmed: false,
    version: 7,
    orderVersion: repair.version,
    reason: "等待学生返校",
  };
  const manager: DispatchBoard["me"] = {
    userId: "manager-1",
    displayName: "测试宿管",
    online: true,
    acceptingNewTasks: true,
    buildingIds: [repair.buildingId],
    positionBuildingId: null,
    positionBuildingName: null,
    positionFloorNo: null,
    positionUpdatedAt: null,
    availableUntil: new Date(Date.now() + 3600000).toISOString(),
    version: 1,
  };
  const board: DispatchBoard = {
    revision: 2,
    generatedAt: new Date().toISOString(),
    dirty: false,
    onlineCount: 2,
    participatingCount: 2,
    me: manager,
    routes: [
      {
        manager,
        tasks: [task],
        travelMinutes: 0,
        waitingMinutes: 0,
        workloadMinutes: 30,
      },
      {
        manager: { ...manager, userId: "manager-2", displayName: "接收宿管" },
        tasks: [],
        travelMinutes: 0,
        waitingMinutes: 0,
        workloadMinutes: 0,
      },
    ],
    unassigned: [],
    metrics: {
      totalTasks: 1,
      assignedCount: 1,
      unassignedCount: 0,
      travelMinutes: 0,
      baselineTravelMinutes: 0,
      maxWorkloadMinutes: 30,
    },
    changes: [],
  };
  let lastRoot: Node | undefined;
  for (const [state, locked, allowed] of [
    ["ASSIGNED", false, true],
    ["EN_ROUTE", false, false],
    ["ON_SITE", false, false],
    ["ASSIGNED", true, false],
    ["BLOCKED", true, false],
    ["BLOCKED", false, true],
  ] as const) {
    unmount?.();
    mocks.board.mockResolvedValue({
      ...board,
      routes: [
        { ...board.routes[0]!, tasks: [{ ...task, state, locked }] },
        board.routes[1]!,
      ],
    });
    const { root } = mount({ ...repair, status: "ACCEPTED" });
    lastRoot = root;
    (button(root, "明确交接责任")!.props.onClick as () => void)();
    await vi.waitFor(() =>
      expect(!!button(root, "确认交接办理责任")).toBe(true),
    );
    expect(
      button(root, "确认交接办理责任")!.props.disabled,
      `${state}, locked=${locked}`,
    ).toBe(!allowed);
  }
  const root = lastRoot!;
  const target = find(root, (n) => n.tag === "select")!;
  (target.props["onUpdate:modelValue"] as (value: string) => void)("manager-2");
  const reason = find(root, (n) => n.tag === "textarea")!;
  (reason.props["onUpdate:modelValue"] as (value: string) => void)(
    "临时换班，交由同楼栋宿管继续跟进",
  );
  mocks.handoff.mockResolvedValue({
    ...repair,
    status: "ACCEPTED",
    handlerUserId: "manager-2",
    version: 3,
  });
  const form = find(root, (n) => n.tag === "form")!;
  (form.props.onSubmit as (event: unknown) => void)({
    preventDefault: vi.fn(),
  });
  await vi.waitFor(() =>
    expect(mocks.handoff).toHaveBeenCalledWith(repair.id, {
      targetManagerId: "manager-2",
      reason: "临时换班，交由同楼栋宿管继续跟进",
      expectedOrderVersion: 2,
      expectedTaskVersion: 7,
    }),
  );
  await nextTick();
});
