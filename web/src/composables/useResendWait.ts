import { ref, onScopeDispose } from "vue";
export function useResendWait() {
  const seconds = ref(0);
  let timer: ReturnType<typeof setInterval> | undefined;
  let disposed = false;
  function stop() {
    clearInterval(timer);
    timer = undefined;
    seconds.value = 0;
  }
  function start() {
    if (disposed) return;
    stop();
    const until = Date.now() + 60000;
    seconds.value = 60;
    timer = setInterval(() => {
      seconds.value = Math.max(0, Math.ceil((until - Date.now()) / 1000));
      if (!seconds.value) stop();
    }, 1000);
  }
  onScopeDispose(() => {
    disposed = true;
    stop();
  });
  return { seconds, start };
}
