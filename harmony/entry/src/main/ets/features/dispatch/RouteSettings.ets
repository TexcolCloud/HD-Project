import { Building, DispatchManager, DispatchSettingsRequest } from '../../models/ApiModels';
import { FormInput } from '../../components/FormInput';
import { palette } from '../../components/Palette';
import { availabilityTimestamp, beijingParts, today, dateText } from '../../services/Labels';

@Component
export struct RouteSettings {
  @Prop manager: DispatchManager | null = null;
  @Prop buildings: Building[] = [];
  @Prop night: boolean = false;
  @Prop busy: boolean = false;
  onSave: (request: DispatchSettingsRequest) => void = () => {};
  onCancel: () => void = () => {};
  @State accepting: boolean = true;
  @State buildingId: string = '';
  @State floor: string = '0';
  @State availableDay: string = today();
  @State availableClock: string = '';
  @State error: string = '';
  @State expectedVersion: number = 0;
  aboutToAppear(): void { this.reset(); }
  private reset(): void {
    if (!this.manager) return;
    this.error = ''; this.availableDay = today(); this.availableClock = '';
    this.expectedVersion = this.manager.version; this.accepting = this.manager.acceptingNewTasks;
    this.buildingId = this.manager.positionBuildingId || ''; this.floor = String(this.manager.positionFloorNo ?? 0);
    if (this.manager.availableUntil) {
      const local = beijingParts(this.manager.availableUntil); this.availableDay = local.day; this.availableClock = local.clock;
    }
  }
  private choices(): SelectOption[] {
    const options: SelectOption[] = [{value: '未指定，使用默认出发点'}];
    return options.concat(this.buildings.map((building: Building): SelectOption => { return {value: building.name}; }));
  }
  private locationLabel(): string { return this.buildings.find((item: Building): boolean => item.id === this.buildingId)?.name || '未指定，使用默认出发点'; }
  private save(): void {
    this.error = '';
    const request: DispatchSettingsRequest = {acceptingNewTasks: this.accepting, expectedVersion: this.expectedVersion,
      positionBuildingId: null, positionFloorNo: null, availableUntil: null};
    if (this.buildingId) {
      const floor = Number(this.floor), building = this.buildings.find((item: Building): boolean => item.id === this.buildingId);
      if (!this.floor.trim() || !Number.isInteger(floor) || floor < 0 || (building && floor > building.floorCount)) {
        this.error = '请填写该楼栋范围内的楼层，0代表出入口'; return;
      }
      request.positionBuildingId = this.buildingId; request.positionFloorNo = floor;
    }
    request.availableUntil = this.manager?.availableUntil || null;
    try { if (this.accepting) request.availableUntil = availabilityTimestamp(this.availableDay, this.availableClock); }
    catch (error) { this.error = (error as Error).message; return; }
    this.onSave(request);
  }
  build() {
    Column({space: 14}) {
      Text('接单与位置设置').fontSize(19).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%')
      Row() {
        Text('接受新任务').fontSize(16).fontColor(palette(this.night).ink).layoutWeight(1)
        Toggle({type: ToggleType.Switch, isOn: this.accepting}).id('route-accepting').selectedColor(palette(this.night).accent)
          .enabled(!this.busy).onChange((value: boolean) => this.accepting = value)
      }.width('100%').height(48)
      Text('暂停只影响新任务，已接单、在途和正在处理的工单仍由你继续跟进。此设置在你的各终端共享。')
        .fontSize(12).fontColor(palette(this.night).muted).lineHeight(21).width('100%')
      Text('当前位置 / 出发点').fontSize(15).fontColor(palette(this.night).ink).width('100%')
      Select(this.choices()).id('route-position-building').value(this.locationLabel())
        .selected(this.buildingId ? this.buildings.findIndex((item: Building): boolean => item.id === this.buildingId) + 1 : 0)
        .width('100%').height(50).fontColor(palette(this.night).ink).backgroundColor(palette(this.night).surface).borderRadius(4)
        .onSelect((index: number) => this.buildingId = index ? this.buildings[index - 1].id : '')
      if (this.buildingId) {
        FormInput({label: '楼层（0为出入口）', value: this.floor, numeric: true, inputId: 'route-position-floor', night: this.night,
          onChange: (value: string) => this.floor = value})
      }
      if (this.manager?.positionUpdatedAt) {
        Text('最近位置确认：' + dateText(this.manager.positionUpdatedAt)).fontSize(12).fontColor(palette(this.night).muted).width('100%')
      }
      if (this.accepting) {
        Text('本次可用截止时间 *').fontSize(16).fontColor(palette(this.night).ink).width('100%')
        Text('接受新任务时须填写有效截止时间。未设置或已过期时，账号在线也不会参与新任务安排。').fontSize(12).fontColor(palette(this.night).muted).lineHeight(21).width('100%')
        FormInput({label: '可用截止日期（北京时间）', value: this.availableDay, placeholder: 'YYYY-MM-DD', inputId: 'route-available-date', night: this.night,
          onChange: (value: string) => this.availableDay = value})
        FormInput({label: '可用截止时间', value: this.availableClock, placeholder: '18:00', inputId: 'route-available-time', night: this.night,
          onChange: (value: string) => this.availableClock = value})
      } else {
        Text(this.manager?.availableUntil ? '暂停期间保留原截止时间：' + dateText(this.manager.availableUntil) : '尚未设置可用时段，恢复接单时请填写。')
          .fontSize(12).fontColor(palette(this.night).muted).lineHeight(21).width('100%')
      }
      if (this.manager && this.manager.version !== this.expectedVersion) {
        Text('设置已在其他终端更新。可保留当前填写，或载入最新状态后再修改。').fontSize(13).lineHeight(22).fontColor(palette(this.night).accent).width('100%')
        Button('载入最新设置').height(48).fontSize(14).fontColor(palette(this.night).ink).backgroundColor(palette(this.night).surface).borderRadius(4).onClick(() => this.reset())
      }
      if (this.error) { Text(this.error).fontSize(13).fontColor(palette(this.night).danger).width('100%') }
      Button(this.busy ? '正在保存…' : '保存接单设置').id('route-settings-save').width('100%').height(50).fontSize(16)
        .backgroundColor(palette(this.night).ink).fontColor(palette(this.night).onPrimary).borderRadius(4).enabled(!this.busy).onClick(() => this.save())
      Button('取消').width('100%').height(48).fontSize(14).fontColor(palette(this.night).muted).backgroundColor(Color.Transparent)
        .enabled(!this.busy).onClick(this.onCancel)
    }.width('100%').alignItems(HorizontalAlign.Start).padding(18).backgroundColor(palette(this.night).soft).borderRadius(6).margin({bottom: 18})
  }
}
