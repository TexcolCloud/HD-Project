export class RouteRevision {
  private current: number = -1;
  accept(revision: number): boolean {
    if (!Number.isInteger(revision) || revision < this.current) return false;
    this.current = revision; return true;
  }
  value(): number { return this.current; }
  clear(): void { this.current = -1; }
}
