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
} from "../../models/ApiModels";
import { WorkspaceHost } from "../WorkspaceHost";
import { ReadScope } from "../ReadScope";
interface EmptyRequest {}

@Observed
export class VisitorState {
  busy: boolean = false;
  loading: boolean = false;
  students: Student[] = [];
  targetSearch: string = "";
  visitors: Visitor[] = [];
  visitorTotal: number = 0;
  visitorPage: number = 1;
  visitorSearch: string = "";
  visitorFilter: string = "";
  visitorName: string = "";
  visitorPhone: string = "";
  visitedStudentId: string = "";
  visitorPurpose: string = "";
  visitorRemark: string = "";
}

export class VisitorController {
  state: VisitorState = new VisitorState();
  private host: WorkspaceHost;
  private listRead: ReadScope = new ReadScope();
  private studentRead: ReadScope = new ReadScope();
  private submissionKeys: SubmissionKeys = new SubmissionKeys((): string =>
    util.generateRandomUUID(),
  );
  constructor(host: WorkspaceHost) {
    this.host = host;
  }
  suspendReads(): void {
    this.listRead.invalidate();
    this.studentRead.invalidate();
    this.state.loading = false;
  }
  reset(): void {
    this.listRead.invalidate();
    this.studentRead.invalidate();
    this.submissionKeys.clear();
    this.state = new VisitorState();
  }
  private requestKey(body: Object, scope: string): string {
    return this.submissionKeys.reserve(scope, JSON.stringify(body));
  }
  async loadVisitors(): Promise<void> {
    const ticket = this.listRead.begin();
    this.state.loading = true;
    try {
      const data = await Api.request<VisitorPage>(
        "/visitors?page=" +
          this.state.visitorPage +
          "&pageSize=10&q=" +
          encodeURIComponent(this.state.visitorSearch) +
          "&status=" +
          this.state.visitorFilter,
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
  async loadStudents(): Promise<void> {
    const ticket = this.studentRead.begin();
    try {
      const data = await Api.request<StudentPage>(
        "/students?pageSize=100&q=" +
          encodeURIComponent(this.state.targetSearch),
      );
      if (!this.studentRead.current(ticket)) return;
      this.state.students = data.items.filter(
        (student: Student): boolean => !!student.roomId,
      );
      if (
        !this.state.students.some(
          (student: Student): boolean =>
            student.id === this.state.visitedStudentId,
        )
      )
        this.state.visitedStudentId = this.state.students.length
          ? this.state.students[0].id
          : "";
    } catch (error) {
      if (this.studentRead.shouldReport(ticket, error as Error))
        this.host.fail(error as Error);
    }
  }
  async submitVisitor(confirmDuplicate: boolean = false): Promise<void> {
    const epoch = Api.epoch,
      state = this.state;
    if (this.state.busy) return;
    this.host.setError("");
    if (
      !this.state.visitorName.trim() ||
      !/^[0-9+ ]{7,20}$/.test(this.state.visitorPhone) ||
      !this.state.visitedStudentId ||
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
      this.state.visitorName = "";
      this.state.visitorPhone = "";
      this.state.visitorPurpose = "";
      this.state.visitorRemark = "";
      this.host.flash("来访登记已保存");
      this.state.visitorPage = 1;
      this.host.navigate("visitors");
    } catch (error) {
      if (state !== this.state) return;
      const apiError = error as ApiError;
      if (apiError.code === "VISITOR_DUPLICATE_CONFIRMATION_REQUIRED") {
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
    if (this.state.busy) return;
    this.state.busy = true;
    this.host.setError("");
    try {
      const request: LeaveRequest = { expectedVersion: visitor.version };
      await Api.post<Visitor>(
        "/visitors/" + visitor.id + "/leave",
        request,
        this.requestKey(request, "leave-" + visitor.id),
      );
      Api.assertCurrent(epoch);
      if (state !== this.state) return;
      this.submissionKeys.complete("leave-" + visitor.id);
      this.host.flash("已记录离开时间");
      await this.loadVisitors();
    } catch (error) {
      if (state === this.state) this.host.fail(error as Error);
    } finally {
      if (state === this.state && epoch === Api.epoch) this.state.busy = false;
    }
  }

  async contact(visitor: Visitor): Promise<void> {
    const epoch = Api.epoch,
      state = this.state;
    try {
      const request: EmptyRequest = {};
      const result = await Api.post<ContactResult>(
        "/visitors/" + visitor.id + "/reveal-contact",
        request,
      );
      if (state !== this.state || epoch !== Api.epoch) return;
      await this.host.inform(
        visitor.visitorName + " · 联系电话",
        result.phone + "\n此次查看已记录。请仅用于本次来访联系。",
      );
    } catch (error) {
      if (state === this.state) this.host.fail(error as Error);
    }
  }

  studentChoice(): string {
    const selected = this.state.students.find(
      (s: Student): boolean => s.id === this.state.visitedStudentId,
    );
    return selected
      ? (selected.buildingName || "") +
          " " +
          (selected.roomNo || "") +
          " · " +
          selected.name
      : "请选择被访学生";
  }
}
