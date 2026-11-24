import { TravelEfficiency } from "./TravelEfficiency";
import { CampusAnchor, DispatchTask, DispatchRoute } from '../../models/ApiModels';
import { palette } from '../../components/Palette';
import { common } from '@kit.AbilityKit';
import { Api, ApiError } from '../../services/Api';
import { AppVisibility } from '../dispatch/AppVisibility';
import { DeviceActions } from './DeviceActions';
import { MobilityController, MobilitySnapshot } from './MobilityController';
import { DispatchController, DispatchSnapshot } from '../dispatch/DispatchController';

interface MobilityActionArgs { label: string; id: string; run: () => void; }

@Component
export struct MobilityPage {
  controller: MobilityController = new MobilityController();
  @Prop night: boolean = false;
  dispatch: DispatchController = new DispatchController();
  onOpenRoute: () => void = () => {};
  @State snapshot: MobilitySnapshot = new MobilitySnapshot(null, false, false, false, '', '', null, false);
  @State consent: boolean = false;
  @State backgroundRequested: boolean = false;
  @State preview: CampusAnchor | null = null;
  @State source: string = 'MANUAL';
  @State scanError: string = '';
  @State scanning: boolean = false;
  @State clock: number = Date.now();
  @State target: DispatchTask | null = null;
  @State dismissedTarget: string = '';
  private live: boolean = false;
  private timer: number = -1;
  private unsubscribe: () => void = () => {};
  private unsubscribeVisibility: () => void = () => {};
  private unsubscribeRoute: () => void = () => {};
  aboutToAppear(): void {
    this.live = true;
    this.unsubscribe = this.controller.subscribe((value: MobilitySnapshot): void => { if (this.live) this.snapshot = value; });
    this.unsubscribeRoute = this.dispatch.subscribe((value: DispatchSnapshot): void => {
      if (!this.live) return;
      const route = value.board?.routes.find((item: DispatchRoute): boolean => item.manager.userId === value.board?.me.userId);
      this.target = route?.tasks.find((task: DispatchTask): boolean => task.state === 'EN_ROUTE') ||
        route?.tasks.find((task: DispatchTask): boolean => task.state === 'ASSIGNED' || task.state === 'RECOMMENDED') || null;
    });
    this.unsubscribeVisibility = AppVisibility.subscribe((foreground: boolean): void => {
      this.stopClock(); if (foreground && this.live) this.tick();
    });
    this.controller.refresh();
  }
  aboutToDisappear(): void { this.live = false; this.unsubscribe(); this.unsubscribeRoute(); this.unsubscribeVisibility(); this.stopClock(); }
  private stopClock(): void { if (this.timer >= 0) clearTimeout(this.timer); this.timer = -1; }
  private tick(): void {
    this.clock = Date.now();
    // Foreground display expiry only. Native location callbacks drive uploads, never this timer.
    this.timer = setTimeout((): void => { if (this.live) this.tick(); }, 15000);
  }
  private fresh(): boolean {
    const context = this.snapshot.view?.context;
    return !!context?.observedAt && context.status === 'VALID' && this.clock - Date.parse(context.observedAt) <= 60000 &&
      !!context.expiresAt && Date.parse(context.expiresAt) > this.clock;
  }
  private canResume(): boolean {
    const session = this.snapshot.view?.session;
    return !!session && session.state === 'PAUSED' && this.snapshot.ownsSource && Date.parse(session.leaseExpiresAt) > this.clock &&
      session.backgroundRequested === this.backgroundRequested;
  }
  private locationText(): string {
    const context = this.snapshot.view?.context;
    if (!context || !this.fresh()) return '当前位置未知 · 楼层未知';
    return (context.buildingName || '未关联楼栋') + ' · ' + (context.floorNo !== null ? context.floorNo + '层' : '楼层未知');
  }
  private sourceText(): string {
    const context = this.snapshot.view?.context;
    if (!context?.observedAt) return '尚未确认位置，可扫码或手动选择楼栋位置';
    const age = Math.max(0, Math.floor((this.clock - Date.parse(context.observedAt)) / 1000));
    const source = context.source === 'QR' ? '扫码确认' : context.source === 'MANUAL' ? '人工确认' : context.source === 'GPS' ? '设备位置' : '未知来源';
    return source + ' · ' + age + '秒前 · ' + (this.fresh() ? '有效' : '已过期') + (context.accuracyMeters !== null ? '\n定位误差约 ' + Math.round(context.accuracyMeters) + ' 米' : '');
  }
  private async scan(): Promise<void> {
    if (!this.snapshot.ownsSource || this.snapshot.view?.session?.state !== 'ACTIVE') {
      this.scanError = '请先开启或继续本次出行，再确认当前位置'; return;
    }
    const epoch = Api.epoch;
    this.scanning = true; this.scanError = '';
    try {
      const anchor = await DeviceActions.scanAnchor(this.getUIContext().getHostContext() as common.UIAbilityContext);
      if (!this.live || epoch !== Api.epoch) return;
      if (!anchor) this.scanError = '该地点未登记、已停用或不在负责范围';
      else { this.preview = anchor; this.source = 'QR'; }
    } catch (error) { if (this.live && epoch === Api.epoch) this.scanError = (error as ApiError).message || '扫码已取消，可手工选择地点'; }
    finally { if (this.live && epoch === Api.epoch) this.scanning = false; }
  }
  private async confirm(): Promise<void> {
    const anchor = this.preview;
    if (!anchor) return;
    await this.controller.confirm(anchor, this.source);
    if (this.live && !this.controller.snapshot().error) this.preview = null;
  }
  private possibleArrival(): boolean {
    const context = this.snapshot.view?.context;
    return this.fresh() && !!this.target && this.dismissedTarget !== this.target.orderId && !!context && context.buildingId === this.target.buildingId &&
      context.floorNo !== null && context.floorNo === this.target.floorNo;
  }
  @Builder
  private Action($$: MobilityActionArgs) {
    Button($$.label, {type: ButtonType.Normal}).id($$.id).height(48).fontSize(14).fontColor(palette(this.night).ink).backgroundColor(palette(this.night).surface)
      .border({width: 1, color: palette(this.night).outline}).borderRadius(4).enabled(!this.snapshot.busy).onClick($$.run)
  }
  build() {
    Column({space: 16}) {
      Text('开启本次工作后，使用设备信息辅助规划路线。').fontSize(14).lineHeight(24).fontColor(palette(this.night).muted).width('100%')
      if (this.snapshot.error) { Text(this.snapshot.error).id('mobility-error').fontSize(14).fontColor(palette(this.night).danger).width('100%') }
      if (this.snapshot.notice) { Text(this.snapshot.notice).id('mobility-notice').fontSize(14).lineHeight(22).fontColor(palette(this.night).accent).width('100%') }
      Column({space: 10}) {
        Text(this.locationText()).id('mobility-location').fontSize(18).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%')
        Text(this.sourceText()).id('mobility-freshness').fontSize(14).lineHeight(22).fontColor(palette(this.night).muted).width('100%')
        Text(this.snapshot.collecting ? (this.snapshot.background ? '设备采集中 · 后台持续任务已开启' : '设备采集中 · 仅前台') : '设备未采集 · 手工办理可用')
          .id('mobility-capture-state').fontSize(14).fontColor(palette(this.night).accent).width('100%')
        Text(this.snapshot.estimate?.speedMps !== undefined && this.clock - this.snapshot.estimate.position.observedMs <= 60000 ? '步行估计 ' + this.snapshot.estimate.speedMps.toFixed(1) + ' m/s · 参考质量 ' + (this.snapshot.estimate.quality === 'MEDIUM' ? '中' : '低') : '当前未测得步行速度')
          .id('mobility-speed').fontSize(14).fontColor(palette(this.night).ink).width('100%')
        Text(this.snapshot.view?.context?.explanation || '尚未配置校园地图，请手动确认位置。路线使用默认通行时间。')
          .fontSize(14).lineHeight(21).fontColor(palette(this.night).muted).width('100%')
      }.width('100%').padding(16).backgroundColor(palette(this.night).surface).borderRadius(4).border({width: 1, color: palette(this.night).line})
      if (this.possibleArrival()) {
        Text('你确认的位置与下一站楼栋／楼层相同，可能已接近目标；请现场核对房间后在路线页确认到达。').fontSize(14).lineHeight(22).fontColor(palette(this.night).accent).width('100%')
        this.Action({label: '核对下一站', id: 'mobility-open-route', run: this.onOpenRoute})
        this.Action({label: '稍后核对', id: 'mobility-dismiss-arrival', run: () => this.dismissedTarget = this.target?.orderId || ''})
      }
      if (!this.snapshot.collecting) {
        Row({space: 10}) {
          Checkbox().select(this.consent).selectedColor(palette(this.night).accent).onChange((value: boolean) => this.consent = value)
          Text('仅在本次工作期间使用位置与运动信息辅助规划路线，结束或退出后立即停止。').fontSize(14).lineHeight(22).fontColor(palette(this.night).ink).layoutWeight(1).constraintSize({minHeight:48}).onClick(() => this.consent = !this.consent)
        }.width('100%')
        Row() {
          Text('允许熄屏／后台出行').fontSize(14).fontColor(palette(this.night).ink).layoutWeight(1)
          Toggle({type: ToggleType.Switch, isOn: this.backgroundRequested}).selectedColor(palette(this.night).accent).onChange((value: boolean) => this.backgroundRequested = value)
        }.width('100%')
        Text('后台模式会显示系统持续任务通知，可返回本页暂停或结束。系统仍可能限制后台任务。').fontSize(14).lineHeight(21).fontColor(palette(this.night).muted).width('100%')
        if (!this.snapshot.view?.session || this.snapshot.ownsSource || this.snapshot.view.session.state !== 'ACTIVE') {
        Button(this.canResume() ? '继续本次出行' : this.snapshot.ownsSource ? '重新开启本次出行' : '开始本次出行', {type: ButtonType.Normal})
          .id('mobility-start').width('100%').height(52).fontColor(palette(this.night).onPrimary).backgroundColor(palette(this.night).ink).borderRadius(4)
          .enabled(this.consent && !this.snapshot.busy && !!this.snapshot.view).onClick(() => {
            if (this.canResume()) this.controller.action('resume');
            else this.controller.start(this.backgroundRequested, this.snapshot.ownsSource);
          })
        }
      }
      if (this.snapshot.ownsSource && this.snapshot.view?.session?.state === 'ACTIVE') { this.Action({label: '暂停采集', id: 'mobility-pause', run: () => this.controller.action('pause')}) }
      if (this.snapshot.ownsSource) { this.Action({label: '结束本次出行', id: 'mobility-end', run: () => this.controller.action('end')}) }
      if (this.snapshot.view?.session?.state === 'ACTIVE' && !this.snapshot.ownsSource) {
        Text('该账号有其他来源正在发布位置。接管会使旧来源失效，不会增加在线宿管人数。').fontSize(14).lineHeight(22).fontColor(palette(this.night).muted).width('100%')
        Button('确认在本机接管').id('mobility-takeover').height(48).fontColor(palette(this.night).accent).backgroundColor(palette(this.night).surface)
          .enabled(this.consent && !this.snapshot.busy).onClick(() => this.controller.start(this.backgroundRequested, true))
      }
      Text('确认当前位置').fontSize(18).fontColor(palette(this.night).ink).width('100%').margin({top: 12})
      Text('请先开始本次工作，再扫码或选择地点。二维码仅用于路线规划，不能作为考勤或进房凭证。').fontSize(14).lineHeight(21).fontColor(palette(this.night).muted).width('100%')
      this.Action({label: this.scanning ? '正在扫码…' : '扫描地点二维码', id: 'mobility-scan', run: () => { if (!this.scanning) this.scan(); }})
      if (this.scanError) { Text(this.scanError).fontSize(14).fontColor(palette(this.night).danger).width('100%') }
      ForEach(this.snapshot.view?.anchors || [], (anchor: CampusAnchor) => {
        Button({type: ButtonType.Normal}) { Text(anchor.buildingName + ' · ' + anchor.name + (anchor.floorNo !== null ? ' · ' + anchor.floorNo + '层' : '')).fontSize(14).fontColor(palette(this.night).ink) }
          .width('100%').constraintSize({minHeight: 48}).padding({top: 8, bottom: 8, left: 12, right: 12}).fontSize(14).fontColor(palette(this.night).ink).backgroundColor(palette(this.night).surface).borderRadius(4)
          .enabled(this.snapshot.ownsSource).onClick(() => { this.preview = anchor; this.source = 'MANUAL'; })
      }, (anchor: CampusAnchor) => anchor.id + ':' + anchor.version)
      if (!this.snapshot.view?.anchors.length) { Text('暂无已登记地点，请在网页端的现场位置页添加楼栋入口或楼层。').fontSize(14).lineHeight(22).fontColor(palette(this.night).muted).width('100%') }
      if (this.preview) {
        Column({space: 10}) {
          Text('请现场核对：' + this.preview.buildingName + ' · ' + this.preview.name).fontSize(16).fontColor(palette(this.night).ink).width('100%')
          Button('确认我在此处', {type: ButtonType.Normal}).id('mobility-confirm-anchor').width('100%').height(48).fontSize(14).fontColor(palette(this.night).ink)
            .backgroundColor(palette(this.night).surface).borderRadius(4).enabled(this.snapshot.ownsSource && this.snapshot.view?.session?.state === 'ACTIVE')
            .onClick(() => this.confirm())
          this.Action({label: '取消', id: 'mobility-cancel-anchor', run: () => this.preview = null})
        }.width('100%').padding(14).backgroundColor(palette(this.night).soft).borderRadius(4)
      }
      this.Action({label: '刷新会话与位置', id: 'mobility-refresh', run: () => this.controller.refresh()})
      TravelEfficiency({night: this.night, onError: (error: Error) => { this.scanError = error.message; }})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
