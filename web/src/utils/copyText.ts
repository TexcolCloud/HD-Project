import { notify } from "../ui";
export async function copyText(text: string, success: string) {
  try {
    if (!navigator.clipboard?.writeText) throw new Error();
    await navigator.clipboard.writeText(text);
    notify(success);
  } catch {
    notify("复制失败，请手动选择文字复制", "error");
  }
}
