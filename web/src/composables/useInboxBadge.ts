import { onScopeDispose, ref, watch } from "vue";
import { get } from "../api/client";
import type { Model, User } from "../api/types";
import { useLatestQuery } from "./useLatestQuery";

const unread = ref(0);
let revision = 0;
export function syncInboxBadge(count = Math.max(0, unread.value - 1)) {
  revision++;
  unread.value = count;
}
export function useInboxBadge(
  user: () => Pick<User, "id" | "role" | "mustChangePassword"> | null,
) {
  const query = useLatestQuery();
  let timer: ReturnType<typeof setInterval> | undefined;
  const identity = () => {
    const current = user();
    return current?.role === "MANAGER" && !current.mustChangePassword
      ? current.id
      : "";
  };
  async function tick() {
    if (!identity() || document.hidden || query.busy.value) return;
    const start = revision;
    await query.run(
      (signal) =>
        get<Model<"ServiceMessageSummary">>("/messages/summary", {}, signal),
      (summary) => {
        if (start === revision) unread.value = summary.unreadCount;
      },
    );
  }
  function restart() {
    if (timer) clearInterval(timer);
    timer = undefined;
    query.cancel();
    if (!identity() || document.hidden) return;
    void tick();
    timer = setInterval(() => void tick(), 30000);
  }
  const stop = watch(
    identity,
    () => {
      unread.value = 0;
      restart();
    },
    { immediate: true },
  );
  document.addEventListener("visibilitychange", restart);
  onScopeDispose(() => {
    stop();
    if (timer) clearInterval(timer);
    query.cancel();
    document.removeEventListener("visibilitychange", restart);
  });
  return unread;
}
