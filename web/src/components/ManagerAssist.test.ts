import { afterEach, expect, it, vi } from "vitest";
import { nextTick, reactive } from "vue";
import ManagerAssist from "./ManagerAssist.vue";
import { find, node, renderer, text } from "../test/renderer";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("../api/managerAssist", () => ({ generateManagerAdvice: vi.fn() }));
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelText: {},
  vModelSelect: {},
  vModelCheckbox: {},
}));
let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  vi.clearAllMocks();
});

it("展开工单研判可读取历史反馈，换工单后旧记录不能覆盖新上下文", async () => {
  let resolveOld!: (value: unknown) => void;
  const old = new Promise((resolve) => {
    resolveOld = resolve;
  });
  api.get.mockImplementation((path: string) =>
    path.includes("/A/")
      ? old
      : Promise.resolve({
          items: [
            {
              id: "2",
              orderId: "B",
              suggestionId: "20",
              decision: "ADOPTED",
              reason: "B的新记录",
              outcome: "",
              operatorName: "QA",
              createdAt: "2026-11-12T01:00:00Z",
              canFollowUp: true,
            },
          ],
        }),
  );
  const props = reactive({ repairId: "A" });
  const root = node("root");
  // Mount with reactive props through the same public component interface as a repair detail.
  const { h } = await import("vue");
  const mounted = renderer.createApp({ render: () => h(ManagerAssist, props) });
  mounted.mount(root);
  unmount = () => mounted.unmount();
  const expand = () =>
    find(root, (n) => n.tag === "button" && text(n).trim() === "展开")!;
  (expand().props.onClick as () => void)();
  await vi.waitFor(() =>
    expect(api.get).toHaveBeenCalledWith(
      "/ai/manager/repairs/A/feedback",
      {},
      expect.anything(),
    ),
  );
  props.repairId = "B";
  await nextTick();
  (expand().props.onClick as () => void)();
  await vi.waitFor(() => expect(text(root)).toContain("B的新记录"));
  resolveOld({
    items: [
      {
        id: "1",
        orderId: "A",
        suggestionId: "10",
        decision: "IGNORED",
        reason: "A的旧记录",
        outcome: "",
        operatorName: "QA",
        createdAt: "2026-11-12T01:00:00Z",
        canFollowUp: true,
      },
    ],
  });
  await old;
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
  expect(text(root)).not.toContain("A的旧记录");
});

it("可对本人历史建议追加结果，换工单后的旧提交不能刷新新上下文", async () => {
  api.get.mockImplementation(async (path: string) => ({
    items: path.includes("/A/")
      ? [
          {
            id: "1",
            orderId: "A",
            suggestionId: "10",
            decision: "MODIFIED",
            reason: "原判断原因",
            outcome: "已联系学生",
            operatorName: "QA",
            createdAt: "2026-11-12T01:00:00Z",
            canFollowUp: true,
          },
        ]
      : [],
  }));
  let finish!: (value: unknown) => void;
  const pending = new Promise((resolve) => {
    finish = resolve;
  });
  api.post.mockReturnValue(pending);
  const props = reactive({ repairId: "A" });
  const { h } = await import("vue");
  const root = node("root");
  const app = renderer.createApp({ render: () => h(ManagerAssist, props) });
  app.mount(root);
  unmount = () => app.unmount();
  const button = (label: string) =>
    find(root, (n) => n.tag === "button" && text(n).trim() === label)!;
  (button("展开").props.onClick as () => void)();
  await vi.waitFor(() => expect(button("补充办理结果")).toBeDefined());
  (button("补充办理结果").props.onClick as () => void)();
  await nextTick();
  const areas: ReturnType<typeof node>[] = [];
  const walk = (n: ReturnType<typeof node>) => {
    if (n.tag === "textarea") areas.push(n);
    n.children.forEach(walk);
  };
  walk(root);
  (areas[1].props["onUpdate:modelValue"] as (value: string) => void)(
    "最终人工核查已解决",
  );
  const form = find(
    root,
    (n) => n.tag === "form" && text(n).includes("判断原因"),
  )!;
  (form.props.onSubmit as (event: Event) => void)({
    preventDefault() {},
  } as Event);
  await vi.waitFor(() =>
    expect(api.post).toHaveBeenCalledWith("/ai/manager/repairs/A/feedback", {
      suggestionId: "10",
      decision: "MODIFIED",
      reason: "原判断原因",
      outcome: "最终人工核查已解决",
    }),
  );
  props.repairId = "B";
  await nextTick();
  (button("展开").props.onClick as () => void)();
  await vi.waitFor(() => expect(text(root)).toContain("暂无反馈记录"));
  const reads = api.get.mock.calls.length;
  finish({ id: "2" });
  await pending;
  await nextTick();
  expect(api.get.mock.calls).toHaveLength(reads);
  expect(text(root)).not.toContain("最终人工核查已解决");
});
