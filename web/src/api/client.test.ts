import { afterEach, expect, it, vi } from "vitest";
import { queryString, request, clearCsrf } from "./client";
import { heartbeat } from "./dispatch";

afterEach(() => {
  vi.unstubAllGlobals();
  clearCsrf();
});

it("换号后拒绝旧成功响应，迟到401不能清除新会话", async () => {
  let complete!: (response: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          complete = resolve;
        }),
    ),
  );
  const success = request("/mobility/session");
  clearCsrf();
  complete(new Response(JSON.stringify({ data: { session: { id: "old" } } })));
  await expect(success).rejects.toMatchObject({ code: "STALE_SESSION" });
  const dispatch = vi.fn();
  vi.stubGlobal("window", { dispatchEvent: dispatch });
  const expired = request("/repairs");
  clearCsrf();
  complete(
    new Response(
      JSON.stringify({ error: { code: "UNAUTHORIZED", message: "expired" } }),
      { status: 401 },
    ),
  );
  await expect(expired).rejects.toMatchObject({ code: "STALE_SESSION" });
  expect(dispatch).not.toHaveBeenCalled();
});

it("旧CSRF读取不能覆盖新会话或继续发出写请求", async () => {
  let complete!: (response: Response) => void;
  const fetcher = vi.fn(
    () =>
      new Promise<Response>((resolve) => {
        complete = resolve;
      }),
  );
  vi.stubGlobal("fetch", fetcher);
  const write = request("/rooms", { method: "POST", body: { roomNo: "306" } });
  clearCsrf();
  complete(
    new Response(
      JSON.stringify({ data: { token: "old", headerName: "X-XSRF-TOKEN" } }),
    ),
  );
  await expect(write).rejects.toMatchObject({ code: "STALE_SESSION" });
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it("编码组合筛选并忽略空值，保留第一页与零值", () => {
  expect(
    queryString({
      keyword: "2 栋&306",
      status: "",
      buildingId: undefined,
      page: 1,
      minimum: 0,
    }),
  ).toBe("?keyword=2+%E6%A0%8B%26306&page=1&minimum=0");
});

it("写请求先取CSRF并使用Cookie与CSRF头，解析data信封", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: { token: "csrf-one", headerName: "X-XSRF-TOKEN" },
        }),
      ),
    )
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { id: "r1" }, traceId: "trace-1" })),
    );
  vi.stubGlobal("fetch", fetcher);
  expect(
    await request("/rooms", { method: "POST", body: { roomNo: "306" } }),
  ).toEqual({ id: "r1" });
  expect(fetcher.mock.calls[1][0]).toBe("/api/v1/rooms");
  const options = fetcher.mock.calls[1][1] as RequestInit;
  expect(options.credentials).toBe("include");
  expect(new Headers(options.headers).get("X-XSRF-TOKEN")).toBe("csrf-one");
  expect(new Headers(options.headers).get("Idempotency-Key")).toBeTruthy();
});

it("代理错误返回HTML时给出可读错误，不把空data当成功", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response("<html>proxy</html>", {
          status: 200,
          headers: { "Content-Type": "text/html" },
        }),
      ),
  );
  await expect(request("/rooms")).rejects.toMatchObject({
    code: "INVALID_RESPONSE",
    message: "服务返回格式异常，请检查后端服务后重试",
  });
});

it("心跳仍校验Cookie和CSRF，但不发送会冻结lastSeen的幂等缓存键", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: { token: "heartbeat-csrf", headerName: "X-XSRF-TOKEN" },
        }),
      ),
    )
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { userId: "manager-1" } })),
    );
  vi.stubGlobal("fetch", fetcher);
  await heartbeat();
  const options = fetcher.mock.calls[1][1] as RequestInit;
  expect(new Headers(options.headers).get("X-XSRF-TOKEN")).toBe(
    "heartbeat-csrf",
  );
  expect(new Headers(options.headers).has("Idempotency-Key")).toBe(false);
});

it("AI网络未知或生成中复用原键，明确终态后仅下一次主动请求换键，普通业务保留原重试策略", async () => {
  const error = (status: number, code: string) =>
    new Response(
      JSON.stringify({ error: { code, message: code }, traceId: "ai-trace" }),
      { status },
    );
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: { token: "ai-csrf", headerName: "X-XSRF-TOKEN" },
        }),
      ),
    )
    .mockRejectedValueOnce(new TypeError("connection interrupted"))
    .mockResolvedValueOnce(error(409, "AI_REQUEST_RUNNING"))
    .mockResolvedValueOnce(error(409, "AI_REQUEST_FAILED"))
    .mockResolvedValueOnce(error(503, "AI_UNAVAILABLE"))
    .mockResolvedValueOnce(error(409, "AI_RESULT_UNKNOWN"))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { summary: "缓存中的完整建议" } })),
    )
    .mockResolvedValueOnce(error(503, "AI_UNAVAILABLE"))
    .mockResolvedValueOnce(error(503, "AI_UNAVAILABLE"));
  vi.stubGlobal("fetch", fetcher);
  const ai = () =>
    request("/ai/manager/brief", {
      method: "POST",
      body: { consentToExternalAnalysis: true, includeImages: false },
    });
  for (const code of [
    "NETWORK_ERROR",
    "AI_REQUEST_RUNNING",
    "AI_REQUEST_FAILED",
    "AI_UNAVAILABLE",
    "AI_RESULT_UNKNOWN",
  ])
    await expect(ai()).rejects.toMatchObject({ code });
  expect(await ai()).toEqual({ summary: "缓存中的完整建议" });
  const key = (call: number) =>
    new Headers((fetcher.mock.calls[call]![1] as RequestInit).headers).get(
      "Idempotency-Key",
    );
  expect(key(1)).toBeTruthy();
  expect(key(2)).toBe(key(1));
  expect(key(3)).toBe(key(1));
  expect(key(4)).not.toBe(key(3));
  expect(key(5)).not.toBe(key(4));
  expect(key(6)).not.toBe(key(5));
  expect(fetcher).toHaveBeenCalledTimes(7);
  for (let i = 0; i < 2; i++)
    await expect(
      request("/repairs/repair-1/notes", {
        method: "POST",
        body: { note: "手动跟进" },
      }),
    ).rejects.toMatchObject({ code: "AI_UNAVAILABLE" });
  expect(key(8)).toBe(key(7));
});
