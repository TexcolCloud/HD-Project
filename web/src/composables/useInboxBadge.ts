import { onScopeDispose, ref, watch } from "vue";
import { get } from "../api/client";
import type { Model, User } from "../api/types";
import { useLatestQuery } from "./useLatestQuery";

export function useInboxBadge(
  user: () => Pick<User, "id" | "role" | "mustChangePassword"> | null,
) {
  const unread = ref(0);
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
    await query.run(
      (signal) =>
        get<Model<"ServiceMessageSummary">>("/messages/summary", {}, signal),
      (summary) => {
        unread.value = summary.unreadCount;
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
