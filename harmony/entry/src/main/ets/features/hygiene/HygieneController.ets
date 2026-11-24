import { LocalDrafts } from "../../services/LocalDrafts";
import { util } from "@kit.ArkTS";
import { Api, ApiError, UploadedPhoto } from "../../services/Api";
import { SubmissionKeys } from "../../services/SubmissionKeys";
import {
  Room,
  RoomPage,
  Hygiene,
  HygienePage,
  HygieneRequest,
  FileAsset,
  CorrectionEvent,
} from "../../models/ApiModels";
import {
  today,
  beijingClock,
  beijingParts,
  inspectionTimestamp,
  dateText,
} from "../../services/Labels";
import {
  PhotoDraftController,
  PhotoSnapshot,
} from "../photos/PhotoDraftController";
import { WorkspaceHost } from "../WorkspaceHost";
import { ReadScope } from "../ReadScope";

interface HygieneDraft {
  inspectionRoomId: string;
  inspectionDate: string;
  inspectionTime: string;
  inspectionRound: string;
  inspectionRelated: string;
  floorScore: string;
  deskScore: string;
  bedScore: string;
  bathroomScore: string;
  issueNote: string;
  remediationNote: string;
  correctionId?: string;
  correctionVersion?: number;
  originalAt?: string;
  correctionReason?: string;
  attachedImages?: FileAsset[];
}

@Observed
export class HygieneState {
  draftRestored: boolean = false;
  draftBlocked: boolean = false;
  busy: boolean = false;
  uploading: boolean = false;
  loading: boolean = false;
  rooms: Room[] = [];
  roomSearch: string = "";
  buildingChoice: string = "";
  floorChoice: number = 0;
  listKeyword: string = "";
  listGrade: string = "";
  hygiene: Hygiene[] = [];
  hygieneTotal: number = 0;
  hygienePage: number = 1;
  inspectionPhotos: UploadedPhoto[] = [];
  failedInspectionPhotos: string[] = [];
  inspectionRoomId: string = "";
  inspectionDate: string = today();
  inspectionTime: string = beijingClock();
  inspectionRound: string = "ROUTINE";
  inspectionRelated: string = "";
  floorScore: string = "23";
  deskScore: string = "23";
  bedScore: string = "23";
  bathroomScore: string = "23";
  issueNote: string = "";
  remediationNote: string = "";
  correctionId: string = "";
  correctionVersion: number = 0;
  originalAt: string = "";
  correctionReason: string = "";
  attachedImages: FileAsset[] = [];
  comparison: Hygiene | null = null;
  alreadyRecorded: boolean = false;
  checking: boolean = false;
  targetChecked: boolean = false;
  correctionConflict: boolean = false;
  currentCorrection: Hygiene | null = null;
}

