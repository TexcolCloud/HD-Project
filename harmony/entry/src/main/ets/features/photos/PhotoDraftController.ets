import { common } from "@kit.AbilityKit";
import { http } from "@kit.NetworkKit";
import { Api, ApiError, UploadedPhoto } from "../../services/Api";
import { DeviceActions } from "../mobility/DeviceActions";

export class PhotoDraft {
  uploaded: UploadedPhoto[] = [];
  failed: string[] = [];
}
export class PhotoSnapshot {
  usage: string;
  draft: PhotoDraft;
  busy: boolean;
  constructor(usage: string, draft: PhotoDraft, busy: boolean) {
    this.usage = usage;
    this.draft = new PhotoDraft();
    this.busy = busy;
    this.draft.uploaded = draft.uploaded.slice();
    this.draft.failed = draft.failed.slice();
  }
}

// Owns the entire selection/capture/upload lifecycle; UI forms consume its current asset IDs.
export class PhotoDraftController {
  private report: PhotoDraft = new PhotoDraft();
  private result: PhotoDraft = new PhotoDraft();
  private inspection: PhotoDraft = new PhotoDraft();
  private busy: boolean = false;
  private run: number = 0;
  private revisions: Map<string, number> = new Map<string, number>();
  private listeners: Array<(snapshot: PhotoSnapshot) => void> = [];
  private onError: (error: Error) => void;
  constructor(onError: (error: Error) => void) {
    this.onError = onError;
  }
  private draft(usage: string): PhotoDraft {
    return usage === "REPORT"
      ? this.report
      : usage === "RESULT"
        ? this.result
        : this.inspection;
  }
  snapshot(usage: string): PhotoSnapshot {
    return new PhotoSnapshot(usage, this.draft(usage), this.busy);
  }
  subscribe(listener: (snapshot: PhotoSnapshot) => void): () => void {
    this.listeners.push(listener);
    this.emit();
    return (): void => {
      this.listeners = this.listeners.filter(
        (item: (snapshot: PhotoSnapshot) => void): boolean => item !== listener,
      );
    };
  }
  private emit(): void {
    for (const usage of ["REPORT", "RESULT", "HYGIENE"]) {
      for (const listener of this.listeners.slice())
        listener(this.snapshot(usage));
    }
  }
  reset(): void {
    this.run++;
    this.revisions.clear();
    this.busy = false;
    this.report = new PhotoDraft();
    this.result = new PhotoDraft();
    this.inspection = new PhotoDraft();
    this.emit();
  }
  clear(usage: string): void {
    // A new task/inspection draft retires pending selection, decode and upload callbacks for this usage only.
    const key = this.usageKey(usage);
    this.revisions.set(key, this.revision(usage) + 1);
    const draft = this.draft(usage);
    draft.uploaded = [];
    draft.failed = [];
    this.emit();
  }
  removeFailed(uri: string, usage: string): void {
    const draft = this.draft(usage);
    draft.failed = draft.failed.filter(
      (value: string): boolean => value !== uri,
    );
    this.emit();
  }
  private usageKey(usage: string): string {
    return usage === "REPORT" || usage === "RESULT" ? usage : "HYGIENE";
  }
  private revision(usage: string): number {
    return this.revisions.get(this.usageKey(usage)) || 0;
  }
  private currentDraft(
    epoch: number,
    run: number,
    usage: string,
    revision: number,
  ): boolean {
    return this.current(epoch, run) && revision === this.revision(usage);
  }
  private current(epoch: number, run: number): boolean {
    return epoch === Api.epoch && run === this.run;
  }
  private expired(error: Error, run: number): boolean {
    const detail = error as ApiError;
    if (
      detail.status !== 401 ||
      detail.sessionEpoch !== Api.epoch ||
      run !== this.run
    )
      return false;
    this.onError(error);
    return true;
  }
  private async upload(
    uri: string,
    usage: string,
    epoch: number,
    run: number,
    revision: number,
  ): Promise<void> {
    try {
      const photo = await Api.upload(uri, usage);
      if (!this.currentDraft(epoch, run, usage, revision)) return;
      const draft = this.draft(usage);
      draft.uploaded = draft.uploaded.concat([photo]);
      this.removeFailed(uri, usage);
    } catch (error) {
      if (this.expired(error as Error, run)) return;
      if (!this.currentDraft(epoch, run, usage, revision)) return;
      const draft = this.draft(usage);
      if (!draft.failed.includes(uri))
        draft.failed = draft.failed.concat([uri]);
      this.onError(error as Error);
      this.emit();
    }
  }
  async choose(
    usage: string,
    context: common.UIAbilityContext,
    camera: boolean = false,
  ): Promise<void> {
    if (this.busy) return;
    const epoch = Api.epoch,
      run = this.run,
      draft = this.draft(usage),
      revision = this.revision(usage);
    this.busy = true;
    this.emit();
    try {
      const remaining = 6 - draft.uploaded.length - draft.failed.length;
      if (remaining <= 0) throw new ApiError("最多上传6张图片");
      const captured = camera ? await DeviceActions.takePhoto(context) : "";
      const uris = camera
        ? captured
          ? [captured]
          : []
        : await Api.choose(remaining);
      if (!this.currentDraft(epoch, run, usage, revision)) return;
      for (const uri of uris.slice(0, remaining)) {
        if (!this.currentDraft(epoch, run, usage, revision)) return;
        await this.upload(uri, usage, epoch, run, revision);
      }
    } catch (error) {
      if (
        !this.expired(error as Error, run) &&
        this.currentDraft(epoch, run, usage, revision)
      )
        this.onError(error as Error);
    } finally {
      if (this.current(epoch, run)) {
        this.busy = false;
        this.emit();
      }
    }
  }
  async retry(uri: string, usage: string): Promise<void> {
    if (this.busy || !this.draft(usage).failed.includes(uri)) return;
    const epoch = Api.epoch,
      run = this.run,
      revision = this.revision(usage);
    this.busy = true;
    this.emit();
    try {
      await this.upload(uri, usage, epoch, run, revision);
    } finally {
      if (this.current(epoch, run)) {
        this.busy = false;
        this.emit();
      }
    }
  }
  async remove(photo: UploadedPhoto, usage: string): Promise<void> {
    if (this.busy) return;
    const epoch = Api.epoch,
      run = this.run,
      revision = this.revision(usage);
    try {
      await Api.request<Object>(
        "/files/" + photo.asset.id,
        http.RequestMethod.DELETE,
      );
      if (!this.currentDraft(epoch, run, usage, revision)) return;
      const draft = this.draft(usage);
      draft.uploaded = draft.uploaded.filter(
        (item: UploadedPhoto): boolean => item.asset.id !== photo.asset.id,
      );
      this.emit();
    } catch (error) {
      if (
        !this.expired(error as Error, run) &&
        this.currentDraft(epoch, run, usage, revision)
      )
        this.onError(error as Error);
    }
  }
}
