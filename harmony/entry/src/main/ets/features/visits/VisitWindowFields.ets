import { DateTimeField, TimePresets } from '../../components/DateTimeFields';
import { palette } from '../../components/Palette';
import { today, beijingClock, suggestedVisitEnd, beijingParts, visitWindowRange, LocalWindow } from '../../services/Labels';

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
    if (field === 'endClock') {
      next.endClock = value;
    } else {
      if (field === 'startDay') { next.startDay = value; next.endDay = value; }
      else { next.startClock = value; next.endDay = next.startDay; }
      if (next.startClock && (!next.endClock || next.endDay + next.endClock <= next.startDay + next.startClock)) {
        const end = suggestedVisitEnd(next.startDay, next.startClock);
        next.endClock = end.clock; next.endDay = end.day;
      }
    }
    this.onChange(next);
  }
  private presets(): string[] {
    const labels: string[] = [];
    const now = beijingClock();
    for (const clock of ['14:00', '19:00']) {
      if (clock > now) labels.push('今天 ' + clock);
    }
    labels.push('明天 09:00', '明天 14:00');
    if (now >= '19:00') labels.push('明天 19:00');
    return labels;
  }
  private preset(label: string): void {
    const next = this.draft.copy();
    next.startClock = label.slice(3);
    const tomorrow = label.startsWith('明天') || next.startClock <= beijingClock();
    next.startDay = beijingParts(new Date(Date.now() + (tomorrow ? 86400000 : 0)).toISOString()).day;
    const end = suggestedVisitEnd(next.startDay, next.startClock);
    next.endDay = end.day; next.endClock = end.clock;
    this.onChange(next);
  }
  build() {
    Column({space: 12}) {
      Row() {
        Text('预约上门时间').fontSize(16).fontColor(palette(this.night).ink).layoutWeight(1)
        Toggle({type: ToggleType.Switch, isOn: this.draft.enabled}).id('visit-window-enabled').selectedColor(palette(this.night).accent)
          .onChange((value: boolean) => { const next = this.draft.copy(); next.enabled = value; this.onChange(next); })
      }.width('100%').height(48)
      Text('可选。填写你方便上门的时间范围；宿管确认后才成为已确认预约。时间均为北京时间。')
        .fontSize(14).fontColor(palette(this.night).muted).lineHeight(21).width('100%')
      if (this.draft.enabled) {
        TimePresets({labels: this.presets(), night: this.night, onChoose: (_index: number, label: string) => this.preset(label)})
        DateTimeField({label: '预约日期', minDay: today(), value: this.draft.startDay, inputId: 'visit-start-date', night: this.night,
          onChange: (value: string) => this.change('startDay', value)})
        Row({space: 12}) {
          DateTimeField({label: '上门时间', value: this.draft.startClock, clock: true, inputId: 'visit-start-time', night: this.night,
            onChange: (value: string) => this.change('startClock', value)}).layoutWeight(1)
          DateTimeField({label: this.draft.endDay === this.draft.startDay ? '结束时间' : this.draft.endDay + ' 结束时间', value: this.draft.endClock, clock: true, inputId: 'visit-end-time', night: this.night,
            onChange: (value: string) => this.change('endClock', value)}).layoutWeight(1)
        }.width('100%').alignItems(VerticalAlign.Top)
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding(14).backgroundColor(palette(this.night).soft).borderRadius(4).margin({bottom: 20})
  }
}
