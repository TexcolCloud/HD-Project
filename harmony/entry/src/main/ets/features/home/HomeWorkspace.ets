import { serviceSecondary, serviceSection, serviceEmpty, serviceDetail, repairStatusColor } from '../../components/ServiceParts';
import { User, Residence, Repair } from '../../models/ApiModels';
import { statusLabel, categoryLabel, gradeLabel, dateText } from '../../services/Labels';
import { palette } from '../../components/Palette';
import { WorkspaceHost, emptyWorkspaceHost } from '../WorkspaceHost';
import { ManagerAssist } from '../assist/ManagerAssist';
import { HomeState } from './HomeController';
interface RepairCardArgs { order: Repair; }

@Component
export struct HomeWorkspace {
  @ObjectLink state: HomeState;
  @Prop page: string = 'home';
  @Prop night: boolean = false;
  user: User | null = null;
  host: WorkspaceHost = emptyWorkspaceHost();
  onRepairListReset: () => void = () => {};
  onHygieneListReset: () => void = () => {};
  private manager(): boolean { return this.user?.role === 'MANAGER'; }
  private surface(): string { return palette(this.night).surface; }

  private ink(): string { return palette(this.night).ink; }

  private muted(): string { return palette(this.night).muted; }

  private line(): string { return palette(this.night).line; }

  private soft(): string { return palette(this.night).soft; }

  private accent(): string { return palette(this.night).accent; }

  private onPrimary(): string { return palette(this.night).onPrimary; }

