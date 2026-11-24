import { util } from '@kit.ArkTS';
import { Repair, RepairVisitRequest } from '../../models/ApiModels';
import { Api, ApiError } from '../../services/Api';
import { SubmissionKeys } from '../../services/SubmissionKeys';
import { appointment, canEditVisit } from '../../services/Labels';
import { palette } from '../../components/Palette';
import { FormInput } from '../../components/FormInput';
import { VisitWindowFields, VisitWindowDraft } from './VisitWindowFields';

import { BackPanels } from '../NavigationStack';

@Component
export struct VisitArrangement {
  @Consume backPanels: BackPanels;
  private releaseBack: () => void = () => {};
  @Prop repair: Repair | null = null;
  @Prop manager: boolean = false;
  @Prop userId: string = '';
  @Prop night: boolean = false;
  onSaved: (repair: Repair) => void = () => {};
  onUnauthorized: (error: Error) => void = () => {};
  @State editing: boolean = false;
  @State draft: VisitWindowDraft = new VisitWindowDraft();
  @State confirmed: boolean = false;
  @State visitRequired: boolean = true;
  @State estimatedMinutes: string = '';
  @State waitingReason: string = '';
  @State busy: boolean = false;
  @State error: string = '';
  private version: number = 0;
  private live: boolean = false;
  private keys: SubmissionKeys = new SubmissionKeys((): string => util.generateRandomUUID());
  aboutToAppear(): void {
    this.live = true;
    this.releaseBack = this.backPanels.register((): boolean => {
      if (!this.editing) return false;
      if (!this.busy) this.editing = false;
      return true;
    });
  }
  aboutToDisappear(): void {
    this.releaseBack();
    this.live = false;
  }
  private editable(): boolean {
    return !!this.repair && canEditVisit(this.repair.status) && (!this.manager || !this.repair.handlerUserId || this.repair.handlerUserId === this.userId);
  }
  private edit(): void {
    if (!this.repair) return;
    this.version = this.repair.version; this.draft = VisitWindowDraft.from(this.repair.windowStart, this.repair.windowEnd);
    this.confirmed = this.repair.windowConfirmed; this.visitRequired = this.repair.visitRequired;
    this.estimatedMinutes = String(this.repair.estimatedMinutes); this.waitingReason = this.repair.waitingReason;
    this.error = ''; this.editing = true;
  }
  private async save(): Promise<void> {
    if (!this.repair || this.busy || !this.editable()) return;
    this.error = '';
    if (!this.manager) {
      const original = VisitWindowDraft.from(this.repair.windowStart, this.repair.windowEnd);
      if (original.enabled === this.draft.enabled && (!original.enabled ||
          (original.startDay === this.draft.startDay && original.startClock === this.draft.startClock &&
            original.endDay === this.draft.endDay && original.endClock === this.draft.endClock))) {
        this.editing = false;
        return;
      }
    }
    const request: RepairVisitRequest = {expectedVersion: this.version, windowConfirmed: this.manager && this.draft.enabled && this.confirmed,
      visitRequired: this.manager ? this.visitRequired : this.repair.visitRequired, windowStart: null, windowEnd: null};
    try {
      const range = this.draft.range();
      if (range) { request.windowStart = range.start; request.windowEnd = range.end; }
      if (this.manager) {
        const minutes = Number(this.estimatedMinutes);
        if (!this.estimatedMinutes.trim() || !Number.isInteger(minutes) || minutes <= 0) throw new Error('预计处理时长须填写正整数分钟');
        if (!this.visitRequired && !this.waitingReason.trim()) throw new Error('暂不安排上门时，请填写等待原因');
        request.estimatedMinutes = minutes; request.waitingReason = this.visitRequired ? '' : this.waitingReason.trim();
      }
    } catch (error) { this.error = (error as Error).message; return; }
    const orderId = this.repair.id, token = Api.token, scope = 'visit-' + orderId;
    this.busy = true;
    try {
      const updated = await Api.patch<Repair>('/repairs/' + orderId + '/visit', request, this.keys.reserve(scope, JSON.stringify(request)));
      if (!this.live || Api.token !== token || this.repair?.id !== orderId) return;
      this.keys.complete(scope); this.editing = false; this.onSaved(updated);
    } catch (error) {
      if (!this.live || this.repair?.id !== orderId) return;
      const apiError = error as ApiError;
      if (apiError.status === 401 && !Api.token) this.onUnauthorized(error as Error);
      else if (Api.token === token) this.error = apiError.message + (apiError.traceId ? '\n查询编号：' + apiError.traceId : '');
    } finally { if (this.live) this.busy = false; }
  }
  build() {
    if (this.repair) {
      Column({space: 12}) {
        Text(this.repair.status === 'PROCESSING' && this.repair.latestResult ? '工单记录的上门安排' : '上门安排')
          .fontSize(18).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%')
        Text(appointment(this.repair).label).id('visit-status').fontSize(14).fontColor(palette(this.night).accent).width('100%')
        if (appointment(this.repair).window) {
          Text(appointment(this.repair).window).id('visit-window').fontSize(14).lineHeight(24).fontColor(palette(this.night).ink).width('100%')
        }
        if (this.manager && this.repair.visitRequired) {
          Text('预计处理约 ' + this.repair.estimatedMinutes + ' 分钟').fontSize(14).fontColor(palette(this.night).muted).width('100%')
        }
        if (!this.repair.visitRequired && this.repair.waitingReason) {
          Text(this.repair.waitingReason).fontSize(14).lineHeight(23).fontColor(palette(this.night).muted).width('100%')
        }
        if (!this.editing && this.editable()) {
          Button(this.manager ? '调整访问安排' : '调整可上门时段').id('visit-edit').width('100%').height(48).fontSize(14).fontColor(palette(this.night).ink)
            .backgroundColor(palette(this.night).surface).border({width: 1, color: palette(this.night).outline}).borderRadius(4).onClick(() => this.edit())
        }
        if (this.editing) {
          VisitWindowFields({draft: this.draft, night: this.night, onChange: (draft: VisitWindowDraft) => { this.draft = draft; this.confirmed = false; }})
          if (this.manager) {
            if (this.draft.enabled) {
              Row() {
                Text('已与学生确认此预约').fontSize(16).fontColor(palette(this.night).ink).layoutWeight(1)
                Toggle({type: ToggleType.Switch, isOn: this.confirmed}).id('visit-confirmed').selectedColor(palette(this.night).accent).onChange((value: boolean) => this.confirmed = value)
              }.width('100%').height(48)
            }
            FormInput({label: '预计处理时长（分钟）', value: this.estimatedMinutes, numeric: true, inputId: 'visit-estimated-minutes', night: this.night,
              onChange: (value: string) => this.estimatedMinutes = value})
            Row() {
              Text('需要上门').fontSize(16).fontColor(palette(this.night).ink).layoutWeight(1)
              Toggle({type: ToggleType.Switch, isOn: this.visitRequired}).id('visit-required').selectedColor(palette(this.night).accent).onChange((value: boolean) => this.visitRequired = value)
            }.width('100%').height(48)
            if (!this.visitRequired) { FormInput({label: '等待原因 *', value: this.waitingReason, multiline: true, inputId: 'visit-waiting-reason', night: this.night,
              onChange: (value: string) => this.waitingReason = value}) }
          }
          if (this.error) { Text(this.error).fontSize(14).lineHeight(22).fontColor(palette(this.night).danger).width('100%') }
          Button(this.busy ? '正在保存…' : '保存访问安排').id('visit-save').width('100%').height(50).fontSize(16).fontColor(palette(this.night).onPrimary)
            .backgroundColor(palette(this.night).ink).borderRadius(4).enabled(!this.busy).onClick(() => this.save())
          Button('取消修改').width('100%').height(48).fontSize(14).fontColor(palette(this.night).muted).backgroundColor(Color.Transparent)
            .enabled(!this.busy).onClick(() => this.editing = false)
        }
      }.width('100%').alignItems(HorizontalAlign.Start).padding({top: 16, bottom: 16}).border({width: {top: 1}, color: palette(this.night).line}).margin({top: 20, bottom: 12})
    }
  }
}
