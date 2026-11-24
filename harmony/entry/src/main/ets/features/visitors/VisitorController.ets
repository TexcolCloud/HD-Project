import { LocalDrafts } from "../../services/LocalDrafts";
import { util } from "@kit.ArkTS";
import { Api, ApiError } from "../../services/Api";
import { SubmissionKeys } from "../../services/SubmissionKeys";
import {
  Student,
  StudentPage,
  Visitor,
  VisitorPage,
  VisitorRequest,
  LeaveRequest,
  ContactResult,
  Building,
  BuildingPage,
} from "../../models/ApiModels";
import { isMobileNumber } from "../../services/Labels";
import { WorkspaceHost } from "../WorkspaceHost";
import { ReadScope } from "../ReadScope";
interface EmptyRequest {}

interface VisitorDraft {
  visitorName: string;
  visitorPhone: string;
  visitedStudentId: string;
  visitorPurpose: string;
  visitorRemark: string;
}

@Observed
export class VisitorState {
  draftRestored: boolean = false;
  draftBlocked: boolean = false;
  busy: boolean = false;
  loading: boolean = false;
  target: Student | null = null;
  students: Student[] = [];
  targetSearch: string = "";
  visitors: Visitor[] = [];
  visitorTotal: number = 0;
  visitorPage: number = 1;
  visitorSearch: string = "";
  visitorFilter: string = "";
  buildings: Building[] = [];
  buildingId: string = "";
  selected: Visitor | null = null;
  detailId: string = "";
  visitorName: string = "";
  visitorPhone: string = "";
  phoneError: string = "";
  visitedStudentId: string = "";
  visitorPurpose: string = "";
  visitorRemark: string = "";
}

