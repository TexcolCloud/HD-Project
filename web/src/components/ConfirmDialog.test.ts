import { nextTick } from "vue";
import { describe, it, expect, vi, afterEach } from "vitest";
import { onBeforeRouteLeave } from "vue-router";
import { askConfirm } from "../composables/useConfirm";
import ConfirmDialog from "./ConfirmDialog.vue";
import Drawer from "./Drawer.vue";
import { renderer, node, find, text } from "../test/renderer";
vi.mock("vue-router", () => ({ onBeforeRouteLeave: vi.fn() }));
afterEach(() => vi.unstubAllGlobals());
const options = {
  title: "放弃记录？",
  message: "录入尚未保存。",
  confirmText: "放弃记录",
  cancelText: "继续录入",
  danger: true,
};
describe("语义化确认", () => {
  it("新请求替代前一次，Esc 和背景点击均取消", async () => {
    const root = node("root"),
      app = renderer.createApp(ConfirmDialog);
    app.mount(root);
    try {
      const old = askConfirm(options);
      const current = askConfirm({ ...options, title: "再次核对" });
      expect(await old).toBe(false);
      await nextTick();
      const dialog = find(root, (n) => n.tag === "dialog")!;
      const title = find(
        dialog,
        (n) => n.props.id === dialog.props["aria-labelledby"],
      );
      expect(text(title!)).toBe("再次核对");
      (dialog.props.onCancel as (e: unknown) => void)({ preventDefault() {} });
      expect(await current).toBe(false);
      await nextTick();
      const backdrop = askConfirm(options);
      await nextTick();
      const el = find(root, (n) => n.tag === "dialog")!;
      (el.props.onClick as (e: unknown) => void)({
        target: el,
        currentTarget: el,
      });
      expect(await backdrop).toBe(false);
    } finally {
      app.unmount();
    }
  });
  it("抽屉离开守卫只在明确选择离开后放行", async () => {
    vi.stubGlobal("window", {
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    });
    const root = node("root"),
      app = renderer.createApp(Drawer, { title: "修改工单", dirty: true });
    app.mount(root);
    try {
      const guard = vi
        .mocked(onBeforeRouteLeave)
        .mock.calls.at(-1)![0] as () => Promise<boolean>;
      for (const [label, result] of [
        ["留在此页", false],
        ["离开并放弃", true],
      ] as const) {
        const answer = guard();
        await nextTick();
        const button = find(
          root,
          (n) => n.tag === "button" && text(n).trim() === label,
        )!;
        (button.props.onClick as () => void)();
        expect(await answer).toBe(result);
        await nextTick();
      }
    } finally {
      app.unmount();
    }
  });
});
