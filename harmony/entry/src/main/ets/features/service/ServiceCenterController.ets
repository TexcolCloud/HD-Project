import { PhotoDraftController } from "../photos/PhotoDraftController";
import { beijingParts, availabilityTimestamp } from "../../services/Labels";
import { Api } from '../../services/Api';
import { util } from '@kit.ArkTS';
import { SubmissionKeys } from '../../services/SubmissionKeys';
import { Repair, ServiceMessage, ServiceMessagePage, ServiceMessageReadRequest, Remediation, RemediationPage, RemediationRequest, RemediationFeedbackRequest, RemediationReviewRequest, RemediationAssignmentRequest, RemediationCancelRequest, Hygiene, Student, StudentPage, HygienePage } from '../../models/ApiModels';
import { ReadScope, isSessionExpiry } from '../ReadScope';
import { WorkspaceHost } from '../WorkspaceHost';

@Observed
export class ServiceCenterState {
  messages: ServiceMessage[] = [];
  messagePage: number = 1;
  messageTotal: number = 0;
  unreadOnly: boolean = false;
  remediations: Remediation[] = [];
  remediationPage: number = 1;
  remediationTotal: number = 0;
  selected: Remediation | null = null;
  inspection: Hygiene | null = null;
  students: Student[] = [];
  rechecks: Hygiene[] = [];
  responsibleStudentId: string = '';
  issue: string = '';
  dueDay: string = '';
  dueClock: string = '18:00';
  feedback: string = '';
  reviewNote: string = '';
  assignmentReason: string = '';
  cancelReason: string = '';
  recheckId: string = '';
  loading: boolean = false;
  busy: boolean = false;
}

