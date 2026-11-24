import { it, expect, vi } from "vitest";
import { find, node, renderer } from "../../test/renderer";
import RoomEditor from "./RoomEditor.vue";

const api = vi.hoisted(() => ({ post: vi.fn(), patch: vi.fn() }));
vi.mock("../../api/client", () => ({
  ...api,
  ApiError: class extends Error {},
}));
vi.mock("vue-router", () => ({ onBeforeRouteLeave: vi.fn() }));
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelText: {},
  vModelSelect: {},
  vModelCheckbox: {},
}));
it("连续建房保留楼层与容量，递增零填充房号并清空备注", async () => {
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  const saved = vi.fn(),
    root = node("root");
  const app = renderer.createApp(RoomEditor, {
    buildings: [{ id: "b", name: "一栋", floorCount: 12 }],
    onSaved: saved,
  });
  app.mount(root);
  try {
    const room = find(root, (n) => n.props.placeholder === "例如 302")!;
    const floor = find(
      root,
      (n) => n.props.type === "number" && n.props.max === 12,
    )!;
    const remark = find(root, (n) => n.tag === "textarea")!;
    function change(input: typeof room, value: string | number) {
      (input.props["onUpdate:modelValue"] as (v: string | number) => void)(
        value,
      );
      (input.props.onInput as (() => void) | undefined)?.();
    }
    const requests: Record<string, unknown>[] = [];
    api.post.mockImplementation(async (_path, body) => {
      requests.push({ ...body });
      return { ...body };
    });
    const submit = () =>
      (
        find(root, (n) => n.tag === "form")!.props.onSubmit as (
          e: unknown,
        ) => Promise<void>
      )({ preventDefault() {}, submitter: { getAttribute: () => "continue" } });
    change(room, "1208");
    await submit();
    expect(requests.at(-1)).toMatchObject({ roomNo: "1208", floorNo: 12 });
    change(floor, 3);
    change(room, "A009");
    change(remark, "临时备注");
    await submit();
    await submit();
    expect(requests.at(-1)).toMatchObject({
      roomNo: "A010",
      floorNo: 3,
      buildingId: "b",
      capacity: 4,
      enabled: true,
      remark: "",
    });
    expect(saved).toHaveBeenCalledWith(true);
    change(room, "402");
    await submit();
    expect(requests.at(-1)?.floorNo).toBe(3);
  } finally {
    app.unmount();
    vi.unstubAllGlobals();
  }
});
