import { notify } from "../ui";
export async function copyText(text: string, success: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      notify(success);
      return;
    }
    const input = document.createElement("textarea");
    input.value = text;
    input.setAttribute("readonly", "");
    input.style.position = "fixed";
    input.style.opacity = "0";
    document.body.appendChild(input);
    try {
      input.select();
      if (!document.execCommand("copy")) throw new Error();
      notify(success);
    } finally {
      document.body.removeChild(input);
    }
  } catch {
    notify("复制失败，请手动选择文字复制", "error");
  }
}
