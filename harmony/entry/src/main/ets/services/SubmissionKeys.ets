// One key represents an unresolved submission, not reusable content after a successful save.
export class SubmissionKeys {
  private signatures: Map<string, string> = new Map<string, string>();
  private values: Map<string, string> = new Map<string, string>();
  private createKey: () => string;

  constructor(createKey: () => string) { this.createKey = createKey; }

  reserve(scope: string, signature: string): string {
    if (this.signatures.get(scope) !== signature || !this.values.has(scope)) {
      this.signatures.set(scope, signature);
      this.values.set(scope, this.createKey());
    }
    return this.values.get(scope)!;
  }

  complete(scope: string): void { this.signatures.delete(scope); this.values.delete(scope); }

  clear(): void { this.signatures.clear(); this.values.clear(); }
}

// An unknown network outcome may still be running. A confirmed failed invocation is a new attempt.
export function shouldReleaseAiKey(code: string): boolean {
  return code === 'AI_UNAVAILABLE' || code === 'AI_REQUEST_FAILED' || code === 'AI_RESULT_UNKNOWN';
}
