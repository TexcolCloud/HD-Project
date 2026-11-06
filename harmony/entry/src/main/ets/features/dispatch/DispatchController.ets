import { util } from '@kit.ArkTS';
import { Api, ApiError } from '../../services/Api';
import { SubmissionKeys } from '../../services/SubmissionKeys';
import { User, Repair, Building, BuildingPage, DispatchBoard, DispatchManager, DispatchTask,
  DispatchHeartbeatRequest, DispatchSettingsRequest, DispatchReplanRequest, DispatchClaimRequest,
  DispatchTaskActionRequest } from '../../models/ApiModels';
import { AppVisibility } from './AppVisibility';
import { ForegroundPoll } from './ForegroundPoll';
import { RequestFlight } from './RequestFlight';
import { RouteRevision } from './RouteRevision';

export class DispatchSnapshot {
  board: DispatchBoard | null;
  buildings: Building[];
  loading: boolean;
  busy: boolean;
  offline: boolean;
  error: string;
  notice: string;
  constructor(board: DispatchBoard | null, buildings: Building[], loading: boolean, busy: boolean,
    offline: boolean, error: string, notice: string) {
    this.board = board; this.buildings = buildings; this.loading = loading; this.busy = busy;
    this.offline = offline; this.error = error; this.notice = notice;
  }
}

// Session-scoped controller: RoutePage only renders snapshots and requests actions.
// It does not own a timer, infer assignments or change a repair's workflow locally.
export class DispatchController {
  private board: DispatchBoard | null = null;
  private buildings: Building[] = [];
  private loading: boolean = false;
  private busy: boolean = false;
  private offline: boolean = false;
  private error: string = '';
  private notice: string = '';
  private session: string = '';
  private userId: string = '';
  private epoch: number = 0;
  private listeners: Array<(snapshot: DispatchSnapshot) => void> = [];
  private unsubscribeVisibility: () => void = () => {};
  private onExpired: (error: Error) => void = () => {};
  private revision: RouteRevision = new RouteRevision();
  private keys: SubmissionKeys = new SubmissionKeys((): string => util.generateRandomUUID());
  private foreground: boolean = true;
  private boardRead: RequestFlight<DispatchBoard> = new RequestFlight<DispatchBoard>();
  private heartbeat: ForegroundPoll<DispatchBoard>;
  private viewPoll: ForegroundPoll<DispatchBoard>;

