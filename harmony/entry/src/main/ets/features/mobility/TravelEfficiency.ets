import { isSessionExpiry } from "../ReadScope";
import { Api } from '../../services/Api';
import { MobilityEfficiency } from '../../models/ApiModels';
import { palette } from '../../components/Palette';

@Component
export struct TravelEfficiency {
  @Prop night: boolean = false;
  onError: (error: Error) => void = () => {};
  @State data: MobilityEfficiency | null = null;
  @State busy: boolean = false;
  private live: boolean = false;
  aboutToAppear(): void { this.live = true; this.load(); }
  aboutToDisappear(): void { this.live = false; }
  private async load(): Promise<void> {
    const epoch = Api.epoch;
    this.busy = true;
    try {
      const data = await Api.request<MobilityEfficiency>('/mobility/efficiency');
      if (this.live && epoch === Api.epoch) this.data = data;
    } catch (error) { if ((this.live && epoch === Api.epoch) || isSessionExpiry(error as Error)) this.onError(error as Error); }
    finally { if (this.live && epoch === Api.epoch) this.busy = false; }
  }
  build() {
    Column({space: 10}) {
      Text('本人的通行估算 · 近30天').fontSize(18).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink)
      if (this.data) {
        Text('有效通行片段 ' + this.data.sampleCount + ' · 可比较 ' + this.data.measuredCount + ' · 已校准方向 ' + this.data.personalModelCount).fontSize(14).lineHeight(23).fontColor(palette(this.night).muted).width('100%')
        Text(this.data.meanAbsoluteErrorMinutes === null ? '尚无足量数据比较预计与实际耗时' : '预计与实际平均偏差 ' + this.data.meanAbsoluteErrorMinutes.toFixed(1) + ' 分钟')
          .fontSize(16).fontColor(palette(this.night).ink).width('100%')
        Text('根据已确认入口之间的实际通行耗时计算。').fontSize(14).lineHeight(23).fontColor(palette(this.night).muted).width('100%')
      }
      Button(this.busy ? '正在读取…' : '更新通行分析').height(48).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).enabled(!this.busy).onClick(() => this.load())
    }.width('100%').padding({top: 16, bottom: 16}).alignItems(HorizontalAlign.Start).border({width: {top: 1}, color: palette(this.night).line}).margin({top: 16, bottom: 16})
  }
}
