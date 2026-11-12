import { onScopeDispose, ref } from "vue";
import { errorText } from "../ui";

/** One query owner per independently visible list or detail panel. */
export function useLatestQuery() {
  const busy = ref(false);
  const error = ref("");
  let generation = 0;
  let disposed = false;
  let controller: AbortController | undefined;

  function cancel() {
    generation++;
    controller?.abort();
    controller = undefined;
    busy.value = false;
  }

  async function run<T>(
    fetch: (signal: AbortSignal) => Promise<T>,
    accept: (result: T) => void,
  ) {
    if (disposed) return;
    cancel();
    const current = generation;
    const request = new AbortController();
    controller = request;
    busy.value = true;
    error.value = "";
    try {
      const result = await fetch(request.signal);
      if (!disposed && generation === current) accept(result);
    } catch (cause) {
      if (!disposed && generation === current && !request.signal.aborted)
        error.value = errorText(cause);
    } finally {
      if (!disposed && generation === current) {
        busy.value = false;
        controller = undefined;
      }
    }
  }

  onScopeDispose(() => {
    disposed = true;
    cancel();
  });
  return { busy, error, run, cancel };
}
