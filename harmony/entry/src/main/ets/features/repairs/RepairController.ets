import { LocalDrafts } from "../../services/LocalDrafts";
import { util } from "@kit.ArkTS";
import { Api, ApiError, UploadedPhoto } from "../../services/Api";
import {
  SubmissionKeys,
  shouldReleaseAiKey,
} from "../../services/SubmissionKeys";
import {
  Repair,
  RepairPage,
  RepairEvent,
  RepairRequest,
  ActionRequest,
  AiRequest,
  AiSuggestion,
} from "../../models/ApiModels";
import { VisitWindowDraft } from "../visits/VisitWindowFields";
import {
  PhotoDraftController,
  PhotoSnapshot,
} from "../photos/PhotoDraftController";
import { WorkspaceHost } from "../WorkspaceHost";
import { ReadScope } from "../ReadScope";
import { isMobileNumber } from "../../services/Labels";

interface RepairDraft {
  repairTitle: string;
  repairDescription: string;
  repairCategory: string;
  repairPriority: string;
  repairUrgentReason: string;
  contactName: string;
  contactPhone: string;
  visitDraft: VisitWindowDraft;
}

@Observed
export class RepairState {
  draftRestored: boolean = false;
  draftBlocked: boolean = false;
  busy: boolean = false;
  uploading: boolean = false;
  listLoading: boolean = false;
  detailLoading: boolean = false;
  repairs: Repair[] = [];
  repairTotal: number = 0;
  repairPage: number = 1;
  repairFilter: string = "";
  repairSearch: string = "";
  selected: Repair | null = null;
  events: RepairEvent[] = [];
  repairTitle: string = "";
  repairDescription: string = "";
  repairCategory: string = "OTHER";
  repairPriority: string = "NORMAL";
  repairUrgentReason: string = "";
  contactName: string = "";
  contactPhone: string = "";
  phoneError: string = "";
  visitDraft: VisitWindowDraft = new VisitWindowDraft();
  reportPhotos: UploadedPhoto[] = [];
  resultPhotos: UploadedPhoto[] = [];
  failedReportPhotos: string[] = [];
  failedResultPhotos: string[] = [];
  ai: AiSuggestion | null = null;
  aiBusy: boolean = false;
  aiConsent: boolean = false;
  aiError: string = "";
  manualRepair: ManualRepair | null = null;
  aiAdopted: boolean = false;
  action: string = "";
  actionNote: string = "";
}

interface ManualRepair { title: string; description: string; category: string; }

