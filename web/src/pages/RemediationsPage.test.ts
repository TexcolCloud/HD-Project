import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { node, renderer, find, text } from "../test/renderer";
import RemediationsPage from "./RemediationsPage.vue";
import type { Model } from "../api/types";
const navigation = vi.hoisted(() => ({ update: vi.fn() }));
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("vue-router", () => ({
  useRouter: () => ({ replace: vi.fn() }),
  useRoute: () => ({ query: {} }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: navigation.update,
}));
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelCheckbox: {},
  vModelSelect: {},
  vModelText: {},
}));
const task: Model<"Remediation"> = {
  id: "8",
  inspectionId: "5",
  roomId: "1",
  roomNo: "302",
  buildingId: "1",
  buildingName: "A栋",
  responsibleStudentId: "1",
  responsibleName: "演示学生",
  issueNote: "整理桌面",
  dueAt: "2026-11-13T02:00:00Z",
  status: "SUBMITTED",
  feedbackNote: "已整理",
  reviewNote: "",
  relatedRecheckId: null,
  createdAt: "2026-11-12T01:00:00Z",
  submittedAt: "2026-11-12T02:00:00Z",
  closedAt: null,
  version: 2,
  overdue: false,
  evidenceImages: [],
};
let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

it("整改提交反馈后开放复核；关闭详情会丢弃晚到的旧任务读取", async () => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });

  let reads = 0,
    resolveOld!: (value: Model<"Remediation">) => void;
  const old = new Promise<Model<"Remediation">>((resolve) => {
    resolveOld = resolve;
  });
  api.get.mockImplementation(async (path: string) =>
    path === "/hygiene-remediations/8"
      ? ++reads === 1
        ? task
        : old
      : path === "/hygiene-remediations"
        ? { items: [task], total: 1, page: 1, pageSize: 20 }
        : { items: [], total: 0, page: 1, pageSize: 100 },
  );
  const root = node("root"),
    app = renderer.createApp(RemediationsPage);
  app.mount(root);
  unmount = () => app.unmount();
  const button = (label: string) =>
    find(
      root,
      (candidate) =>
        candidate.tag === "button" && text(candidate).trim() === label,
    );
  await vi.waitFor(() => expect(button("查看整改")).toBeDefined());
  (button("查看整改")!.props.onClick as () => void)();
  await vi.waitFor(() => expect(button("复核学生反馈")).toBeDefined());
  (
    find(root, (candidate) => candidate.props["aria-label"] === "关闭详情")!
      .props.onClick as () => void
  )();
  await nextTick();
  (button("查看整改")!.props.onClick as () => void)();
  await nextTick();
  (
    find(root, (candidate) => candidate.props["aria-label"] === "关闭详情")!
      .props.onClick as () => void
  )();
  await nextTick();
  resolveOld(task);
  await old;
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
  expect(find(root, (candidate) => candidate.tag === "dialog")).toBeUndefined();
});

it("提交整改处置时阻止同页面深链接切换，未保存原因需确认后才能切换任务", async () => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  api.get.mockImplementation(async (path: string) =>
    path === "/hygiene-remediations/8"
      ? task
      : path === "/hygiene-remediations"
        ? { items: [task], total: 1, page: 1, pageSize: 20 }
        : { items: [], total: 0, page: 1, pageSize: 100 },
  );
  let resolveSave!: (value: Model<"Remediation">) => void;
  api.post.mockReturnValue(
    new Promise<Model<"Remediation">>((resolve) => {
      resolveSave = resolve;
    }),
  );
  const root = node("root"),
    app = renderer.createApp(RemediationsPage);
  app.mount(root);
  unmount = () => app.unmount();
  const button = (label: string) =>
    find(
      root,
      (candidate) =>
        candidate.tag === "button" && text(candidate).trim() === label,
    );
  await vi.waitFor(() => expect(button("查看整改")).toBeDefined());
  (button("查看整改")!.props.onClick as () => void)();
  await vi.waitFor(() => expect(button("取消整改")).toBeDefined());
  (button("取消整改")!.props.onClick as () => void)();
  await nextTick();
  expect(navigation.update).toHaveBeenCalled();
  const guard = navigation.update.mock.calls.at(-1)![0] as () =>
    | boolean
    | Promise<boolean>;
  for (const [choice, accept] of [
    ["继续编辑", false],
    ["放弃并切换", true],
  ] as const) {
    const answer = guard();
    await nextTick();
    (button(choice)!.props.onClick as () => void)();
    expect(await answer).toBe(accept);
  }
  const form = find(
    root,
    (candidate) =>
      candidate.tag === "form" && text(candidate).includes("取消整改任务"),
  );
  expect(form).toBeDefined();
  const pending = (
    form!.props.onSubmit as (event: { preventDefault(): void }) => Promise<void>
  )({ preventDefault() {} });
  await nextTick();
  expect(guard()).toBe(false);
  resolveSave({ ...task, status: "CANCELLED", version: 3 });
  await pending;
  await nextTick();
  expect(guard()).toBe(true);
});

