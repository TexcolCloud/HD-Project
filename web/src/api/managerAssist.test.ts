import { expect, it, vi } from "vitest";
import { generateManagerAdvice } from "./managerAssist";
const mocks = vi.hoisted(() => ({ request: vi.fn().mockResolvedValue({}) }));
vi.mock("./client", () => mocks);

it("未明确同意外部分析时不发送宿管工单内容", async () => {
  await expect(
    generateManagerAdvice("repair-1", { consentToExternalAnalysis: false }),
  ).rejects.toThrow("请先同意发送本次内容进行外部分析");
  expect(mocks.request).not.toHaveBeenCalled();
});