  constructor() {
    this.heartbeat = new ForegroundPoll<DispatchBoard>(() => this.pulse(),
      (board: DispatchBoard) => this.applyBoard(board), (error: Error) => this.handle(error));
    this.viewPoll = new ForegroundPoll<DispatchBoard>(() => this.fetchBoard(),
      (board: DispatchBoard) => this.applyBoard(board), (error: Error) => this.handle(error), 5000);
  }
  startSession(user: User, token: string, onExpired: (error: Error) => void): void {
    const identity = user.role === 'MANAGER' && token ? user.id + ':' + token : '';
    if (identity === this.session) return;
    this.stopSession();
    if (!identity) return;
    this.session = identity; this.userId = user.id; this.onExpired = onExpired;
    this.unsubscribeVisibility = AppVisibility.subscribe((foreground: boolean) => {
      this.foreground = foreground; this.heartbeat.setForeground(foreground); this.viewPoll.setForeground(foreground);
    });
    this.heartbeat.setSession(identity); this.syncViewPolling();
  }
  stopSession(): void {
    this.epoch++; this.heartbeat.stop(); this.viewPoll.stop(); this.boardRead.reset(); this.unsubscribeVisibility(); this.unsubscribeVisibility = () => {};
    this.session = ''; this.userId = ''; this.board = null; this.buildings = []; this.revision.clear(); this.keys.clear();
    this.error = ''; this.notice = ''; this.offline = false; this.loading = false; this.busy = false;
    this.emit();
  }
  snapshot(): DispatchSnapshot {
    return new DispatchSnapshot(this.board, this.buildings, this.loading, this.busy, this.offline, this.error, this.notice);
  }
  subscribe(listener: (snapshot: DispatchSnapshot) => void): () => void {
    this.listeners.push(listener); listener(this.snapshot()); this.syncViewPolling();
    return (): void => {
      this.listeners = this.listeners.filter((item: (snapshot: DispatchSnapshot) => void): boolean => item !== listener);
      this.syncViewPolling();
    };
  }
  private syncViewPolling(): void {
    this.viewPoll.setSession(this.session && this.listeners.length ? this.session : '');
  }
  private fetchBoard(): Promise<DispatchBoard> {
    return this.boardRead.run(this.epoch, (): Promise<DispatchBoard> => Api.request<DispatchBoard>('/dispatch/board'));
  }
  private emit(): void { for (const listener of this.listeners.slice()) listener(this.snapshot()); }
  private active(epoch: number): boolean { return !!this.session && epoch === this.epoch; }
  private async pulse(): Promise<DispatchBoard> {
    const epoch = this.epoch;
    const request: DispatchHeartbeatRequest = {};
    await Api.post<DispatchManager>('/dispatch/heartbeat', request);
    if (!this.active(epoch) || !this.foreground) throw new Error('会话已切换或进入后台');
    return this.fetchBoard();
  }
  private applyBoard(board: DispatchBoard): void {
    if (!this.session || board.me.userId !== this.userId) return;
    const previous = this.revision.value();
    if (!this.revision.accept(board.revision)) return;
    if (previous >= 0 && board.revision > previous) {
      this.notice = '路线已更新至版本 ' + board.revision + (board.changes.length ? '：' + board.changes.slice(0, 3).join('；') : '，请核对剩余站点。');
    }
    this.error = '';
    this.board = board; this.offline = false; this.emit();
  }
  private handle(error: Error): void {
    const apiError = error as ApiError;
    if (apiError.status === 401) { this.onExpired(error); return; }
    this.offline = apiError.status === 0 || apiError.status === 503;
    this.error = error.message || '调度暂时不可用，请刷新后重试';
    if (apiError.traceId) this.error += '\n查询编号：' + apiError.traceId;
    this.emit();
  }
  dismissNotice(): void { this.notice = ''; this.emit(); }
  async refresh(showLoading: boolean = true): Promise<void> {
    if (!this.session) return;
    const epoch = this.epoch;
    if (showLoading) { this.loading = true; this.error = ''; this.emit(); }
    try {
      const board = await this.fetchBoard();
      if (this.active(epoch)) this.applyBoard(board);
    } catch (error) { if (this.active(epoch)) this.handle(error as Error); }
    finally { if (this.active(epoch)) { this.loading = false; this.emit(); } }
  }
  async loadBuildings(): Promise<void> {
    const epoch = this.epoch;
    if (!this.session) return;
    try {
      const page = await Api.request<BuildingPage>('/buildings?pageSize=100');
      if (this.active(epoch)) { this.buildings = page.items; this.emit(); }
    } catch (error) { if (this.active(epoch)) this.handle(error as Error); }
  }
  async replan(): Promise<void> {
    if (!this.board || !this.session || this.busy || this.offline) return;
    const epoch = this.epoch; this.busy = true; this.error = ''; this.emit();
    const request: DispatchReplanRequest = {expectedRevision: this.board.revision};
    try {
      const board = await Api.post<DispatchBoard>('/dispatch/replan', request, this.keys.reserve('replan', JSON.stringify(request)));
      if (this.active(epoch)) { this.keys.complete('replan'); this.applyBoard(board); }
    } catch (error) { if (this.active(epoch)) { this.handle(error as Error); await this.refresh(false); } }
    finally { if (this.active(epoch)) { this.busy = false; this.emit(); } }
  }
  async saveSettings(request: DispatchSettingsRequest): Promise<boolean> {
    if (!this.session || this.busy || this.offline) return false;
    const epoch = this.epoch; this.busy = true; this.error = ''; this.emit();
    try {
      await Api.patch<DispatchManager>('/dispatch/me', request,
        this.keys.reserve('settings', JSON.stringify(request)));
      if (!this.active(epoch)) return false;
      this.keys.complete('settings'); await this.refresh(false); return true;
    } catch (error) { if (this.active(epoch)) this.handle(error as Error); return false; }
    finally { if (this.active(epoch)) { this.busy = false; this.emit(); } }
  }
  async taskAction(task: DispatchTask, action: string, reason: string = ''): Promise<void> {
    if (!this.board || !this.session || this.busy || this.offline) return;
    const epoch = this.epoch; this.busy = true; this.error = ''; this.emit();
    const path = '/dispatch/tasks/' + task.orderId + '/' + action;
    try {
      if (action === 'claim') {
        const request: DispatchClaimRequest = {expectedOrderVersion: task.orderVersion, expectedRevision: this.board.revision};
        await Api.post<Repair>(path, request, this.keys.reserve(path, JSON.stringify(request)));
      } else if (['depart', 'arrive', 'block', 'resume'].includes(action)) {
        const request: DispatchTaskActionRequest = {expectedTaskVersion: task.version};
        if (reason.trim()) request.reason = reason.trim();
        await Api.post<DispatchTask>(path, request, this.keys.reserve(path, JSON.stringify(request)));
      } else throw new Error('无法识别这项路线操作');
      if (this.active(epoch)) { this.keys.complete(path); await this.refresh(false); }
    } catch (error) { if (this.active(epoch)) { this.handle(error as Error); await this.refresh(false); } }
    finally { if (this.active(epoch)) { this.busy = false; this.emit(); } }
  }
  async openRepair(orderId: string): Promise<Repair | null> {
    const epoch = this.epoch;
    if (!this.session) return null;
    try {
      const repair = await Api.request<Repair>('/repairs/' + orderId);
      return this.active(epoch) ? repair : null;
    } catch (error) { if (this.active(epoch)) this.handle(error as Error); return null; }
  }
}
