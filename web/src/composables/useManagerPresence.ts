import { reactive, watch, onScopeDispose } from "vue";
import type { User } from "../api/types";
import { heartbeat } from "../api/dispatch";
import { errorText } from "../ui";

export const managerPresence = reactive<{
  state: "inactive" | "background" | "connecting" | "online" | "error";
  lastResponseAt: string | null;
  error: string;
}>({ state: "inactive", lastResponseAt: null, error: "" });

export function useManagerPresence(
  getUser: () => Pick<User, "id" | "role" | "mustChangePassword"> | null,
): void {
  let timer: ReturnType<typeof setInterval> | undefined;
  let controller: AbortController | undefined;
  let generation = 0;
  let inFlight = false;
  const managerId = () => {
    const user = getUser();
    return user?.role === "MANAGER" && !user.mustChangePassword ? user.id : "";
  };
  function stop() {
    generation++;
    if (timer !== undefined) clearInterval(timer);
    timer = undefined;
    controller?.abort();
    controller = undefined;
    inFlight = false;
  }
  async function tick() {
    if (!managerId() || document.hidden || inFlight) return;
    const current = generation;
    controller = new AbortController();
    inFlight = true;
    if (!managerPresence.lastResponseAt) managerPresence.state = "connecting";
    try {
      await heartbeat(controller.signal);
      if (current !== generation) return;
      managerPresence.lastResponseAt = new Date().toISOString();
      managerPresence.state = "online";
      managerPresence.error = "";
    } catch (error) {
      if (current !== generation) return;
      managerPresence.state = "error";
      managerPresence.error = errorText(error);
    } finally {
      if (current === generation) {
        inFlight = false;
        controller = undefined;
      }
    }
  }
  function start() {
    stop();
    if (!managerId()) {
      managerPresence.state = "inactive";
      managerPresence.lastResponseAt = null;
      managerPresence.error = "";
      return;
    }
    if (document.hidden) {
      managerPresence.state = "background";
      return;
    }
    void tick();
    timer = setInterval(() => void tick(), 30_000);
  }
  const stopWatch = watch(managerId, start, { immediate: true });
  document.addEventListener("visibilitychange", start);
  onScopeDispose(() => {
    stopWatch();
    stop();
    document.removeEventListener("visibilitychange", start);
    managerPresence.state = "inactive";
  });
}
