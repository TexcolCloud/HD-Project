import { reactive } from "vue";
import { clearCsrf, get, post, refreshCsrf } from "./api/client";
import type { Model, User } from "./api/types";
export const session = reactive<{ user: User | null; checked: boolean }>({
  user: null,
  checked: false,
});
export function accountDestination(user: User, path: string) {
  const restricted = path === "/change-password" || path === "/activate-phone";
  if (user.mustChangePassword && !restricted) return "/change-password";
  if (!user.mustChangePassword && restricted) return "/dashboard";
  return undefined;
}
export async function restoreSession() {
  if (session.checked) return;
  try {
    session.user = await get<User>("/auth/me");
  } catch {
    session.user = null;
  } finally {
    session.checked = true;
  }
}
export async function login(
  username: string,
  password: string,
  identifierType?: Model<"LoginRequest">["identifierType"],
) {
  clearCsrf();
  await refreshCsrf();
  const result = await post<Model<"LoginResult">>("/auth/login", {
    username,
    password,
    clientType: "WEB",
    identifierType,
  } satisfies Model<"LoginRequest">);
  await refreshCsrf();
  if (result.user.role !== "MANAGER") {
    await post("/auth/logout");
    clearCsrf();
    throw new Error("Web 管理端仅向宿管开放。学生请使用鸿蒙端。");
  }
  session.user = result.user;
  session.checked = true;
}
export async function logout() {
  await post("/auth/logout");
  clearLocalSession();
}
export function clearLocalSession() {
  clearCsrf();
  session.user = null;
  session.checked = true;
}
