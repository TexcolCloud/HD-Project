import { shallowRef } from "vue";

const dialogs = new Set<HTMLDialogElement>();
export const activeModal = shallowRef<HTMLDialogElement>();
// A modal makes its siblings inert, including otherwise visible popovers.
export function openModal(dialog?: HTMLDialogElement | null) {
  dialog?.showModal?.();
  if (dialog?.open) {
    dialogs.add(dialog);
    activeModal.value = dialog;
  }
  return () => {
    if (dialog && dialogs.delete(dialog)) {
      activeModal.value = [...dialogs]
        .reverse()
        .find((item) => item.open && item.isConnected);
    }
  };
}
