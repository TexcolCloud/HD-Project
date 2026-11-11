import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { Api, ApiError } from '../../services/Api';
import { User, CampusAnchor, MobilityView, MobilityContext, MobilitySample, MobilityStartRequest,
  MobilityActionRequest, MobilityBatchRequest, MobilityBatchAck, MobilityAnchorRequest } from '../../models/ApiModels';
import { AppVisibility } from '../dispatch/AppVisibility';
import { SystemMobility, DeviceMovement } from './SystemMobility';
import { MobilityEstimator, MovementEstimate } from './MobilityEstimator';

export class MobilitySnapshot {
  view: MobilityView | null;
  collecting: boolean;
  background: boolean;
  busy: boolean;
  error: string;
  notice: string;
  estimate: MovementEstimate | null;
  ownsSource: boolean;
  constructor(view: MobilityView | null, collecting: boolean, background: boolean, busy: boolean, error: string,
    notice: string, estimate: MovementEstimate | null, ownsSource: boolean) {
    this.view = view; this.collecting = collecting; this.background = background; this.busy = busy;
    this.error = error; this.notice = notice; this.estimate = estimate; this.ownsSource = ownsSource;
  }
}

// Explicit work sessions are independent of login activity and accepting-new-task settings.
// Native observations only provide evidence; no device callback executes repair/arrival actions.
export class MobilityController {
  private view: MobilityView | null = null;
  private context: common.UIAbilityContext | undefined = undefined;
  private epoch: number = -1;
  private userId: string = '';
  private clientId: string = '';
  private streamId: string = '';
  private ownerEpoch: number = -1;
  private run: number = 0;
  private command: number = 0;
  private leaseTimer: number = -1;
  private sequence: number = 0;
  private collecting: boolean = false;
  private busy: boolean = false;
  private sending: boolean = false;
  private lastSend: number = 0;
  private error: string = '';
  private notice: string = '';
  private estimate: MovementEstimate | null = null;
  private pending: MobilitySample[] = [];
  private retryBatch: MobilityBatchRequest | null = null;
  private estimator: MobilityEstimator = new MobilityEstimator();
  private device: SystemMobility = new SystemMobility();
  private listeners: Array<(snapshot: MobilitySnapshot) => void> = [];
  private unsubscribe: () => void = () => {};
  private expired: (error: Error) => void = () => {};
  attach(user: User, context: common.UIAbilityContext, onExpired: (error: Error) => void): void {
    this.detach();
    if (user.role !== 'MANAGER') return;
    this.userId = user.id; this.epoch = Api.epoch; this.context = context; this.expired = onExpired;
    this.clientId = util.generateRandomUUID();
    this.unsubscribe = AppVisibility.subscribe((foreground: boolean): void => {
      if (!foreground && this.collecting && !this.device.isBackgroundEnabled()) {
        this.notice = '已离开前台，采集暂停；返回后请明确继续。手工办理仍可用'; this.action('pause');
      }
    });
    this.refresh();
  }
  detach(): void {
    this.command++;
    this.stopDevice(); this.unsubscribe(); this.unsubscribe = () => {};
    this.epoch = -1; this.userId = ''; this.ownerEpoch = -1; this.view = null; this.context = undefined;
    this.busy = false; this.error = ''; this.notice = ''; this.emit();
  }
  snapshot(): MobilitySnapshot {
    return new MobilitySnapshot(this.view, this.collecting && this.device.isCollecting(), this.device.isBackgroundEnabled(), this.busy,
      this.error, this.notice, this.estimate, this.ownerEpoch >= 0 && this.ownerEpoch === this.view?.session?.sourceEpoch);
  }
  subscribe(listener: (snapshot: MobilitySnapshot) => void): () => void {
    this.listeners.push(listener); listener(this.snapshot());
    return (): void => { this.listeners = this.listeners.filter((item: (snapshot: MobilitySnapshot) => void): boolean => item !== listener); };
  }
  private emit(): void { for (const listener of this.listeners.slice()) listener(this.snapshot()); }
  private current(epoch: number): boolean { return epoch >= 0 && epoch === this.epoch && epoch === Api.epoch && !!this.userId; }
  private failure(error: Error): void {
    const detail = error as ApiError;
    if (detail.sessionEpoch !== undefined && detail.sessionEpoch !== Api.epoch) return;
    if (detail.code === 'SESSION_REPLACED') return;
    if (detail.status === 401) { this.detach(); this.expired(error); return; }
    this.error = error.message || '出行辅助暂时不可用，可继续手工办理';
    // A rejected lease/source must immediately stop all old native callbacks and uploads.
    if (detail.status === 403 || detail.status === 409 || detail.status === 410) {
      this.stopDevice(); this.ownerEpoch = -1;
    }
    this.emit();
  }
  private apply(view: MobilityView): void {
    if (view.session && view.session.userId !== this.userId) return;
    if (view.context && view.context.userId !== this.userId) return;
    if (this.view?.context && view.context && view.context.contextVersion < this.view.context.contextVersion) return;
    this.view = view;
    if (!view.session || view.session.state !== 'ACTIVE' || view.session.sourceEpoch !== this.ownerEpoch) this.stopDevice();
    else if (this.collecting) this.armLease();
    this.emit();
  }
  async refresh(): Promise<void> {
    const epoch = this.epoch, command = this.command;
    if (!this.current(epoch) || this.busy) return;
    try {
      const view = await Api.request<MobilityView>('/mobility/session');
      if (this.current(epoch) && command === this.command) { this.error = ''; this.apply(view); }
    } catch (error) { if ((this.current(epoch) && command === this.command) || (error as ApiError).status === 401) this.failure(error as Error); }
  }
  async start(background: boolean, takeover: boolean = false): Promise<void> {
    const epoch = this.epoch;
    if (!this.current(epoch) || this.busy || !this.context || !this.view) return;
    const command = ++this.command;
    this.busy = true; this.error = ''; this.emit();
    const request: MobilityStartRequest = { clientInstanceId: this.clientId, consentVersion: this.view.policy.consentVersion,
      backgroundRequested: background, takeover: takeover };
    if (this.view.session) request.expectedVersion = this.view.session.version;
    try {
      const view = await Api.post<MobilityView>('/mobility/sessions', request);
      if (!this.current(epoch) || command !== this.command) return;
      this.ownerEpoch = view.session?.sourceEpoch ?? -1; this.apply(view);
      await this.collect(background);
    } catch (error) { if ((this.current(epoch) && command === this.command) || (error as ApiError).status === 401) this.failure(error as Error); }
    finally { if (this.current(epoch) && command === this.command) { this.busy = false; this.emit(); } }
  }
  async action(action: string): Promise<void> {
    const epoch = this.epoch, session = this.view?.session;
    if (!this.current(epoch) || !session || this.ownerEpoch !== session.sourceEpoch) return;
    const command = ++this.command;
    // Local stop happens before the network operation, including an offline pause/end.
    if (action !== 'resume') this.stopDevice();
    const request: MobilityActionRequest = { sourceEpoch: session.sourceEpoch, expectedVersion: session.version };
    this.busy = true; this.error = ''; this.emit();
    try {
      const view = await Api.post<MobilityView>('/mobility/sessions/' + session.id + '/' + action, request);
      if (!this.current(epoch) || command !== this.command) return;
      if (action === 'resume') this.ownerEpoch = view.session?.sourceEpoch ?? -1;
      this.apply(view);
      if (action === 'resume') await this.collect(session.backgroundRequested);
      else if (action === 'end') { this.ownerEpoch = -1; this.notice = '本次出行已结束，设备采集已停止'; }
    } catch (error) { if ((this.current(epoch) && command === this.command) || (error as ApiError).status === 401) this.failure(error as Error); }
    finally { if (this.current(epoch) && command === this.command) { this.busy = false; this.emit(); } }
  }
  private async collect(background: boolean): Promise<void> {
    const context = this.context, epoch = this.epoch;
    if (!context || !this.current(epoch)) return;
    this.stopDevice(); const run = this.run;
    this.streamId = util.generateRandomUUID(); this.sequence = 0; this.collecting = true;
    this.armLease();
    this.notice = '正在申请设备能力；拒绝后仍可用人工锚点与工单'; this.emit();
    await this.device.start(context, background, (value: DeviceMovement): void => {
      if (this.current(epoch) && run === this.run) this.observe(value);
    }, (message: string, stopped: boolean = false): void => {
      if (this.current(epoch) && run === this.run) {
        this.notice = message;
        if (stopped) { this.stopDevice(); this.action('pause'); }
        this.emit();
      }
    });
    if (!this.current(epoch) || run !== this.run) { this.device.stop(); return; }
    this.collecting = this.device.isCollecting();
    this.emit();
  }
  private stopDevice(): void {
    if (this.leaseTimer >= 0) clearTimeout(this.leaseTimer); this.leaseTimer = -1;
    this.run++; this.device.stop(); this.collecting = false; this.pending = []; this.retryBatch = null;
    this.estimator.reset(); this.estimate = null; this.lastSend = 0;
  }
  private armLease(): void {
    if (this.leaseTimer >= 0) clearTimeout(this.leaseTimer);
    const lease = this.view?.session, epoch = this.epoch, run = this.run;
    if (!lease || lease.sourceEpoch !== this.ownerEpoch) return;
    // A one-shot expiry safeguard, not a background publication timer. Every observation also checks expiry.
    this.leaseTimer = setTimeout((): void => {
      if (!this.current(epoch) || run !== this.run) return;
      this.stopDevice(); this.error = '位置来源租约已过期，设备已停止，请重新开启'; this.emit();
    }, Math.max(1, Date.parse(lease.leaseExpiresAt) - Date.now()));
  }
  private observe(value: DeviceMovement): void {
    if (!this.view?.session || Date.parse(this.view.session.leaseExpiresAt) <= Date.now()) {
      this.stopDevice(); this.error = '位置来源租约已过期，请重新开启'; this.emit(); return;
    }
    const estimate = this.estimator.accept(value.position, Date.now());
    if (!estimate) return;
    if (estimate.speedMps === undefined && value.stepDelta !== undefined && value.stepDelta > 0) {
      estimate.motion = 'WALKING'; estimate.explanation = '计步表明有移动，未校准步长所以速度未知';
    }
    this.estimate = estimate;
    const sample: MobilitySample = { sequence: ++this.sequence, streamId: this.streamId,
      observedAt: new Date(estimate.position.observedMs).toISOString(), elapsedMs: estimate.position.elapsedMs,
      latitude: estimate.position.latitude, longitude: estimate.position.longitude, accuracyMeters: estimate.position.accuracyMeters,
      motion: estimate.motion, clientQuality: estimate.quality };
    if (estimate.speedMps !== undefined) sample.speedMps = estimate.speedMps;
    if (estimate.position.speedAccuracyMps !== undefined && Number.isFinite(estimate.position.speedAccuracyMps) && estimate.position.speedAccuracyMps >= 0) {
      sample.speedAccuracyMps = estimate.position.speedAccuracyMps;
    }
    if (value.stepDelta !== undefined) sample.stepDelta = value.stepDelta;
    const limit = Math.min(20, this.view?.policy.maxBatchSize || 20);
    this.pending = this.pending.concat([sample]).slice(-limit);
    this.emit(); this.flush();
  }
  private async flush(): Promise<void> {
    const epoch = this.epoch, run = this.run, session = this.view?.session, now = Date.now();
    if (!this.current(epoch) || !session || !this.collecting || this.sending || this.ownerEpoch !== session.sourceEpoch ||
      now - this.lastSend < Math.max(15000, (this.view?.policy.reportIntervalSeconds || 15) * 1000)) return;
    if (Date.parse(session.leaseExpiresAt) <= now) { this.stopDevice(); this.error = '位置来源租约已过期，请刷新并重新开启'; this.emit(); return; }
    this.pending = this.pending.filter((sample: MobilitySample): boolean => now - Date.parse(sample.observedAt) <= 60000);
    if (this.retryBatch && this.retryBatch.samples.some((sample: MobilitySample): boolean => now - Date.parse(sample.observedAt) > 60000)) this.retryBatch = null;
    if (!this.retryBatch && !this.pending.length) return;
    if (!this.retryBatch) {
      this.retryBatch = { sourceEpoch: session.sourceEpoch, batchId: util.generateRandomUUID(), samples: this.pending };
      this.pending = [];
    }
    const request = this.retryBatch;
    // ASCII numeric summaries only; strict byte upper bound before crossing the native transport.
    if (JSON.stringify(request).length > Math.min(16384, this.view?.policy.maxBodyBytes || 16384)) { this.retryBatch = null; return; }
    this.lastSend = now; this.sending = true;
    try {
      const ack = await Api.post<MobilityBatchAck>('/mobility/sessions/' + session.id + '/samples', request, request.batchId);
      if (!this.current(epoch) || run !== this.run) return;
      this.retryBatch = null; this.error = '';
      if (this.view && ack.context && (!this.view.context || ack.context.contextVersion >= this.view.context.contextVersion)) this.view.context = ack.context;
      // Renewed expiry is server-owned. Read it after the bounded accepted batch.
      await this.refresh();
    } catch (error) { if ((this.current(epoch) && run === this.run) || (error as ApiError).status === 401) this.failure(error as Error); }
    finally { this.sending = false; if (this.current(epoch) && run === this.run) this.emit(); }
  }
  async confirm(anchor: CampusAnchor, source: string = 'MANUAL'): Promise<void> {
    const epoch = this.epoch, session = this.view?.session;
    if (!this.current(epoch) || !session || this.ownerEpoch !== session.sourceEpoch || !anchor.enabled) return;
    const request: MobilityAnchorRequest = { sourceEpoch: session.sourceEpoch, anchorId: anchor.id, source,
      expectedContextVersion: this.view?.context?.contextVersion || 0 };
    try {
      const context = await Api.post<MobilityContext>('/mobility/sessions/' + session.id + '/anchor-confirmations', request);
      if (this.current(epoch) && this.view && (!this.view.context || context.contextVersion >= this.view.context.contextVersion)) {
        this.view.context = context; this.notice = '已确认地点；这不会自动到达或完成工单'; this.emit();
        await this.refresh();
        if (this.current(epoch) && this.view?.session?.id === session.id &&
          this.view.session.sourceEpoch === session.sourceEpoch && this.view.session.state === 'ACTIVE') this.armLease();
      }
    } catch (error) { if (this.current(epoch) || (error as ApiError).status === 401) this.failure(error as Error); }
  }
}