it("空白复核可直接关闭；内联合格复查保存后，验收重试复用其 ID", async () => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  api.get.mockImplementation(async (path: string) =>
    path.endsWith("/8")
      ? task
      : { items: path === "/hygiene-remediations" ? [task] : [], total: 1 },
  );
  api.post
    .mockReset()
    .mockResolvedValueOnce({
      id: "check-2",
      round: "RECHECK",
      relatedInspectionId: task.inspectionId,
      totalScore: 80,
    })
    .mockRejectedValueOnce(new Error("连接失败"))
    .mockResolvedValueOnce({ ...task, status: "CLOSED" });
  const root = node("root"),
    app = renderer.createApp(RemediationsPage);
  app.mount(root);
  unmount = () => app.unmount();
  const click = async (label: string) => {
    const button = () =>
      find(root, (el) => el.tag === "button" && text(el).trim() === label);
    await vi.waitFor(() => expect(button()).toBeDefined());
    (button()!.props.onClick as () => void)();
    await nextTick();
  };
  await click("查看整改");
  await click("复核学生反馈");
  (
    find(root, (el) => el.props["aria-label"] === "关闭详情")!.props
      .onClick as () => void
  )();
  await nextTick();
  expect(find(root, (n) => n.tag === "dialog")).toBeUndefined();
  await click("查看整改");
  await click("复核学生反馈");
  await click("当场录入合格复查");
  const form = find(
    root,
    (el) => el.tag === "form" && text(el).includes("保存并通知相关人员"),
  )!;
  const submit = () =>
    (form.props.onSubmit as (e: { preventDefault(): void }) => Promise<void>)({
      preventDefault() {},
    });
  await submit();
  expect(api.post).not.toHaveBeenCalled();
  for (const label of ["地面", "桌面", "床铺", "卫生间"]) {
    const field = find(
      form,
      (el) => el.tag === "label" && text(el).startsWith(label),
    )!;
    (
      find(field, (el) => el.tag === "input")!.props["onUpdate:modelValue"] as (
        value: number,
      ) => void
    )(20);
  }
  (
    find(form, (el) => el.tag === "textarea")!.props["onUpdate:modelValue"] as (
      value: string,
    ) => void
  )("现场已合格");
  await submit();
  expect(text(root)).toContain("复查已保存并选中");
  await submit();
  expect(api.post.mock.calls.map(([path]) => path)).toEqual([
    "/hygiene-inspections",
    "/hygiene-remediations/8/review",
    "/hygiene-remediations/8/review",
  ]);
  expect(api.post.mock.calls[0]?.[1]).toMatchObject({
    roomId: task.roomId,
    relatedInspectionId: task.inspectionId,
    round: "RECHECK",
    floorScore: 20,
  });
  expect(api.post.mock.calls[2]?.[1]).toMatchObject({
    expectedVersion: 2,
    relatedRecheckId: "check-2",
    approved: true,
    reviewNote: "现场已合格",
  });
});
