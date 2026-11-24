import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { qrMatrix, qrSvg } from "./qrcode";
const cases = [
  ["dormlink-anchor:入口", 25],
  ["dormlink-anchor:948d26b2-7220-47d0-beda-636ebfcd2ecf", 29],
  ["z".repeat(78), 33],
] as const;
describe("地点二维码", () => {
  it.each(cases)("按字节容量选择尺寸并生成三个寻像图形", (text, size) => {
    const grid = qrMatrix(text);
    expect(grid).toHaveLength(size);
    expect(grid.every((row) => row.length === size)).toBe(true);
    for (const [cx, cy] of [
      [3, 3],
      [size - 4, 3],
      [3, size - 4],
    ] as const)
      for (let dy = -3; dy <= 3; dy++)
        for (let dx = -3; dx <= 3; dx++)
          expect(grid[cy + dy]![cx + dx]).toBe(
            Math.max(Math.abs(dx), Math.abs(dy)) !== 2,
          );
    expect(grid[7]![7]).toBe(false);
    const svg = qrSvg(text);
    expect(svg).toBe(qrSvg(text));
    expect(svg).toContain(`viewBox="0 0 ${size + 8} ${size + 8}"`);
    expect(svg).not.toContain(text);
    expect(svg.match(/h1v1h-1z/g)?.length).toBe(
      grid.flat().filter(Boolean).length,
    );
  });
  it("保留已由独立解码器读回 UUID 的参考矩阵", () => {
    const grid = qrMatrix(
      "dormlink-anchor:948d26b2-7220-47d0-beda-636ebfcd2ecf",
    );
    const hash = createHash("sha256")
      .update(grid.flat().map(Number).join(""))
      .digest("hex");
    expect(hash).toBe(
      "624c80487459479d9e3271fd8b917cbdf16a4faf7000cb683ce072fb148a4211",
    );
  });
  it("显式拒绝超出版本容量的内容", () =>
    expect(() => qrMatrix("字".repeat(26))).toThrow(RangeError));
});
