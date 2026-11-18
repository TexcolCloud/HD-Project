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

let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  session.user = null;
  vi.clearAllMocks();
});

it("换绑验证旧号与新号，保留一次性恢复码直到确认后返回登录", async () => {
  session.user = {
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
  api.post
    .mockResolvedValueOnce({ challengeId: "challenge-old" })
    .mockResolvedValueOnce({ challengeId: "challenge-new" })
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
  const click = (name: string) => (button(name)!.props.onClick as () => void)();
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
  click("向旧号发送验证码");
  await vi.waitFor(() => expect(text(root)).toContain("旧号验证码"));
  expect(api.post).toHaveBeenNthCalledWith(1, "/auth/phone/challenges", {
    purpose: "CHANGE_PHONE_OLD",
    currentPassword: "only-for-test",
  });
  click("向新号发送验证码");
  await vi.waitFor(() => expect(text(root)).toContain("新号验证码"));
  expect(api.post).toHaveBeenNthCalledWith(2, "/auth/phone/challenges", {
    purpose: "CHANGE_PHONE_NEW",
    phone: "13800000001",
    currentPassword: "only-for-test",
  });
  input("旧号验证码", "000011");
  input("新号验证码", "000012");
  const form = find(root, (candidate) => candidate.tag === "form")!;
  (form.props.onSubmit as (event: { preventDefault: () => void }) => void)({
    preventDefault() {},
  });
  await vi.waitFor(() => expect(text(root)).toContain("RECOVER-DEMO"));
  expect(api.post).toHaveBeenNthCalledWith(3, "/auth/phone/change", {
    oldChallengeId: "challenge-old",
    oldCode: "000011",
    newChallengeId: "challenge-new",
    newCode: "000012",
  });
  expect(session.user).toBeNull();
  expect(router.replace).not.toHaveBeenCalled();
  click("我已保存，重新登录");
  await nextTick();
  expect(text(root)).not.toContain("RECOVER-DEMO");
  expect(router.replace).toHaveBeenCalledWith("/login");
});
