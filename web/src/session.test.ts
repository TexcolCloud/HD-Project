import { afterEach, expect, it, vi } from "vitest";
import { accountDestination, login, session } from "./session";
import type { User } from "./api/types";

afterEach(() => {
  vi.unstubAllGlobals();
  session.user = null;
  session.checked = false;
});

it("首次改密账号必须先进入改密页面，改密后不能再次进入强制改密页", () => {
  const user: User = {
    id: "1",
    username: "staff",
    displayName: "宿管",
    role: "MANAGER",
    buildingIds: [],
    studentId: null,
    currentRoom: null,
    mustChangePassword: true,
  };
  expect(accountDestination(user, "/dispatch")).toBe("/change-password");
  expect(accountDestination(user, "/change-password")).toBeUndefined();
  expect(
    accountDestination(
      { ...user, mustChangePassword: false },
      "/change-password",
    ),
  ).toBe("/dashboard");
});

it("手机号模式按手机号提交标识，并保留服务端宿管身份和楼栋范围", async () => {
  const user: User = {
    id: "1",
    username: "staff",
    displayName: "宿管",
    role: "MANAGER",
    buildingIds: ["2"],
    studentId: null,
    currentRoom: null,
    mustChangePassword: false,
  };
  const fetch = vi.fn(async (url: string, _options?: RequestInit) =>
    Response.json({
      data: url.endsWith("/csrf")
        ? { headerName: "X-XSRF-TOKEN", token: "test-csrf" }
        : { user },
    }),
  );
  vi.stubGlobal("fetch", fetch);
  await login("13800000000", "test-password", "PHONE");
  const loginRequest = fetch.mock.calls.find(([url]) => url.endsWith("/login"));
  expect(JSON.parse(loginRequest![1]!.body as string)).toEqual({
    username: "13800000000",
    password: "test-password",
    clientType: "WEB",
    identifierType: "PHONE",
  });
  expect(session.user).toEqual(user);
});
