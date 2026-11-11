import { common } from '@kit.AbilityKit';
import { Repair } from '../../models/ApiModels';
import { Api, ApiError } from '../../services/Api';
import { palette } from '../../components/Palette';
import { DeviceActions } from './DeviceActions';

@Component
export struct RepairReminders {
  repair: Repair | null = null;
  @Prop night: boolean = false;
  @State message: string = '';
  @State busy: boolean = false;
  private live: boolean = false;
  aboutToAppear(): void { this.live = true; }
  aboutToDisappear(): void { this.live = false; }
  private async trigger(calendar: boolean): Promise<void> {
    const epoch = Api.epoch;
    if (!this.repair) return;
    this.busy = true; this.message = '';
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      if (calendar) await DeviceActions.calendar(context, this.repair);
      else await DeviceActions.notify(context);
      if (this.live && epoch === Api.epoch) this.message = calendar ? '已打开系统日历，请由你确认保存；返回后保留当前工单。' : '已向系统提交提醒；预约可另存日历。';
    } catch (error) { if (this.live && epoch === Api.epoch) this.message = (error as ApiError).message || '系统操作已取消，当前工单保留'; }
    finally { if (this.live && epoch === Api.epoch) this.busy = false; }
  }
  build() {
    Column({space: 8}) {
      if (this.repair?.windowStart && this.repair?.windowEnd && this.repair?.windowConfirmed) {
        Button('添加预约至系统日历').id('repair-calendar').width('100%').height(48).fontSize(14).fontColor(palette(this.night).accent)
          .backgroundColor(palette(this.night).surface).borderRadius(4).enabled(!this.busy).onClick(() => this.trigger(true))
      }
      Button('开启并测试系统通知').id('repair-notifications').width('100%').height(48).fontSize(14).fontColor(palette(this.night).ink)
        .backgroundColor(palette(this.night).surface).borderRadius(4).enabled(!this.busy).onClick(() => this.trigger(false))
      if (this.message) { Text(this.message).fontSize(12).lineHeight(21).fontColor(palette(this.night).muted).width('100%') }
      Text('本地提醒仅处理已知预约，不代表应用关闭后能接收新的服务端工单。').fontSize(12).lineHeight(21).fontColor(palette(this.night).muted).width('100%')
    }.width('100%').margin({top: 12, bottom: 16})
  }
}
