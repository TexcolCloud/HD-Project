import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import type { User } from "../api/types";
import { session } from "../session";
import { find, node, renderer, text } from "../test/renderer";
import AccountSecurityPage from "./AccountSecurityPage.vue";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("vue-router", () => ({ useRouter: () => router }));
vi.mock("../session", async () => {
  const { reactive } = await import("vue");
  const session = reactive<{ user: User | null }>({ user: null });
  return {
    session,
    clearLocalSession: () => {
      session.user = null;
    },
  };
});
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelText: {},
}));

const manager: User = {
  id: "manager-1",
  username: "demo-manager",
  displayName: "演示宿管",
  role: "MANAGER",
  buildingIds: ["building-1"],
  studentId: null,
  currentRoom: null,
  mustChangePassword: false,
  phoneMasked: "138****0000",
  recoveryCodeConfigured: true,
};
let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  session.user = null;
  vi.clearAllMocks();
});

it.each(["SMS", "GRAPHIC"])(
  "%s 换绑核对验证码并在退出前展示一次性恢复码",
  async (delivery) => {
    session.user = { ...manager };
    api.post
      .mockResolvedValueOnce({
        challengeId: "first-new",
        delivery,
        captchaImage: delivery === "GRAPHIC" ? "first-image" : undefined,
      })
      .mockResolvedValueOnce(
        delivery === "GRAPHIC"
          ? {
              challengeId: "challenge-new",
              delivery,
              captchaImage: "new-image",
            }
          : { challengeId: "challenge-old" },
      )
      .mockResolvedValueOnce({ recoveryCode: "RECOVER-DEMO" });
    const root = node("root");
    const app = renderer.createApp(AccountSecurityPage);
    app.component("RouterLink", { template: "<a><slot /></a>" });
    app.mount(root);
    unmount = () => app.unmount();
    const button = (name: string) =>
      find(
        root,
        (candidate) =>
          candidate.tag === "button" && text(candidate).trim() === name,
      );
    const click = (name: string) =>
      (button(name)!.props.onClick as () => void)();
    const input = (label: string, value: string) => {
      const wrapper = find(
        root,
        (candidate) =>
          candidate.tag === "label" && text(candidate).trim().startsWith(label),
      )!;
      const field = find(wrapper, (candidate) => candidate.tag === "input")!;
      (field.props["onUpdate:modelValue"] as (value: string) => void)(value);
    };
    click("更换手机号");
    await nextTick();
    input("当前密码", "only-for-test");
    input("新手机号", "13800000001");
    await nextTick();
    click("获取新号验证码");
    await vi.waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    await nextTick();
    expect(api.post).toHaveBeenNthCalledWith(1, "/auth/phone/challenges", {
      purpose: "CHANGE_PHONE_NEW",
      phone: "13800000001",
      currentPassword: "only-for-test",
    });
    if (delivery === "GRAPHIC") {
      expect(find(root, (n) => n.tag === "img")?.props.src).toBe("first-image");
      expect(button("向旧号发送验证码")).toBeUndefined();
      click("换一张");
      await vi.waitFor(() =>
        expect(find(root, (n) => n.tag === "img")?.props.src).toBe("new-image"),
      );
      input("图形验证码", "a2bc");
    } else {
      expect(text(root)).toMatch(/\d+秒后可重发/);
      input("当前密码", "edited-for-test");
      await nextTick();
      expect(text(root)).toContain("新号验证码");
      click("向旧号发送验证码");
      await vi.waitFor(() => expect(text(root)).toContain("旧号验证码"));
      expect(api.post).toHaveBeenNthCalledWith(2, "/auth/phone/challenges", {
        purpose: "CHANGE_PHONE_OLD",
        currentPassword: "edited-for-test",
      });
      input("旧号验证码", "000011");
      input("新号验证码", "000012");
    }
    const form = find(root, (candidate) => candidate.tag === "form")!;
    (form.props.onSubmit as (event: { preventDefault: () => void }) => void)({
      preventDefault() {},
    });
    await vi.waitFor(() => expect(text(root)).toContain("RECOVER-DEMO"));
    expect(api.post).toHaveBeenNthCalledWith(
      3,
      "/auth/phone/change",
      delivery === "GRAPHIC"
        ? {
            newChallengeId: "challenge-new",
            newCode: "a2bc",
          }
        : {
            oldChallengeId: "challenge-old",
            oldCode: "000011",
            newChallengeId: "first-new",
            newCode: "000012",
          },
    );
    expect(session.user).toBeNull();
    expect(router.replace).not.toHaveBeenCalled();
    click("我已保存，重新登录");
    await nextTick();
    expect(text(root)).not.toContain("RECOVER-DEMO");
    expect(router.replace).toHaveBeenCalledWith("/login");
  },
);

it("两次新密码一致才修改登录密码并清除会话", async () => {
  session.user = { ...manager };
  const root = node("root"),
    app = renderer.createApp(AccountSecurityPage);
  vi.stubGlobal("window", { setTimeout });
  app.component("RouterLink", { template: "<a><slot /></a>" });
  app.mount(root);
  unmount = () => {
    app.unmount();
    vi.unstubAllGlobals();
  };
  const change = find(
    root,
    (n) => n.tag === "button" && text(n).trim() === "修改登录密码",
  )!;
  (change.props.onClick as () => void)();
  await nextTick();
  function type(label: string, value: string) {
    const wrapper = find(
      root,
      (n) => n.tag === "label" && text(n).trim().startsWith(label),
    )!;
    (
      find(wrapper, (n) => n.tag === "input")!.props["onUpdate:modelValue"] as (
        v: string,
      ) => void
    )(value);
  }
  const submit = () =>
    (
      find(root, (n) => n.tag === "form")!.props.onSubmit as (e: {
        preventDefault(): void;
      }) => Promise<void>
    )({ preventDefault() {} });
  type("当前密码", "current-example");
  type("新密码", "new-example-1");
  type("确认新密码", "mismatch-example");
  await submit();
  await nextTick();
  expect(text(root)).toContain("两次输入的新密码不一致");
  expect(api.post).not.toHaveBeenCalled();
  type("确认新密码", "new-example-1");
  await submit();
  expect(session.user).toBeNull();
  expect(api.post).toHaveBeenCalledWith("/auth/change-password", {
    oldPassword: "current-example",
    newPassword: "new-example-1",
  });
  expect(router.replace).toHaveBeenCalledWith("/login");
});
