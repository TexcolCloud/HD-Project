import { expect, it } from "vitest";
import { messageDestination } from "./serviceLinks";

it("消息仅生成已知业务的内部链接，并编码业务ID", () => {
  expect(messageDestination({ businessType: "REPAIR", businessId: "23" })).toBe(
    "/repairs/23",
  );
  expect(
    messageDestination({ businessType: "REMEDIATION", businessId: "8" }),
  ).toBe("/remediations?taskId=8");
  expect(
    messageDestination({
      businessType: "VISITOR",
      businessId: "7&next=//outside",
    }),
  ).toBe("/visitors?visitorId=7%26next%3D%2F%2Foutside");
  expect(
    messageDestination({ businessType: "https://outside", businessId: "1" }),
  ).toBeUndefined();
});
