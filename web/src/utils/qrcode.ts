// Versions 2–4 at correction level L each use a single Reed–Solomon block.
const layouts = [
  { version: 2, data: 34, parity: 10 },
  { version: 3, data: 55, parity: 15 },
  { version: 4, data: 80, parity: 20 },
];
// GF(256) arithmetic with the QR primitive polynomial x⁸ + x⁴ + x³ + x² + 1.
function multiply(a: number, b: number) {
  let product = 0;
  while (b) {
    if (b & 1) product ^= a;
    a = (a << 1) ^ (a & 128 ? 0x11d : 0);
    b >>= 1;
  }
  return product;
}
function parity(bytes: number[], count: number) {
  let generator = [1],
    root = 1;
  for (let i = 0; i < count; i++) {
    const next = [...generator, 0];
    generator.forEach((c, j) => (next[j + 1]! ^= multiply(c, root)));
    generator = next;
    root = multiply(root, 2);
  }
  const remainder = Array<number>(count).fill(0);
  for (const byte of bytes) {
    const factor = byte ^ remainder.shift()!;
    remainder.push(0);
    for (let i = 0; i < count; i++)
      remainder[i]! ^= multiply(factor, generator[i + 1]!);
  }
  return remainder;
}
const masks = [
  (x: number, y: number) => (x + y) % 2 === 0,
  (_x: number, y: number) => y % 2 === 0,
  (x: number) => x % 3 === 0,
  (x: number, y: number) => (x + y) % 3 === 0,
  (x: number, y: number) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
  (x: number, y: number) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x: number, y: number) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x: number, y: number) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
];
function penalty(matrix: boolean[][]) {
  const size = matrix.length;
  let cost = 0,
    dark = 0;
  for (let i = 0; i < size; i++) {
    for (const line of [matrix[i]!, matrix.map((row) => row[i]!)]) {
      let run = 0,
        pattern = 0;
      for (let j = 0; j < size; j++) {
        run = j > 0 && line[j] === line[j - 1] ? run + 1 : 1;
        if (run === 5) cost += 3;
        else if (run > 5) cost++;
        pattern = ((pattern << 1) | Number(line[j])) & 0x7ff;
        if (j >= 10 && (pattern === 0x05d || pattern === 0x5d0)) cost += 40;
      }
    }
    for (let j = 0; j < size; j++) {
      const cell = matrix[i]![j];
      dark += Number(cell);
      if (
        i &&
        j &&
        cell === matrix[i - 1]![j] &&
        cell === matrix[i]![j - 1] &&
        cell === matrix[i - 1]![j - 1]
      )
        cost += 3;
    }
  }
  return cost + 10 * Math.floor(Math.abs((20 * dark) / (size * size) - 10));
}
export function qrMatrix(content: string): boolean[][] {
  const bytes = new TextEncoder().encode(content);
  const utf8 = bytes.some((byte) => byte > 127);
  const layout = layouts.find(
    (option) => 12 + (utf8 ? 12 : 0) + bytes.length * 8 <= option.data * 8,
  );
  if (!layout) throw new RangeError("扫码内容过长");
  const bits: number[] = [];
  function append(value: number, width: number) {
    for (let i = width - 1; i >= 0; i--) bits.push((value >>> i) & 1);
  }
  // ECI assignment 26 selects UTF-8 for non-ASCII byte payloads.
  if (utf8) {
    append(7, 4);
    append(26, 8);
  }
  append(4, 4);
  append(bytes.length, 8);
  bytes.forEach((byte) => append(byte, 8));
  append(0, Math.min(4, layout.data * 8 - bits.length));
  while (bits.length % 8) bits.push(0);
  const words: number[] = [];
  for (let i = 0; i < bits.length; i += 8)
    words.push(bits.slice(i, i + 8).reduce((n, b) => n * 2 + b, 0));
  for (let pad = 0; words.length < layout.data; pad++)
    words.push(pad % 2 ? 0x11 : 0xec);
  words.push(...parity(words, layout.parity));
  const size = 17 + 4 * layout.version;
  const base = Array.from({ length: size }, () =>
    Array<boolean | null>(size).fill(null),
  );
  function square(
    cx: number,
    cy: number,
    radius: number,
    lightRings: number[],
  ) {
    for (
      let y = Math.max(0, cy - radius);
      y <= Math.min(size - 1, cy + radius);
      y++
    )
      for (
        let x = Math.max(0, cx - radius);
        x <= Math.min(size - 1, cx + radius);
        x++
      )
        base[y]![x] = !lightRings.includes(
          Math.max(Math.abs(x - cx), Math.abs(y - cy)),
        );
  }
  for (let i = 0; i < size; i++) base[6]![i] = base[i]![6] = i % 2 === 0;
  square(3, 3, 4, [2, 4]);
  square(size - 4, 3, 4, [2, 4]);
  square(3, size - 4, 4, [2, 4]);
  square(size - 7, size - 7, 2, [1]);
  base[size - 8]![8] = true;
  function candidate(mask: number) {
    const grid = base.map((row) => row.slice());
    // BCH-encode the error-correction level and mask into both format strips.
    const format = 8 | mask;
    let rem = format;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const code = ((format << 10) | rem) ^ 0x5412;
    for (let i = 0; i < 15; i++) {
      const bit = !!(code & (1 << i));
      if (i < 8) grid[i < 6 ? i : i + 1]![8] = bit;
      else grid[8]![i === 8 ? 7 : 14 - i] = bit;
      if (i < 8) grid[8]![size - 1 - i] = bit;
      else grid[size - 15 + i]![8] = bit;
    }
    // Weave codewords in two-column stripes, skipping the reserved function patterns.
    let index = 0,
      upward = true;
    for (let right = size - 1; right > 0; right -= 2) {
      if (right === 6) right--;
      for (let n = 0; n < size; n++) {
        const y = upward ? size - 1 - n : n;
        for (const x of [right, right - 1]) {
          if (grid[y]![x] !== null) continue;
          const data = ((words[index >>> 3] ?? 0) >>> (7 - (index % 8))) & 1;
          grid[y]![x] = Boolean(data) !== masks[mask]!(x, y);
          index++;
        }
      }
      upward = !upward;
    }
    return grid.map((row) => row.map(Boolean));
  }
  let best = candidate(0),
    lowest = penalty(best);
  for (let mask = 1; mask < 8; mask++) {
    const grid = candidate(mask),
      score = penalty(grid);
    if (score < lowest) {
      best = grid;
      lowest = score;
    }
  }
  return best;
}
export function qrSvg(content: string): string {
  const matrix = qrMatrix(content),
    size = matrix.length + 8;
  const path = matrix
    .flatMap((row, y) =>
      row.map((on, x) => (on ? `M${x + 4} ${y + 4}h1v1h-1z` : "")),
    )
    .join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" role="img" aria-label="地点二维码" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges"><rect width="${size}" height="${size}" fill="white"/><path d="${path}" fill="black"/></svg>`;
}
