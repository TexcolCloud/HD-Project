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
      Text('本人的通行估算 · 近30天').fontSize(17).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink)
      if (this.data) {
        Text('有效通行片段 ' + this.data.sampleCount + ' · 可比较 ' + this.data.measuredCount + ' · 已校准方向 ' + this.data.personalModelCount).fontSize(13).lineHeight(23).fontColor(palette(this.night).muted).width('100%')
        Text(this.data.meanAbsoluteErrorMinutes === null ? '尚无足量数据比较预计与实际耗时' : '预计与实际平均误差 ' + this.data.meanAbsoluteErrorMinutes.toFixed(1) + ' 分钟')
          .fontSize(15).fontColor(palette(this.night).ink).width('100%')
        Text(this.data.explanation).fontSize(13).lineHeight(23).fontColor(palette(this.night).muted).width('100%')
      }
      Button(this.busy ? '正在读取…' : '更新通行分析').height(44).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).enabled(!this.busy).onClick(() => this.load())
    }.width('100%').padding(16).alignItems(HorizontalAlign.Start).backgroundColor(palette(this.night).soft).borderRadius(4).margin({top: 16, bottom: 16})
  }
}
