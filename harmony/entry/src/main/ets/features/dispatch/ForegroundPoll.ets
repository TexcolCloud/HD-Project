export interface PulseScheduler {
  every(callback: () => void, milliseconds: number): number;
  cancel(handle: number): void;
}

class NativeScheduler implements PulseScheduler {
  every(callback: () => void, milliseconds: number): number { return setInterval(callback, milliseconds); }
  cancel(handle: number): void { clearInterval(handle); }
}

export class ForegroundPoll<T> {
  private run: () => Promise<T>;
  private deliver: (result: T) => void;
  private failed: (error: Error) => void;
  private scheduler: PulseScheduler;
  private periodMs: number;
  private identity: string = '';
  private foreground: boolean = true;
  private handle: number = -1;
  private generation: number = 0;
  private inFlight: number = -1;
  constructor(run: () => Promise<T>, deliver: (result: T) => void, failed: (error: Error) => void,
    periodMs: number = 30000, scheduler: PulseScheduler = new NativeScheduler()) {
    this.run = run; this.deliver = deliver; this.failed = failed; this.scheduler = scheduler; this.periodMs = periodMs;
  }
  setSession(identity: string): void {
    if (identity === this.identity) return;
    this.cancel(); this.identity = identity;
    this.startIfActive();
  }
  setForeground(foreground: boolean): void {
    if (foreground === this.foreground) return;
    this.cancel(); this.foreground = foreground;
    this.startIfActive();
  }
  stop(): void { this.cancel(); this.identity = ''; }
  private cancel(): void {
    this.generation++; this.inFlight = -1;
    if (this.handle >= 0) { this.scheduler.cancel(this.handle); this.handle = -1; }
  }
  private startIfActive(): void {
    if (!this.identity || !this.foreground || this.handle >= 0) return;
    this.pulse();
    this.handle = this.scheduler.every(() => this.pulse(), this.periodMs);
  }
  private async pulse(): Promise<void> {
    const generation = this.generation;
    if (!this.identity || !this.foreground || this.inFlight === generation) return;
    this.inFlight = generation;
    try {
      const result = await this.run();
      if (generation === this.generation && this.identity && this.foreground) this.deliver(result);
    } catch (error) {
      if (generation === this.generation && this.identity && this.foreground) this.failed(error as Error);
    } finally { if (this.inFlight === generation) this.inFlight = -1; }
  }
}
