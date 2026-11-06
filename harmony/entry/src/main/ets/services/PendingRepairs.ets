export function pendingRepairPaths(): string[] {
  return ['SUBMITTED', 'ACCEPTED', 'PROCESSING', 'PENDING_CONFIRMATION']
    .map((status: string): string => '/repairs?pageSize=3&status=' + status);
}

export function newestUnique<T>(groups: T[][], id: (item: T) => string,
  submitted: (item: T) => string, limit: number): T[] {
  const unique: Map<string, T> = new Map<string, T>();
  for (const group of groups) for (const item of group) unique.set(id(item), item);
  return Array.from(unique.values()).sort((a: T, b: T): number => {
    return new Date(submitted(b)).getTime() - new Date(submitted(a)).getTime();
  }).slice(0, limit);
}
