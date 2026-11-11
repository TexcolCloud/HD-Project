import { common } from '@kit.AbilityKit';
import { Room } from '../../models/ApiModels';
import { Api, ApiError } from '../../services/Api';
import { palette } from '../../components/Palette';
import { DeviceActions } from './DeviceActions';

@Component
export struct StudentPlaceCheck {
  room: Room | null = null;
  @Prop night: boolean = false;
  onUnauthorized: (error: Error) => void = () => {};
  @State busy: boolean = false;
  @State message: string = '';
  private live: boolean = false;
  aboutToAppear(): void { this.live = true; }
  aboutToDisappear(): void { this.live = false; }
  private async scan(): Promise<void> {
    const epoch = Api.epoch;
    this.busy = true; this.message = '';
    try {
      const anchor = await DeviceActions.scanAnchor(this.getUIContext().getHostContext() as common.UIAbilityContext);
      if (!this.live || epoch !== Api.epoch) return;
      if (!anchor || !this.room || anchor.buildingId !== this.room.buildingId) this.message = '该地点与本人当前入住不匹配；报修仍关联本人宿舍';
      else this.message = '已核对：' + anchor.buildingName + (anchor.floorNo !== null ? ' ' + anchor.floorNo + '层' : '') + '。锚点不能证明房间，报修仍使用当前入住信息。';
    } catch (error) {
      const detail = error as ApiError;
      if (this.live && detail.status === 401) this.onUnauthorized(error as Error);
      else if (this.live && epoch === Api.epoch) this.message = detail.message || '扫码已取消，可继续手工填报';
    } finally { if (this.live && epoch === Api.epoch) this.busy = false; }
  }
  build() {
    Column({space: 8}) {
      Button(this.busy ? '正在核对…' : '扫码核对入住楼栋').id('student-place-scan').width('100%').height(48).fontSize(14)
        .fontColor(palette(this.night).accent).backgroundColor(palette(this.night).surface).borderRadius(4).enabled(!this.busy && !!this.room).onClick(() => this.scan())
      if (this.message) { Text(this.message).fontSize(12).lineHeight(21).fontColor(palette(this.night).muted).width('100%') }
    }.width('100%').margin({bottom: 18})
  }
}
