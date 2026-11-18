export function accountCode(value: string): string {
  const code = value.trim();
  return /^[0-9]{6}$/.test(code) ? code : '';
}

export function cooldownSeconds(sentAt: number, now: number): number {
  return sentAt > 0 ? Math.max(0, Math.ceil((sentAt + 60000 - now) / 1000)) : 0;
}
