import { DispatchTask } from '../../models/ApiModels';
import { palette } from '../../components/Palette';
import { dateText } from '../../services/Labels';

export function taskStateLabel(state: string): string {
  switch (state) {
    case 'RECOMMENDED': return '待认领'; case 'ASSIGNED': return '已接单'; case 'EN_ROUTE': return '在途';
    case 'ON_SITE': return '已到达'; case 'BLOCKED': return '暂缓'; default: return state;
  }
}

@Component
export struct RouteTaskCard {
  @Prop task: DispatchTask | null = null;
  @Prop night: boolean = false;
  @Prop busy: boolean = false;
  @Prop canDepart: boolean = true;
  @Prop actionsEnabled: boolean = true;
  kind: string = '';
  onAction: (task: DispatchTask, action: string) => void = () => {};
  onOpen: (task: DispatchTask) => void = () => {};
  build() {
    if (this.task) {
      Column({space: 12}) {
        Row() {
          Text(this.kind || '第 ' + this.task.sequence + ' 站').fontSize(12).fontColor(palette(this.night).muted).layoutWeight(1)
          Text(taskStateLabel(this.task.state) + (this.task.locked ? ' · 已锁定' : '')).fontSize(12).fontColor(palette(this.night).accent)
        }.width('100%')
        Text(this.task.buildingName + ' ' + this.task.roomNo).fontFamily('DormSerif').fontSize(23).fontWeight(FontWeight.Medium)
          .fontColor(palette(this.night).ink).width('100%')
        Text(this.task.title).fontSize(17).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%')
        Text(this.task.description).fontSize(14).lineHeight(24).fontColor(palette(this.night).muted).maxLines(3).textOverflow({overflow: TextOverflow.Ellipsis}).width('100%')
        Text(this.task.estimatedArrival ? '预计到达 ' + dateText(this.task.estimatedArrival) : '到达时间待安排')
          .fontSize(13).fontColor(palette(this.night).ink).width('100%')
        Text('预计通行 ' + this.task.travelMinutes + ' 分钟 · 处理约 ' + this.task.serviceMinutes + ' 分钟')
          .fontSize(12).fontColor(palette(this.night).muted).width('100%')
        if (this.task.windowStart && this.task.windowEnd) {
          Text((this.task.windowConfirmed ? '已确认预约：' : '学生可上门时段：') + dateText(this.task.windowStart) + ' 至 ' + dateText(this.task.windowEnd))
            .fontSize(12).lineHeight(21).fontColor(palette(this.night).muted).width('100%')
        }
        if (this.task.reason) { Text(this.task.reason).fontSize(13).lineHeight(22).fontColor(palette(this.night).muted).width('100%') }
        if (this.task.state === 'RECOMMENDED') {
          Button('接受推荐任务').id('route-claim-' + this.task.orderId).width('100%').height(48).fontSize(15).borderRadius(4)
            .backgroundColor(palette(this.night).ink).fontColor(palette(this.night).onPrimary).enabled(!this.busy && this.actionsEnabled)
            .onClick(() => { if (this.task) this.onAction(this.task, 'claim'); })
        } else if (this.task.state === 'ASSIGNED') {
          Button(this.canDepart ? '向下一站出发' : '当前任务完成后再出发').id('route-depart-' + this.task.orderId).width('100%').height(48).fontSize(15).borderRadius(4)
            .backgroundColor(palette(this.night).ink).fontColor(palette(this.night).onPrimary).enabled(!this.busy && this.actionsEnabled && this.canDepart)
            .onClick(() => { if (this.task) this.onAction(this.task, 'depart'); })
        } else if (this.task.state === 'EN_ROUTE') {
          Button('已到达现场').id('route-arrive-' + this.task.orderId).width('100%').height(48).fontSize(15).borderRadius(4)
            .backgroundColor(palette(this.night).ink).fontColor(palette(this.night).onPrimary).enabled(!this.busy && this.actionsEnabled)
            .onClick(() => { if (this.task) this.onAction(this.task, 'arrive'); })
        } else if (this.task.state === 'BLOCKED') {
          Button('恢复路线安排').id('route-resume-' + this.task.orderId).width('100%').height(48).fontSize(15).borderRadius(4)
            .backgroundColor(palette(this.night).ink).fontColor(palette(this.night).onPrimary).enabled(!this.busy && this.actionsEnabled)
            .onClick(() => { if (this.task) this.onAction(this.task, 'resume'); })
        }
        Row({space: 10}) {
          Button(this.task.state === 'ON_SITE' ? '打开工单处理 →' : '查看工单 →').id('route-order-' + this.task.orderId)
            .layoutWeight(1).height(48).fontSize(14).fontColor(palette(this.night).ink).backgroundColor(palette(this.night).soft).borderRadius(4)
            .enabled(!this.busy).onClick(() => { if (this.task) this.onOpen(this.task); })
          if (['EN_ROUTE', 'ON_SITE'].includes(this.task.state)) {
            Button('标记受阻').height(48).fontSize(13).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent)
              .enabled(!this.busy && this.actionsEnabled).onClick(() => { if (this.task) this.onAction(this.task, 'block'); })
          }
        }.width('100%')
      }.width('100%').alignItems(HorizontalAlign.Start).padding(18).backgroundColor(palette(this.night).surface)
        .border({width: 1, color: palette(this.night).line}).borderRadius(6).margin({bottom: 14})
    }
  }
}
