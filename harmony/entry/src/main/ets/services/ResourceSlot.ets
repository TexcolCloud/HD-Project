// Owns exactly one native resource. A late decoder result is released even after its view exits.
export class ResourceSlot<T> {
  private generation: number = 0;
  private value: T | undefined = undefined;
  private release: (value: T) => void;
  constructor(release: (value: T) => void) { this.release = release; }
  begin(): number { return ++this.generation; }
  current(generation: number): boolean { return generation === this.generation; }
  accept(generation: number, value: T): boolean {
    if (!this.current(generation)) { this.release(value); return false; }
    if (this.value) this.release(this.value);
    this.value = value;
    return true;
  }
  clear(): void {
    this.generation++;
    if (this.value) this.release(this.value);
    this.value = undefined;
  }
}
