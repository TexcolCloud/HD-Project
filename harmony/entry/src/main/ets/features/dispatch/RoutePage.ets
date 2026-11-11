import { Repair, Building, DispatchBoard, DispatchTask, DispatchRoute, DispatchUnassigned, DispatchSettingsRequest } from '../../models/ApiModels';
import { DispatchController, DispatchSnapshot } from './DispatchController';
import { RouteTaskCard } from './RouteTaskCard';
import { RouteSettings } from './RouteSettings';
import { FormInput } from '../../components/FormInput';
import { palette } from '../../components/Palette';
import { dateText } from '../../services/Labels';

interface RouteCardArgs { task: DispatchTask | null; kind: string; canDepart: boolean; }

@Component
export struct RoutePage {
  controller: DispatchController = new DispatchController();
  @Prop night: boolean = false;
  onOpenRepair: (repair: Repair) => void = () => {};
  @State board: DispatchBoard | null = null;
  @State buildings: Building[] = [];
  @State loading: boolean = false;
  @State busy: boolean = false;
  @State offline: boolean = false;
  @State error: string = '';
  @State notice: string = '';
  @State showSettings: boolean = false;
  @State opening: boolean = false;
  @State blockedTask: DispatchTask | null = null;
  @State blockReason: string = '';
  private live: boolean = false;
  private unsubscribe: () => void = () => {};
  aboutToAppear(): void {
    this.live = true;
    this.unsubscribe = this.controller.subscribe((snapshot: DispatchSnapshot) => {
      if (!this.live) return;
      this.board = snapshot.board; this.buildings = snapshot.buildings; this.loading = snapshot.loading;
      this.busy = snapshot.busy; this.offline = snapshot.offline; this.error = snapshot.error; this.notice = snapshot.notice;
    });
    this.controller.refresh(); this.controller.loadBuildings();
  }
  aboutToDisappear(): void { this.live = false; this.unsubscribe(); }
  private route(): DispatchRoute | null {
    if (!this.board) return null;
    return this.board.routes.find((route: DispatchRoute): boolean => route.manager.userId === this.board?.me.userId) || null;
  }
  private tasks(): DispatchTask[] { return (this.route()?.tasks || []).slice().sort((a: DispatchTask, b: DispatchTask): number => a.sequence - b.sequence); }
  private current(): DispatchTask | null { return this.tasks().find((task: DispatchTask): boolean => task.state === 'ON_SITE') || null; }
  private next(): DispatchTask | null {
    return this.tasks().find((task: DispatchTask): boolean => task.state === 'EN_ROUTE') ||
      this.tasks().find((task: DispatchTask): boolean => ['ASSIGNED', 'RECOMMENDED'].includes(task.state)) || null;
  }
  private remaining(): DispatchTask[] {
    const currentId = this.current()?.orderId, nextId = this.next()?.orderId;
    return this.tasks().filter((task: DispatchTask): boolean => task.orderId !== currentId && task.orderId !== nextId && task.state !== 'BLOCKED');
  }
  private blocked(): DispatchTask[] { return this.tasks().filter((task: DispatchTask): boolean => task.state === 'BLOCKED'); }
  private action(task: DispatchTask, action: string): void {
    if (action === 'block') { this.blockedTask = task; this.blockReason = ''; }
    else this.controller.taskAction(task, action);
  }
  private async submitBlock(): Promise<void> {
    if (!this.blockedTask || !this.blockReason.trim()) return;
    await this.controller.taskAction(this.blockedTask, 'block', this.blockReason);
    if (!this.controller.snapshot().error) { this.blockedTask = null; this.blockReason = ''; }
  }
  private async open(task: DispatchTask): Promise<void> {
    if (this.opening) return;
    this.opening = true;
    try { const repair = await this.controller.openRepair(task.orderId); if (this.live && repair) this.onOpenRepair(repair); }
    finally { if (this.live) this.opening = false; }
  }
  private async settings(request: DispatchSettingsRequest): Promise<void> {
    if (await this.controller.saveSettings(request)) this.showSettings = false;
  }
  @Builder
  private Card($$: RouteCardArgs) {
    RouteTaskCard({task: $$.task, kind: $$.kind, canDepart: $$.canDepart, night: this.night,
      busy: this.busy || this.loading || this.opening || this.offline, actionsEnabled: !this.board?.dirty,
      onAction: (task: DispatchTask, action: string) => this.action(task, action), onOpen: (task: DispatchTask) => this.open(task)})
  }
  build() {
    Column({space: 0}) {
      Row() {
        Text('本次路线').fontFamily('DormSerif').fontSize(29).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).layoutWeight(1)
        Button('刷新').id('route-refresh').height(48).fontSize(14).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent)
          .enabled(!this.loading && !this.busy).onClick(() => this.controller.refresh())
      }.width('100%').margin({bottom: 8})
      Text('当前现场、下一站与剩余安排。到达只标记位置，维修结果仍在工单中填写。').fontSize(13).lineHeight(23)
        .fontColor(palette(this.night).muted).width('100%').margin({bottom: 18})
      if (this.offline) {
        Text('暂时离线，显示上次缓存路线。请刷新并由服务端确认后再执行新安排。').fontSize(13).lineHeight(22)
          .fontColor(palette(this.night).danger).width('100%').padding(14).backgroundColor(palette(this.night).soft).margin({bottom: 14})
      }
      if (this.error) { Text(this.error).id('route-error').fontSize(13).lineHeight(22).fontColor(palette(this.night).danger).width('100%').margin({bottom: 14}) }
      if (this.notice) {
        Column({space: 8}) {
          Text(this.notice).id('route-change-notice').fontSize(13).lineHeight(23).fontColor(palette(this.night).ink).width('100%')
          Button('已了解本次变化').fontSize(13).height(44).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).onClick(() => this.controller.dismissNotice())
        }.width('100%').padding(14).backgroundColor(palette(this.night).soft).borderRadius(4).margin({bottom: 14})
      }
      if (this.loading) { LoadingProgress().width(24).height(24).color(palette(this.night).accent).margin({bottom: 12}) }
      if (this.board) {
        Row() {
          Column({space: 5}) {
            Text(this.board.onlineCount + ' 人在线 · ' + this.board.participatingCount + ' 人参与').id('route-manager-counts').fontSize(16).fontColor(palette(this.night).ink)
            Text('按活跃宿管账号去重 · 版本 ' + this.board.revision).fontSize(12).fontColor(palette(this.night).muted)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Button('接单与位置').id('route-settings').height(48).fontSize(13).fontColor(palette(this.night).ink)
            .backgroundColor(palette(this.night).surface).borderRadius(4).enabled(!this.busy).onClick(() => this.showSettings = !this.showSettings)
        }.width('100%').padding({top: 16, bottom: 16}).border({width: {top: 1, bottom: 1}, color: palette(this.night).line}).margin({bottom: 16})
        if (!this.board.me.acceptingNewTasks) { Text('已暂停新增任务；现有办理责任保留。').fontSize(13).fontColor(palette(this.night).accent).width('100%').margin({bottom: 14}) }
        if (this.board.me.acceptingNewTasks && (!this.board.me.availableUntil || Date.parse(this.board.me.availableUntil) <= Date.now())) {
          Text('尚未设置有效的本次可用截止时间，暂不参与新任务安排。请打开“接单与位置”填写。').id('route-availability-required')
            .fontSize(14).lineHeight(24).fontColor(palette(this.night).accent).width('100%').padding(14).backgroundColor(palette(this.night).soft).margin({bottom: 14})
        }
        Text(this.board.me.positionBuildingName ? '当前位置：' + this.board.me.positionBuildingName + ' · ' +
          (this.board.me.positionFloorNo === null ? '楼层待确认' : this.board.me.positionFloorNo === 0 ? '出入口' : this.board.me.positionFloorNo + '层') : '尚未确认当前位置，路线按默认出发点估算。')
          .fontSize(13).lineHeight(23).fontColor(palette(this.night).muted).width('100%').margin({bottom: 14})
        if (this.board.dirty) { Text('正在合并新的任务变化，当前为最近发布的计划。刷新后再确认新安排。').fontSize(13).lineHeight(22).fontColor(palette(this.night).accent).width('100%').margin({bottom: 14}) }
        if (this.showSettings) {
          RouteSettings({manager: this.board.me, buildings: this.buildings, night: this.night, busy: this.busy || this.offline,
            onSave: (request: DispatchSettingsRequest) => this.settings(request), onCancel: () => this.showSettings = false})
        }
        if (this.blockedTask) {
          Column({space: 12}) {
            Text('记录受阻：' + this.blockedTask.buildingName + ' ' + this.blockedTask.roomNo).fontSize(17).fontColor(palette(this.night).ink).width('100%')
            FormInput({label: '受阻原因', value: this.blockReason, multiline: true, inputId: 'route-block-reason', night: this.night,
              onChange: (value: string) => this.blockReason = value})
            Button('确认受阻').id('route-block-submit').height(48).width('100%').fontColor(palette(this.night).onPrimary).backgroundColor(palette(this.night).ink)
              .borderRadius(4).enabled(!this.busy && !!this.blockReason.trim()).onClick(() => this.submitBlock())
            Button('取消').height(44).fontColor(palette(this.night).muted).backgroundColor(Color.Transparent).onClick(() => this.blockedTask = null)
          }.width('100%').padding(16).backgroundColor(palette(this.night).soft).borderRadius(4).margin({bottom: 16})
        }
        if (this.current()) { this.Card({task: this.current(), kind: '当前现场', canDepart: false}) }
        if (this.next()) { this.Card({task: this.next(), kind: this.next()?.state === 'EN_ROUTE' ? '正在前往' : '下一站', canDepart: !this.current()}) }
        if (!this.tasks().length) { Text('当前没有待执行站点。新任务到来或人员状态变化后，剩余路线会更新。').fontSize(14).lineHeight(25)
          .fontColor(palette(this.night).muted).width('100%').padding(22).backgroundColor(palette(this.night).soft).margin({bottom: 18}) }
        if (this.remaining().length) { Text('后续站点').fontSize(18).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%').margin({top: 10, bottom: 14}) }
        ForEach(this.remaining(), (task: DispatchTask) => { this.Card({task: task, kind: '第 ' + task.sequence + ' 站', canDepart: false}) },
          (task: DispatchTask) => task.orderId + ':' + task.version + ':' + this.board?.revision)
        if (this.blocked().length) { Text('暂缓任务').fontSize(18).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%').margin({top: 10, bottom: 14}) }
        ForEach(this.blocked(), (task: DispatchTask) => { this.Card({task: task, kind: '等待恢复', canDepart: false}) },
          (task: DispatchTask) => task.orderId + ':' + task.version + ':' + this.board?.revision)
        if (this.board.unassigned.length) {
          Text('待安排').fontSize(18).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%').margin({top: 14, bottom: 12})
          ForEach(this.board.unassigned, (item: DispatchUnassigned) => {
            Column({space: 8}) {
              Text(item.task.buildingName + ' ' + item.task.roomNo + ' · ' + item.task.title).fontSize(15).fontColor(palette(this.night).ink).width('100%')
              Text(item.reason + ' · 候选 ' + item.candidateCount + ' 人').fontSize(13).lineHeight(22).fontColor(palette(this.night).muted).width('100%')
              Button('查看工单 →').height(48).fontSize(13).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).enabled(!this.offline).onClick(() => this.open(item.task))
            }.width('100%').alignItems(HorizontalAlign.Start).padding(14).border({width: 1, color: palette(this.night).line}).borderRadius(4).margin({bottom: 10})
          }, (item: DispatchUnassigned) => item.task.orderId + ':' + this.board?.revision)
        }
        Button(this.busy ? '正在更新…' : '更新剩余路线').id('route-replan').width('100%').height(50).fontSize(16).fontColor(palette(this.night).onPrimary)
          .backgroundColor(palette(this.night).ink).borderRadius(4).enabled(!this.busy && !this.offline).onClick(() => this.controller.replan()).margin({top: 18, bottom: 12})
        Text('预计通行 ' + (this.route()?.travelMinutes ?? 0) + ' 分钟 · 等待 ' + (this.route()?.waitingMinutes ?? 0) + ' 分钟 · 剩余工作约 ' + (this.route()?.workloadMinutes ?? 0) + ' 分钟')
          .fontSize(12).lineHeight(22).fontColor(palette(this.night).muted).width('100%')
        Text('室内通行与处理时间为估算。计划生成于 ' + dateText(this.board.generatedAt)).fontSize(12).lineHeight(22).fontColor(palette(this.night).muted).width('100%').margin({top: 8})
      } else if (!this.loading) {
        Text('尚未取得路线，请检查连接后刷新。').fontSize(14).fontColor(palette(this.night).muted).width('100%').padding(20)
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
