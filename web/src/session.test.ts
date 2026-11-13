import { expect, it } from "vitest";
import { accountDestination } from "./session";
import type { User } from "./api/types";

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
