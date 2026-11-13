// Application-private preferences only; tokens, passwords and image bytes are never drafts.
export interface DraftStorage {
  get: (key: string) => string;
  put: (key: string, value: string) => void;
  remove: (key: string) => void;
}

interface DraftEnvelope {
  savedAt: number;
  payload: string;
}

interface PendingSubmission {
  savedAt: number;
  signature: string;
  key: string;
}

export class LocalDrafts {
  private storage: DraftStorage;
  private prefix: string;
  private now: () => number;

  constructor(storage: DraftStorage, server: string, userId: string, now: () => number = (): number => Date.now()) {
    this.storage = storage;
    this.prefix = encodeURIComponent(server) + ':' + encodeURIComponent(userId) + ':';
    this.now = now;
  }

  save(kind: string, payload: string): void {
    if (payload.length > 16000) throw new Error('草稿内容过长，请精简后保存');
    const envelope: DraftEnvelope = { savedAt: this.now(), payload: payload };
    this.storage.put(this.prefix + kind, JSON.stringify(envelope));
  }

  read(kind: string): string {
    const value = this.storage.get(this.prefix + kind);
    if (!value) return '';
    try {
      const envelope = JSON.parse(value) as DraftEnvelope;
      const age = this.now() - envelope.savedAt;
      if (typeof envelope.payload === 'string' && Number.isFinite(age) && age >= 0 && age <= 7 * 86400000)
        return envelope.payload;
    } catch (_) {
      // A damaged draft is discarded; it must never block authentication.
    }
    this.clear(kind);
    return '';
  }

  clear(kind: string): void {
    this.storage.remove(this.prefix + kind);
  }

  hasPending(kind: string): boolean {
    return this.storage.get(this.prefix + 'pending:' + kind).length > 0;
  }

  reserveKey(kind: string, signature: string, create: () => string): string {
    const location = this.prefix + 'pending:' + kind;
    const value = this.storage.get(location);
    if (value) {
      const pending = JSON.parse(value) as PendingSubmission;
      if (pending.signature === signature) {
        const age = this.now() - pending.savedAt;
        if (age < 0 || age > 23 * 3600000)
          throw new Error('上次提交已超过安全重试期限，请先核对服务记录，再清除草稿重新填写');
        return pending.key;
      }
      throw new Error('上次提交结果尚未确认，请先核对服务记录，再清除草稿修改内容');
    }
    const pending: PendingSubmission = { savedAt: this.now(), signature: signature, key: create() };
    this.storage.put(location, JSON.stringify(pending));
    return pending.key;
  }

  completeKey(kind: string): void {
    this.storage.remove(this.prefix + 'pending:' + kind);
  }
}
