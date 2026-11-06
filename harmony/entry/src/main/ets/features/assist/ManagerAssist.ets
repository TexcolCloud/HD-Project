import { util } from '@kit.ArkTS';
import { Api, ApiError } from '../../services/Api';
import { SubmissionKeys, shouldReleaseAiKey } from '../../services/SubmissionKeys';
import { ManagerAssistRequest, ManagerAdvice, AssistEvidence, AssistFact, Repair } from '../../models/ApiModels';
import { palette } from '../../components/Palette';
import { dateText, statusLabel } from '../../services/Labels';

interface AssistLinesArgs { title: string; values: string[]; }

@Component
export struct ManagerAssist {
  @Prop @Watch('orderChanged') orderId: string = '';
  @Prop night: boolean = false;
  onOpenRepair: (repair: Repair) => void = () => {};
  onUnauthorized: (error: Error) => void = () => {};
  @State expanded: boolean = false;
  @State consent: boolean = false;
  @State includeImages: boolean = false;
  @State busy: boolean = false;
  @State opening: boolean = false;
  @State advice: ManagerAdvice | null = null;
  @State error: string = '';
  private live: boolean = false;
  private generation: number = 0;
  private keys: SubmissionKeys = new SubmissionKeys((): string => util.generateRandomUUID());
  aboutToAppear(): void { this.live = true; }
  aboutToDisappear(): void { this.live = false; this.generation++; }
  private orderChanged(): void {
    this.generation++; this.advice = null; this.error = ''; this.busy = false; this.opening = false;
    this.consent = false; this.includeImages = false; this.expanded = false; this.keys.clear();
  }
  private async generate(): Promise<void> {
    if (this.busy) return;
    this.error = '';
    if (!this.consent) { this.error = '请先同意发送本次业务信息进行分析'; return; }
    const request: ManagerAssistRequest = {consentToExternalAnalysis: true, includeImages: !!this.orderId && this.includeImages};
    const orderId = this.orderId, token = Api.token, generation = ++this.generation;
    const path = orderId ? '/ai/manager/repairs/' + encodeURIComponent(orderId) : '/ai/manager/brief';
    this.busy = true;
    try {
      const advice = await Api.post<ManagerAdvice>(path, request, this.keys.reserve(path, JSON.stringify(request)));
      if (!this.live || generation !== this.generation || token !== Api.token || orderId !== this.orderId) return;
      this.keys.complete(path); this.advice = advice;
    } catch (error) {
      if (!this.live || generation !== this.generation || orderId !== this.orderId) return;
      const apiError = error as ApiError;
      if (shouldReleaseAiKey(apiError.code)) this.keys.complete(path);
      if (apiError.status === 401 && !Api.token) this.onUnauthorized(error as Error);
      else if (Api.token === token) this.error = apiError.message + '。可继续人工办理。' + (apiError.traceId ? '\n查询编号：' + apiError.traceId : '');
    } finally { if (this.live && generation === this.generation) this.busy = false; }
  }
  private async evidence(item: AssistEvidence): Promise<void> {
    if (this.opening) return;
    const token = Api.token, generation = this.generation;
    this.opening = true;
    try {
      const repair = await Api.request<Repair>('/repairs/' + encodeURIComponent(item.orderId));
      if (this.live && generation === this.generation && Api.token === token) this.onOpenRepair(repair);
    } catch (error) {
      if (!this.live || generation !== this.generation) return;
      const apiError = error as ApiError;
      if (apiError.status === 401 && !Api.token) this.onUnauthorized(error as Error);
      else if (Api.token === token) this.error = apiError.message;
    } finally { if (this.live && generation === this.generation) this.opening = false; }
  }
  @Builder
  private Lines($$: AssistLinesArgs) {
    if ($$.values.length) {
      Text($$.title).fontSize(15).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%').margin({top: 8})
      ForEach($$.values, (value: string) => { Text('· ' + value).fontSize(14).lineHeight(24).fontColor(palette(this.night).ink).width('100%') })
    }
  }
  build() {
    Column({space: 12}) {
      Row() {
        Text(this.orderId ? 'AI 研判' : '值班简报').fontSize(18).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).layoutWeight(1)
        Button(this.expanded ? '收起' : '打开助手').id(this.orderId ? 'manager-assist-detail-open' : 'manager-assist-brief-open')
          .height(48).fontSize(13).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).onClick(() => this.expanded = !this.expanded)
      }.width('100%')
      if (this.expanded) {
        Text('整理授权范围内的业务信息，建议仅供核对，办理仍使用工单操作。').fontSize(13).lineHeight(23).fontColor(palette(this.night).muted).width('100%')
        Row({space: 8}) {
          Checkbox({name: 'manager-assist-consent'}).id('manager-assist-consent').select(this.consent).selectedColor(palette(this.night).accent)
            .onChange((value: boolean) => this.consent = value)
          Text('同意发送本次工单摘要与业务统计进行AI分析，不含学生电话或访客信息。').fontSize(13).lineHeight(22).fontColor(palette(this.night).ink).layoutWeight(1)
        }.width('100%')
        if (this.orderId) {
        Row({space: 8}) {
          Checkbox({name: 'manager-assist-images'}).id('manager-assist-images').select(this.includeImages).selectedColor(palette(this.night).accent)
            .onChange((value: boolean) => this.includeImages = value)
          Text('附带本单前3张故障图（可选）').fontSize(13).lineHeight(22).fontColor(palette(this.night).muted).layoutWeight(1)
        }.width('100%')
        }
        Button(this.busy ? '正在整理…' : this.orderId ? '生成本单研判' : '生成值班简报').id('manager-assist-generate').width('100%').height(48).fontSize(15)
          .fontColor(palette(this.night).onPrimary).backgroundColor(palette(this.night).ink).borderRadius(4).enabled(!this.busy).onClick(() => this.generate())
        if (this.error) { Text(this.error).id('manager-assist-error').fontSize(13).lineHeight(23).fontColor(palette(this.night).danger).width('100%') }
        if (this.advice) {
          Text('AI建议，请结合现场核对').fontSize(12).fontColor(palette(this.night).accent).width('100%')
          Text(this.advice.summary).fontSize(16).lineHeight(27).fontColor(palette(this.night).ink).width('100%').copyOption(CopyOptions.InApp)
          if (this.advice.facts.length) {
            Text('业务事实（服务端统计）').fontSize(15).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%').margin({top: 8})
            ForEach(this.advice.facts, (fact: AssistFact) => {
              Row({space: 10}) { Text(fact.label).fontSize(13).fontColor(palette(this.night).muted).layoutWeight(1); Text(fact.value).fontSize(14).fontColor(palette(this.night).ink) }
                .width('100%').padding({top: 8, bottom: 8}).border({width: {bottom: 1}, color: palette(this.night).line})
            })
          }
          this.Lines({title: '现场观察', values: this.advice.findings})
          this.Lines({title: '建议行动（需人工判断）', values: this.advice.suggestedActions})
          this.Lines({title: '待补充信息', values: this.advice.questions})
          if (this.advice.uncertainty) { Text('不确定之处：' + this.advice.uncertainty).fontSize(13).lineHeight(23).fontColor(palette(this.night).muted).width('100%') }
          if (this.advice.evidence.length) { Text('相关工单依据').fontSize(15).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%') }
          ForEach(this.advice.evidence, (item: AssistEvidence) => {
            Button(item.orderNo + ' · ' + item.title + '\n' + statusLabel(item.status) + '  查看 →').id('assist-evidence-' + item.orderId)
              .width('100%').constraintSize({minHeight: 56}).fontSize(13).fontColor(palette(this.night).ink).backgroundColor(palette(this.night).surface)
              .borderRadius(4).enabled(!this.opening).onClick(() => this.evidence(item))
          }, (item: AssistEvidence) => item.orderId)
          Text('生成于 ' + dateText(this.advice.generatedAt)).fontSize(12).fontColor(palette(this.night).muted).width('100%')
        }
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16).backgroundColor(palette(this.night).soft).borderRadius(4).margin({top: 18, bottom: 16})
  }
}
