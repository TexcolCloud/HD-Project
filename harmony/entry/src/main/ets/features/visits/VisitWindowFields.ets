import { DateTimeField, TimePresets } from '../../components/DateTimeFields';
import { palette } from '../../components/Palette';
import { today, beijingParts, visitWindowRange, LocalWindow } from '../../services/Labels';

export class VisitWindowDraft {
  enabled: boolean = false;
  startDay: string = today();
  startClock: string = '';
  endDay: string = today();
  endClock: string = '';
  static from(start: string | null, end: string | null): VisitWindowDraft {
    const draft = new VisitWindowDraft();
    if (start && end) {
      draft.enabled = true;
      const first = beijingParts(start), last = beijingParts(end);
      draft.startDay = first.day; draft.startClock = first.clock; draft.endDay = last.day; draft.endClock = last.clock;
    }
    return draft;
  }
  copy(): VisitWindowDraft {
    const next = new VisitWindowDraft();
    next.enabled = this.enabled; next.startDay = this.startDay; next.startClock = this.startClock;
    next.endDay = this.endDay; next.endClock = this.endClock; return next;
  }
  range(): LocalWindow | null {
    return visitWindowRange(this.enabled, this.startDay, this.startClock, this.endDay, this.endClock);
  }
}

@Component
export struct VisitWindowFields {
  @Prop draft: VisitWindowDraft = new VisitWindowDraft();
  @Prop night: boolean = false;
  onChange: (draft: VisitWindowDraft) => void = () => {};
  private change(field: string, value: string): void {
    const next = this.draft.copy();
    if (field === 'startDay') next.startDay = value;
    else if (field === 'startClock') next.startClock = value;
    else if (field === 'endDay') next.endDay = value;
    else next.endClock = value;
    this.onChange(next);
  }
  private preset(index: number): void {
    const next = this.draft.copy();
    next.startDay = beijingParts(new Date(Date.now() + (index >= 2 ? 86400000 : 0)).toISOString()).day;
    next.endDay = next.startDay;
    next.startClock = ['14:00','18:30','09:00','14:00'][index];
    next.endClock = ['18:00','21:30','12:00','18:00'][index];
    this.onChange(next);
  }
  build() {
    Column({space: 12}) {
      Row() {
        Text('提供可上门时段').fontSize(16).fontColor(palette(this.night).ink).layoutWeight(1)
        Toggle({type: ToggleType.Switch, isOn: this.draft.enabled}).id('visit-window-enabled').selectedColor(palette(this.night).accent)
          .onChange((value: boolean) => { const next = this.draft.copy(); next.enabled = value; this.onChange(next); })
      }.width('100%').height(48)
      Text('可选。填写你方便上门的时间范围；宿管确认后才成为已确认预约。时间均为北京时间。')
        .fontSize(14).fontColor(palette(this.night).muted).lineHeight(21).width('100%')
      if (this.draft.enabled) {
        TimePresets({labels: ['今天下午 14:00–18:00','今天晚间 18:30–21:30','明天上午 09:00–12:00','明天下午 14:00–18:00'], night: this.night, onChoose: (index: number) => this.preset(index)})
        Row({space: 12}) {
          DateTimeField({label: '开始日期', value: this.draft.startDay, clock: false, inputId: 'visit-start-date', night: this.night,
            onChange: (value: string) => this.change('startDay', value)}).layoutWeight(1.4)
          DateTimeField({label: '开始时间', value: this.draft.startClock, clock: true, inputId: 'visit-start-time', night: this.night,
            onChange: (value: string) => this.change('startClock', value)}).layoutWeight(1)
        }.width('100%').alignItems(VerticalAlign.Top)
        Row({space: 12}) {
          DateTimeField({label: '结束日期', value: this.draft.endDay, clock: false, inputId: 'visit-end-date', night: this.night,
            onChange: (value: string) => this.change('endDay', value)}).layoutWeight(1.4)
          DateTimeField({label: '结束时间', value: this.draft.endClock, clock: true, inputId: 'visit-end-time', night: this.night,
            onChange: (value: string) => this.change('endClock', value)}).layoutWeight(1)
        }.width('100%').alignItems(VerticalAlign.Top)
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding(14).backgroundColor(palette(this.night).soft).borderRadius(4).margin({bottom: 20})
  }
}
