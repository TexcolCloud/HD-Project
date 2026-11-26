export const mobilePhoneError = "请输入11位手机号";

export function isMobilePhone(value: string) {
  return /^1[3-9][0-9]{9}$/.test(value.trim());
}

export function filterPhoneInput(event: Event): string {
  const input = event.target as HTMLInputElement;
  input.value = input.value.replace(/\D/g, "").slice(0, 11);
  return input.value;
}
