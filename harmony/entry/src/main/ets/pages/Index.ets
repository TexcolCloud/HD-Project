import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { util } from '@kit.ArkTS';
import { Api, ApiError, UploadedPhoto } from '../services/Api';
import { SubmissionKeys, shouldReleaseAiKey } from '../services/SubmissionKeys';
import { pendingRepairPaths, newestUnique } from '../services/PendingRepairs';
import { statusLabel, categoryLabel, gradeLabel, actionLabel, dateText, today, beijingClock, inspectionTimestamp } from '../services/Labels';
import { SecureImage } from '../components/SecureImage';
import { palette } from '../components/Palette';
import { DispatchController } from '../features/dispatch/DispatchController';
import { RoutePage } from '../features/dispatch/RoutePage';
import { VisitWindowFields, VisitWindowDraft } from '../features/visits/VisitWindowFields';
import { VisitArrangement } from '../features/visits/VisitArrangement';
import { ManagerAssist } from '../features/assist/ManagerAssist';
import { MobilityController } from '../features/mobility/MobilityController';
import { MobilityPage } from '../features/mobility/MobilityPage';
import { PhotoDraftController, PhotoSnapshot } from '../features/photos/PhotoDraftController';
import { PhotoAttachments } from '../features/photos/PhotoAttachments';
import { StudentPlaceCheck } from '../features/mobility/StudentPlaceCheck';
import { RepairReminders } from '../features/mobility/RepairReminders';
import { User, Room, RoomPage, Student, StudentPage, Residence, ResidencePage, Repair, RepairPage,
  RepairEvent, FileAsset, Hygiene, HygienePage, Visitor, VisitorPage, Overview, LoginRequest,
  LoginResult, RepairRequest, ActionRequest, VisitorRequest, HygieneRequest, AiRequest, AiSuggestion
} from '../models/ApiModels';

interface FieldArgs { label: string; value: string; change: (value: string) => void; placeholder?: string; multiline?: boolean; }
interface ScoreArgs { label: string; hint: string; value: string; change: (value: string) => void; id: string; }
interface DetailArgs { label: string; value: string; }
interface RepairCardArgs { order: Repair; }

interface EmptyRequest { }
interface LeaveRequest { expectedVersion: number; }
interface PasswordRequest { oldPassword: string; newPassword: string; }
interface ContactResult { phone: string; }

@Entry
@Component
struct Index {
  @State user: User | null = null;
  @State page: string = 'home';
  @State night: boolean = false;
  @State busy: boolean = false;
  @State uploading: boolean = false;
  @State error: string = '';
  @State loading: boolean = false;
  @State username: string = '';
  @State password: string = '';
  @State server: string = Api.base;
  @State showServer: boolean = false;
  @State room: Room | null = null;
  @State rooms: Room[] = [];
  @State students: Student[] = [];
  @State targetSearch: string = '';
  @State roomSearch: string = '';
  @State residences: Residence[] = [];
  @State repairs: Repair[] = [];
  @State repairTotal: number = 0;
  @State repairPage: number = 1;
  @State repairFilter: string = '';
  @State repairSearch: string = '';
  @State selected: Repair | null = null;
  @State events: RepairEvent[] = [];
  @State hygiene: Hygiene[] = [];
  @State hygieneTotal: number = 0;
  @State hygienePage: number = 1;
  @State visitors: Visitor[] = [];
  @State visitorTotal: number = 0;
  @State visitorPage: number = 1;
  @State visitorSearch: string = '';
  @State visitorFilter: string = '';
  @State overview: Overview | null = null;
  @State repairTitle: string = '';
  @State repairDescription: string = '';
  @State repairCategory: string = 'OTHER';
  @State repairPriority: string = 'NORMAL';
  @State repairUrgentReason: string = '';
  @State contactName: string = '';
  @State contactPhone: string = '';
  @State contactTime: string = '';
  @State visitDraft: VisitWindowDraft = new VisitWindowDraft();
  @State reportPhotos: UploadedPhoto[] = [];
  @State resultPhotos: UploadedPhoto[] = [];
  @State inspectionPhotos: UploadedPhoto[] = [];
  @State failedReportPhotos: string[] = [];
  @State failedResultPhotos: string[] = [];
  @State failedInspectionPhotos: string[] = [];
  @State ai: AiSuggestion | null = null;
  @State aiBusy: boolean = false;
  @State aiConsent: boolean = false;
  @State aiError: string = '';
  @State aiAdopted: boolean = false;
  @State action: string = '';
  @State actionNote: string = '';
  @State visitorName: string = '';
  @State visitorPhone: string = '';
  @State visitedStudentId: string = '';
  @State visitorPurpose: string = '';
  @State visitorRemark: string = '';
  @State inspectionRoomId: string = '';
  @State inspectionDate: string = today();
  @State inspectionTime: string = beijingClock();
  @State inspectionRound: string = 'ROUTINE';
  @State inspectionRelated: string = '';
  @State floorScore: string = '23';
  @State deskScore: string = '23';
  @State bedScore: string = '23';
  @State bathroomScore: string = '23';
  @State issueNote: string = '';
  @State remediationNote: string = '';
  @State oldPassword: string = '';
  @State newPassword: string = '';
  private dispatch: DispatchController = new DispatchController();
  private repairReturnPage: string = 'repairs';
  private scroller: Scroller = new Scroller();
  private submissionKeys: SubmissionKeys = new SubmissionKeys((): string => util.generateRandomUUID());
  private inspectionDraftStarted: boolean = false;
  private aiEpoch: number = 0;
  private mobility: MobilityController = new MobilityController();
  private photoDrafts: PhotoDraftController = new PhotoDraftController((error: Error) => this.fail(error));
  private unsubscribePhotos: () => void = () => {};