export class VisitorController {
  state: VisitorState = new VisitorState();
  private drafts: LocalDrafts | null = null;
  private host: WorkspaceHost;
  private listRead: ReadScope = new ReadScope();
  private contactRead: ReadScope = new ReadScope();
  private searchTimer: number = -1;
  private studentRead: ReadScope = new ReadScope();
  private buildingRead: ReadScope = new ReadScope();
  private detailRead: ReadScope = new ReadScope();
  private listRevision: number = 0;
  private submissionKeys: SubmissionKeys = new SubmissionKeys((): string =>
    util.generateRandomUUID(),
  );
  constructor(host: WorkspaceHost) {
    this.host = host;
  }
  bindDrafts(drafts: LocalDrafts | null): void {
    this.drafts = drafts;
    if (!drafts) return;
    const value = drafts.read("visitor");
    this.state.draftBlocked = drafts.hasPending("visitor");
    if (!value) return;
    try {
      const data = JSON.parse(value) as VisitorDraft;
      if (data) {
        if (typeof data.visitorName === "string") this.state.visitorName = data.visitorName;
        if (typeof data.visitorPhone === "string") this.state.visitorPhone = data.visitorPhone;
        if (typeof data.visitedStudentId === "string") this.state.visitedStudentId = data.visitedStudentId;
        if (typeof data.visitorPurpose === "string") this.state.visitorPurpose = data.visitorPurpose;
        if (typeof data.visitorRemark === "string") this.state.visitorRemark = data.visitorRemark;
        this.state.draftRestored = true;
      }
    } catch (_) { drafts.clear("visitor"); }
  }
  saveDraft(): void {
    if (!this.drafts || !(this.state.visitorName.trim() || this.state.visitorPhone.trim())) return;
    const draft: VisitorDraft = {
      visitorName: this.state.visitorName,
      visitorPhone: this.state.visitorPhone,
      visitedStudentId: this.state.visitedStudentId,
      visitorPurpose: this.state.visitorPurpose,
      visitorRemark: this.state.visitorRemark
    };
    try { this.drafts.save("visitor", JSON.stringify(draft)); }
    catch (error) { this.host.setError("本机草稿保存失败：" + (error as Error).message); }
  }
  discardDraft(): void {
    this.drafts?.clear("visitor");
    this.drafts?.completeKey("visitor");
    const fresh = new VisitorState();
    this.state.visitorName = fresh.visitorName;
    this.state.visitorPhone = fresh.visitorPhone;
    this.state.phoneError = "";
    this.state.target = null;
    this.state.targetSearch = "";
    this.state.visitedStudentId = fresh.visitedStudentId;
    this.state.visitorPurpose = fresh.visitorPurpose;
    this.state.visitorRemark = fresh.visitorRemark;
    this.state.draftRestored = false;
    this.state.draftBlocked = false;
  }
  suspendReads(): void {
    this.listRevision++;
    this.listRead.invalidate();
    clearTimeout(this.searchTimer);
    this.contactRead.invalidate();
    this.studentRead.invalidate();
    this.buildingRead.invalidate();
    this.detailRead.invalidate();
    this.state.loading = false;
  }
  reset(): void {
    this.suspendReads();
    this.submissionKeys.clear();
    this.state = new VisitorState();
  }
  private requestKey(body: Object, scope: string): string {
    const signature = JSON.stringify(body);
    if (scope === "visitor" && this.drafts) {
      this.saveDraft();
      return this.drafts.reserveKey(scope, signature, (): string => this.submissionKeys.reserve(scope, signature));
    }
    return this.submissionKeys.reserve(scope, signature);
  }
  async loadVisitors(): Promise<void> {
    this.listRevision++;
    this.state.detailId = "";
    this.state.selected = null;
    const ticket = this.listRead.begin();
    this.state.loading = true;
    try {
      const data = await Api.request<VisitorPage>(
        "/visitors?page=" +
          this.state.visitorPage +
          "&pageSize=10&q=" +
          encodeURIComponent(this.state.visitorSearch) +
          "&status=" +
          this.state.visitorFilter + "&buildingId=" + encodeURIComponent(this.state.buildingId),
      );
      if (!this.listRead.current(ticket)) return;
      this.state.visitors = data.items;
      this.state.visitorTotal = data.total;
    } catch (error) {
      if (this.listRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    } finally {
      if (this.listRead.current(ticket)) this.state.loading = false;
    }
  }
  async loadBuildings(): Promise<void> {
    const ticket = this.buildingRead.begin();
    const buildings: Building[] = [];
    try {
      for (let page = 1; ; page++) {
        const data = await Api.request<BuildingPage>("/buildings?pageSize=100&page=" + page);
        if (!this.buildingRead.current(ticket)) return;
        buildings.push(...data.items);
        if (!data.items.length || buildings.length >= data.total) break;
      }
      this.state.buildings = buildings;
      if (this.state.buildingId && !buildings.some((building: Building): boolean => building.id === this.state.buildingId)) {
        this.selectBuilding("");
      }
    } catch (error) {
      if (this.buildingRead.shouldReport(ticket, error as Error)) this.host.fail(error as Error);
    }
  }
  open(visitor: Visitor): void {
    this.state.detailId = visitor.id;
    this.state.selected = visitor;
    this.host.navigate("visitor-detail");
  }
  async loadDetail(): Promise<void> {
    const id = this.state.detailId;
    if (!id) return;
    const ticket = this.detailRead.begin();
    const state = this.state;
    this.listRevision++;
    state.loading = true;
    try {
      const visitor = await Api.request<Visitor>("/visitors/" + id);
      if (this.detailRead.current(ticket)) state.selected = visitor;
    } catch (error) {
      if (this.detailRead.shouldReport(ticket, error as Error)) {
        if (this.detailRead.current(ticket) && [403, 404].includes((error as ApiError).status)) state.selected = null;
        this.host.fail(error as Error);
      }
    } finally {
      if (this.detailRead.current(ticket)) state.loading = false;
    }
  }
  selectBuilding(id: string): void {
    this.state.buildingId = id;
    this.state.visitorPage = 1;
    this.loadVisitors();
  }
  buildingChoice(): string {
    return this.state.buildings.find((building: Building): boolean => building.id === this.state.buildingId)?.name || "全部负责楼栋";
  }
  searchStudents(text: string): void {
    this.state.targetSearch = text;
    this.state.students = [];
    this.studentRead.invalidate();
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.loadStudents(),250);
  }
  chooseStudent(student: Student): void {
    this.state.target = student;
    this.state.visitedStudentId = student.id;
    this.saveDraft();
  }
  async loadStudents(): Promise<void> {
    clearTimeout(this.searchTimer);
    const ticket = this.studentRead.begin();
    try {
      const data = await Api.request<StudentPage>(
        "/students?pageSize=30&occupancy=ASSIGNED&q=" +
          encodeURIComponent(this.state.targetSearch),
      );
      if (!this.studentRead.current(ticket)) return;
      this.state.students = data.items.filter(
        (student: Student): boolean => !!student.roomId,
      );
      const id = this.state.visitedStudentId;
      if (id) {
        const match = this.state.students.find((student: Student): boolean => student.id === id);
        if (match) this.state.target = match;
        else if (!this.state.target) {
          let selected: Student | null = null;
          try { selected = await Api.request<Student>("/students/" + encodeURIComponent(id)); }
          catch (error) { if (![403,404].includes((error as ApiError).status)) throw error as Error; }
          if (!this.studentRead.current(ticket) || this.state.visitedStudentId !== id) return;
          if (selected && selected.id === id && selected.roomId) this.state.target = selected;
          else { this.state.visitedStudentId = ""; this.saveDraft(); }
        }
      }
    } catch (error) {
      if (this.studentRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    }
  }
  async submitVisitor(confirmDuplicate: boolean = false): Promise<void> {
    if (this.state.draftBlocked) { this.host.setError("上次提交结果未确认，请先查看服务记录；确认后可清除草稿重新填写"); return; }
    const epoch = Api.epoch,
      state = this.state;
    if (this.state.busy) return;
    this.host.setError("");
    if (this.state.phoneError || !isMobileNumber(this.state.visitorPhone)) {
      this.state.phoneError = "请输入11位手机号";
      return;
    }
    if (
      !this.state.visitorName.trim() ||
      !this.state.visitedStudentId || (this.state.draftRestored && !this.state.target) ||
      !this.state.visitorPurpose.trim()
    ) {
      this.host.setError("请填写访客姓名、有效电话、被访学生和来访事由");
      return;
    }
    const request: VisitorRequest = {
      visitorName: this.state.visitorName.trim(),
      phone: this.state.visitorPhone.trim(),
      visitedStudentId: this.state.visitedStudentId,
      purpose: this.state.visitorPurpose.trim(),
      remark: this.state.visitorRemark,
      confirmDuplicate: confirmDuplicate,
    };
    this.state.busy = true;
    try {
      await Api.post<Visitor>(
        "/visitors",
        request,
        this.requestKey(request, "visitor"),
      );
      Api.assertCurrent(epoch);
      if (state !== this.state) return;
      this.submissionKeys.complete("visitor");
      this.discardDraft();
      this.drafts?.completeKey("visitor");
      this.drafts?.clear("visitor");
      this.state.draftRestored = false;
      this.state.visitorName = "";
      this.state.visitorPhone = "";
      this.state.visitorPurpose = "";
      this.state.visitorRemark = "";
      this.host.flash("来访登记已保存");
      this.state.visitorPage = 1;
      this.host.navigate("visitors");
    } catch (error) {
      if (state === this.state && [400, 403, 404, 422, 429].includes((error as ApiError).status)) this.drafts?.completeKey("visitor");
      if (state !== this.state) return;
      const apiError = error as ApiError;
      if (apiError.code === "VISITOR_DUPLICATE_CONFIRMATION_REQUIRED") {
        // The server rejected this attempt and asks for a distinct explicit confirmation.
        this.drafts?.completeKey("visitor");
        this.state.busy = false;
        const accepted = await this.host.confirm(
          "已有相同电话的在访记录",
          "请确认是否为同一访客。确有新的来访需要时可继续登记。",
          "返回核对",
          "继续登记",
        );
        if (state === this.state && epoch === Api.epoch && accepted)
          await this.submitVisitor(true);
      } else this.host.fail(error as Error);
    } finally {
      if (state === this.state && epoch === Api.epoch) this.state.busy = false;
    }
  }

  async leave(visitor: Visitor): Promise<void> {
    const epoch = Api.epoch,
      state = this.state;
    const listRevision = this.listRevision;
    if (state.busy || state.loading || visitor.status !== "VISITING") return;
    this.state.busy = true;
    this.host.setError("");
    try {
      const accepted = await this.host.confirm(
        "确认 " + visitor.visitorName + " 已离开 " + visitor.buildingName + " " + visitor.roomNo + "？",
        "确认后将按当前时间记录离访。",
        "返回",
        "确认离访",
      );
      const detail = state.detailId === visitor.id && state.selected?.id === visitor.id;
      const current = detail ? state.selected : state.visitors.find((item: Visitor): boolean => item.id === visitor.id);
      if (!accepted || state !== this.state || epoch !== Api.epoch || listRevision !== this.listRevision ||
          current?.version !== visitor.version || current.status !== "VISITING") return;
      const request: LeaveRequest = { expectedVersion: visitor.version };
      const saved = await Api.post<Visitor>(
        "/visitors/" + visitor.id + "/leave",
        request,
        this.requestKey(request, "leave-" + visitor.id),
      );
      Api.assertCurrent(epoch);
      if (state !== this.state) return;
      this.submissionKeys.complete("leave-" + visitor.id);
      this.host.flash("已记录离开时间");
      if (listRevision === this.listRevision) {
        state.visitors = state.visitors.map((item: Visitor): Visitor => item.id === visitor.id ? saved : item);
        if (detail) {
          state.selected = saved;
          await this.loadDetail();
        } else await this.loadVisitors();
      }
    } catch (error) {
      if (state === this.state) {
        this.host.fail(error as Error);
        if ((error as ApiError).status === 409 && listRevision === this.listRevision) {
          if (state.detailId === visitor.id && state.selected?.id === visitor.id) await this.loadDetail();
          else await this.loadVisitors();
        }
      }
    } finally {
      if (state === this.state && epoch === Api.epoch) this.state.busy = false;
    }
  }

  async contact(visitor: Visitor, show: (phone: string, current: () => boolean) => Promise<void>): Promise<void> {
    const ticket = this.contactRead.begin();
    try {
      const request: EmptyRequest = {};
      const result = await Api.post<ContactResult>(
        "/visitors/" + visitor.id + "/reveal-contact",
        request,
      );
      if (this.contactRead.current(ticket)) await show(result.phone, () => this.contactRead.current(ticket));
    } catch (error) {
      if (this.contactRead.shouldReport(ticket, error as Error)) this.host.fail(error as Error);
    }
  }

  studentChoice(): string {
    const selected = this.state.target;
    return selected
      ? (selected.buildingName || "") +
          " " +
          (selected.roomNo || "") +
          " · " +
          selected.name
      : "请选择被访学生";
  }
}
