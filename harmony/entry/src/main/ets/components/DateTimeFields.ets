import { LengthMetrics } from '@kit.ArkUI';
import { palette } from './Palette';
import { beijingClock, today } from '../services/Labels';

@Component
export struct DateTimeField {
  label: string = '';
  inputId: string = '';
  @Prop minDay: string = '';
  @Prop maxDay: string = '';
  clock: boolean = false;
  @Prop value: string = '';
  @Prop night: boolean = false;
  @Prop disabled: boolean = false;
  onChange: (value: string) => void = () => {};
  private live: boolean = false;
  aboutToAppear(): void { this.live = true; }
  aboutToDisappear(): void { this.live = false; }

  private pickerDate(day: string): Date {
    return new Date(Number(day.slice(0, 4)), Number(day.slice(5, 7)) - 1, Number(day.slice(8)));
  }
  private pick(): void {
    const original = this.value;
    if (this.clock) {
      const time = original || beijingClock();
      const selected = new Date();
      selected.setHours(Number(time.slice(0, 2)), Number(time.slice(3)), 0, 0);
      this.getUIContext().showTimePickerDialog({
        selected: selected, useMilitaryTime: true,
        onAccept: (result: TimePickerResult): void => {
          if (this.live && !this.disabled && this.value === original)
            this.onChange(String(result.hour).padStart(2, '0') + ':' + String(result.minute).padStart(2, '0'));
        }
      });
    } else {
      let day = original || today();
      if (this.minDay && day < this.minDay) day = this.minDay;
      if (this.maxDay && day > this.maxDay) day = this.maxDay;
      this.getUIContext().showDatePickerDialog({
        start: this.minDay ? this.pickerDate(this.minDay) : undefined,
        end: this.maxDay ? this.pickerDate(this.maxDay) : undefined, selected: this.pickerDate(day),
        onDateAccept: (date: Date): void => {
          if (this.live && !this.disabled && this.value === original)
            this.onChange(date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0'));
        }
      });
    }
  }
  build() {
    Column({space: 4}) {
      Text(this.label).fontSize(16).fontColor(palette(this.night).ink)
      Button(this.value || '请选择', {type: ButtonType.Normal}).id(this.inputId).width('100%').height(48).fontSize(16)
        .accessibilityText(this.label + ' ' + (this.value || '请选择'))
        .fontColor(palette(this.night).ink).backgroundColor(palette(this.night).surface)
        .border({width: 1, color: palette(this.night).outline}).borderRadius(4).enabled(!this.disabled).onClick(() => this.pick())
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}

@Component
export struct TimePresets {
  @Prop labels: string[] = [];
  @Prop night: boolean = false;
  @Prop disabled: boolean = false;
  onChoose: (index: number, label: string) => void = () => {};
  build() {
    Flex({wrap: FlexWrap.Wrap, space: {main: LengthMetrics.vp(8), cross: LengthMetrics.vp(4)}}) {
      ForEach(this.labels, (label: string, index: number) => {
        Button(label, {type: ButtonType.Normal}).height(48).fontSize(14).borderRadius(4).fontColor(palette(this.night).ink)
          .backgroundColor(palette(this.night).soft).enabled(!this.disabled).onClick(() => this.onChoose(index, label))
      }, (label: string) => label)
    }.width('100%')
  }
}