export class RepairController {
  private defaultContact: string = "";
  initializeContact(name: string): void { this.defaultContact = name; this.state.contactName = name; }
  editRepairText(title: string, description: string): void {
    this.state.repairTitle = title;
    this.state.repairDescription = description;
    this.state.aiAdopted = false;
    this.state.ai = null;
    this.saveDraft();
  }
  adoptSuggestion(): void {
    const state = this.state;
    if (!state.ai || state.busy || state.draftBlocked || state.aiAdopted) return;
    if (!state.manualRepair) state.manualRepair = {title: state.repairTitle, description: state.repairDescription, category: state.repairCategory};
    state.repairTitle = state.ai.title;
    state.repairDescription = state.ai.summary;
    state.repairCategory = state.ai.category;
    state.aiAdopted = true;
    this.saveDraft();
  }
  undoSuggestion(): void {
    if (!this.state.manualRepair || this.state.busy || this.state.draftBlocked) return;
    this.state.repairTitle = this.state.manualRepair.title;
    this.state.repairDescription = this.state.manualRepair.description;
    this.state.repairCategory = this.state.manualRepair.category;
    this.state.aiAdopted = false;
    this.state.manualRepair = null;
    this.saveDraft();
  }
  state: RepairState = new RepairState();
  private drafts: LocalDrafts | null = null;
  private host: WorkspaceHost;
  private photoDrafts: PhotoDraftController;
  private aiEpoch: number = 0;
  private listRead: ReadScope = new ReadScope();
  private detailRead: ReadScope = new ReadScope();
  private submissionKeys: SubmissionKeys = new SubmissionKeys((): string =>
    util.generateRandomUUID(),
  );
  constructor(host: WorkspaceHost, photos: PhotoDraftController) {
    this.host = host;
    this.photoDrafts = photos;
  }
  bindDrafts(drafts: LocalDrafts | null): void {
    this.drafts = drafts;
    if (!drafts) return;
    const value = drafts.read("repair");
    this.state.draftBlocked = drafts.hasPending("repair");
    if (!value) return;
    try {
      const data = JSON.parse(value) as RepairDraft;
      if (data) {
        if (typeof data.repairTitle === "string") this.state.repairTitle = data.repairTitle;
        if (typeof data.repairDescription === "string") this.state.repairDescription = data.repairDescription;
        if (typeof data.repairCategory === "string") this.state.repairCategory = data.repairCategory;
        if (typeof data.repairPriority === "string") this.state.repairPriority = data.repairPriority;
        if (typeof data.repairUrgentReason === "string") this.state.repairUrgentReason = data.repairUrgentReason;
        if (typeof data.contactName === "string") this.state.contactName = data.contactName;
        if (typeof data.contactPhone === "string") this.state.contactPhone = data.contactPhone;
        if (data.visitDraft) {
          const draft = new VisitWindowDraft();
          draft.enabled = data.visitDraft.enabled === true;
          draft.startDay = data.visitDraft.startDay; draft.startClock = data.visitDraft.startClock;
          draft.endDay = data.visitDraft.endDay; draft.endClock = data.visitDraft.endClock;
          this.state.visitDraft = draft;
        }
        this.state.draftRestored = true;
      }
    } catch (_) { drafts.clear("repair"); }
  }
  saveDraft(): void {
    if (!this.drafts || !(this.state.repairTitle.trim() || this.state.repairDescription.trim())) return;
    const draft: RepairDraft = {
      repairTitle: this.state.repairTitle,
      repairDescription: this.state.repairDescription,
      repairCategory: this.state.repairCategory,
      repairPriority: this.state.repairPriority,
      repairUrgentReason: this.state.repairUrgentReason,
      contactName: this.state.contactName,
      contactPhone: this.state.contactPhone,
      visitDraft: this.state.visitDraft
    };
    try { this.drafts.save("repair", JSON.stringify(draft)); }
    catch (error) { this.host.setError("本机草稿保存失败：" + (error as Error).message); }
  }
  discardDraft(): void {
    this.drafts?.clear("repair");
    this.drafts?.completeKey("repair");
    const fresh = new RepairState();
    this.state.repairTitle = fresh.repairTitle;
    this.state.repairDescription = fresh.repairDescription;
    this.state.repairCategory = fresh.repairCategory;
    this.state.repairPriority = fresh.repairPriority;
    this.state.repairUrgentReason = fresh.repairUrgentReason;
    this.state.contactName = this.defaultContact;
    this.state.manualRepair = null;
    this.aiEpoch++;
    this.state.ai = null;
    this.state.aiBusy = this.state.aiAdopted = this.state.aiConsent = false;
    this.state.aiError = "";
    this.state.contactPhone = fresh.contactPhone;
    this.state.phoneError = "";
    this.state.visitDraft = new VisitWindowDraft();
    this.photoDrafts.clear("REPORT");
    this.state.draftRestored = false;
    this.state.draftBlocked = false;
  }
  suspendReads(): void {
    this.listRead.invalidate();
    this.detailRead.invalidate();
    this.state.listLoading = false;
    this.state.detailLoading = false;
  }
  reset(): void {
    this.state.manualRepair = null;
    this.defaultContact = '';
    this.listRead.invalidate();
    this.detailRead.invalidate();
    this.aiEpoch++;
    this.submissionKeys.clear();
    this.state = new RepairState();
  }
  private requestKey(body: Object, scope: string): string {
    const signature = JSON.stringify(body);
    if (scope === "repair" && this.drafts) {
      this.saveDraft();
      return this.drafts.reserveKey(scope, signature, (): string => this.submissionKeys.reserve(scope, signature));
    }
    return this.submissionKeys.reserve(scope, signature);
  }
  photoChanged(snapshot: PhotoSnapshot): void {
    this.state.uploading = snapshot.busy;
    if (snapshot.usage === "REPORT") {
      this.state.reportPhotos = snapshot.draft.uploaded;
      this.state.failedReportPhotos = snapshot.draft.failed;
    } else if (snapshot.usage === "RESULT") {
      this.state.resultPhotos = snapshot.draft.uploaded;
      this.state.failedResultPhotos = snapshot.draft.failed;
    }
  }
  async loadRepairs(): Promise<void> {
    const ticket = this.listRead.begin();
    this.state.listLoading = true;
    try {
      const data = await Api.request<RepairPage>(
        "/repairs?page=" +
          this.state.repairPage +
          "&pageSize=10&status=" +
          encodeURIComponent(this.state.repairFilter) +
          "&q=" +
          encodeURIComponent(this.state.repairSearch),
      );
      if (!this.listRead.current(ticket)) return;
      this.state.repairs = data.items;
      this.state.repairTotal = data.total;
    } catch (error) {
      if (this.listRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    } finally {
      if (this.listRead.current(ticket)) this.state.listLoading = false;
    }
  }
  async loadDetail(order: Repair): Promise<void> {
    this.state.selected = order;
    await this.restoreDetail(order.id);
  }
  async restoreDetail(id: string): Promise<void> {
    const ticket = this.detailRead.begin();
    this.state.detailLoading = true;
    if (this.state.selected?.id !== id) this.state.selected = null;
    this.state.events = [];
    try {
      const selected = await Api.request<Repair>("/repairs/" + id);
      if (!this.detailRead.current(ticket)) return;
      this.state.selected = selected;
      const events = await Api.request<RepairEvent[]>(
        "/repairs/" + id + "/events",
      );
      if (this.detailRead.current(ticket)) this.state.events = events;
    } catch (error) {
      if (this.detailRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    } finally {
      if (this.detailRead.current(ticket)) this.state.detailLoading = false;
    }
  }
  async submitRepair(): Promise<void> {
    if (this.state.draftBlocked) { this.host.setError("上次提交结果未确认，请先查看服务记录；确认后可清除草稿重新填写"); return; }
    const epoch = Api.epoch,
      state = this.state;
    if (this.state.busy || this.state.uploading) return;
    this.host.setError("");
    if (this.state.failedReportPhotos.length) {
      this.host.setError("请重试或移除上传失败的图片后再提交");
      return;
    }
    if (
      this.state.repairTitle.trim().length < 4 ||
      this.state.repairDescription.trim().length < 4
    ) {
      this.host.setError("标题和故障描述请至少填写4个字");
      return;
    }
    if (this.state.phoneError || !isMobileNumber(this.state.contactPhone)) {
      this.state.phoneError = "请输入11位手机号";
      return;
    }
    if (!this.state.contactName.trim()) {
      this.host.setError("请填写联系人");
      return;
    }
    if (
      this.state.repairPriority === "URGENT" &&
      !this.state.repairUrgentReason.trim()
    ) {
      this.host.setError("请说明较急的原因");
      return;
    }
    const request: RepairRequest = {
      title: this.state.repairTitle.trim(),
      description: this.state.repairDescription.trim(),
      category: this.state.repairCategory,
      priority: this.state.repairPriority,
      contactName: this.state.contactName.trim(),
      contactPhone: this.state.contactPhone.trim(),
      contactTimeSlot: "",
      reportImageIds: this.state.reportPhotos.map(
        (p: UploadedPhoto): string => p.asset.id,
      ),
    };
    if (this.state.repairPriority === "URGENT")
      request.urgentReason = this.state.repairUrgentReason.trim();
    try {
      const range = this.state.visitDraft.range();
      if (range) {
        const visit = this.state.visitDraft;
        request.contactTimeSlot = visit.startDay + ' ' + visit.startClock + '–' +
          (visit.endDay === visit.startDay ? '' : visit.endDay + ' ') + visit.endClock;
        request.windowStart = range.start;
        request.windowEnd = range.end;
      }
    } catch (error) {
      this.host.fail(error as Error);
      return;
    }
    this.state.busy = true;
    try {
      const repair = await Api.post<Repair>(
        "/repairs",
        request,
        this.requestKey(request, "repair"),
      );
      Api.assertCurrent(epoch);
      if (state !== this.state) return;
      this.submissionKeys.complete("repair");
      this.discardDraft();
      this.drafts?.completeKey("repair");
      this.drafts?.clear("repair");
      this.state.draftRestored = false;
      this.state.visitDraft = new VisitWindowDraft();
      this.aiEpoch++;
      this.state.aiBusy = false;
      this.photoDrafts.clear("REPORT");
      this.state.reportPhotos = [];
      this.state.repairTitle = "";
      this.state.repairDescription = "";
      this.state.ai = null;
      this.state.aiAdopted = false;
      this.state.repairPriority = "NORMAL";
      this.state.repairUrgentReason = "";
      this.state.repairPage = 1;
      this.host.flash("报修已提交，可查看处理进度");
      await this.host.openRepair(repair);
    } catch (error) {
      if (state === this.state && [400, 403, 404, 422, 429].includes((error as ApiError).status)) this.drafts?.completeKey("repair");
      if (state === this.state) this.host.fail(error as Error);
    } finally {
      if (state === this.state && epoch === Api.epoch) this.state.busy = false;
    }
  }

  async suggest(): Promise<void> {
    if (this.state.aiBusy) return;
    this.state.aiError = "";
    if (!this.state.aiConsent) {
      this.state.aiError = "请先同意发送本次故障描述与所选图片进行分析";
      return;
    }
    if (this.state.repairDescription.trim().length < 4) {
      this.state.aiError = "请先描述故障现象，至少4个字";
      return;
    }
    const title = this.state.repairTitle.trim();
    const description = this.state.repairDescription;
    const request: AiRequest = {
      description: (title ? '标题：' + title + '\n现象描述：' + description : description).slice(0, 1000).replace(/[\uD800-\uDBFF]$/, ''),
      imageIds: this.state.reportPhotos
        .slice(0, 3)
        .map((p: UploadedPhoto): string => p.asset.id),
      consentToExternalAnalysis: true,
    };
    const epoch = ++this.aiEpoch,
      token = Api.token;
    this.state.aiBusy = true;
    try {
      const suggestion = await Api.post<AiSuggestion>(
        "/ai/repair-suggestions",
        request,
        this.requestKey(request, "ai"),
      );
      if (epoch !== this.aiEpoch || Api.token !== token) return;
      this.submissionKeys.complete("ai");
      if (this.state.repairTitle.trim() === title && this.state.repairDescription === description) {
        this.state.ai = suggestion;
        this.state.aiAdopted = false;
      } else this.state.aiError = "标题或故障描述已修改，可按当前内容重新整理。";
    } catch (error) {
      if (epoch !== this.aiEpoch) return;
      const apiError = error as ApiError;
      if (apiError.status === 401 && !Api.token) {
        this.host.fail(error as Error);
        return;
      }
      if (Api.token !== token) return;
      if (shouldReleaseAiKey(apiError.code)) this.submissionKeys.complete("ai");
      this.state.aiError = apiError.message + "。已保留原文，可直接提交报修。";
    } finally {
      if (epoch === this.aiEpoch) this.state.aiBusy = false;
    }
  }

  preserveAction(): () => void {
    const state = this.state, order = state.selected, action = state.action, note = state.actionNote;
    const revision = this.photoDrafts.draftVersion("RESULT");
    const photos = this.photoDrafts.snapshot("RESULT");
    return (): void => {
      if (this.state !== state) return;
      state.selected = order;
      state.action = action;
      state.actionNote = note;
      if (this.photoDrafts.draftVersion("RESULT") !== revision) this.photoDrafts.restoreResult(photos);
    };
  }
  async beginAction(action: string): Promise<void> {
    const epoch = Api.epoch,
      state = this.state;
    const selected = this.state.selected;
    if (!selected) return;
    if (action === "cancel") {
      const accepted = await this.host.confirm(
        "取消本次报修？",
        "仅待受理工单可取消。取消后再次报修需要新建工单。",
        "继续等待",
        "确认取消",
      );
      if (
        epoch !== Api.epoch ||
        state !== this.state ||
        selected !== this.state.selected ||
        !accepted
      )
        return;
    }
    this.state.action = action;
    this.state.actionNote = "";
    this.photoDrafts.clear("RESULT");
    this.state.resultPhotos = [];
    if (["accept", "start", "confirm", "cancel"].includes(action))
      this.runAction();
    else this.host.navigate("action");
  }

  async runAction(): Promise<void> {
    const epoch = Api.epoch,
      state = this.state;
    if (!this.state.selected || this.state.busy || this.state.uploading) return;
    if (
      this.state.action === "finish" &&
      this.state.failedResultPhotos.length
    ) {
      this.host.setError("请重试或移除上传失败的图片");
      return;
    }
    if (
      ["reject", "rework", "notes", "finish"].includes(this.state.action) &&
      this.state.actionNote.trim().length < 2
    ) {
      this.host.setError("请填写具体说明，至少2个字");
      return;
    }
    this.state.busy = true;
    this.host.setError("");
    const request: ActionRequest = {
      expectedVersion: this.state.selected.version,
    };
    if (this.state.action === "finish") {
      request.resultDescription = this.state.actionNote;
      request.resultImageIds = this.state.resultPhotos.map(
        (p: UploadedPhoto): string => p.asset.id,
      );
    } else if (this.state.action === "notes")
      request.note = this.state.actionNote;
    else if (["reject", "rework"].includes(this.state.action))
      request.reason = this.state.actionNote;
    const path = "/repairs/" + this.state.selected.id + "/" + this.state.action;
    try {
      const updated = await Api.post<Repair>(
        path,
        request,
        this.requestKey(request, path),
      );
      Api.assertCurrent(epoch);
      if (state !== this.state) return;
      this.submissionKeys.complete(path);
      this.photoDrafts.clear("RESULT");
      this.state.resultPhotos = [];
      this.host.flash("工单已更新");
      await this.host.openRepair(updated);
    } catch (error) {
      if (state === this.state) this.host.fail(error as Error);
    } finally {
      if (state === this.state && epoch === Api.epoch) this.state.busy = false;
    }
  }
}
