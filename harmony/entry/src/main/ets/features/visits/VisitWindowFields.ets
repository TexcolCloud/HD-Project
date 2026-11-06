import { FormInput } from '../../components/FormInput';
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
  build() {
    Column({space: 12}) {
      Row() {
        Text('提供可上门时段').fontSize(16).fontColor(palette(this.night).ink).layoutWeight(1)
        Toggle({type: ToggleType.Switch, isOn: this.draft.enabled}).id('visit-window-enabled').selectedColor(palette(this.night).accent)
          .onChange((value: boolean) => { const next = this.draft.copy(); next.enabled = value; this.onChange(next); })
      }.width('100%').height(48)
      Text('可选。填写你方便上门的时间范围；宿管确认后才成为已确认预约。时间均为北京时间。')
        .fontSize(12).fontColor(palette(this.night).muted).lineHeight(21).width('100%')
      if (this.draft.enabled) {
        Row({space: 12}) {
          FormInput({label: '开始日期', value: this.draft.startDay, placeholder: 'YYYY-MM-DD', inputId: 'visit-start-date', night: this.night,
            onChange: (value: string) => this.change('startDay', value)}).layoutWeight(1.4)
          FormInput({label: '开始时间', value: this.draft.startClock, placeholder: '09:00', inputId: 'visit-start-time', night: this.night,
            onChange: (value: string) => this.change('startClock', value)}).layoutWeight(1)
        }.width('100%').alignItems(VerticalAlign.Top)
        Row({space: 12}) {
          FormInput({label: '结束日期', value: this.draft.endDay, placeholder: 'YYYY-MM-DD', inputId: 'visit-end-date', night: this.night,
            onChange: (value: string) => this.change('endDay', value)}).layoutWeight(1.4)
          FormInput({label: '结束时间', value: this.draft.endClock, placeholder: '11:00', inputId: 'visit-end-time', night: this.night,
            onChange: (value: string) => this.change('endClock', value)}).layoutWeight(1)
        }.width('100%').alignItems(VerticalAlign.Top)
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding(14).backgroundColor(palette(this.night).soft).borderRadius(4).margin({bottom: 20})
  }
}
