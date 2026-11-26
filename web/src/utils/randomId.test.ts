import { describe, expect, it, vi } from "vitest";
import { randomId } from "./randomId";

describe("randomId", () => {
  it("generates valid UUID v4 strings even on plain HTTP LAN origins without crypto.randomUUID", () => {
    const spy = vi
      .spyOn(globalThis.crypto, "randomUUID")
      .mockImplementation(
        undefined as unknown as typeof globalThis.crypto.randomUUID,
      );
    // Simulate non-secure HTTP context where crypto.randomUUID is undefined
    Object.defineProperty(globalThis.crypto, "randomUUID", {
      value: undefined,
      configurable: true,
    });
    try {
      const a = randomId();
      const b = randomId();
      expect(a).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      );
      expect(b).not.toBe(a);
    } finally {
      spy.mockRestore();
    }
  });
});