  @Builder
  private RepairCard($$: RepairCardArgs) {
    Column({space: 10}) {
      Row() {
        Text($$.order.orderNo).fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Text(statusLabel($$.order.status)).fontSize(12).fontColor(repairStatusColor($$.order.status, this.night)).padding({left: 8, right: 8, top: 3, bottom: 3})
          .backgroundColor(this.soft()).borderRadius(3)
      }.width('100%')
      Text($$.order.title).fontSize(17).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%')
      Text($$.order.buildingName + ' ' + $$.order.roomNo + ' · ' + categoryLabel($$.order.category)).fontSize(13).fontColor(this.muted()).width('100%')
      Row() {
        Text(dateText($$.order.submittedAt)).fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Text('查看进度 →').fontSize(13).fontColor(this.accent())
      }.width('100%')
    }.padding(18).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.surface())
      .border({width: 1, color: this.line()}).borderRadius(6).margin({bottom: 12}).onClick(() => this.host.openRepair($$.order))
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
      if (this.state.room) {
        Row() {
          Text(this.state.room.buildingName + ' ' + this.state.room.roomNo).fontSize(17).fontColor(this.ink()).layoutWeight(1)
          Text(this.state.room.capacity + '人间 · 在住').fontSize(13).fontColor(this.muted())
        }.width('100%').height(54).border({width: {top: 1, bottom: 1}, color: this.line()}).onClick(() => this.host.navigate('room'))
      } else { serviceEmpty({message: '尚未关联宿舍，请联系宿管办理入住。', night: this.night}) }
      Image($rawfile('campus-courtyard.jpg')).width('100%').height(150).objectFit(ImageFit.Cover).borderRadius(2).margin({top: 16})
      Text('校园氛围示意 · Nick Allen / CC BY-SA 3.0  ↗').fontSize(11).fontColor(this.muted()).width('100%').height(36)
        .onClick(() => this.host.navigate('credits'))
      Button() {
        Row({space: 16}) {
          Text('＋').fontSize(27).fontColor(this.onPrimary())
          Column({space: 4}) { Text('提交宿舍报修').fontSize(17).fontColor(this.onPrimary()).fontWeight(FontWeight.Medium); Text('描述问题，上传故障照片').fontSize(13).fontColor(this.onPrimary()) }
            .alignItems(HorizontalAlign.Start).layoutWeight(1)
          Text('→').fontSize(23).fontColor(this.onPrimary())
        }.width('100%')
      }.id('create-repair').width('100%').height(82).padding({left: 18, right: 18}).borderRadius(4)
        .backgroundColor(this.accent()).fontColor(this.onPrimary()).enabled(!!this.state.room).onClick(() => this.host.navigate('repair-create'))
      serviceSection({title: '最近报修', night: this.night, action: '查看全部', click: () => { this.onRepairListReset();  this.host.navigate('repairs'); }})
      if (this.state.repairs.length) { this.RepairCard({order: this.state.repairs[0]}) } else { serviceEmpty({message: '暂时没有报修记录。遇到设施问题时可在这里提交。', night: this.night}) }
      Row() {
        Text('卫生记录').fontSize(15).fontColor(this.ink()).layoutWeight(1)
        Text(this.state.hygiene.length ? this.state.hygiene[0].totalScore + ' 分 · ' + gradeLabel(this.state.hygiene[0].grade) + ' →' : '查看 →')
          .fontSize(14).fontColor(this.muted())
      }.width('100%').height(56).border({width: {top: 1, bottom: 1}, color: this.line()}).onClick(() => { this.onHygieneListReset(); this.host.navigate('hygiene'); })
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
        Column({space: 6}) { Text(String(this.state.overview?.openRepairs ?? 0)).fontSize(34).fontColor(this.ink()).fontWeight(FontWeight.Medium); Text('待跟进工单').fontSize(13).fontColor(this.muted()) }
          .alignItems(HorizontalAlign.Start).layoutWeight(1).onClick(() => this.host.navigate('repairs'))
        Column({space: 6}) { Text(String(this.state.overview?.visitingCount ?? 0)).fontSize(34).fontColor(this.ink()).fontWeight(FontWeight.Medium); Text('当前在访').fontSize(13).fontColor(this.muted()) }
          .alignItems(HorizontalAlign.Start).layoutWeight(1).padding({left: 20}).border({width: {left: 1}, color: this.line()}).onClick(() => this.host.navigate('visitors'))
      }.width('100%').padding({top: 20, bottom: 20}).margin({top: 20, bottom: 18})
        .border({width: {top: 1, bottom: 1}, color: this.line()})
      Row({space: 12}) {
        Button() { Column({space: 6}) { Text('访客登记').fontSize(16).fontColor(this.ink()).fontWeight(FontWeight.Medium); Text('快速记录来访').fontSize(13).fontColor(this.muted()) } }
          .id('create-visitor').layoutWeight(1).height(88).borderRadius(4).backgroundColor(this.surface()).fontColor(this.ink())
          .border({width: 1, color: this.line()}).onClick(() => this.host.navigate('visitor-create'))
        Button() { Column({space: 6}) { Text('卫生评分').fontSize(16).fontColor(this.ink()).fontWeight(FontWeight.Medium); Text('现场录入检查').fontSize(13).fontColor(this.muted()) } }
          .id('create-hygiene').layoutWeight(1).height(88).borderRadius(4).backgroundColor(this.surface()).fontColor(this.ink())
          .border({width: 1, color: this.line()}).onClick(() => this.host.navigate('hygiene-create'))
      }.width('100%')
      Row({space: 12}) { serviceSecondary({label: '访客记录', click: () => this.host.navigate('visitors'), night: this.night, busy: false}); serviceSecondary({label: '卫生记录', click: () => this.host.navigate('hygiene'), night: this.night, busy: false}) }.margin({top: 12})
      Button('本次路线 · 查看下一站 →').id('open-route').width('100%').height(52).fontSize(16).fontColor(this.onPrimary())
        .backgroundColor(this.ink()).borderRadius(4).margin({top: 18, bottom: 4}).onClick(() => this.host.navigate('route'))
      Button('本次出行辅助 · 位置与设备').id('open-mobility').width('100%').height(48).fontSize(14).fontColor(this.accent())
        .backgroundColor(this.surface()).borderRadius(4).margin({top: 8}).onClick(() => this.host.navigate('mobility'))
      serviceSection({title: '待跟进工单', night: this.night, action: '查看全部', click: () => { this.onRepairListReset(); this.host.navigate('repairs'); }})
      if (!this.state.repairs.length) { serviceEmpty({message: '暂时没有工单。', night: this.night}) }
      ForEach(this.state.repairs.slice(0, 3), (item: Repair) => { this.RepairCard({order: item}) }, (item: Repair) => item.id + ':' + item.version)
      ManagerAssist({night: this.night, onOpenRepair: (repair: Repair) => this.host.openRepair(repair), onUnauthorized: (error: Error) => this.host.fail(error)})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private MyRoom() {
    Column({space: 0}) {
      if (this.state.room) {
        Text(this.state.room.buildingName + ' ' + this.state.room.roomNo).fontFamily('DormSerif').fontSize(30).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%').margin({bottom: 20})
        serviceDetail({label: '楼层', value: this.state.room.floorNo + ' 层', night: this.night})
        serviceDetail({label: '房间容量', value: this.state.room.capacity + ' 人', night: this.night})
        serviceDetail({label: '当前状态', value: '在住', night: this.night})
      } else { serviceEmpty({message: '尚未关联宿舍，请联系宿管办理入住。', night: this.night}) }
      serviceSection({title: '入住历史', night: this.night})
      ForEach(this.state.residences, (r: Residence) => {
        Column({space: 8}) {
          Text(r.buildingName + ' ' + r.roomNo).fontSize(18).fontColor(this.ink()).width('100%')
          Text('入住：' + dateText(r.checkedInAt)).fontSize(13).fontColor(this.muted()).width('100%')
          Text(r.checkedOutAt ? '退宿：' + dateText(r.checkedOutAt) : '当前有效入住').fontSize(13).fontColor(this.muted()).width('100%')
          if (r.reason) { Text(r.reason).fontSize(13).fontColor(this.muted()).width('100%') }
        }.padding(18).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.surface()).borderRadius(4).margin({bottom: 12})
      }, (r: Residence) => r.id + ':' + r.version)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  build() {
    Column() {
      if (this.page === 'room') { this.MyRoom() } else { this.Home() }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