export class ServiceCenterController {
  state: ServiceCenterState = new ServiceCenterState();
  private view: number = 0;
  private reads: ReadScope = new ReadScope();
  private keys: SubmissionKeys = new SubmissionKeys((): string => util.generateRandomUUID());
  private host: WorkspaceHost;
  private photos: PhotoDraftController | null;
  constructor(host: WorkspaceHost, photos: PhotoDraftController | null = null) { this.host = host; this.photos = photos; }
  suspend(): void { this.view++; this.reads.invalidate(); this.state.loading = false; this.state.busy = false; }
  reset(): void { this.suspend(); this.keys.clear(); this.state = new ServiceCenterState(); }
  async loadMessages(): Promise<void> {
    const ticket = this.reads.begin();
    this.state.loading = true;
    try {
      const data = await Api.request<ServiceMessagePage>('/messages?pageSize=10&page=' + this.state.messagePage + '&unreadOnly=' + this.state.unreadOnly);
      if (this.reads.current(ticket)) { this.state.messages = data.items; this.state.messageTotal = data.total; }
    } catch (error) { if (this.reads.shouldReport(ticket, error as Error)) this.host.fail(error as Error); }
    finally { if (this.reads.current(ticket)) this.state.loading = false; }
  }
  async readMessage(message: ServiceMessage): Promise<void> {
    if (this.state.busy) return;
    const view = this.view, state = this.state, epoch = Api.epoch;
    this.state.busy = true;
    try {
      const body: ServiceMessageReadRequest = {expectedVersion: message.version};
      const path = '/messages/' + message.id + '/read';
      await Api.post<ServiceMessage>(path, body, this.keys.reserve(path, JSON.stringify(body)));
      if (state !== this.state || epoch !== Api.epoch || view !== this.view) return;
      this.keys.complete(path);
      await this.loadMessages();
    } catch (error) { if ((state === this.state && epoch === Api.epoch && view === this.view) || isSessionExpiry(error as Error)) this.host.fail(error as Error); }
    finally { if (state === this.state && view === this.view) this.state.busy = false; }
  }
  async openMessage(message: ServiceMessage): Promise<void> {
    const ticket = this.reads.begin();
    try {
      if (message.businessType === 'REPAIR') {
        const repair = await Api.request<Repair>('/repairs/' + message.businessId);
        if (this.reads.current(ticket)) await this.host.openRepair(repair);
      } else if (message.businessType === 'REMEDIATION') {
        await this.openRemediation(message.businessId);
      } else this.host.navigate('visitors');
    } catch (error) { if (this.reads.shouldReport(ticket, error as Error)) this.host.fail(error as Error); }
  }
  async loadRemediations(): Promise<void> {
    const ticket = this.reads.begin();
    this.state.loading = true;
    try {
      const data = await Api.request<RemediationPage>('/hygiene-remediations?pageSize=10&page=' + this.state.remediationPage);
      if (this.reads.current(ticket)) { this.state.remediations = data.items; this.state.remediationTotal = data.total; }
    } catch (error) { if (this.reads.shouldReport(ticket, error as Error)) this.host.fail(error as Error); }
    finally { if (this.reads.current(ticket)) this.state.loading = false; }
  }
  async openRemediation(id: string): Promise<void> {
    this.photos?.clear("REMEDIATION");
    const ticket = this.reads.begin();
    try {
      const item = await Api.request<Remediation>('/hygiene-remediations/' + id);
      if (!this.reads.current(ticket)) return;
      this.state.selected = item; this.state.feedback = ''; this.state.reviewNote = ''; this.state.recheckId = ''; this.state.rechecks = [];
      this.host.navigate('remediation-detail');
    } catch (error) { if (this.reads.shouldReport(ticket, error as Error)) this.host.fail(error as Error); }
  }
  async loadRechecks(): Promise<void> {
    const item = this.state.selected;
    if (!item) return;
    const ticket = this.reads.begin();
    try {
      const data = await Api.request<HygienePage>('/hygiene-inspections?roomId=' + item.roomId + '&pageSize=100');
      if (this.reads.current(ticket) && item === this.state.selected)
        this.state.rechecks = data.items.filter((h: Hygiene): boolean => !h.voidedAt && h.totalScore >= 60 && h.relatedInspectionId === item.inspectionId && h.id !== item.inspectionId && h.round.startsWith('RECHECK'));
    } catch (error) { if (this.reads.shouldReport(ticket, error as Error)) this.host.fail(error as Error); }
  }
  async prepare(inspection: Hygiene): Promise<void> {
    this.state.inspection = inspection; this.state.issue = inspection.issueNote || inspection.remediationNote;
    this.state.dueDay = beijingParts(new Date(Date.now() + 2 * 86400000).toISOString()).day; this.state.dueClock = "18:00";
    this.state.responsibleStudentId = ''; this.state.students = [];
    const ticket = this.reads.begin();
    try {
      const existing = await Api.request<RemediationPage>('/hygiene-remediations?inspectionId=' + inspection.id + '&pageSize=1');
      if (!this.reads.current(ticket)) return;
      if (existing.items.length) await this.openRemediation(existing.items[0].id);
      else this.host.navigate('remediation-create');
    } catch (error) { if (this.reads.shouldReport(ticket, error as Error)) this.host.fail(error as Error); }
  }
  async findResidents(): Promise<void> {
    const item = this.state.inspection || this.state.selected;
    if (!item) return;
    const ticket = this.reads.begin();
    try {
      const data = await Api.request<StudentPage>('/students?pageSize=100&buildingId=' + item.buildingId + '&roomId=' + item.roomId);
      if (this.reads.current(ticket)) {
        this.state.students = data.items.filter((s: Student): boolean => s.roomId === item.roomId);
        if (!this.state.students.some((student: Student): boolean => student.id === this.state.responsibleStudentId))
          this.state.responsibleStudentId = this.state.students[0]?.id || '';
      }
    } catch (error) { if (this.reads.shouldReport(ticket, error as Error)) this.host.fail(error as Error); }
  }
  async create(): Promise<void> {
    if (this.state.busy || !this.state.inspection) return;
    const view = this.view, state = this.state, epoch = Api.epoch;
    this.state.busy = true;
    try {
      const deadline = availabilityTimestamp(state.dueDay, state.dueClock);
      const body: RemediationRequest = {inspectionId: state.inspection!.id, responsibleStudentId: state.responsibleStudentId, dueAt: deadline, issueNote: state.issue};
      const item = await Api.post<Remediation>('/hygiene-remediations', body, this.keys.reserve('create', JSON.stringify(body)));
      if (state !== this.state || epoch !== Api.epoch || view !== this.view) return;
      this.keys.complete('create');
      this.host.flash('整改任务已发送给责任学生'); await this.openRemediation(item.id);
    } catch (error) { if ((state === this.state && epoch === Api.epoch && view === this.view) || isSessionExpiry(error as Error)) this.host.fail(error as Error); }
    finally { if (state === this.state && view === this.view) this.state.busy = false; }
  }
  editAssignment(): void {
    const item = this.state.selected;
    if (!item) return;
    this.state.inspection = null;
    this.state.responsibleStudentId = item.responsibleStudentId;
    const due = beijingParts(item.dueAt);
    this.state.dueDay = due.day; this.state.dueClock = due.clock; this.state.assignmentReason = '';
    this.host.navigate('remediation-assignment');
  }
  async saveAssignment(): Promise<void> {
    const view = this.view, state = this.state, epoch = Api.epoch, item = state.selected;
    if (!item || state.busy) return;
    state.busy = true;
    try {
      const body: RemediationAssignmentRequest = {expectedVersion: item.version, responsibleStudentId: state.responsibleStudentId, dueAt: availabilityTimestamp(state.dueDay, state.dueClock), reason: state.assignmentReason};
      const path = '/hygiene-remediations/' + item.id;
      const updated = await Api.patch<Remediation>(path, body, this.keys.reserve(path, JSON.stringify(body)));
      if (state !== this.state || epoch !== Api.epoch || view !== this.view) return;
      this.keys.complete(path); state.selected = updated; this.host.flash('责任与期限已调整，请责任学生重新反馈'); this.host.navigate('remediation-detail');
    } catch (error) { if ((state === this.state && epoch === Api.epoch && view === this.view) || isSessionExpiry(error as Error)) this.host.fail(error as Error); }
    finally { if (state === this.state && view === this.view) state.busy = false; }
  }
  async cancel(): Promise<void> {
    const view = this.view, state = this.state, epoch = Api.epoch, item = state.selected;
    if (!item || state.busy || !state.cancelReason.trim()) return;
    const confirmed = await this.host.confirm('取消整改任务？', '保留任务和取消原因。取消不会标记验收通过。', '返回', '确认取消');
    if (!confirmed || state !== this.state || epoch !== Api.epoch || view !== this.view) return;
    const body: RemediationCancelRequest = {expectedVersion: item.version, reason: state.cancelReason};
    await this.act('cancel', body);
  }
  async feedback(imageIds: string[]): Promise<boolean> {
    const item = this.state.selected;
    if (!item || this.state.busy) return false;
    const body: RemediationFeedbackRequest = {expectedVersion: item.version, feedbackNote: this.state.feedback, imageIds: imageIds};
    return this.act('feedback', body);
  }
  async review(approved: boolean): Promise<void> {
    const item = this.state.selected;
    if (!item || this.state.busy) return;
    const body: RemediationReviewRequest = {expectedVersion: item.version, approved: approved, reviewNote: this.state.reviewNote};
    if (approved) body.relatedRecheckId = this.state.recheckId;
    await this.act('review', body);
  }
  private async act(action: string, body: Object): Promise<boolean> {
    const view = this.view, state = this.state, epoch = Api.epoch, item = state.selected;
    if (!item) return false;
    state.busy = true;
    try {
      const path = '/hygiene-remediations/' + item.id + '/' + action;
      const updated = await Api.post<Remediation>(path, body, this.keys.reserve(path, JSON.stringify(body)));
      if (state !== this.state || epoch !== Api.epoch || view !== this.view) return false;
      this.keys.complete(path);
      if (state.selected?.id !== item.id) return false;
      state.selected = updated; this.host.flash(action === 'feedback' ? '整改反馈已提交，等待宿管复查' : '验收结果已保存');
      return true;
    } catch (error) { if ((state === this.state && epoch === Api.epoch && view === this.view) || isSessionExpiry(error as Error)) this.host.fail(error as Error); return false; }
    finally { if (state === this.state && view === this.view) state.busy = false; }
  }
}
