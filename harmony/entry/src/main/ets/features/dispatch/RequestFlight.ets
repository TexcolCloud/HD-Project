// Read-only requests share one promise per login epoch, independent of their caller.
export class RequestFlight<T> {
  private pending: Promise<T> | null = null;
  private epoch: number = -1;
  run(epoch: number, request: () => Promise<T>): Promise<T> {
    if (this.pending && this.epoch === epoch) return this.pending;
    const pending = request();
    this.pending = pending; this.epoch = epoch;
    pending.then(() => this.release(pending), () => this.release(pending));
    return pending;
  }
  reset(): void { this.pending = null; this.epoch = -1; }
  private release(pending: Promise<T>): void { if (this.pending === pending) this.pending = null; }
}
