import { expect, it } from "vitest";
import { filterPhoneInput, isMobilePhone } from "./phone";
it("手机输入清除非数字并限长11位，拒绝无效号段", () => {
  const target = { value: "13a800000000999" };
  const number = filterPhoneInput({ target } as unknown as Event);
  expect(number).toBe("13800000000");
  expect(target.value).toBe(number);
  expect(isMobilePhone(number)).toBe(true);
  for (const invalid of [
    "00000000000",
    "12345678901",
    "1380000000",
    "138000000000",
  ])
    expect(isMobilePhone(invalid)).toBe(false);
});
