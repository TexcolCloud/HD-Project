import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { node, renderer, find, text } from "../test/renderer";
import RemediationsPage from "./RemediationsPage.vue";
import type { Model } from "../api/types";
const navigation = vi.hoisted(() => ({ update: vi.fn() }));
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("vue-router", () => ({
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
    confirm: () => true,
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
  const confirm = vi.fn().mockReturnValue(false);
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    confirm,
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
  const guard = navigation.update.mock.calls.at(-1)![0] as () => boolean;
  expect(guard()).toBe(false);
  confirm.mockReturnValue(true);
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
