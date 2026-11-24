import { expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { find, node, renderer, text } from "../test/renderer";
import LookupSelect from "./LookupSelect.vue";
const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
it.each([
  {
    kind: "room" as const,
    available: true,
    requireResidence: false,
    path: "/rooms",
    occupancy: "AVAILABLE",
  },
  {
    kind: "student" as const,
    available: true,
    requireResidence: false,
    path: "/students",
    occupancy: "UNASSIGNED",
  },
  {
    kind: "student" as const,
    available: false,
    requireResidence: true,
    path: "/students",
    occupancy: "ASSIGNED",
  },
])(
  "查找 $kind 时按 $occupancy 过滤，点击候选项回传记录 ID",
  async ({ kind, available, requireResidence, path, occupancy }) => {
    api.get.mockResolvedValue({
      items: [
        kind === "student"
          ? { id: "12", studentNo: "20260003", name: "王同学" }
          : {
              id: "12",
              buildingName: "A栋",
              roomNo: "302",
              enabled: true,
              occupiedCount: 1,
              capacity: 4,
            },
      ],
    });
    const update = vi.fn();
    const root = node("root");
    const app = renderer.createApp(LookupSelect, {
      kind,
      available,
      requireResidence,
      label: "记录",
      modelValue: "",
      "onUpdate:modelValue": update,
    });
    app.mount(root);
    try {
      const input = find(root, (el) => el.props.role === "combobox")!;
      (input.props.onFocus as () => void)();
      await vi.waitFor(() =>
        expect(find(root, (el) => el.props.role === "option")).toBeDefined(),
      );
      expect(api.get).toHaveBeenCalledWith(
        path,
        { q: "", pageSize: 30, occupancy },
        expect.any(AbortSignal),
      );
      const option = find(root, (el) => el.props.role === "option")!;
      expect(text(option)).toContain(kind === "student" ? "王同学" : "A栋 302");
      (option.props.onClick as () => void)();
      expect(update).toHaveBeenCalledWith("12");
      await nextTick();
      expect(input.props["aria-expanded"]).toBe(false);
    } finally {
      app.unmount();
    }
  },
);
it("方向键移动高亮，回车选中，Escape 收起候选", async () => {
  api.get.mockResolvedValue({
    items: [
      { id: "1", studentNo: "s1", name: "陈同学" },
      { id: "2", studentNo: "s2", name: "李同学" },
    ],
  });
  const update = vi.fn(),
    root = node("root");
  const app = renderer.createApp(LookupSelect, {
    kind: "student",
    label: "学生",
    modelValue: "",
    "onUpdate:modelValue": update,
  });
  app.mount(root);
  try {
    const input = find(root, (el) => el.tag === "input")!;
    const press = (key: string) =>
      (input.props.onKeydown as (e: unknown) => void)({
        key,
        preventDefault() {},
        stopPropagation() {},
      });
    (input.props.onFocus as () => void)();
    await vi.waitFor(() =>
      expect(find(root, (el) => el.props.role === "option")).toBeDefined(),
    );
    press("Escape");
    await nextTick();
    expect(input.props["aria-expanded"]).toBe(false);
    press("ArrowDown");
    press("ArrowDown");
    await nextTick();
    expect(
      text(find(root, (el) => el.props["aria-selected"] === true)!),
    ).toContain("李同学");
    press("Enter");
    expect(update).toHaveBeenCalledWith("2");
  } finally {
    app.unmount();
  }
});
