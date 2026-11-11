export function queryString(
  values: Record<string, string | number | undefined | null>,
): string {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "")
      params.set(key, String(value));
  });
  return params.size ? `?${params}` : "";
}
import type { Model } from "./types";
const prefix = "/api/v1";
let csrf: Model<"Csrf"> | null = null;
let sessionEpoch = 0;
const pendingKeys = new Map<string, string>();

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public traceId = "",
    public fieldErrors: Model<"Error">["error"]["fieldErrors"] = [],
  ) {
    super(message);
  }
}
export function clearCsrf() {
  sessionEpoch++;
  csrf = null;
  pendingKeys.clear();
}
function requireEpoch(epoch: number) {
  if (epoch !== sessionEpoch)
    throw new ApiError(
      0,
      "STALE_SESSION",
      "登录状态已变化，请在当前账号重新操作",
    );
}
export async function refreshCsrf() {
  const epoch = sessionEpoch;
  const response = await fetch(`${prefix}/auth/csrf`, {
    credentials: "include",
  });
  requireEpoch(epoch);
  if (!response.ok)
    throw new ApiError(
      response.status,
      "CSRF_UNAVAILABLE",
      "无法建立安全会话，请检查后端服务后重试",
    );
  const result = (await response.json()).data;
  requireEpoch(epoch);
  csrf = result;
  return csrf;
}
export async function request<T>(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    signal?: AbortSignal;
    idempotent?: boolean;
  } = {},
): Promise<T> {
  const epoch = sessionEpoch;
  const method = options.method || "GET";
  const write = !["GET", "HEAD"].includes(method);
  const headers = new Headers();
  if (write && !csrf) await refreshCsrf();
  requireEpoch(epoch);
  if (write && csrf) headers.set(csrf.headerName, csrf.token);
  const isForm = options.body instanceof FormData;
  if (options.body !== undefined && !isForm)
    headers.set("Content-Type", "application/json");
  const fingerprint = `${method}:${path}:${isForm ? crypto.randomUUID() : JSON.stringify(options.body)}`;
  if (write && options.idempotent !== false && !path.startsWith("/auth/")) {
    if (!pendingKeys.has(fingerprint))
      pendingKeys.set(fingerprint, crypto.randomUUID());
    headers.set("Idempotency-Key", pendingKeys.get(fingerprint)!);
  }
  let response: Response;
  try {
    const body =
      options.body instanceof FormData
        ? options.body
        : options.body === undefined
          ? undefined
          : JSON.stringify(options.body);
    response = await fetch(`${prefix}${path}`, {
      method,
      headers,
      credentials: "include",
      signal: options.signal,
      body,
    });
  } catch (error) {
    requireEpoch(epoch);
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new ApiError(
      0,
      "NETWORK_ERROR",
      "连接服务失败，填写内容已保留，请检查网络后重试",
    );
  }
  const json = await response.json().catch(() => null);
  requireEpoch(epoch);
  if (!response.ok) {
    if (
      path.startsWith("/ai/") &&
      ["AI_UNAVAILABLE", "AI_REQUEST_FAILED", "AI_RESULT_UNKNOWN"].includes(
        json?.error?.code,
      )
    )
      pendingKeys.delete(fingerprint);
    if (
      response.status === 401 &&
      !path.startsWith("/auth/") &&
      typeof window !== "undefined"
    )
      window.dispatchEvent(new Event("session-expired"));
    throw new ApiError(
      response.status,
      json?.error?.code || "REQUEST_FAILED",
      json?.error?.message || `请求失败（${response.status}），请稍后重试`,
      json?.traceId || "",
      json?.error?.fieldErrors || [],
    );
  }
  if (!json || typeof json !== "object" || !("data" in json))
    throw new ApiError(
      response.status,
      "INVALID_RESPONSE",
      "服务返回格式异常，请检查后端服务后重试",
    );
  pendingKeys.delete(fingerprint);
  return json?.data as T;
}
export const get = <T>(
  path: string,
  query: Record<string, string | number | null | undefined> = {},
) => request<T>(path + queryString(query));
export const post = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: "POST", body });
export const patch = <T>(path: string, body: unknown) =>
  request<T>(path, { method: "PATCH", body });
