import { isSessionExpiry } from "../ReadScope";
import { util } from '@kit.ArkTS';
import { Api } from '../../services/Api';
import { SubmissionKeys } from '../../services/SubmissionKeys';
import { AssistFeedback as FeedbackRecord, AssistFeedbackList, AssistFeedbackRequest } from '../../models/ApiModels';
import { FormInput } from '../../components/FormInput';
import { palette } from '../../components/Palette';
import { dateText } from '../../services/Labels';

@Component
export struct AssistFeedback {
  @Prop @Watch('contextChanged') orderId: string = '';
  @Prop @Watch('contextChanged') suggestionId: string = '';
  @State followUpId: string = '';
  private generation: number = 0;
  @Prop night: boolean = false;
  onError: (error: Error) => void = () => {};
  @State items: FeedbackRecord[] = [];
  @State decision: string = 'ADOPTED';
  @State reason: string = '';
  @State outcome: string = '';
  @State busy: boolean = false;
  @State notice: string = '';
  private live: boolean = false;
  private keys: SubmissionKeys = new SubmissionKeys((): string => util.generateRandomUUID());
  private contextChanged(): void {
    this.generation++; this.items = []; this.reason = ''; this.outcome = ''; this.followUpId = ''; this.notice = ''; this.busy = false; this.keys.clear();
    if (this.live) this.load();
  }
  aboutToAppear(): void { this.live = true; this.load(); }
  aboutToDisappear(): void { this.live = false; this.generation++; }
  private label(value: string): string { return value === 'ADOPTED' ? '采用' : value === 'MODIFIED' ? '修改后采用' : '忽略'; }
  private async load(): Promise<void> {
    const epoch = Api.epoch, order = this.orderId, generation = this.generation;
    try {
      const data = await Api.request<AssistFeedbackList>('/ai/manager/repairs/' + order + '/feedback');
      if (this.live && epoch === Api.epoch && order === this.orderId && generation === this.generation) this.items = data.items;
    } catch (error) { if ((this.live && epoch === Api.epoch && generation === this.generation) || isSessionExpiry(error as Error)) this.onError(error as Error); }
  }
  private async save(): Promise<void> {
    if (this.busy || !(this.followUpId || this.suggestionId)) return;
    const epoch = Api.epoch, order = this.orderId, generation = this.generation;
    this.busy = true; this.notice = '';
    const path = '/ai/manager/repairs/' + order + '/feedback';
    const body: AssistFeedbackRequest = {suggestionId: this.followUpId || this.suggestionId, decision: this.decision, reason: this.reason, outcome: this.outcome};
    try {
      await Api.post<FeedbackRecord>(path, body, this.keys.reserve(path, JSON.stringify(body)));
      if (!this.live || epoch !== Api.epoch || order !== this.orderId || generation !== this.generation) return;
      this.keys.complete(path); this.notice = '反馈已记录，可在办理后追加实际结果'; this.reason = ''; this.outcome = ''; await this.load();
    } catch (error) { if ((this.live && epoch === Api.epoch && generation === this.generation) || isSessionExpiry(error as Error)) this.onError(error as Error); }
    finally { if (this.live && epoch === Api.epoch && generation === this.generation) this.busy = false; }
  }
  build() {
    Column({space: 12}) {
      Text('建议使用反馈').fontSize(16).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink)
      if (this.followUpId || this.suggestionId) {
        Select([{value: '采用'}, {value: '修改后采用'}, {value: '忽略'}]).selected(['ADOPTED', 'MODIFIED', 'IGNORED'].indexOf(this.decision)).value(this.label(this.decision))
          .height(48).width('100%').onSelect((index: number) => this.decision = ['ADOPTED', 'MODIFIED', 'IGNORED'][index])
        FormInput({label: '采用或调整原因', value: this.reason, multiline: true, night: this.night, onChange: (value: string) => this.reason = value})
        FormInput({label: '实际办理结果（可后续补充）', value: this.outcome, multiline: true, night: this.night, onChange: (value: string) => this.outcome = value})
        Button('记录建议反馈').height(48).width('100%').fontColor(palette(this.night).onPrimary).backgroundColor(palette(this.night).ink).borderRadius(4).enabled(!this.busy && !!this.reason.trim()).onClick(() => this.save())
      }
      if (this.notice) { Text(this.notice).fontSize(13).fontColor(palette(this.night).accent).width('100%') }
      Button('查看历史反馈').height(44).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).onClick(() => this.load())
      ForEach(this.items, (item: FeedbackRecord) => {
        if (item.canFollowUp) { Button('补充此建议的实际结果').height(44).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).onClick(() => { this.followUpId = item.suggestionId; this.decision = item.decision; this.reason = ''; this.outcome = ''; this.notice = '正在补充历史建议的实际结果'; }) }
        Text(this.label(item.decision) + ' · ' + item.reason + (item.outcome ? '\n实际结果：' + item.outcome : '') + '\n' + item.operatorName + ' · ' + dateText(item.createdAt))
          .fontSize(13).lineHeight(23).fontColor(palette(this.night).muted).width('100%').padding(10).border({width: {bottom: 1}, color: palette(this.night).line})
      }, (item: FeedbackRecord) => item.id)
    }.width('100%').alignItems(HorizontalAlign.Start).padding({top: 16, bottom: 8})
  }
}
