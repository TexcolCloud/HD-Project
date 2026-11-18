import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import type { Hygiene } from "../api/types";
import { find, node, renderer, text, type TestNode } from "../test/renderer";
import HygienePage from "./HygienePage.vue";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("vue-router", () => ({
  onBeforeRouteLeave: vi.fn(),
  useRoute: () => ({ query: {} }),
  useRouter: () => ({}),
}));
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelText: {},
  vModelSelect: {},
}));
vi.mock("../components/LookupSelect.vue", async () => {
  const { h } = await import("vue");
  return {
    default: {
      emits: ["update:modelValue"],
      setup(
        _props: unknown,
        context: { emit: (name: string, value: string) => void },
      ) {
        return () =>
          h(
            "button",
            { onClick: () => context.emit("update:modelValue", "room-1") },
            "选择101",
          );
      },
    },
  };
});

const previous: Hygiene = {
  id: "inspection-1",
  roomId: "room-1",
  roomNo: "101",
  buildingId: "building-1",
  buildingName: "一号楼",
  inspectionDate: "2026-10-30",
  round: "ROUTINE",
  inspectedAt: "2026-10-30T10:00:00+08:00",
  floorScore: 15,
  deskScore: 16,
  bedScore: 17,
  bathroomScore: 18,
  totalScore: 66,
  grade: "PASS",
  issueNote: "旧检查的记录",
  remediationNote: "",
  inspectorName: "演示宿管",
  relatedInspectionId: null,
  voidedAt: null,
  version: 1,
  images: [],
};
let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

function button(root: TestNode, label: string) {
  return find(
    root,
    (candidate) =>
      candidate.tag === "button" && text(candidate).trim() === label,
  );
}
function click(root: TestNode, label: string) {
  (button(root, label)!.props.onClick as () => void)();
}
function updateField(
  root: TestNode,
  field: TestNode | undefined,
  value: string,
) {
  if (!field) throw new Error(text(root));
  (field.props["onUpdate:modelValue"] as (value: string) => void)(value);
}
async function openForm() {
  vi.stubGlobal("window", {
    confirm: () => true,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  const root = node("root");
  const app = renderer.createApp(HygienePage);
  app.component("RouterLink", { template: "<a><slot /></a>" });
  app.mount(root);
  unmount = () => app.unmount();
  click(root, "录入卫生评分");
  await nextTick();
  click(root, "选择101");
  const dialog = find(root, (candidate) => candidate.tag === "dialog")!;
  return { root, dialog };
}

it("先查同轮，再展示此前评分，明确选择后只带入分数", async () => {
  api.get.mockImplementation(
    async (_path: string, query: { endDate?: string }) => ({
      items: query.endDate ? [previous] : [],
      total: query.endDate ? 1 : 0,
    }),
  );
  const { dialog } = await openForm();
  await vi.waitFor(() => expect(text(dialog)).toContain("最近一次巡检"));
  expect(api.get).toHaveBeenCalledWith(
    "/hygiene-inspections",
    expect.objectContaining({
      roomId: "room-1",
      round: "ROUTINE",
      inspectionDate: expect.any(String),
      pageSize: 1,
    }),
    expect.any(AbortSignal),
  );
  expect(text(dialog)).toContain("2026-10-30");
  expect(text(dialog)).toContain("66");
  expect(text(dialog)).toContain("本次总分 / 优秀");
  click(dialog, "带入四项分数");
  await nextTick();
  expect(text(dialog)).toContain("本次总分 / 合格");
  expect(text(dialog)).not.toContain(previous.issueNote);
  expect(api.post).not.toHaveBeenCalled();
});

it("同轮记录进入带原版本和四项分数的更正流程", async () => {
  api.get.mockImplementation(
    async (_path: string, query: { inspectionDate?: string }) => ({
      items: query.inspectionDate ? [previous] : [],
      total: query.inspectionDate ? 1 : 0,
    }),
  );
  api.patch.mockResolvedValue(previous);
  const { dialog } = await openForm();
  await vi.waitFor(() => expect(text(dialog)).toContain("已有同轮评分"));
  expect(button(dialog, "保存卫生评分")?.props.disabled).toBe(true);
  click(dialog, "更正评分");
  await nextTick();
  expect(text(dialog)).toContain("更正卫生评分");
  const notes = find(
    dialog,
    (candidate) =>
      candidate.tag === "label" && text(candidate).includes("更正原因"),
  );
  updateField(
    dialog,
    find(notes!, (candidate) => candidate.tag === "textarea"),
    "核对记录并更正",
  );
  const form = find(dialog, (candidate) => candidate.tag === "form")!;
  (form.props.onSubmit as (event: { preventDefault: () => void }) => void)({
    preventDefault() {},
  });
  await vi.waitFor(() =>
    expect(api.patch).toHaveBeenCalledWith(
      "/hygiene-inspections/inspection-1",
      expect.objectContaining({
        expectedVersion: 1,
        floorScore: 15,
        deskScore: 16,
        bedScore: 17,
        bathroomScore: 18,
        inspectedAt: previous.inspectedAt,
        correctionReason: "核对记录并更正",
      }),
    ),
  );
  expect(api.post).not.toHaveBeenCalled();
});
