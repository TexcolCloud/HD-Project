export function openModal(dialog?: HTMLDialogElement | null) {
  if (!dialog) return;
  dialog.showModal?.();
  const notices =
    globalThis.document?.querySelector?.<HTMLElement>(".notice-stack");
  if (notices?.childElementCount && notices.showPopover) {
    notices.hidePopover?.();
    notices.showPopover?.();
  }
}
