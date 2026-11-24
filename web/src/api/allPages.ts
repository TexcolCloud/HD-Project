/** Read all pages within the caller's current query scope. */
export async function allPages<T>(
  read: (page: number) => Promise<{ items: T[]; total: number }>,
  signal: AbortSignal,
): Promise<T[]> {
  const rows: T[] = [];
  for (let page = 1; !signal.aborted; page++) {
    const result = await read(page);
    rows.push(...result.items);
    if (!result.items.length || rows.length >= result.total) break;
  }
  return rows;
}
