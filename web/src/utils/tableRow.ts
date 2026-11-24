export function isRowClick(event: MouseEvent) {
  return !(event.target as Element | null)?.closest?.(
    "button,a,input,select,summary,details,label,textarea",
  );
}
