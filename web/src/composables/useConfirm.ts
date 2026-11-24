import { onScopeDispose, shallowRef } from "vue";
export const confirmHost = Symbol("confirm-dialog-host");
export type ConfirmOptions = {
  title: string;
  message: string;
  confirmText: string;
  cancelText?: string;
  danger?: boolean;
};
export const confirmation = shallowRef<ConfirmOptions>();
let resolveAnswer: ((answer: boolean) => void) | undefined;
export function answerConfirm(answer: boolean) {
  const resolve = resolveAnswer;
  resolveAnswer = undefined;
  confirmation.value = undefined;
  resolve?.(answer);
}
export function askConfirm(options: ConfirmOptions): Promise<boolean> {
  answerConfirm(false);
  confirmation.value = options;
  return new Promise((resolve) => {
    resolveAnswer = resolve;
  });
}
// An abandoned page must never act on a later dialog's answer.
export function useConfirm() {
  let owned: ConfirmOptions | undefined;
  onScopeDispose(() => {
    if (owned && confirmation.value === owned) answerConfirm(false);
  });
  return (options: ConfirmOptions) => {
    owned = options;
    return askConfirm(options);
  };
}
