import { afterEach, expect, it, vi } from "vitest";
import { h, nextTick, reactive } from "vue";
import type { Model } from "../../api/types";
import MobilityEfficiencyPanel from "./MobilityEfficiencyPanel.vue";
import { node, renderer, text } from "../../test/renderer";
const mocks = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock("../../api/client", () => ({
  get: mocks.get,
  ApiError: class extends Error {},
}));
const empty: Model<"MobilityEfficiency"> = {
  sampleCount: 0,
  measuredCount: 0,
  meanAbsoluteErrorMinutes: null,
  predictedMinutes: null,
  actualMinutes: null,
  personalModelCount: 0,
  explanation: "本人近30天确认片段；预测误差不等同节省",
};
let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  unmount = undefined;
  vi.clearAllMocks();
});
function mount() {
  const props = reactive({ refreshKey: 0 });
  const root = node("root");
  const app = renderer.createApp({
    render: () => h(MobilityEfficiencyPanel, props),
  });
  app.mount(root);
  unmount = () => app.unmount();
  return { props, root };
}
it("无有效样本时明确未知误差，不显示零误差或节省比例", async () => {
  mocks.get.mockResolvedValue(empty);
  const { root } = mount();
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(text(root)).toContain("本人的通行校准");
  expect(text(root)).toContain("暂无可比较样本");
  expect(text(root)).not.toContain("0.0 分钟");
});
it("刷新后只接受最新效率统计，迟到结果不能覆盖当前数据", async () => {
  let resolveOld!: (value: Model<"MobilityEfficiency">) => void;
  mocks.get.mockReturnValueOnce(
    new Promise<Model<"MobilityEfficiency">>((resolve) => {
      resolveOld = resolve;
    }),
  );
  const { root, props } = mount();
  props.refreshKey++;
  mocks.get.mockResolvedValueOnce({
    ...empty,
    sampleCount: 6,
    measuredCount: 6,
    meanAbsoluteErrorMinutes: 1.25,
    predictedMinutes: 30,
    actualMinutes: 32,
    personalModelCount: 1,
  });
  await nextTick();
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(text(root)).toContain("1.3 分钟");
  resolveOld(empty);
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(text(root)).toContain("1.3 分钟");
  expect(text(root)).not.toContain("暂无可比较样本");
});
