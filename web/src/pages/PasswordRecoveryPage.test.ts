import { afterEach, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { node, renderer, find, text } from "../test/renderer";
import PasswordRecoveryPage from "./PasswordRecoveryPage.vue";

const api = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("../api/client", () => ({ ...api, ApiError: class extends Error {} }));
vi.mock("../session", () => ({ clearLocalSession: vi.fn() }));
vi.mock("vue-router", () => ({
  useRouter: () => ({ replace: vi.fn() }),
}));
vi.mock("vue", async (original) => ({
  ...(await original<typeof import("vue")>()),
  vModelText: {},
}));

let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  vi.clearAllMocks();
});

it("只在短信验证通过并收到要求后显示宿管恢复码输入", async () => {
  api.post
    .mockResolvedValueOnce({ challengeId: "sms-demo" })
    .mockResolvedValueOnce({
      resetToken: "proof-demo",
      recoveryCodeRequired: true,
    });
  const root = node("root");
  const app = renderer.createApp(PasswordRecoveryPage);
  app.component("RouterLink", { template: "<a><slot /></a>" });
  app.mount(root);
  unmount = () => app.unmount();
  const update = (key: string, value: string) => {
    const input = find(
      root,
      (candidate) => candidate.props.autocomplete === key,
    )!;
    (input.props["onUpdate:modelValue"] as (value: string) => void)(value);
  };
  const submit = async () => {
    const form = find(root, (candidate) => candidate.tag === "form")!;
    (form.props.onSubmit as (event: { preventDefault: () => void }) => void)({
      preventDefault() {},
    });
    await nextTick();
  };
  const recoveryInput = () =>
    find(root, (candidate) => candidate.props.name === "recovery-code");
  update("tel", "13800000000");
  await submit();
  await vi.waitFor(() =>
    expect(text(root)).toContain("如果该号码可以找回密码"),
  );
  expect(recoveryInput()).toBeUndefined();
  expect(api.post).toHaveBeenCalledWith("/auth/password-recovery/challenges", {
    phone: "13800000000",
    clientType: "WEB",
  });
  update("one-time-code", "000123");
  await submit();
  await vi.waitFor(() => expect(recoveryInput()).toBeDefined());
  expect(api.post).toHaveBeenCalledWith("/auth/password-recovery/verify", {
    challengeId: "sms-demo",
    code: "000123",
    clientType: "WEB",
  });
});
