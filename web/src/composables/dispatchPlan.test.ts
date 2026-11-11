import { expect, it } from "vitest";
import type { DispatchBoard, DispatchTask } from "../api/dispatch";
import {
  selectBoardVersion,
  describeDispatchChanges,
  currentDispatchTask,
  changedDispatchOrders,
} from "./dispatchPlan";
import { dispatchPositionFields } from "./dispatchPlan";

it("只修改接单时段不重新确认位置，未知楼层必须由本人补充", () => {
  expect(dispatchPositionFields("b", "", false)).toEqual({});
  expect(() => dispatchPositionFields("b", "", true)).toThrow("请确认当前楼层");
  expect(dispatchPositionFields("b", 3, true)).toEqual({
    positionBuildingId: "b",
    positionFloorNo: 3,
  });
  expect(dispatchPositionFields("", "", true)).toEqual({
    positionBuildingId: null,
    positionFloorNo: null,
  });
});

it("较旧计划迟到时保留当前新版本与路线变更说明", () => {
  const current: DispatchBoard = {
    revision: 8,
    generatedAt: "2026-11-07T04:00:00Z",
    dirty: false,
    onlineCount: 1,
    participatingCount: 1,
    me: {
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
      version: 2,
    },
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
    changes: ["新增任务已插入剩余路线"],
  };
  const late = { ...current, revision: 7, changes: ["旧计划"] };
  expect(selectBoardVersion(current, late)).toBe(current);
  expect(
    selectBoardVersion(current, { ...current, revision: 9 }).revision,
  ).toBe(9);
  const task: DispatchTask = {
    orderId: "order-1",
    orderNo: "TEST-1",
    title: "测试报修",
    description: "测试故障",
    buildingId: "a",
    buildingName: "A栋",
    roomId: "room-1",
    roomNo: "302",
    floorNo: 3,
    priority: "NORMAL",
    repairStatus: "SUBMITTED",
    handlerUserId: null,
    managerUserId: "m1",
    state: "RECOMMENDED",
    sequence: 2,
    locked: false,
    estimatedArrival: "2026-11-07T05:00:00Z",
    estimatedFinish: "2026-11-07T05:30:00Z",
    travelMinutes: 5,
    serviceMinutes: 30,
    windowStart: null,
    windowEnd: null,
    windowConfirmed: false,
    version: 0,
    orderVersion: 0,
    reason: "",
  };
  const before = {
    ...current,
    routes: [
      {
        manager: current.me,
        tasks: [task],
        travelMinutes: 5,
        waitingMinutes: 0,
        workloadMinutes: 35,
      },
    ],
  };
  const after = {
    ...before,
    revision: 9,
    routes: [
      {
        ...before.routes[0]!,
        tasks: [
          { ...task, sequence: 1, estimatedArrival: "2026-11-07T04:45:00Z" },
        ],
      },
    ],
  };
  expect(describeDispatchChanges(before, after)).toEqual([
    expect.objectContaining({
      orderId: "order-1",
      detail: expect.stringContaining("第2站 → 第1站"),
    }),
  ]);
  // A blocked repair may still be PROCESSING; after resume it must return to the next-stop queue.
  const resumed: DispatchTask = {
    ...task,
    repairStatus: "PROCESSING",
    state: "ASSIGNED",
  };
  expect(currentDispatchTask([resumed])).toBeUndefined();
  const travelling: DispatchTask = {
    ...task,
    orderId: "order-2",
    state: "EN_ROUTE",
  };
  expect(currentDispatchTask([resumed, travelling])).toBe(travelling);
});

it("同一分钟内的预计到达变化不标调整，跨分钟及空时间变化仍保留", () => {
  const task: DispatchTask = {
    orderId: "order-1",
    orderNo: "TEST-1",
    title: "测试报修",
    description: "测试故障",
    buildingId: "a",
    buildingName: "A栋",
    roomId: "room-1",
    roomNo: "302",
    floorNo: 3,
    priority: "NORMAL",
    repairStatus: "SUBMITTED",
    handlerUserId: null,
    managerUserId: "m1",
    state: "RECOMMENDED",
    sequence: 1,
    locked: false,
    estimatedArrival: "2026-11-07T05:00:01.100Z",
    estimatedFinish: null,
    travelMinutes: 5,
    serviceMinutes: 30,
    windowStart: null,
    windowEnd: null,
    windowConfirmed: false,
    version: 0,
    orderVersion: 0,
    reason: "",
  };
  const manager: DispatchBoard["me"] = {
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
    version: 2,
  };
  const board = (
    revision: number,
    estimatedArrival: string | null,
  ): DispatchBoard => ({
    revision,
    generatedAt: "2026-11-07T04:00:00Z",
    dirty: false,
    onlineCount: 1,
    participatingCount: 1,
    me: manager,
    routes: [
      {
        manager,
        tasks: [{ ...task, estimatedArrival }],
        travelMinutes: 5,
        waitingMinutes: 0,
        workloadMinutes: 35,
      },
    ],
    unassigned: [],
    metrics: {
      totalTasks: 1,
      assignedCount: 1,
      unassignedCount: 0,
      travelMinutes: 5,
      baselineTravelMinutes: 5,
      maxWorkloadMinutes: 35,
    },
    changes: [],
  });
  const before = board(1, task.estimatedArrival),
    secondsOnly = board(2, "2026-11-07T05:00:59.999Z");
  expect(describeDispatchChanges(before, secondsOnly)).toEqual([]);
  expect(changedDispatchOrders(before, secondsOnly)).toEqual(new Set());
  expect(
    describeDispatchChanges(before, board(2, "2026-11-07T05:01:00Z")),
  ).toHaveLength(1);
  expect(describeDispatchChanges(before, board(2, null))).toHaveLength(1);
  expect(describeDispatchChanges(board(1, null), secondsOnly)).toHaveLength(1);
});
