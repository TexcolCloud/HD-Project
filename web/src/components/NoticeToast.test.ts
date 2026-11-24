import { it, expect, vi } from "vitest";
import NoticeToast from "./NoticeToast.vue";
import { notify, notices, dismissNotice } from "../ui";
import { renderer, node, find } from "../test/renderer";
it("错误即时播报，悬停和焦点独立暂停，手动关闭只移除指定通知", () => {
  vi.useFakeTimers();
  notify("未能保存", "error");
  notify("已更新");
  const [err, ok] = notices,
    root = node("root");
  const app = renderer.createApp(NoticeToast, { notice: err! });
  app.mount(root);
  try {
    const el = find(root, (n) => n.props.role === "alert")!;
    vi.advanceTimersByTime(2000);
    (el.props.onMouseenter as () => void)();
    (el.props.onFocusin as () => void)();
    vi.advanceTimersByTime(10000);
    expect(notices.map((n) => n.id)).toEqual([err!.id]);
    (el.props.onMouseleave as () => void)();
    vi.advanceTimersByTime(10000);
    expect(notices).toHaveLength(1);
    (el.props.onFocusout as () => void)();
    vi.advanceTimersByTime(3999);
    expect(notices).toHaveLength(1);
    notify("另一条");
    const next = notices[1]!;
    (
      find(root, (n) => n.props["aria-label"] === "关闭提示")!.props
        .onClick as () => void
    )();
    expect(next.id).not.toBe(ok!.id);
    expect(notices.map((n) => n.id)).toEqual([next.id]);
    vi.advanceTimersByTime(6000);
    expect(notices).toHaveLength(0);
  } finally {
    app.unmount();
    for (const n of [...notices]) dismissNotice(n.id);
    vi.useRealTimers();
  }
});