export class HygieneController {
  returnRemediationId: string = '';
  onRecheckSaved: (taskId: string, saved: Hygiene) => void = () => {};
  state: HygieneState = new HygieneState();
  manager: boolean = false;
  private drafts: LocalDrafts | null = null;
  private host: WorkspaceHost;
  private photoDrafts: PhotoDraftController;
  private inspectionDraftStarted: boolean = false;
  private listRead: ReadScope = new ReadScope();
  private roomRead: ReadScope = new ReadScope();
  private comparisonRead: ReadScope = new ReadScope();
  private targetRevision: number = 0;
  private draftEdited: boolean = false;
  private awaitingSwitch: boolean = false;
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
    const value = drafts.read("hygiene");
    this.state.draftBlocked = drafts.hasPending("hygiene");
    if (!value) return;
    try {
      const data = JSON.parse(value) as HygieneDraft;
      if (data) {
        if (typeof data.inspectionRoomId === "string") this.state.inspectionRoomId = data.inspectionRoomId;
        if (typeof data.inspectionDate === "string") this.state.inspectionDate = data.inspectionDate;
        if (typeof data.inspectionTime === "string") this.state.inspectionTime = data.inspectionTime;
        if (typeof data.inspectionRound === "string") this.state.inspectionRound = data.inspectionRound;
        if (typeof data.inspectionRelated === "string") this.state.inspectionRelated = data.inspectionRelated;
        if (typeof data.floorScore === "string") this.state.floorScore = data.floorScore;
        if (typeof data.deskScore === "string") this.state.deskScore = data.deskScore;
        if (typeof data.bedScore === "string") this.state.bedScore = data.bedScore;
        if (typeof data.bathroomScore === "string") this.state.bathroomScore = data.bathroomScore;
        if (typeof data.issueNote === "string") this.state.issueNote = data.issueNote;
        if (typeof data.remediationNote === "string") this.state.remediationNote = data.remediationNote;
        if (typeof data.correctionId === "string") this.state.correctionId = data.correctionId;
        if (typeof data.correctionVersion === "number") this.state.correctionVersion = data.correctionVersion;
        if (typeof data.originalAt === "string") this.state.originalAt = data.originalAt;
        if (typeof data.correctionReason === "string") this.state.correctionReason = data.correctionReason;
        if (Array.isArray(data.attachedImages)) this.state.attachedImages = data.attachedImages;
        this.inspectionDraftStarted = true;
        this.state.draftRestored = true;
      }
    } catch (_) { drafts.clear("hygiene"); }
  }
  saveDraft(): void {
    if (!this.drafts || !(this.state.inspectionRoomId)) return;
    const draft: HygieneDraft = {
      inspectionRoomId: this.state.inspectionRoomId,
      inspectionDate: this.state.inspectionDate,
      inspectionTime: this.state.inspectionTime,
      inspectionRound: this.state.inspectionRound,
      inspectionRelated: this.state.inspectionRelated,
      floorScore: this.state.floorScore,
      deskScore: this.state.deskScore,
      bedScore: this.state.bedScore,
      bathroomScore: this.state.bathroomScore,
      issueNote: this.state.issueNote,
      remediationNote: this.state.remediationNote,
      correctionId: this.state.correctionId,
      correctionVersion: this.state.correctionVersion,
      originalAt: this.state.originalAt,
      correctionReason: this.state.correctionReason,
      attachedImages: this.state.attachedImages
    };
    try { this.drafts.save("hygiene", JSON.stringify(draft)); }
    catch (error) { this.host.setError("本机草稿保存失败：" + (error as Error).message); }
  }
  editDraft(): void {
    this.draftEdited = true;
    this.saveDraft();
  }
  discardDraft(): void {
    this.returnRemediationId = "";
    this.drafts?.clear("hygiene");
    this.drafts?.completeKey("hygiene");
    const fresh = new HygieneState();
    this.state.inspectionRoomId = fresh.inspectionRoomId;
    this.state.inspectionDate = fresh.inspectionDate;
    this.state.inspectionTime = fresh.inspectionTime;
    this.state.inspectionRound = fresh.inspectionRound;
    this.state.inspectionRelated = fresh.inspectionRelated;
    this.state.floorScore = fresh.floorScore;
    this.state.deskScore = fresh.deskScore;
    this.state.bedScore = fresh.bedScore;
    this.state.bathroomScore = fresh.bathroomScore;
    this.state.issueNote = fresh.issueNote;
    this.state.remediationNote = fresh.remediationNote;
    this.state.correctionId = "";
    this.state.correctionVersion = 0;
    this.state.originalAt = "";
    this.state.correctionReason = "";
    this.state.attachedImages = [];
    this.state.correctionConflict = false;
    this.state.currentCorrection = null;
    this.clearComparison();
    this.photoDrafts.clear("HYGIENE");
    this.inspectionDraftStarted = false;
    this.draftEdited = false;
    this.state.draftRestored = false;
    this.state.draftBlocked = false;
  }
  suspendReads(): void {
    this.listRead.invalidate();
    this.roomRead.invalidate();
    this.targetRevision++;
    this.clearComparison();
    this.state.loading = false;
  }
  reset(): void {
    this.returnRemediationId = "";
    this.suspendReads();
    this.inspectionDraftStarted = false;
    this.draftEdited = false;
    this.submissionKeys.clear();
    this.state = new HygieneState();
  }
  private requestKey(body: Object, scope: string): string {
    const signature = JSON.stringify(body);
    if (scope === "hygiene" && this.drafts) {
      this.saveDraft();
      return this.drafts.reserveKey(scope, signature, (): string => this.submissionKeys.reserve(scope, signature));
    }
    return this.submissionKeys.reserve(scope, signature);
  }
  photoChanged(snapshot: PhotoSnapshot): void {
    this.state.uploading = snapshot.busy;
    if (snapshot.usage === "HYGIENE") {
      this.state.inspectionPhotos = snapshot.draft.uploaded;
      this.state.failedInspectionPhotos = snapshot.draft.failed;
    }
  }
  beginDraft(): void {
    if (!this.inspectionDraftStarted) {
      this.state.inspectionDate = today();
      this.state.inspectionTime = beijingClock();
      this.inspectionDraftStarted = true;
    }
  }
  async loadRooms(): Promise<void> {
    const ticket = this.roomRead.begin();
    try {
      const rooms: Room[] = [];
      for (let page = 1; ; page++) {
        const data = await Api.request<RoomPage>("/rooms?status=ENABLED&pageSize=100&page=" + page);
        if (!this.roomRead.current(ticket)) return;
        rooms.push(...data.items);
        if (!data.items.length || rooms.length >= data.total || data.items.length < 100) break;
      }
      this.state.rooms = rooms;
      const selected = rooms.find((r: Room): boolean => r.id === this.state.inspectionRoomId);
      const first = selected || rooms[0];
      if (first) { this.state.buildingChoice = first.buildingId; this.state.floorChoice = first.floorNo; }
      if (this.state.inspectionRoomId && !this.state.correctionId) await this.loadComparison();
    } catch (error) {
      if (this.roomRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    }
  }
  buildingRooms(): Room[] {
    return this.state.rooms.filter((r: Room): boolean => r.buildingId === this.state.buildingChoice);
  }
  floors(): number[] {
    return Array.from(new Set(this.buildingRooms().map((r: Room): number => r.floorNo))).sort((a: number,b:number):number => a-b);
  }
  visibleRooms(): Room[] {
    const search = this.state.roomSearch.trim();
    return this.state.rooms.filter((r: Room): boolean => search ? r.roomNo.includes(search) :
      r.buildingId === this.state.buildingChoice && r.floorNo === this.state.floorChoice);
  }
  async browseFloor(building: string, floor: number): Promise<void> {
    if (this.state.busy || this.state.uploading || this.state.draftBlocked) return;
    if (building === this.state.buildingChoice && (!floor || floor === this.state.floorChoice)) {
      this.state.roomSearch = "";
      return;
    }
    if (this.state.inspectionRoomId) {
      await this.chooseRoom("");
      if (this.state.inspectionRoomId) return;
    }
    this.state.roomSearch = "";
    this.state.buildingChoice = building;
    this.state.floorChoice = floor || this.floors()[0] || 0;
  }
  fullScore(): void {
    this.state.floorScore = this.state.deskScore = this.state.bedScore = this.state.bathroomScore = "25";
    this.editDraft();
  }
  async loadHygiene(): Promise<void> {
    const ticket = this.listRead.begin();
    this.state.loading = true;
    try {
      const data = await Api.request<HygienePage>(
        (this.manager ? "/hygiene-inspections" : "/me/hygiene-inspections") +
          "?page=" +
          this.state.hygienePage +
          "&pageSize=10" + (this.manager ? "&q=" + encodeURIComponent(this.state.listKeyword.trim()) + "&grade=" + encodeURIComponent(this.state.listGrade) : ""),
      );
      if (!this.listRead.current(ticket)) return;
      this.state.hygiene = data.items;
      this.state.hygieneTotal = data.total;
    } catch (error) {
      if (this.listRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    } finally {
      if (this.listRead.current(ticket)) this.state.loading = false;
    }
  }
  roomChoice(): string {
    const selected = this.state.rooms.find(
      (r: Room): boolean => r.id === this.state.inspectionRoomId,
    );
    return selected
      ? selected.buildingName + " " + selected.roomNo
      : this.state.inspectionRoomId ? "已选宿舍（不在当前搜索结果中）" : "请选择检查宿舍";
  }

  private clearComparison(): void {
    this.comparisonRead.invalidate();
    this.state.comparison = null;
    this.state.alreadyRecorded = false;
    this.state.targetChecked = false;
    this.state.checking = false;
  }
  async loadComparison(): Promise<void> {
    this.clearComparison();
    const state = this.state;
    if (!state.inspectionRoomId || !state.inspectionDate || state.correctionId) return;
    const ticket = this.comparisonRead.begin();
    const query = "/hygiene-inspections?pageSize=1&roomId=" + encodeURIComponent(state.inspectionRoomId);
    state.checking = true;
    try {
      const same = await Api.request<HygienePage>(query + "&inspectionDate=" + state.inspectionDate + "&round=" + encodeURIComponent(state.inspectionRound));
      if (!this.comparisonRead.current(ticket)) return;
      if (same.items.length) {
        state.comparison = same.items[0];
        state.alreadyRecorded = true;
      } else {
        const previousDay = new Date(Date.parse(state.inspectionDate + "T00:00:00Z") - 86400000).toISOString().slice(0, 10);
        const previous = await Api.request<HygienePage>(query + "&endDate=" + previousDay);
        if (!this.comparisonRead.current(ticket)) return;
        state.comparison = previous.items.length ? previous.items[0] : null;
      }
      state.targetChecked = true;
    } catch (error) {
      if (this.comparisonRead.shouldReport(ticket, error as Error)) this.host.fail(error as Error);
    } finally {
      if (this.comparisonRead.current(ticket)) state.checking = false;
    }
  }
  private draftInUse(): boolean {
    return this.draftEdited || this.state.draftRestored || this.state.inspectionPhotos.length > 0 || this.state.failedInspectionPhotos.length > 0;
  }
  private async confirmDiscard(): Promise<boolean> {
    if (this.awaitingSwitch) return false;
    const revision = this.targetRevision, epoch = Api.epoch;
    this.awaitingSwitch = true;
    try {
      const accepted = await this.host.confirm("切换巡检记录？", "当前填写内容将清除。", "继续编辑", "切换记录");
      return accepted && revision === this.targetRevision && epoch === Api.epoch && !this.state.busy && !this.state.uploading;
    } finally { this.awaitingSwitch = false; }
  }
  async chooseRoom(id: string): Promise<void> {
    const state = this.state;
    if (state.correctionId || state.inspectionRelated || state.draftBlocked || state.busy || state.uploading || state.inspectionRoomId === id) return;
    if (this.draftInUse() && !await this.confirmDiscard()) return;
    const date = state.inspectionDate, time = state.inspectionTime;
    this.discardDraft();
    this.inspectionDraftStarted = true;
    state.inspectionRoomId = id;
    const chosen = state.rooms.find((r: Room): boolean => r.id === id);
    if (chosen) { state.buildingChoice = chosen.buildingId; state.floorChoice = chosen.floorNo; state.roomSearch = ''; }
    state.inspectionDate = date;
    state.inspectionTime = time;
    this.saveDraft();
    await this.loadComparison();
  }
  async chooseDate(date: string): Promise<void> {
    const state = this.state;
    if (state.draftBlocked || state.busy || state.uploading || state.inspectionDate === date || date > today()) return;
    if (!state.correctionId && this.draftInUse() && !await this.confirmDiscard()) return;
    const previousTime = state.inspectionTime;
    if (!state.correctionId && this.draftInUse()) {
      const taskId = this.returnRemediationId;
      const roomId = state.inspectionRoomId, related = state.inspectionRelated, round = state.inspectionRound;
      this.discardDraft();
      this.returnRemediationId = taskId;
      state.inspectionRoomId = roomId;
      state.inspectionRelated = related;
      state.inspectionRound = round;
    }
    this.inspectionDraftStarted = true;
    state.inspectionDate = date;
    state.inspectionTime = date === today() ? beijingClock() : (previousTime || "09:00");
    this.saveDraft();
    if (!state.correctionId) await this.loadComparison();
  }
  usePreviousScores(): void {
    const record = this.state.comparison;
    if (record && !this.state.alreadyRecorded && !this.state.draftBlocked && !this.state.busy) {
      this.setScores(record);
      this.editDraft();
    }
  }
  private setScores(record: Hygiene): void {
    this.state.floorScore = String(record.floorScore);
    this.state.deskScore = String(record.deskScore);
    this.state.bedScore = String(record.bedScore);
    this.state.bathroomScore = String(record.bathroomScore);
  }
  async correct(item: Hygiene): Promise<void> {
    if (this.state.busy || this.state.uploading || this.state.draftBlocked) return;
    if (this.draftInUse() && !await this.confirmDiscard()) return;
    this.discardDraft();
    const state = this.state;
    state.inspectionRoomId = item.roomId;
    state.inspectionDate = item.inspectionDate;
    state.inspectionTime = beijingParts(item.inspectedAt).clock;
    state.inspectionRound = item.round;
    state.inspectionRelated = item.relatedInspectionId || "";
    state.issueNote = item.issueNote;
    state.remediationNote = item.remediationNote;
    state.correctionId = item.id;
    state.correctionVersion = item.version;
    state.originalAt = item.inspectedAt;
    state.attachedImages = item.images;
    this.setScores(item);
    this.inspectionDraftStarted = true;
    this.saveDraft();
    this.host.navigate("hygiene-create");
  }
  async loadCorrectionConflict(): Promise<void> {
    const state = this.state, id = state.correctionId, epoch = Api.epoch, revision = this.targetRevision;
    if (!id) return;
    try {
      const latest = await Api.request<Hygiene>("/hygiene-inspections/" + id);
      if (epoch === Api.epoch && revision === this.targetRevision && state.correctionId === id) state.currentCorrection = latest;
    } catch (error) { this.host.fail(error as Error); }
  }
  async adoptCorrectionVersion(): Promise<void> {
    const state = this.state, latest = state.currentCorrection, epoch = Api.epoch, revision = this.targetRevision;
    if (!latest || latest.voidedAt || state.busy || state.uploading) return;
    const accepted = await this.host.confirm("使用最新记录版本？", "保留你填写的评分与说明。请对照最新记录再确认提交。", "返回核对", "保留填写并继续");
    if (!accepted || epoch !== Api.epoch || revision !== this.targetRevision || latest !== state.currentCorrection) return;
    const original = beijingParts(state.originalAt);
    const keepLatestTime = original.day === state.inspectionDate && original.clock === state.inspectionTime.trim();
    if (keepLatestTime) {
      state.inspectionDate = latest.inspectionDate;
      state.inspectionTime = beijingParts(latest.inspectedAt).clock;
    }
    state.originalAt = latest.inspectedAt;
    state.inspectionRelated = latest.relatedInspectionId || "";
    state.inspectionRound = latest.round;
    state.correctionVersion = latest.version;
    state.attachedImages = latest.images;
    state.correctionConflict = false;
    state.currentCorrection = null;
    this.saveDraft();
  }
  async history(item: Hygiene): Promise<void> {
    const epoch = Api.epoch, revision = this.targetRevision;
    try {
      const history = await Api.request<CorrectionEvent[]>("/hygiene-inspections/" + item.id + "/history");
      if (epoch !== Api.epoch || revision !== this.targetRevision) return;
      const events = history.filter((event: CorrectionEvent): boolean => event.action === "HYGIENE_CORRECT");
      await this.host.inform("评分更正记录", events.length ? events.map((event: CorrectionEvent): string =>
        dateText(event.createdAt) + " · " + event.operatorName + "\n" + event.note).join("\n\n") : "这条记录暂无更正。");
    } catch (error) { this.host.fail(error as Error); }
  }
  async abandon(): Promise<void> {
    if (this.state.busy || this.state.uploading || !await this.confirmDiscard()) return;
    this.discardDraft();
    this.host.navigate("hygiene");
  }

  totalScore(): number {
    return (
      Number(this.state.floorScore) +
      Number(this.state.deskScore) +
      Number(this.state.bedScore) +
      Number(this.state.bathroomScore)
    );
  }

  scoreGrade(): string {
    const value = this.totalScore();
    return value >= 90
      ? "优秀"
      : value >= 80
        ? "良好"
        : value >= 60
          ? "合格"
          : "待整改";
  }

  async recheck(item: Hygiene, taskId: string = ""): Promise<void> {
    if (this.state.busy || this.state.uploading || this.state.draftBlocked) return;
    if (this.draftInUse() && !await this.confirmDiscard()) return;
    if (this.draftInUse() || this.state.correctionId) this.discardDraft();
    // A recheck is a new inspection draft; it never inherits another room's photo work.
    this.photoDrafts.clear("HYGIENE");
    this.state.inspectionPhotos = [];
    this.state.failedInspectionPhotos = [];
    this.state.inspectionRoomId = item.roomId;
    this.state.inspectionRelated = item.id;
    this.returnRemediationId = taskId;
    this.state.inspectionDate = today();
    this.state.inspectionTime = beijingClock();
    this.inspectionDraftStarted = true;
    const previous = item.round.startsWith("RECHECK")
      ? Number(item.round.replace("RECHECK", "") || "1")
      : 0;
    this.state.inspectionRound = previous
      ? "RECHECK" + (previous + 1)
      : "RECHECK";
    this.state.issueNote = "";
    this.state.remediationNote = "";
    this.saveDraft();
    this.host.navigate("hygiene-create");
  }

  async submitHygiene(continueNext: boolean = false): Promise<void> {
    if (this.state.draftBlocked) { this.host.setError("上次提交结果未确认，请先查看服务记录；确认后可清除草稿重新填写"); return; }
    const epoch = Api.epoch,
      state = this.state;
    if (this.state.busy || this.state.uploading) return;
    this.host.setError("");
    if (!state.correctionId && (!state.targetChecked || state.alreadyRecorded)) {
      this.host.setError(state.alreadyRecorded ? "同轮已有评分，请选择更正评分" : "请先核对所选的巡检记录");
      return;
    }
    if (state.correctionId && !state.correctionReason.trim()) {
      this.host.setError("请填写更正原因");
      return;
    }
    if (state.correctionConflict) {
      this.host.setError("评分记录已更新，请核对最新版本后再保存");
      return;
    }
    if (this.state.failedInspectionPhotos.length) {
      this.host.setError("请重试或移除上传失败的图片");
      return;
    }
    const scoreValues = [
      this.state.floorScore,
      this.state.deskScore,
      this.state.bedScore,
      this.state.bathroomScore,
    ];
    if (scoreValues.some((s: string): boolean => !s.trim())) {
      this.host.setError("四项评分均需填写，最低可填0分");
      return;
    }
    const scores = scoreValues.map((s: string): number => Number(s));
    if (
      scores.some(
        (s: number): boolean => !Number.isInteger(s) || s < 0 || s > 25,
      ) ||
      !this.state.inspectionRoomId
    ) {
      this.host.setError("请选择宿舍，并将每项评分填写为0至25的整数");
      return;
    }
    if (this.totalScore() < 60 && !this.state.issueNote.trim()) {
      this.host.setError("低于60分时，请说明检查发现的问题");
      return;
    }
    let inspectedAt: string = "";
    try {
      inspectedAt = inspectionTimestamp(
        this.state.inspectionDate,
        this.state.inspectionTime,
      );
    } catch (error) {
      this.host.fail(error as Error);
      return;
    }
    if (state.originalAt) {
      const original = beijingParts(state.originalAt);
      if (original.day === state.inspectionDate && original.clock === state.inspectionTime.trim()) inspectedAt = state.originalAt;
    }
    if (state.attachedImages.length + state.inspectionPhotos.length > 6) {
      this.host.setError("已有图片和新增图片合计最多6张");
      return;
    }
    const request: HygieneRequest = {
      roomId: this.state.inspectionRoomId,
      inspectionDate: this.state.inspectionDate.trim(),
      inspectedAt: inspectedAt,
      round: this.state.inspectionRound,
      floorScore: scores[0],
      deskScore: scores[1],
      bedScore: scores[2],
      bathroomScore: scores[3],
      issueNote: this.state.issueNote.trim(),
      remediationNote: this.state.remediationNote.trim(),
      imageIds: this.state.inspectionPhotos.map(
        (p: UploadedPhoto): string => p.asset.id,
      ),
    };
    if (this.state.inspectionRelated)
      request.relatedInspectionId = this.state.inspectionRelated;
    if (state.correctionId) {
      request.expectedVersion = state.correctionVersion;
      request.correctionReason = state.correctionReason.trim();
    }
    this.state.busy = true;
    try {
      const key = this.requestKey(request, "hygiene");
      const saved = state.correctionId
        ? await Api.patch<Hygiene>("/hygiene-inspections/" + state.correctionId, request, key)
        : await Api.post<Hygiene>("/hygiene-inspections", request, key);
      Api.assertCurrent(epoch);
      if (state !== this.state) return;
      const taskId = this.returnRemediationId;
      const continueRoutine = continueNext && !state.correctionId && state.inspectionRound === "ROUTINE" && !taskId;
      const room = state.rooms.find((r: Room): boolean => r.id === request.roomId);
      const time = state.inspectionTime;
      this.submissionKeys.complete("hygiene");
      this.discardDraft();
      this.drafts?.completeKey("hygiene");
      this.drafts?.clear("hygiene");
      this.state.draftRestored = false;
      this.inspectionDraftStarted = false;
      this.photoDrafts.clear("HYGIENE");
      this.state.inspectionPhotos = [];
      this.state.inspectionRelated = "";
      this.state.inspectionRound = "ROUTINE";
      this.host.flash("卫生评分" + saved.totalScore + "分已保存");
      this.state.hygienePage = 1;
      if (taskId && saved.relatedInspectionId) this.onRecheckSaved(taskId, saved);
      else if (continueRoutine) {
        this.inspectionDraftStarted = true;
        state.inspectionDate = request.inspectionDate;
        state.inspectionTime = time;
        const siblings = state.rooms.filter((r: Room): boolean => !!room && r.enabled && r.buildingId === room.buildingId && r.floorNo === room.floorNo)
          .sort((a: Room,b: Room): number => a.roomNo.localeCompare(b.roomNo, 'zh-CN', {numeric:true}));
        const index = siblings.findIndex((r: Room): boolean => r.id === request.roomId);
        const next = index >= 0 ? siblings[index + 1] : undefined;
        state.inspectionRoomId = next ? next.id : "";
        state.roomSearch = "";
        if (next) { this.saveDraft(); await this.loadComparison(); }
        else this.host.flash("本层已查完，请选择其他楼层或宿舍");
      } else this.host.navigate("hygiene");
    } catch (error) {
      if (state === this.state) {
        const apiError = error as ApiError;
        if ([400, 403, 404, 409, 422, 429].includes(apiError.status)) this.drafts?.completeKey("hygiene");
        this.host.fail(error as Error);
        if (apiError.status === 409) {
          if (state.correctionId) {
            state.correctionConflict = true;
            await this.loadCorrectionConflict();
          } else await this.loadComparison();
        }
      }
    } finally {
      if (state === this.state && epoch === Api.epoch) this.state.busy = false;
    }
  }
}
