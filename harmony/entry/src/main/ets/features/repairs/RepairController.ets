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

@Observed
export class RepairState {
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
  contactTime: string = "";
  visitDraft: VisitWindowDraft = new VisitWindowDraft();
  reportPhotos: UploadedPhoto[] = [];
  resultPhotos: UploadedPhoto[] = [];
  failedReportPhotos: string[] = [];
  failedResultPhotos: string[] = [];
  ai: AiSuggestion | null = null;
  aiBusy: boolean = false;
  aiConsent: boolean = false;
  aiError: string = "";
  aiAdopted: boolean = false;
  action: string = "";
  actionNote: string = "";
}

export class RepairController {
  state: RepairState = new RepairState();
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
  suspendReads(): void {
    this.listRead.invalidate();
    this.detailRead.invalidate();
    this.state.listLoading = false;
    this.state.detailLoading = false;
  }
  reset(): void {
    this.listRead.invalidate();
    this.detailRead.invalidate();
    this.aiEpoch++;
    this.submissionKeys.clear();
    this.state = new RepairState();
  }
  private requestKey(body: Object, scope: string): string {
    return this.submissionKeys.reserve(scope, JSON.stringify(body));
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
    const ticket = this.detailRead.begin();
    this.state.detailLoading = true;
    this.state.selected = order;
    this.state.events = [];
    try {
      const selected = await Api.request<Repair>("/repairs/" + order.id);
      if (!this.detailRead.current(ticket)) return;
      this.state.selected = selected;
      const events = await Api.request<RepairEvent[]>(
        "/repairs/" + order.id + "/events",
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
    if (
      !/^[0-9+ ]{7,20}$/.test(this.state.contactPhone) ||
      !this.state.contactName.trim()
    ) {
      this.host.setError("请填写联系人和有效联系电话");
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
      contactTimeSlot: this.state.contactTime.trim(),
      reportImageIds: this.state.reportPhotos.map(
        (p: UploadedPhoto): string => p.asset.id,
      ),
    };
    if (this.state.repairPriority === "URGENT")
      request.urgentReason = this.state.repairUrgentReason.trim();
    try {
      const range = this.state.visitDraft.range();
      if (range) {
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
    const request: AiRequest = {
      description: this.state.repairDescription,
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
      if (this.state.repairDescription === request.description) {
        this.state.ai = suggestion;
        this.state.aiAdopted = false;
      } else this.state.aiError = "故障描述已修改，可按当前内容重新整理。";
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