  aboutToAppear(): void {
    this.unsubscribePhotos = this.photoDrafts.subscribe((snapshot: PhotoSnapshot) => this.photoChanged(snapshot));
    this.getUIContext().getFont().registerFont({familyName: 'DormSerif', familySrc: $rawfile('dorm-serif.ttf')});
    this.restoreSettings();
  }
  aboutToDisappear(): void { this.unsubscribePhotos(); this.photoDrafts.reset(); this.dispatch.stopSession(); this.mobility.detach(); }
  onBackPress(): boolean {
    if (this.user && this.page !== 'home') {
      this.go(this.page === 'action' ? 'detail' : this.page === 'detail' ? this.repairReturnPage : 'home');
      return true;
    }
    return false;
  }
  private bg(): string { return palette(this.night).canvas; }
  private surface(): string { return palette(this.night).surface; }
  private ink(): string { return palette(this.night).ink; }
  private muted(): string { return palette(this.night).muted; }
  private line(): string { return palette(this.night).line; }
  private soft(): string { return palette(this.night).soft; }
  private accent(): string { return palette(this.night).accent; }
  private onPrimary(): string { return palette(this.night).onPrimary; }
  private manager(): boolean { return this.user?.role === 'MANAGER'; }
  private async restoreSettings(): Promise<void> {
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const prefs = await preferences.getPreferences(context, 'dormlink-settings');
      this.server = await prefs.get('server', Api.base) as string;
      this.night = await prefs.get('night', false) as boolean;
      Api.base = Api.normalizeBase(this.server);
    } catch (_) { /* Defaults remain usable when settings have not been stored. */ }
  }
  private async saveSettings(): Promise<void> {
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    const prefs = await preferences.getPreferences(context, 'dormlink-settings');
    await prefs.put('server', Api.base); await prefs.put('night', this.night); await prefs.flush();
  }
  private flash(message: string): void {
    this.getUIContext().getPromptAction().showToast({message: message, duration: 3000});
  }
  private fail(error: Error): void {
    const apiError = error as ApiError;
    if (apiError.sessionEpoch !== undefined && apiError.sessionEpoch !== Api.epoch) return;
    if (apiError.code === 'SESSION_REPLACED') return;
    this.error = apiError.message || '操作未完成，请稍后重试';
    if (apiError.traceId) this.error += '\n查询编号：' + apiError.traceId;
    if (apiError.status === 401) { this.clearSession(); }
  }
  private requestKey(body: Object, scope: string): string {
    return this.submissionKeys.reserve(scope, JSON.stringify(body));
  }

  private async login(): Promise<void> {
    let epoch = Api.epoch;
    if (this.busy) return;
    this.error = '';
    if (!this.username.trim() || !this.password) { this.error = '请填写账号和密码'; return; }
    this.busy = true;
    try {
      Api.base = Api.normalizeBase(this.server);
      const request: LoginRequest = {username: this.username.trim(), password: this.password, clientType: 'MOBILE'};
      const result = await Api.post<LoginResult>('/auth/login', request);
      Api.assertCurrent(epoch);
      this.password = ''; request.password = '';
      if (!result.token) throw new ApiError('未获得移动端会话，请联系管理员');
      Api.token = result.token; epoch = Api.epoch; this.user = result.user;
      this.dispatch.startSession(result.user, result.token, (error: Error) => this.fail(error));
      this.mobility.attach(result.user, this.getUIContext().getHostContext() as common.UIAbilityContext, (error: Error) => this.fail(error));
      this.contactName = result.user.displayName; this.page = 'home';
      await this.saveSettings(); Api.assertCurrent(epoch); await this.loadHome();
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.busy = false; }
  }
  private async logout(): Promise<void> {
    const epoch = Api.epoch;
    this.dispatch.stopSession();
    this.mobility.detach();
    this.password = ''; this.oldPassword = ''; this.newPassword = '';
    this.busy = true;
    try { const request: EmptyRequest = {}; await Api.post<EmptyRequest>('/auth/logout', request); }
    catch (_) { /* Clear local session even after network loss. Server sessions have a fixed expiry. */ }
    finally { if (epoch === Api.epoch || !Api.token) { this.clearSession(); this.error = ''; this.busy = false; } }
  }
  private clearSession(): void {
    this.mobility.detach(); this.photoDrafts.reset();
    this.dispatch.stopSession(); this.visitDraft = new VisitWindowDraft(); this.repairReturnPage = 'repairs';
    this.aiEpoch++; this.aiBusy = false; this.uploading = false; this.busy = false;
    this.submissionKeys.clear(); this.inspectionDraftStarted = false;
    Api.token = ''; this.user = null; this.room = null; this.rooms = []; this.students = []; this.residences = [];
    this.repairs = []; this.events = []; this.hygiene = []; this.visitors = []; this.selected = null; this.overview = null;
    this.repairTitle = ''; this.repairDescription = ''; this.reportPhotos = []; this.resultPhotos = []; this.inspectionPhotos = [];
    this.contactName = ''; this.contactPhone = ''; this.contactTime = ''; this.actionNote = ''; this.repairUrgentReason = '';
    this.visitorName = ''; this.visitorPhone = ''; this.visitorPurpose = ''; this.visitorRemark = ''; this.visitedStudentId = '';
    this.issueNote = ''; this.remediationNote = ''; this.inspectionRelated = ''; this.inspectionRoomId = '';
    this.oldPassword = ''; this.newPassword = ''; this.password = ''; this.ai = null; this.aiConsent = false;
    this.aiError = ''; this.aiAdopted = false; this.page = 'home'; this.loading = false;
    this.failedReportPhotos = []; this.failedResultPhotos = []; this.failedInspectionPhotos = [];
    this.repairSearch = ''; this.visitorSearch = ''; this.repairFilter = ''; this.visitorFilter = ''; this.targetSearch = ''; this.roomSearch = '';
  }

  private go(page: string): void {
    this.page = page; this.error = ''; this.scroller.scrollTo({xOffset: 0, yOffset: 0});
    if (page === 'home') this.loadHome();
    else if (page === 'repairs') this.loadRepairs();
    else if (page === 'visitors') this.loadVisitors();
    else if (page === 'visitor-create') this.loadStudents();
    else if (page === 'hygiene') this.loadHygiene();
    else if (page === 'hygiene-create') {
      if (!this.inspectionDraftStarted) { this.inspectionDate = today(); this.inspectionTime = beijingClock(); this.inspectionDraftStarted = true; }
      this.loadRooms();
    }
    else if (page === 'room') this.loadResidences();
  }
  private async loadHome(): Promise<void> {
    const epoch = Api.epoch;
    this.loading = true;
    try {
      if (this.manager()) {
        const overview = await Api.request<Overview>('/statistics/overview');
        Api.assertCurrent(epoch); this.overview = overview;
        const pages = await Promise.all(pendingRepairPaths().map((path: string): Promise<RepairPage> => Api.request<RepairPage>(path)));
        Api.assertCurrent(epoch); this.repairs = newestUnique<Repair>(pages.map((page: RepairPage): Repair[] => page.items),
          (order: Repair): string => order.id, (order: Repair): string => order.submittedAt, 3);
      } else {
        const room = await Api.request<Room | null>('/me/room');
        Api.assertCurrent(epoch); this.room = room;
        const repairs = await Api.request<RepairPage>('/repairs?pageSize=5'); Api.assertCurrent(epoch); this.repairs = repairs.items;
        const hygiene = await Api.request<HygienePage>('/me/hygiene-inspections?pageSize=3'); Api.assertCurrent(epoch); this.hygiene = hygiene.items;
      }
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.loading = false; }
  }
  private async loadRepairs(): Promise<void> {
    const epoch = Api.epoch;
    this.loading = true;
    try {
      const data = await Api.request<RepairPage>('/repairs?page=' + this.repairPage + '&pageSize=10&status=' +
        encodeURIComponent(this.repairFilter) + '&q=' + encodeURIComponent(this.repairSearch));
      Api.assertCurrent(epoch); this.repairs = data.items; this.repairTotal = data.total;
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.loading = false; }
  }
  private async openRepair(order: Repair): Promise<void> {
    const epoch = Api.epoch;
    if (!['route', 'detail', 'action'].includes(this.page)) this.repairReturnPage = 'repairs';
    this.loading = true; this.error = ''; this.page = 'detail'; this.scroller.scrollTo({xOffset: 0, yOffset: 0}); this.selected = order; this.events = [];
    try {
      const selected = await Api.request<Repair>('/repairs/' + order.id);
      Api.assertCurrent(epoch); if (this.selected?.id !== order.id) return; this.selected = selected;
      const events = await Api.request<RepairEvent[]>('/repairs/' + order.id + '/events');
      Api.assertCurrent(epoch); if (this.selected?.id === order.id) this.events = events;
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.loading = false; }
  }
  private async loadVisitors(): Promise<void> {
    const epoch = Api.epoch;
    this.loading = true;
    try {
      const data = await Api.request<VisitorPage>('/visitors?page=' + this.visitorPage + '&pageSize=10&q=' +
        encodeURIComponent(this.visitorSearch) + '&status=' + this.visitorFilter);
      Api.assertCurrent(epoch); this.visitors = data.items; this.visitorTotal = data.total;
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.loading = false; }
  }
  private async loadStudents(): Promise<void> {
    const epoch = Api.epoch;
    try { const data = await Api.request<StudentPage>('/students?pageSize=100&q=' + encodeURIComponent(this.targetSearch));
      Api.assertCurrent(epoch); this.students = data.items.filter((s: Student) => !!s.roomId);
      if (!this.students.some((s: Student): boolean => s.id === this.visitedStudentId)) this.visitedStudentId = this.students.length ? this.students[0].id : '';
    } catch (error) { this.fail(error as Error); }
  }
  private async loadRooms(): Promise<void> {
    const epoch = Api.epoch;
    try { const data = await Api.request<RoomPage>('/rooms?pageSize=100&q=' + encodeURIComponent(this.roomSearch));
      Api.assertCurrent(epoch); this.rooms = data.items;
      if (!this.rooms.some((r: Room): boolean => r.id === this.inspectionRoomId)) this.inspectionRoomId = this.rooms.length ? this.rooms[0].id : '';
    } catch (error) { this.fail(error as Error); }
  }
  private async loadHygiene(): Promise<void> {
    const epoch = Api.epoch;
    this.loading = true;
    try {
      const data = await Api.request<HygienePage>((this.manager() ? '/hygiene-inspections' : '/me/hygiene-inspections') +
        '?page=' + this.hygienePage + '&pageSize=10');
      Api.assertCurrent(epoch); this.hygiene = data.items; this.hygieneTotal = data.total;
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.loading = false; }
  }
  private async loadResidences(): Promise<void> {
    const epoch = Api.epoch;
    try { const data = await Api.request<ResidencePage>('/me/residences?pageSize=100'); Api.assertCurrent(epoch); this.residences = data.items; }
    catch (error) { this.fail(error as Error); }
  }
  private photoChanged(snapshot: PhotoSnapshot): void {
    this.uploading = snapshot.busy;
    if (snapshot.usage === 'REPORT') { this.reportPhotos = snapshot.draft.uploaded; this.failedReportPhotos = snapshot.draft.failed; }
    else if (snapshot.usage === 'RESULT') { this.resultPhotos = snapshot.draft.uploaded; this.failedResultPhotos = snapshot.draft.failed; }
    else { this.inspectionPhotos = snapshot.draft.uploaded; this.failedInspectionPhotos = snapshot.draft.failed; }
  }
  private async submitRepair(): Promise<void> {
    const epoch = Api.epoch;
    if (this.busy || this.uploading) return;
    this.error = '';
    if (this.failedReportPhotos.length) { this.error = '请重试或移除上传失败的图片后再提交'; return; }
    if (this.repairTitle.trim().length < 4 || this.repairDescription.trim().length < 4) { this.error = '标题和故障描述请至少填写4个字'; return; }
    if (!/^[0-9+ ]{7,20}$/.test(this.contactPhone) || !this.contactName.trim()) { this.error = '请填写联系人和有效联系电话'; return; }
    if (this.repairPriority === 'URGENT' && !this.repairUrgentReason.trim()) { this.error = '请说明较急的原因'; return; }
    const request: RepairRequest = {title: this.repairTitle.trim(), description: this.repairDescription.trim(),
      category: this.repairCategory, priority: this.repairPriority, contactName: this.contactName.trim(),
      contactPhone: this.contactPhone.trim(), contactTimeSlot: this.contactTime.trim(),
      reportImageIds: this.reportPhotos.map((p: UploadedPhoto): string => p.asset.id)};
    if (this.repairPriority === 'URGENT') request.urgentReason = this.repairUrgentReason.trim();
    try { const range = this.visitDraft.range(); if (range) { request.windowStart = range.start; request.windowEnd = range.end; } }
    catch (error) { this.error = (error as Error).message; return; }
    this.busy = true;
    try {
      const repair = await Api.post<Repair>('/repairs', request, this.requestKey(request, 'repair'));
      Api.assertCurrent(epoch);
      this.submissionKeys.complete('repair'); this.visitDraft = new VisitWindowDraft(); this.aiEpoch++; this.aiBusy = false;
      this.photoDrafts.clear('REPORT'); this.reportPhotos = []; this.repairTitle = ''; this.repairDescription = ''; this.ai = null; this.aiAdopted = false;
      this.repairPriority = 'NORMAL'; this.repairUrgentReason = ''; this.repairPage = 1;
      this.flash('报修已提交，可查看处理进度'); await this.openRepair(repair);
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.busy = false; }
  }

  private async suggest(): Promise<void> {
    if (this.aiBusy) return;
    this.aiError = '';
    if (!this.aiConsent) { this.aiError = '请先同意发送本次故障描述与所选图片进行分析'; return; }
    if (this.repairDescription.trim().length < 4) { this.aiError = '请先描述故障现象，至少4个字'; return; }
    const request: AiRequest = {description: this.repairDescription,
      imageIds: this.reportPhotos.slice(0, 3).map((p: UploadedPhoto): string => p.asset.id), consentToExternalAnalysis: true};
    const epoch = ++this.aiEpoch, token = Api.token;
    this.aiBusy = true;
    try {
      const suggestion = await Api.post<AiSuggestion>('/ai/repair-suggestions', request, this.requestKey(request, 'ai'));
      if (epoch !== this.aiEpoch || Api.token !== token) return;
      this.submissionKeys.complete('ai');
      if (this.repairDescription === request.description) { this.ai = suggestion; this.aiAdopted = false; }
      else this.aiError = '故障描述已修改，可按当前内容重新整理。';
    } catch (error) {
      if (epoch !== this.aiEpoch) return;
      const apiError = error as ApiError;
      if (apiError.status === 401 && !Api.token) { this.fail(error as Error); return; }
      if (Api.token !== token) return;
      if (shouldReleaseAiKey(apiError.code)) this.submissionKeys.complete('ai');
      this.aiError = apiError.message + '。已保留原文，可直接提交报修。';
    } finally { if (epoch === this.aiEpoch) this.aiBusy = false; }
  }
  private async beginAction(action: string): Promise<void> {
    const epoch = Api.epoch;
    if (action === 'cancel') {
      const answer = await this.getUIContext().getPromptAction().showDialog({title: '取消本次报修？',
        message: '仅待受理工单可取消。取消后再次报修需要新建工单。',
        buttons: [{text: '继续等待', color: this.muted()}, {text: '确认取消', color: this.accent()}]});
      if (epoch !== Api.epoch || answer.index !== 1) return;
    }
    this.action = action; this.actionNote = ''; this.photoDrafts.clear('RESULT'); this.resultPhotos = [];
    if (['accept', 'start', 'confirm', 'cancel'].includes(action)) this.runAction();
    else this.go('action');
  }
  private async runAction(): Promise<void> {
    const epoch = Api.epoch;
    if (!this.selected || this.busy || this.uploading) return;
    if (this.action === 'finish' && this.failedResultPhotos.length) { this.error = '请重试或移除上传失败的图片'; return; }
    if (['reject', 'rework', 'notes', 'finish'].includes(this.action) && this.actionNote.trim().length < 2) {
      this.error = '请填写具体说明，至少2个字'; return;
    }
    this.busy = true; this.error = '';
    const request: ActionRequest = {expectedVersion: this.selected.version};
    if (this.action === 'finish') { request.resultDescription = this.actionNote; request.resultImageIds = this.resultPhotos.map((p: UploadedPhoto): string => p.asset.id); }
    else if (this.action === 'notes') request.note = this.actionNote;
    else if (['reject', 'rework'].includes(this.action)) request.reason = this.actionNote;
    const path = '/repairs/' + this.selected.id + '/' + this.action;
    try {
      const updated = await Api.post<Repair>(path, request, this.requestKey(request, path));
      Api.assertCurrent(epoch);
      this.submissionKeys.complete(path);
      this.photoDrafts.clear('RESULT'); this.resultPhotos = []; this.flash('工单已更新'); await this.openRepair(updated);
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.busy = false; }
  }

  private async submitVisitor(confirmDuplicate: boolean = false): Promise<void> {
    const epoch = Api.epoch;
    if (this.busy) return;
    this.error = '';
    if (!this.visitorName.trim() || !/^[0-9+ ]{7,20}$/.test(this.visitorPhone) || !this.visitedStudentId || !this.visitorPurpose.trim()) {
      this.error = '请填写访客姓名、有效电话、被访学生和来访事由'; return;
    }
    const request: VisitorRequest = {visitorName: this.visitorName.trim(), phone: this.visitorPhone.trim(),
      visitedStudentId: this.visitedStudentId, purpose: this.visitorPurpose.trim(), remark: this.visitorRemark, confirmDuplicate: confirmDuplicate};
    this.busy = true;
    try {
      await Api.post<Visitor>('/visitors', request, this.requestKey(request, 'visitor'));
      Api.assertCurrent(epoch);
      this.submissionKeys.complete('visitor');
      this.visitorName = ''; this.visitorPhone = ''; this.visitorPurpose = ''; this.visitorRemark = '';
      this.flash('来访登记已保存'); this.visitorPage = 1; this.go('visitors');
    } catch (error) {
      const apiError = error as ApiError;
      if (apiError.code === 'VISITOR_DUPLICATE_CONFIRMATION_REQUIRED') {
        this.busy = false;
        const response = await this.getUIContext().getPromptAction().showDialog({title: '已有相同电话的在访记录',
          message: '请确认是否为同一访客。确有新的来访需要时可继续登记。',
          buttons: [{text: '返回核对', color: this.muted()}, {text: '继续登记', color: this.accent()}]});
        if (epoch === Api.epoch && response.index === 1) this.submitVisitor(true);
      } else this.fail(error as Error);
    } finally { if (epoch === Api.epoch) this.busy = false; }
  }

  private async leave(visitor: Visitor): Promise<void> {
    const epoch = Api.epoch;
    if (this.busy) return;
    this.busy = true; this.error = '';
    try { const request: LeaveRequest = {expectedVersion: visitor.version};
      await Api.post<Visitor>('/visitors/' + visitor.id + '/leave', request, this.requestKey(request, 'leave-' + visitor.id));
      Api.assertCurrent(epoch);
      this.submissionKeys.complete('leave-' + visitor.id);
      this.flash('已记录离开时间'); await this.loadVisitors();
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.busy = false; }
  }

  private async contact(visitor: Visitor): Promise<void> {
    try {
      const request: EmptyRequest = {};
      const result = await Api.post<ContactResult>('/visitors/' + visitor.id + '/reveal-contact', request);
      await this.getUIContext().getPromptAction().showDialog({title: visitor.visitorName + ' · 联系电话',
        message: result.phone + '\n此次查看已记录。请仅用于本次来访联系。', buttons: [{text: '知道了', color: this.accent()}]});
    } catch (error) { this.fail(error as Error); }
  }
  private studentChoice(): string {
    const selected = this.students.find((s: Student): boolean => s.id === this.visitedStudentId);
    return selected ? (selected.buildingName || '') + ' ' + (selected.roomNo || '') + ' · ' + selected.name : '请选择被访学生';
  }
  private roomChoice(): string {
    const selected = this.rooms.find((r: Room): boolean => r.id === this.inspectionRoomId);
    return selected ? selected.buildingName + ' ' + selected.roomNo : '请选择检查宿舍';
  }
  private totalScore(): number { return Number(this.floorScore) + Number(this.deskScore) + Number(this.bedScore) + Number(this.bathroomScore); }
  private scoreGrade(): string { const value = this.totalScore(); return value >= 90 ? '优秀' : value >= 80 ? '良好' : value >= 60 ? '合格' : '待整改'; }
  private recheck(item: Hygiene): void {
    this.inspectionRoomId = item.roomId; this.inspectionRelated = item.id; this.inspectionDate = today(); this.inspectionTime = beijingClock(); this.inspectionDraftStarted = true;
    const previous = item.round.startsWith('RECHECK') ? Number(item.round.replace('RECHECK', '') || '1') : 0;
    this.inspectionRound = previous ? 'RECHECK' + (previous + 1) : 'RECHECK'; this.issueNote = ''; this.remediationNote = ''; this.go('hygiene-create');
  }
  private async submitHygiene(): Promise<void> {
    if (this.busy || this.uploading) return;
    this.error = '';
    if (this.failedInspectionPhotos.length) { this.error = '请重试或移除上传失败的图片'; return; }
    const scoreValues = [this.floorScore, this.deskScore, this.bedScore, this.bathroomScore];
    if (scoreValues.some((s: string): boolean => !s.trim())) { this.error = '四项评分均需填写，最低可填0分'; return; }
    const scores = scoreValues.map((s: string): number => Number(s));
    if (scores.some((s: number): boolean => !Number.isInteger(s) || s < 0 || s > 25) || !this.inspectionRoomId) {
      this.error = '请选择宿舍，并将每项评分填写为0至25的整数'; return;
    }
    if (this.totalScore() < 60 && !this.issueNote.trim()) { this.error = '低于60分时，请说明检查发现的问题'; return; }
    let inspectedAt: string = '';
    try { inspectedAt = inspectionTimestamp(this.inspectionDate, this.inspectionTime); }
    catch (error) { this.error = (error as Error).message; return; }
    const request: HygieneRequest = {roomId: this.inspectionRoomId, inspectionDate: this.inspectionDate.trim(), inspectedAt: inspectedAt,
      round: this.inspectionRound, floorScore: scores[0], deskScore: scores[1], bedScore: scores[2], bathroomScore: scores[3],
      issueNote: this.issueNote.trim(), remediationNote: this.remediationNote.trim(),
      imageIds: this.inspectionPhotos.map((p: UploadedPhoto): string => p.asset.id)};
    if (this.inspectionRelated) request.relatedInspectionId = this.inspectionRelated;
    this.busy = true;
    try {
      const saved = await Api.post<Hygiene>('/hygiene-inspections', request, this.requestKey(request, 'hygiene'));
      this.submissionKeys.complete('hygiene'); this.inspectionDraftStarted = false;
      this.photoDrafts.clear('HYGIENE'); this.inspectionPhotos = []; this.inspectionRelated = ''; this.inspectionRound = 'ROUTINE';
      this.flash('卫生评分' + saved.totalScore + '分已保存'); this.hygienePage = 1; this.go('hygiene');
    } catch (error) { this.fail(error as Error); } finally { this.busy = false; }
  }
  private async changePassword(): Promise<void> {
    const epoch = Api.epoch;
    if (this.busy) return;
    if (this.newPassword.length < 8 || !this.oldPassword) { this.error = '请填写原密码，新密码至少8位'; return; }
    this.busy = true;
    try {
      const request: PasswordRequest = {oldPassword: this.oldPassword, newPassword: this.newPassword};
      await Api.post<EmptyRequest>('/auth/change-password', request, this.requestKey(request, 'password'));
      Api.assertCurrent(epoch);
      this.submissionKeys.complete('password');
      this.clearSession(); this.flash('密码已修改，请重新登录');
    } catch (error) { this.fail(error as Error); } finally { if (epoch === Api.epoch) this.busy = false; }
  }

  private pageTitle(): string {
    switch (this.page) {
      case 'repair-create': return '提交报修'; case 'repairs': return this.manager() ? '报修工单' : '我的报修';
      case 'detail': return '工单详情'; case 'action': return this.action === 'rework' && this.manager() ? '返工处理' : actionLabel(this.action);
      case 'visitor-create': return '登记访客'; case 'visitors': return '访客记录';
      case 'hygiene-create': return '卫生评分'; case 'hygiene': return '卫生记录';
      case 'route': return '本次路线';
      case 'mobility': return '出行辅助';
      case 'profile': return '我的'; case 'room': return '入住信息'; case 'password': return '修改密码'; case 'credits': return '图片来源';
      default: return '寓见';
    }
  }
  private statusColor(status: string): string {
    if (['SUBMITTED', 'PENDING_CONFIRMATION'].includes(status)) return this.night ? '#E5BE8B' : '#87532A';
    if (['ACCEPTED', 'PROCESSING'].includes(status)) return this.night ? '#BDCFD4' : '#466170';
    if (status === 'COMPLETED') return this.night ? '#B3CCA0' : '#3A6448';
    if (status === 'REJECTED') return this.night ? '#F1A99C' : '#A13E37';
    return this.muted();
  }
  private fieldId(label: string): string {
    if (label === '报修标题 *') return 'repair-title';
    if (label === '故障描述 *') return 'repair-description';
    if (label === '联系电话 *') return this.page === 'visitor-create' ? 'visitor-phone' : 'repair-phone';
    if (label === '访客姓名 *') return 'visitor-name';
    if (label === '来访事由 *') return 'visitor-purpose';
    if (label === '服务地址') return 'api-base-url';
    if (label === '检查日期 *') return 'hygiene-date';
    if (label === '实际检查时间 *') return 'hygiene-time';
    return 'field-' + label;
  }
  private primaryId(): string {
    if (this.page === 'repair-create') return 'repair-submit';
    if (this.page === 'visitor-create') return 'visitor-submit';
    if (this.page === 'hygiene-create') return 'hygiene-submit';
    if (this.page === 'action') return 'repair-action-submit';
    return 'primary-' + this.page;
  }
  @Builder
  private Label(text: string) { Text(text).fontSize(15).fontColor(this.ink()).fontWeight(FontWeight.Medium).width('100%') }
  @Builder
  private Field($$: FieldArgs) {
    Column({space: 8}) {
      this.Label($$.label)
      if ($$.multiline) {
        TextArea({text: $$.value, placeholder: $$.placeholder || ''}).id(this.fieldId($$.label)).onChange($$.change).fontSize(16).fontColor(this.ink())
          .placeholderColor(this.muted()).backgroundColor(this.surface()).border({width: 1, color: this.line()})
          .borderRadius(4).padding(12).height(112).maxLength(1000)
      } else {
        TextInput({text: $$.value, placeholder: $$.placeholder || ''}).id(this.fieldId($$.label)).onChange($$.change).fontSize(16).fontColor(this.ink())
          .placeholderColor(this.muted()).backgroundColor(this.surface()).border({width: 1, color: this.line()})
          .borderRadius(4).padding({left: 12, right: 12}).height(50).maxLength(150)
      }
    }.alignItems(HorizontalAlign.Start).width('100%').margin({bottom: 18})
  }
  @Builder
  private Primary(label: string, click: () => void, enabled: boolean = true) {
    Button(this.busy ? '正在保存…' : label).id(this.primaryId()).width('100%').height(50).fontSize(16).fontWeight(FontWeight.Medium)
      .backgroundColor(this.ink()).fontColor(this.onPrimary()).borderRadius(4)
      .enabled(enabled && !this.busy && !this.uploading).onClick(click)
  }
  @Builder
  private Secondary(label: string, click: () => void) {
    Button(label).height(48).fontSize(14).fontColor(this.ink()).backgroundColor(this.surface())
      .border({width: 1, color: this.line()}).borderRadius(4).enabled(!this.busy).onClick(click)
  }
  @Builder
  private Section(title: string, action: string = '', click: () => void = () => {}) {
    Row() {
      Text(title).fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink()).layoutWeight(1)
      if (action) { Button(action + ' →').fontSize(13).fontColor(this.ink()).backgroundColor(Color.Transparent).height(48).onClick(click) }
    }.width('100%').margin({top: 18, bottom: 8})
  }
  @Builder
  private Empty(message: string) {
    Text(message).fontSize(14).fontColor(this.muted()).lineHeight(24).width('100%').padding(24)
      .backgroundColor(this.soft()).borderRadius(4).textAlign(TextAlign.Center)
  }
  @Builder
  private DetailLine($$: DetailArgs) {
    Row({space: 16}) {
      Text($$.label).fontSize(13).fontColor(this.muted()).width(88)
      Text($$.value || '未填写').fontSize(14).fontColor(this.ink()).layoutWeight(1).textAlign(TextAlign.End)
    }.width('100%').padding({top: 10, bottom: 10}).border({width: {bottom: 0.5}, color: this.line()})
  }
  @Builder
  private Login() {
    Column({space: 0}) {
      Text('寓见').fontFamily('DormSerif').fontWeight(FontWeight.Medium).fontSize(24).fontColor(this.ink()).width('100%')
      Text('公寓里的日常，\n清楚，也从容。').fontFamily('DormSerif').fontWeight(FontWeight.Medium).fontSize(32)
        .fontColor(this.ink()).lineHeight(48).width('100%').margin({top: 40, bottom: 16})
      Text('报修、来访与巡检，从这里开始。').fontSize(14).fontColor(this.muted()).width('100%').margin({bottom: 38})
      this.Label('账号')
      TextInput({text: this.username, placeholder: '请输入学校提供的账号'}).id('login-username')
        .onChange((value: string) => this.username = value).height(52).fontSize(16).fontColor(this.ink())
        .placeholderColor(this.muted()).backgroundColor(this.surface()).border({width: 1, color: this.line()})
        .borderRadius(4).margin({top: 8, bottom: 22}).maxLength(64)
      this.Label('密码')
      TextInput({text: this.password, placeholder: '请输入密码'}).id('login-password').type(InputType.Password)
        .onChange((value: string) => this.password = value).height(52).fontSize(16).fontColor(this.ink())
        .placeholderColor(this.muted()).backgroundColor(this.surface()).border({width: 1, color: this.line()})
        .borderRadius(4).margin({top: 8, bottom: 28}).maxLength(100)
      Button(this.busy ? '正在登录…' : '登录').id('login-submit').width('100%').height(52).fontSize(16)
        .backgroundColor(this.ink()).fontColor(this.onPrimary()).borderRadius(4).enabled(!this.busy).onClick(() => this.login())
      Row() {
        Text('由账号决定学生或宿管身份').fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Button(this.night ? '日间' : '夜间').height(48).fontSize(13).fontColor(this.ink())
          .backgroundColor(Color.Transparent).onClick(() => { this.night = !this.night; this.saveSettings(); })
      }.margin({top: 12})
      Button(this.showServer ? '收起服务连接设置' : '服务连接设置').fontSize(12).fontColor(this.muted())
        .backgroundColor(Color.Transparent).height(48).onClick(() => this.showServer = !this.showServer)
      if (this.showServer) {
        this.Field({label: '服务地址', value: this.server, change: (value: string) => this.server = value, placeholder: 'http://电脑地址:8080/api/v1'})
        Text('模拟器默认通过本机端口转发连接；真机请填写可访问的服务地址。').fontSize(12).fontColor(this.muted()).lineHeight(21)
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding({left: 24, right: 24, top: 32, bottom: 36})
  }
  @Builder
  private RepairCard($$: RepairCardArgs) {
    Column({space: 10}) {
      Row() {
        Text($$.order.orderNo).fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Text(statusLabel($$.order.status)).fontSize(12).fontColor(this.statusColor($$.order.status)).padding({left: 8, right: 8, top: 3, bottom: 3})
          .backgroundColor(this.soft()).borderRadius(3)
      }.width('100%')
      Text($$.order.title).fontSize(17).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%')
      Text($$.order.buildingName + ' ' + $$.order.roomNo + ' · ' + categoryLabel($$.order.category)).fontSize(13).fontColor(this.muted()).width('100%')
      Row() {
        Text(dateText($$.order.submittedAt)).fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Text('查看进度 →').fontSize(13).fontColor(this.accent())
      }.width('100%')
    }.padding(18).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.surface())
      .border({width: 1, color: this.line()}).borderRadius(6).margin({bottom: 12}).onClick(() => this.openRepair($$.order))
  }
  @Builder
  private Home() {
    if (this.manager()) { this.ManagerHome() } else { this.StudentHome() }
  }
  @Builder
  private StudentHome() {
    Column({space: 0}) {
      Text((this.user?.displayName || '同学') + '，你好').fontSize(14).fontColor(this.muted()).width('100%')
      Text('我的公寓').fontFamily('DormSerif').fontSize(29).fontColor(this.ink()).fontWeight(FontWeight.Medium)
        .width('100%').margin({top: 8, bottom: 16})
      if (this.room) {
        Row() {
          Text(this.room.buildingName + ' ' + this.room.roomNo).fontSize(17).fontColor(this.ink()).layoutWeight(1)
          Text(this.room.capacity + '人间 · 在住').fontSize(13).fontColor(this.muted())
        }.width('100%').height(54).border({width: {top: 1, bottom: 1}, color: this.line()}).onClick(() => this.go('room'))
      } else { this.Empty('尚未关联宿舍，请联系宿管办理入住。') }
      Image($rawfile('campus-courtyard.jpg')).width('100%').height(150).objectFit(ImageFit.Cover).borderRadius(2).margin({top: 16})
      Text('校园氛围示意 · Nick Allen / CC BY-SA 3.0  ↗').fontSize(11).fontColor(this.muted()).width('100%').height(36)
        .onClick(() => this.go('credits'))
      Button() {
        Row({space: 16}) {
          Text('＋').fontSize(27).fontColor(this.onPrimary())
          Column({space: 4}) { Text('提交宿舍报修').fontSize(17).fontColor(this.onPrimary()).fontWeight(FontWeight.Medium); Text('描述问题，上传故障照片').fontSize(13).fontColor(this.onPrimary()) }
            .alignItems(HorizontalAlign.Start).layoutWeight(1)
          Text('→').fontSize(23).fontColor(this.onPrimary())
        }.width('100%')
      }.id('create-repair').width('100%').height(82).padding({left: 18, right: 18}).borderRadius(4)
        .backgroundColor(this.accent()).fontColor(this.onPrimary()).enabled(!!this.room).onClick(() => this.go('repair-create'))
      this.Section('最近报修', '查看全部', () => { this.repairPage = 1; this.repairFilter = ''; this.go('repairs'); })
      if (this.repairs.length) { this.RepairCard({order: this.repairs[0]}) } else { this.Empty('暂时没有报修记录。遇到设施问题时可在这里提交。') }
      Row() {
        Text('卫生记录').fontSize(15).fontColor(this.ink()).layoutWeight(1)
        Text(this.hygiene.length ? this.hygiene[0].totalScore + ' 分 · ' + gradeLabel(this.hygiene[0].grade) + ' →' : '查看 →')
          .fontSize(14).fontColor(this.muted())
      }.width('100%').height(56).border({width: {top: 1, bottom: 1}, color: this.line()}).onClick(() => { this.hygienePage = 1; this.go('hygiene'); })
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private ManagerHome() {
    Column({space: 0}) {
      Text((this.user?.displayName || '老师') + '，你好').fontFamily('DormSerif').fontWeight(FontWeight.Medium)
        .fontSize(27).fontColor(this.ink()).width('100%')
      Text('宿管 · 授权楼栋内的现场服务').fontSize(13).fontColor(this.muted()).width('100%').margin({top: 6, bottom: 20})
      Column({space: 10}) {
        Text('今日值班').fontSize(13).fontColor(this.onPrimary()).width('100%')
        Text('一处服务台，连接楼里的日常。').fontFamily('DormSerif').fontSize(21).fontColor(this.onPrimary()).fontWeight(FontWeight.Medium).width('100%')
        Text('登记来访、录入评分、跟进报修。').fontSize(14).fontColor(this.onPrimary()).width('100%')
      }.padding(20).width('100%').backgroundColor(this.ink()).borderRadius(4)
      Row() {
        Column({space: 6}) { Text(String(this.overview?.openRepairs ?? 0)).fontSize(34).fontColor(this.ink()).fontWeight(FontWeight.Medium); Text('待跟进工单').fontSize(13).fontColor(this.muted()) }
          .alignItems(HorizontalAlign.Start).layoutWeight(1).onClick(() => this.go('repairs'))
        Column({space: 6}) { Text(String(this.overview?.visitingCount ?? 0)).fontSize(34).fontColor(this.ink()).fontWeight(FontWeight.Medium); Text('当前在访').fontSize(13).fontColor(this.muted()) }
          .alignItems(HorizontalAlign.Start).layoutWeight(1).padding({left: 20}).border({width: {left: 1}, color: this.line()}).onClick(() => this.go('visitors'))
      }.width('100%').padding({top: 20, bottom: 20}).margin({top: 20, bottom: 18})
        .border({width: {top: 1, bottom: 1}, color: this.line()})
      Row({space: 12}) {
        Button() { Column({space: 6}) { Text('访客登记').fontSize(16).fontColor(this.ink()).fontWeight(FontWeight.Medium); Text('快速记录来访').fontSize(13).fontColor(this.muted()) } }
          .id('create-visitor').layoutWeight(1).height(88).borderRadius(4).backgroundColor(this.surface()).fontColor(this.ink())
          .border({width: 1, color: this.line()}).onClick(() => this.go('visitor-create'))
        Button() { Column({space: 6}) { Text('卫生评分').fontSize(16).fontColor(this.ink()).fontWeight(FontWeight.Medium); Text('现场录入检查').fontSize(13).fontColor(this.muted()) } }
          .id('create-hygiene').layoutWeight(1).height(88).borderRadius(4).backgroundColor(this.surface()).fontColor(this.ink())
          .border({width: 1, color: this.line()}).onClick(() => this.go('hygiene-create'))
      }.width('100%')
      Row({space: 12}) { this.Secondary('访客记录', () => this.go('visitors')); this.Secondary('卫生记录', () => this.go('hygiene')) }.margin({top: 12})
      Button('本次路线 · 查看下一站 →').id('open-route').width('100%').height(52).fontSize(16).fontColor(this.onPrimary())
        .backgroundColor(this.ink()).borderRadius(4).margin({top: 18, bottom: 4}).onClick(() => this.go('route'))
      Button('本次出行辅助 · 位置与设备').id('open-mobility').width('100%').height(48).fontSize(14).fontColor(this.accent())
        .backgroundColor(this.surface()).borderRadius(4).margin({top: 8}).onClick(() => this.go('mobility'))
      this.Section('待跟进工单', '查看全部', () => { this.repairPage = 1; this.go('repairs'); })
      if (!this.repairs.length) { this.Empty('暂时没有工单。') }
      ForEach(this.repairs.slice(0, 3), (item: Repair) => { this.RepairCard({order: item}) }, (item: Repair) => item.id + ':' + item.version)
      ManagerAssist({night: this.night, onOpenRepair: (repair: Repair) => this.openRepair(repair), onUnauthorized: (error: Error) => this.fail(error)})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private Uploads(usage: string) {
    PhotoAttachments({usage: usage, controller: this.photoDrafts, night: this.night, disabled: this.busy})
  }
  @Builder
  private RepairCreate() {
    Column({space: 0}) {
      Text('报修房间依据当前入住信息关联，请描述现象和发生位置。').fontSize(14).fontColor(this.muted()).lineHeight(24)
        .width('100%').padding(14).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 22})
      this.DetailLine({label: '报修宿舍', value: this.room ? this.room.buildingName + ' ' + this.room.roomNo : '尚未关联宿舍'})
      StudentPlaceCheck({room: this.room, night: this.night, onUnauthorized: (error: Error) => this.fail(error)})
      Blank().height(20)
      this.Field({label: '报修标题 *', value: this.repairTitle, change: (v: string) => this.repairTitle = v, placeholder: '例如：洗手池排水缓慢'})
      this.Field({label: '故障描述 *', value: this.repairDescription, change: (v: string) => this.repairDescription = v, placeholder: '说明发生位置、现象和持续时间', multiline: true})
      this.Label('故障分类')
      Select([{value: '其他'}, {value: '水电'}, {value: '门窗家具'}, {value: '网络'}])
        .selected(['OTHER', 'UTILITIES', 'FURNITURE', 'NETWORK'].indexOf(this.repairCategory)).value(categoryLabel(this.repairCategory)).width('100%').height(50)
        .fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4)
        .onSelect((index: number) => this.repairCategory = ['OTHER', 'UTILITIES', 'FURNITURE', 'NETWORK'][index]).margin({top: 8, bottom: 20})
      this.Field({label: '联系人 *', value: this.contactName, change: (v: string) => this.contactName = v, placeholder: '方便宿管联系的姓名'})
      this.Field({label: '联系电话 *', value: this.contactPhone, change: (v: string) => this.contactPhone = v, placeholder: '请输入方便接听的电话'})
      this.Field({label: '方便联系时段', value: this.contactTime, change: (v: string) => this.contactTime = v, placeholder: '例如：工作日18:00后'})
      VisitWindowFields({draft: this.visitDraft, night: this.night, onChange: (draft: VisitWindowDraft) => this.visitDraft = draft})
      this.Label('紧急程度')
      Select([{value: '一般'}, {value: '较急'}]).selected(this.repairPriority === 'URGENT' ? 1 : 0).value(this.repairPriority === 'URGENT' ? '较急' : '一般').width('100%').height(50)
        .fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4)
        .onSelect((index: number) => this.repairPriority = index === 1 ? 'URGENT' : 'NORMAL').margin({top: 8, bottom: 20})
      if (this.repairPriority === 'URGENT') { this.Field({label: '较急原因 *', value: this.repairUrgentReason, change: (v: string) => this.repairUrgentReason = v, placeholder: '说明需要优先处理的情况', multiline: true}) }
      this.Uploads('REPORT')
      Column({space: 12}) {
        Text('报修填写助手').fontSize(17).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%')
        Text('根据本次描述和最多3张所选图片整理建议，核对后再采纳。').fontSize(13).fontColor(this.muted()).lineHeight(22).width('100%')
        Row({space: 8}) {
          Checkbox({name: 'analysis-consent'}).select(this.aiConsent).selectedColor(this.accent()).onChange((v: boolean) => this.aiConsent = v)
          Text('同意将本次故障描述与所选图片发送给AI服务分析').fontSize(13).fontColor(this.ink()).layoutWeight(1).lineHeight(22)
        }.width('100%')
        Button(this.aiBusy ? '正在整理…' : 'AI 帮我完善').id('repair-ai').height(48).width('100%').fontSize(15)
          .backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).enabled(!this.aiBusy && !this.uploading && !this.busy).onClick(() => this.suggest())
        if (this.aiError) { Text(this.aiError).fontSize(13).fontColor(this.night ? '#F1A99C' : '#A13E37').lineHeight(22).width('100%') }
        if (this.ai) {
          Text('AI建议，请核对').fontSize(14).fontColor(this.accent()).width('100%')
          Text(this.ai.title + '\n' + categoryLabel(this.ai.category) + '\n' + this.ai.summary).fontSize(14).fontColor(this.ink()).lineHeight(24).width('100%')
          if (this.ai.uncertainty) { Text(this.ai.uncertainty).fontSize(13).fontColor(this.muted()).width('100%') }
          ForEach(this.ai.questions, (question: string) => { Text('待补充：' + question).fontSize(13).fontColor(this.muted()).width('100%') })
          Button(this.aiAdopted ? '已采纳，可继续修改' : '采纳建议').height(48).width('100%').fontSize(15).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
            .onClick(() => { if (this.ai) { this.repairTitle = this.ai.title; this.repairDescription = this.ai.summary; this.repairCategory = this.ai.category; this.aiAdopted = true; } })
        }
      }.padding(16).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 24})
      this.Primary('提交报修', () => this.submitRepair(), !!this.room)
      Text('确认内容后提交。AI暂不可用时，仍可手动完成报修。').fontSize(12).fontColor(this.muted()).lineHeight(21).margin({top: 12})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Repairs() {
    Column({space: 0}) {
      Row({space: 8}) {
        TextInput({text: this.repairSearch, placeholder: '搜索工单或宿舍'}).id('repair-search').layoutWeight(1).height(48)
          .fontSize(15).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.repairSearch = v).onSubmit(() => { this.repairPage = 1; this.loadRepairs(); })
        this.Secondary('查询', () => { this.repairPage = 1; this.loadRepairs(); })
      }.width('100%').margin({bottom: 12})
      Select([{value: '全部状态'}, {value: '待受理'}, {value: '已接单'}, {value: '处理中'}, {value: '待确认'}, {value: '已完成'}, {value: '已取消'}, {value: '已驳回'}])
        .selected(['', 'SUBMITTED', 'ACCEPTED', 'PROCESSING', 'PENDING_CONFIRMATION', 'COMPLETED', 'CANCELLED', 'REJECTED'].indexOf(this.repairFilter))
        .value(this.repairFilter ? statusLabel(this.repairFilter) : '全部状态').height(48).width('100%').backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
        .onSelect((index: number) => {
          this.repairFilter = ['', 'SUBMITTED', 'ACCEPTED', 'PROCESSING', 'PENDING_CONFIRMATION', 'COMPLETED', 'CANCELLED', 'REJECTED'][index];
          this.repairPage = 1; this.loadRepairs();
        }).margin({bottom: 18})
      if (!this.manager()) { this.Primary('＋ 提交报修', () => this.go('repair-create'), !!this.room); Blank().height(18) }
      Text('共 ' + this.repairTotal + ' 条记录').fontSize(12).fontColor(this.muted()).width('100%').margin({bottom: 12})
      if (!this.repairs.length && !this.loading) { this.Empty('暂无符合条件的工单，请调整筛选条件。') }
      ForEach(this.repairs, (item: Repair) => { this.RepairCard({order: item}) }, (item: Repair) => item.id + ':' + item.version)
      Row({space: 12}) {
        Button('上一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.repairPage > 1 && !this.loading).onClick(() => { this.repairPage--; this.loadRepairs(); })
        Text(String(this.repairPage)).fontSize(14).fontColor(this.muted())
        Button('下一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.repairPage * 10 < this.repairTotal && !this.loading).onClick(() => { this.repairPage++; this.loadRepairs(); })
      }.width('100%').margin({top: 12})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private RepairDetail() {
    if (this.selected) {
    Column({space: 0}) {
      Row() {
        Text(this.selected.orderNo).fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Text(statusLabel(this.selected.status)).fontSize(13).fontColor(this.statusColor(this.selected.status)).padding(6).backgroundColor(this.soft()).borderRadius(3)
      }.width('100%')
      Text(this.selected.title).fontFamily('DormSerif').fontSize(27).fontWeight(FontWeight.Medium).fontColor(this.ink()).lineHeight(38)
        .width('100%').margin({top: 16, bottom: 20})
      this.DetailLine({label: '报修宿舍', value: this.selected.buildingName + ' ' + this.selected.roomNo})
      this.DetailLine({label: '报修学生', value: this.selected.reporterName})
      this.DetailLine({label: '故障分类', value: categoryLabel(this.selected.category)})
      this.DetailLine({label: '紧急程度', value: this.selected.priority === 'URGENT' ? '较急' : '一般'})
      if (this.selected.urgentReason) { this.DetailLine({label: '较急原因', value: this.selected.urgentReason}) }
      this.DetailLine({label: '联系电话', value: this.selected.contactPhone})
      this.DetailLine({label: '联系时段', value: this.selected.contactTimeSlot})
      this.DetailLine({label: '办理宿管', value: this.selected.handlerName || '等待接单'})
      this.DetailLine({label: '提交时间', value: dateText(this.selected.submittedAt)})
      VisitArrangement({repair: this.selected, manager: this.manager(), night: this.night,
        onSaved: (repair: Repair) => { this.openRepair(repair); this.dispatch.refresh(false); }, onUnauthorized: (error: Error) => this.fail(error)})
      RepairReminders({repair: this.selected, night: this.night})
      if (this.manager()) { ManagerAssist({orderId: this.selected.id, night: this.night, onOpenRepair: (repair: Repair) => this.openRepair(repair), onUnauthorized: (error: Error) => this.fail(error)}) }
      this.Section('问题描述')
      Text(this.selected.description).fontSize(16).fontColor(this.ink()).lineHeight(27).width('100%').copyOption(CopyOptions.InApp)
      if (this.selected.reportImages.length) {
        this.Section('故障图片')
        ForEach(this.selected.reportImages, (asset: FileAsset) => { SecureImage({url: asset.url, night: this.night}).margin({bottom: 10}) }, (asset: FileAsset) => asset.id)
      }
      if (this.selected.latestResult) {
        this.Section('处理结果')
        Text(this.selected.latestResult).fontSize(16).fontColor(this.ink()).lineHeight(27).width('100%')
      }
      if (this.selected.resultImages.length) {
        ForEach(this.selected.resultImages, (asset: FileAsset) => { SecureImage({url: asset.url, night: this.night}).margin({bottom: 10, top: 10}) }, (asset: FileAsset) => asset.id)
      }
      this.Section('处理进度')
      ForEach(this.events, (event: RepairEvent) => {
        Row({space: 12}) {
          Text('●').fontSize(9).fontColor(this.accent()).margin({top: 5})
          Column({space: 6}) {
            Text(statusLabel(event.toStatus) + ' · ' + event.operatorName).fontSize(15).fontColor(this.ink()).width('100%')
            if (event.note) { Text(event.note).fontSize(14).fontColor(this.ink()).lineHeight(24).width('100%') }
            Text(dateText(event.createdAt)).fontSize(12).fontColor(this.muted()).width('100%')
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        }.width('100%').alignItems(VerticalAlign.Top).padding({top: 8, bottom: 18})
      }, (event: RepairEvent) => event.id)
      if (!this.events.length && !this.loading) { this.Empty('暂无进度记录') }
      ForEach(this.selected.allowedActions, (action: string) => {
        Button(action === 'rework' && this.manager() ? '撤回结果并返修' : actionLabel(action)).id('repair-action-' + action)
          .width('100%').height(50).fontSize(16).fontColor(['reject', 'cancel'].includes(action) ? this.accent() : this.onPrimary())
          .backgroundColor(['reject', 'cancel'].includes(action) ? this.surface() : this.ink()).borderRadius(4)
          .enabled(!this.busy).onClick(() => this.beginAction(action)).margin({top: 10})
      }, (action: string) => action)
      this.Secondary('刷新进度', () => this.selected && this.openRepair(this.selected))
    }.width('100%').alignItems(HorizontalAlign.Start)
    }
  }
  @Builder
  private ActionForm() {
    Column({space: 0}) {
      Text(this.selected?.title || '').fontSize(19).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%').margin({bottom: 20})
      this.Field({label: this.action === 'finish' ? '处理结果 *' : this.action === 'notes' ? '进度说明 *' : '具体原因 *', value: this.actionNote, change: (v: string) => this.actionNote = v, placeholder: '记录现场情况，便于后续跟进', multiline: true})
      if (this.action === 'finish') {
        this.Uploads('RESULT')
        Text('提交后进入待确认，由报修学生确认是否解决。').fontSize(13).fontColor(this.muted()).lineHeight(23).margin({bottom: 20})
      }
      this.Primary(this.action === 'finish' ? '提交完工结果' : '确认提交', () => this.runAction())
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private VisitorCreate() {
    Column({space: 0}) {
      Text('仅登记本次来访所需信息，离开时补记离访时间。').fontSize(14).fontColor(this.muted()).lineHeight(24)
        .width('100%').padding(14).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 22})
      this.Field({label: '访客姓名 *', value: this.visitorName, change: (v: string) => this.visitorName = v, placeholder: '请输入姓名'})
      this.Field({label: '联系电话 *', value: this.visitorPhone, change: (v: string) => this.visitorPhone = v, placeholder: '请输入联系电话'})
      this.Label('被访学生 / 宿舍 *')
      Row({space: 8}) {
        TextInput({text: this.targetSearch, placeholder: '按学号或姓名查找'}).id('visitor-target-search').height(48).layoutWeight(1)
          .fontSize(14).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.targetSearch = v).onSubmit(() => this.loadStudents())
        this.Secondary('查找', () => this.loadStudents())
      }.width('100%').margin({top: 8, bottom: 4})
      if (this.students.length) {
        Select(this.students.map((s: Student): SelectOption => { return {value: (s.buildingName || '') + ' ' + (s.roomNo || '') + ' · ' + s.name}; }))
          .id('visitor-target').selected(this.students.findIndex((s: Student): boolean => s.id === this.visitedStudentId)).value(this.studentChoice())
          .onSelect((index: number) => this.visitedStudentId = this.students[index].id).width('100%').height(50)
          .fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4).margin({top: 8, bottom: 20})
      } else { this.Empty('暂无可登记的在住学生，请先核对入住信息。') }
      this.Field({label: '来访事由 *', value: this.visitorPurpose, change: (v: string) => this.visitorPurpose = v, placeholder: '例如：家属探访'})
      this.Field({label: '备注', value: this.visitorRemark, change: (v: string) => this.visitorRemark = v, placeholder: '可补充说明', multiline: true})
      Text('到访时间以保存时的服务端时间记录。').fontSize(12).fontColor(this.muted()).margin({bottom: 20})
      this.Primary('保存来访登记', () => this.submitVisitor())
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private Visitors() {
    Column({space: 0}) {
      this.Primary('＋ 登记访客', () => this.go('visitor-create'))
      Row({space: 8}) {
        TextInput({text: this.visitorSearch, placeholder: '访客姓名、电话或房间'}).id('visitor-search').height(48).layoutWeight(1)
          .fontSize(15).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.visitorSearch = v).onSubmit(() => { this.visitorPage = 1; this.loadVisitors(); })
        this.Secondary('查询', () => { this.visitorPage = 1; this.loadVisitors(); })
      }.width('100%').margin({top: 18, bottom: 12})
      Select([{value: '全部来访'}, {value: '当前在访'}, {value: '已离访'}]).selected(['', 'VISITING', 'LEFT'].indexOf(this.visitorFilter)).value(this.visitorFilter === 'VISITING' ? '当前在访' : this.visitorFilter === 'LEFT' ? '已离访' : '全部来访')
        .onSelect((index: number) => { this.visitorFilter = ['', 'VISITING', 'LEFT'][index]; this.visitorPage = 1; this.loadVisitors(); })
        .height(48).width('100%').backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).margin({bottom: 18})
      Text('共 ' + this.visitorTotal + ' 条记录').fontSize(12).fontColor(this.muted()).width('100%').margin({bottom: 12})
      if (!this.visitors.length && !this.loading) { this.Empty('暂无符合条件的访客记录。') }
      ForEach(this.visitors, (visitor: Visitor) => {
        Column({space: 8}) {
          Row() {
            Text(visitor.visitorName).fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink()).layoutWeight(1)
            Text(visitor.status === 'VISITING' ? '在访' : '已离访').fontSize(12).fontColor(visitor.status === 'VISITING' ? this.accent() : this.muted())
          }.width('100%')
          Text(visitor.buildingName + ' ' + visitor.roomNo + ' · ' + visitor.studentName).fontSize(14).fontColor(this.ink()).width('100%')
          Text(visitor.purpose).fontSize(14).fontColor(this.muted()).width('100%')
          Text('到访：' + dateText(visitor.arrivedAt)).fontSize(12).fontColor(this.muted()).width('100%')
          if (visitor.leftAt) { Text('离访：' + dateText(visitor.leftAt)).fontSize(12).fontColor(this.muted()).width('100%') }
          Row({space: 8}) {
            Button(visitor.phone + '  查看').fontSize(13).fontColor(this.ink()).height(48).backgroundColor(this.soft()).borderRadius(4).layoutWeight(1).onClick(() => this.contact(visitor))
            if (visitor.status === 'VISITING') {
              Button('登记离开').fontSize(14).height(48).fontColor(this.onPrimary()).backgroundColor(this.ink()).borderRadius(4)
                .enabled(!this.busy).onClick(() => this.leave(visitor))
            }
          }.width('100%')
        }.padding(18).width('100%').backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(6).margin({bottom: 12})
      }, (visitor: Visitor) => visitor.id + ':' + visitor.version)
      Row({space: 12}) {
        Button('上一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.visitorPage > 1 && !this.loading).onClick(() => { this.visitorPage--; this.loadVisitors(); })
        Text(String(this.visitorPage)).fontColor(this.muted()).fontSize(14)
        Button('下一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.visitorPage * 10 < this.visitorTotal && !this.loading).onClick(() => { this.visitorPage++; this.loadVisitors(); })
      }.width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private ScoreInput($$: ScoreArgs) {
    Row({space: 12}) {
      Column({space: 4}) { Text($$.label).fontSize(16).fontColor(this.ink()); Text($$.hint).fontSize(12).fontColor(this.muted()) }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
      TextInput({text: $$.value}).id($$.id).type(InputType.Number).width(80).height(50).fontSize(18).fontColor(this.ink())
        .backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4).textAlign(TextAlign.Center).maxLength(2).onChange($$.change)
    }.width('100%').padding({top: 14, bottom: 14}).border({width: {bottom: 1}, color: this.line()})
  }
  @Builder
  private HygieneCreate() {
    Column({space: 0}) {
      Row() {
        Column({space: 6}) { Text('卫生总分').fontSize(17).fontWeight(FontWeight.Medium).fontColor(this.ink()); Text('四项各25分 · 满分100分').fontSize(12).fontColor(this.muted()) }
          .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(String(this.totalScore())).id('hygiene-total').fontSize(36).fontColor(this.ink()).fontWeight(FontWeight.Medium)
        Text(this.scoreGrade()).fontSize(13).fontColor(this.muted()).margin({left: 6})
      }.width('100%').padding(18).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 24})
      this.Label('检查宿舍 *')
      Row({space: 8}) {
        TextInput({text: this.roomSearch, placeholder: '按房间号查找'}).id('hygiene-room-search').height(48).layoutWeight(1)
          .fontSize(14).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.roomSearch = v).onSubmit(() => this.loadRooms())
        this.Secondary('查找', () => this.loadRooms())
      }.width('100%').margin({top: 8, bottom: 4})
      if (this.rooms.length) {
        Select(this.rooms.map((r: Room): SelectOption => { return {value: r.buildingName + ' ' + r.roomNo}; })).id('hygiene-room')
          .selected(this.rooms.findIndex((r: Room): boolean => r.id === this.inspectionRoomId)).value(this.roomChoice()).onSelect((i: number) => this.inspectionRoomId = this.rooms[i].id)
          .width('100%').height(50).fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4).margin({top: 8, bottom: 20})
      } else { this.Empty('暂无可巡检的宿舍，请刷新或联系管理员。') }
      this.Field({label: '检查日期 *', value: this.inspectionDate, change: (v: string) => this.inspectionDate = v, placeholder: 'YYYY-MM-DD'})
      this.Field({label: '实际检查时间 *', value: this.inspectionTime, change: (v: string) => this.inspectionTime = v, placeholder: 'HH:mm，例如18:30'})
      Text('北京时间。补录时填写实际检查的日期与时刻，不使用保存时间代替。').fontSize(12).fontColor(this.muted()).lineHeight(21).margin({bottom: 18})
      this.Label('巡检轮次 *')
      if (this.inspectionRelated) { Text('关联复查轮次：' + this.inspectionRound).fontSize(13).fontColor(this.muted()).margin({top: 8}) }
      else { Text('需要复查时，请从卫生记录选择“录入整改复查”以关联原记录。').fontSize(12).fontColor(this.muted()).lineHeight(21).margin({top: 8}) }
      Select(this.inspectionRelated ? [{value: '日常巡检'}, {value: '整改复查'}] : [{value: '日常巡检'}]).selected(this.inspectionRound.startsWith('RECHECK') ? 1 : 0).value(this.inspectionRound.startsWith('RECHECK') ? '整改复查' : '日常巡检')
        .onSelect((i: number) => { if (i) { if (!this.inspectionRound.startsWith('RECHECK')) this.inspectionRound = 'RECHECK'; } else { this.inspectionRound = 'ROUTINE'; this.inspectionRelated = ''; } }).width('100%').height(50)
        .fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4).margin({top: 8, bottom: 20})
      this.ScoreInput({label: '地面', hint: '无垃圾、无积水', value: this.floorScore, change: (v: string) => this.floorScore = v, id: 'hygiene-floor'})
      this.ScoreInput({label: '桌面', hint: '物品摆放整齐', value: this.deskScore, change: (v: string) => this.deskScore = v, id: 'hygiene-desk'})
      this.ScoreInput({label: '床铺', hint: '床铺整洁、无杂物', value: this.bedScore, change: (v: string) => this.bedScore = v, id: 'hygiene-bed'})
      this.ScoreInput({label: '卫生间', hint: '洁净无异味', value: this.bathroomScore, change: (v: string) => this.bathroomScore = v, id: 'hygiene-bathroom'})
      Blank().height(22)
      this.Field({label: '问题与扣分说明', value: this.issueNote, change: (v: string) => this.issueNote = v, placeholder: '低于60分时必须说明问题', multiline: true})
      this.Field({label: '整改建议', value: this.remediationNote, change: (v: string) => this.remediationNote = v, placeholder: '写明可执行的改进建议', multiline: true})
      this.Uploads('INSPECTION')
      this.Primary('保存评分', () => this.submitHygiene())
      Text('同一宿舍、日期和轮次只保留一条有效记录。更正成绩请在Web中操作并说明原因。').fontSize(12).fontColor(this.muted()).lineHeight(21).margin({top: 12})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private HygieneList() {
    Column({space: 0}) {
      if (this.manager()) { this.Primary('＋ 录入卫生评分', () => this.go('hygiene-create')); Blank().height(18) }
      Text(this.manager() ? '授权楼栋内的巡检记录' : '仅展示本人有效入住期间的宿舍检查记录').fontSize(13).fontColor(this.muted()).lineHeight(22).width('100%').margin({bottom: 18})
      if (!this.hygiene.length && !this.loading) { this.Empty('暂时没有可查看的卫生记录。') }
      ForEach(this.hygiene, (item: Hygiene) => {
        Column({space: 12}) {
          Row() {
            Column({space: 6}) { Text(item.buildingName + ' ' + item.roomNo).fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink());
              Text(item.inspectionDate + ' · ' + (item.round === 'ROUTINE' ? '日常巡检' : '整改复查')).fontSize(12).fontColor(this.muted()) }
              .layoutWeight(1).alignItems(HorizontalAlign.Start)
            Column({space: 2}) { Text(String(item.totalScore)).fontSize(30).fontWeight(FontWeight.Medium).fontColor(this.ink()); Text(gradeLabel(item.grade)).fontSize(12).fontColor(this.muted()) }
          }.width('100%')
          Text('地面 ' + item.floorScore + '  ·  桌面 ' + item.deskScore + '  ·  床铺 ' + item.bedScore + '  ·  卫生间 ' + item.bathroomScore)
            .fontSize(13).fontColor(this.ink()).lineHeight(23).width('100%')
          if (item.issueNote) { Text('检查说明：' + item.issueNote).fontSize(14).fontColor(this.ink()).lineHeight(24).width('100%') }
          if (item.remediationNote) { Text('整改建议：' + item.remediationNote).fontSize(14).fontColor(this.muted()).lineHeight(24).width('100%') }
          Text('检查人：' + item.inspectorName).fontSize(12).fontColor(this.muted()).width('100%')
          ForEach(item.images, (asset: FileAsset) => { SecureImage({url: asset.url, night: this.night}) }, (asset: FileAsset) => asset.id)
          if (this.manager()) { this.Secondary('录入整改复查', () => this.recheck(item)) }
        }.padding(18).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(6).margin({bottom: 14})
      }, (item: Hygiene) => item.id + ':' + item.version)
      Row({space: 12}) {
        Button('上一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.hygienePage > 1 && !this.loading).onClick(() => { this.hygienePage--; this.loadHygiene(); })
        Text(String(this.hygienePage)).fontSize(14).fontColor(this.muted())
        Button('下一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.hygienePage * 10 < this.hygieneTotal && !this.loading).onClick(() => { this.hygienePage++; this.loadHygiene(); })
      }.width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private MyRoom() {
    Column({space: 0}) {
      if (this.room) {
        Text(this.room.buildingName + ' ' + this.room.roomNo).fontFamily('DormSerif').fontSize(30).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%').margin({bottom: 20})
        this.DetailLine({label: '楼层', value: this.room.floorNo + ' 层'})
        this.DetailLine({label: '房间容量', value: this.room.capacity + ' 人'})
        this.DetailLine({label: '当前状态', value: '在住'})
      } else { this.Empty('尚未关联宿舍，请联系宿管办理入住。') }
      this.Section('入住历史')
      ForEach(this.residences, (r: Residence) => {
        Column({space: 8}) {
          Text(r.buildingName + ' ' + r.roomNo).fontSize(18).fontColor(this.ink()).width('100%')
          Text('入住：' + dateText(r.checkedInAt)).fontSize(13).fontColor(this.muted()).width('100%')
          Text(r.checkedOutAt ? '退宿：' + dateText(r.checkedOutAt) : '当前有效入住').fontSize(13).fontColor(this.muted()).width('100%')
          if (r.reason) { Text(r.reason).fontSize(13).fontColor(this.muted()).width('100%') }
        }.padding(18).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.surface()).borderRadius(4).margin({bottom: 12})
      }, (r: Residence) => r.id + ':' + r.version)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private Profile() {
    Column({space: 0}) {
      Text(this.user?.displayName || '').fontFamily('DormSerif').fontSize(30).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%').margin({bottom: 18})
      this.DetailLine({label: '当前身份', value: this.manager() ? '宿管' : '学生'})
      this.DetailLine({label: '登录账号', value: this.user?.username || ''})
      if (!this.manager()) { this.DetailLine({label: '当前宿舍', value: this.room ? this.room.buildingName + ' ' + this.room.roomNo : '尚未入住'}) }
      Blank().height(24)
      if (!this.manager()) { this.Primary('查看入住信息', () => this.go('room')); Blank().height(12) }
      this.Secondary('修改密码', () => this.go('password'))
      Row() {
        Text('暖黑夜间').fontSize(16).fontColor(this.ink()).layoutWeight(1)
        Toggle({type: ToggleType.Switch, isOn: this.night}).selectedColor(this.accent()).onChange((v: boolean) => { this.night = v; this.saveSettings(); })
      }.width('100%').height(72).margin({top: 14}).border({width: {top: 1, bottom: 1}, color: this.line()})
      Text('数据仅限本人或负责楼栋范围。').fontSize(13).fontColor(this.muted()).width('100%').margin({top: 20, bottom: 24})
      Button('退出登录').id('profile-logout').width('100%').height(50).fontSize(16).fontColor(this.accent()).backgroundColor(this.surface())
        .border({width: 1, color: this.line()}).borderRadius(4).enabled(!this.busy).onClick(() => this.logout())
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private Password() {
    Column({space: 18}) {
      this.Label('原密码 *')
      TextInput({text: this.oldPassword, placeholder: '请输入原密码'}).type(InputType.Password).height(50).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).onChange((v: string) => this.oldPassword = v)
      this.Label('新密码 *')
      TextInput({text: this.newPassword, placeholder: '至少8位'}).type(InputType.Password).height(50).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).onChange((v: string) => this.newPassword = v)
      Text('修改成功后需要重新登录。').fontSize(13).fontColor(this.muted())
      this.Primary('确认修改密码', () => this.changePassword())
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private Credits() {
    Column({space: 18}) {
      Image($rawfile('campus-courtyard.jpg')).width('100%').height(200).objectFit(ImageFit.Cover)
      Text('校园氛围示意').fontFamily('DormSerif').fontSize(24).fontWeight(FontWeight.Medium).fontColor(this.ink())
      Text('摄影：Nick Allen（Wikimedia Commons 用户 Nickknack00）\n作品：Lanman Wright courtyard elevated.JPG\n许可：CC BY-SA 3.0\n本地处理：缩放为1200×801并压缩；未调色或合成。').fontSize(15).fontColor(this.ink()).lineHeight(26)
      Text('原始来源：https://commons.wikimedia.org/wiki/File:Lanman_Wright_courtyard_elevated.JPG\n\n许可：https://creativecommons.org/licenses/by-sa/3.0/').fontSize(13).fontColor(this.muted()).lineHeight(24).copyOption(CopyOptions.LocalDevice)
      Text('图片仅表达校园生活氛围，不代表本平台服务学校的真实宿舍。').fontSize(13).fontColor(this.muted()).lineHeight(24)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private Content() {
    if (this.page === 'home') { this.Home() }
    else if (this.page === 'repair-create' && !this.manager()) { this.RepairCreate() }
    else if (this.page === 'route' && this.manager()) {
      RoutePage({controller: this.dispatch, night: this.night, onOpenRepair: (repair: Repair) => { this.repairReturnPage = 'route'; this.openRepair(repair); }})
    }
    else if (this.page === 'mobility' && this.manager()) {
      MobilityPage({controller: this.mobility, dispatch: this.dispatch, night: this.night, onOpenRoute: () => this.go('route')})
    }
    else if (this.page === 'repairs') { this.Repairs() }
    else if (this.page === 'detail' && this.selected) { this.RepairDetail() }
    else if (this.page === 'action') { this.ActionForm() }
    else if (this.page === 'visitor-create' && this.manager()) { this.VisitorCreate() }
    else if (this.page === 'visitors' && this.manager()) { this.Visitors() }
    else if (this.page === 'hygiene-create' && this.manager()) { this.HygieneCreate() }
    else if (this.page === 'hygiene') { this.HygieneList() }
    else if (this.page === 'room' && !this.manager()) { this.MyRoom() }
    else if (this.page === 'password') { this.Password() }
    else if (this.page === 'credits') { this.Credits() }
    else { this.Profile() }
  }
  @Builder
  private Header() {
    Row({space: 12}) {
      if (this.page !== 'home') {
        Button('‹').id('page-back').fontSize(28).fontColor(this.ink()).backgroundColor(Color.Transparent).width(40).height(48)
          .onClick(() => this.go(this.page === 'action' ? 'detail' : this.page === 'detail' ? this.repairReturnPage : 'home'))
      }
      Text(this.pageTitle()).fontFamily('DormSerif').fontSize(24).fontWeight(FontWeight.Medium).fontColor(this.ink()).layoutWeight(1)
      Button(this.night ? '日间' : '夜间').id('theme-toggle').fontSize(13).fontColor(this.muted()).height(48).backgroundColor(Color.Transparent)
        .onClick(() => { this.night = !this.night; this.saveSettings(); })
    }.width('100%').padding({left: 16, right: 16, top: 4, bottom: 8}).backgroundColor(this.surface()).border({width: {bottom: 1}, color: this.line()})
  }
  @Builder
  private BottomTabs() {
    Row() {
      Button() { Column({space: 2}) { SymbolGlyph($r('sys.symbol.house')).fontSize(23).fontColor([this.page === 'home' ? this.accent() : this.muted()]); Text('首页').fontSize(12).fontColor(this.page === 'home' ? this.accent() : this.muted()) } }
        .id('tab-home').layoutWeight(1).height(58).fontColor(this.page === 'home' ? this.accent() : this.muted())
        .backgroundColor(Color.Transparent).borderRadius(0).onClick(() => this.go('home'))
      Button() { Column({space: 2}) { SymbolGlyph($r('sys.symbol.doc_text')).fontSize(21).fontColor([['repairs', 'detail', 'action'].includes(this.page) ? this.accent() : this.muted()]); Text(this.manager() ? '工单' : '我的报修').fontSize(12).fontColor(['repairs', 'detail', 'action'].includes(this.page) ? this.accent() : this.muted()) } }
        .id('tab-repairs').layoutWeight(1).height(58).fontColor(['repairs', 'detail', 'action'].includes(this.page) ? this.accent() : this.muted())
        .backgroundColor(Color.Transparent).borderRadius(0).onClick(() => { this.repairPage = 1; this.repairFilter = ''; this.go('repairs'); })
      Button() { Column({space: 2}) { SymbolGlyph($r('sys.symbol.person')).fontSize(21).fontColor([this.page === 'profile' ? this.accent() : this.muted()]); Text('我的').fontSize(12).fontColor(this.page === 'profile' ? this.accent() : this.muted()) } }
        .id('tab-profile').layoutWeight(1).height(58).fontColor(this.page === 'profile' ? this.accent() : this.muted())
        .backgroundColor(Color.Transparent).borderRadius(0).onClick(() => this.go('profile'))
    }.width('100%').backgroundColor(this.surface()).border({width: {top: 1}, color: this.line()})
  }
  build() {
    Column() {
      if (this.user) { this.Header() }
      if (this.error) {
        Row({space: 8}) {
          Text(this.error).id('error-message').fontSize(13).lineHeight(22).fontColor(this.night ? '#F1A99C' : '#A13E37').layoutWeight(1)
          Button('×').width(40).height(48).fontColor(this.ink()).backgroundColor(Color.Transparent).onClick(() => this.error = '')
        }.width('100%').padding({left: 16, right: 8, top: 8, bottom: 8}).backgroundColor(this.night ? '#442E2B' : '#F7EAE6')
      }
      if (this.loading) {
        Row({space: 8}) { LoadingProgress().width(20).height(20).color(this.accent()); Text('正在更新…').fontSize(12).fontColor(this.muted()) }
          .width('100%').padding(8).justifyContent(FlexAlign.Center)
      }
      Scroll(this.scroller) {
        Column() {
          if (this.user) { this.Content() } else { this.Login() }
        }.width('100%').constraintSize({maxWidth: 720}).padding({left: this.user ? 16 : 0, right: this.user ? 16 : 0, top: this.user ? 16 : 0, bottom: 28}).alignItems(HorizontalAlign.Start)
      }.id('page-scroll').layoutWeight(1).width('100%').scrollBar(BarState.Auto).align(Alignment.Top)
      if (this.user) { this.BottomTabs() }
    }.width('100%').height('100%').backgroundColor(this.bg())
  }
}
