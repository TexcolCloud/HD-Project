import { effectScope } from "vue";
import { expect, it } from "vitest";
import { useLatestQuery } from "./useLatestQuery";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

it("旧请求失败不能清除新请求的忙碌状态或显示过期错误", async () => {
  const scope = effectScope();
  const query = scope.run(useLatestQuery)!;
  const old = deferred<string>(),
    latest = deferred<string>();
  const accepted: string[] = [];
  const first = query.run(
    () => old.promise,
    (value) => accepted.push(value),
  );
  const second = query.run(
    () => latest.promise,
    (value) => accepted.push(value),
  );
  old.reject(new Error("旧筛选失败"));
  await first;
  expect(query.busy.value).toBe(true);
  expect(query.error.value).toBe("");
  latest.resolve("当前筛选");
  await second;
  expect(accepted).toEqual(["当前筛选"]);
  expect(query.busy.value).toBe(false);
  scope.stop();
});

it("关闭页面会取消网络和延迟回调，关闭后不再启动请求", async () => {
  const scope = effectScope();
  const query = scope.run(useLatestQuery)!;
  const pending = deferred<string>();
  let signal!: AbortSignal;
  const accepted: string[] = [];
  const run = query.run(
    (current) => {
      signal = current;
      return pending.promise;
    },
    (value) => accepted.push(value),
  );
  scope.stop();
  expect(signal.aborted).toBe(true);
  pending.resolve("已关闭页面的数据");
  await run;
  await query.run(
    () => {
      throw new Error("不得执行");
    },
    () => {},
  );
  expect(accepted).toEqual([]);
  expect(query.busy.value).toBe(false);
  expect(query.error.value).toBe("");
});
