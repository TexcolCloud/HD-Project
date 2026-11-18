export const mobilePhoneError = "请输入11位手机号";

export function isMobilePhone(value: string) {
  return /^[0-9]{11}$/.test(value.trim());
}
