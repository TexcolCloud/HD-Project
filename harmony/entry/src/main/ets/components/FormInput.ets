import { palette } from './Palette';

@Component
export struct FormInput {
  label: string = '';
  @Prop value: string = '';
  @Prop night: boolean = false;
  placeholder: string = '';
  multiline: boolean = false;
  numeric: boolean = false;
  inputId: string = '';
  onChange: (value: string) => void = () => {};
  build() {
    Column({space: 8}) {
      Text(this.label).fontSize(15).fontColor(palette(this.night).ink).width('100%')
      if (this.multiline) {
        TextArea({text: this.value, placeholder: this.placeholder}).id(this.inputId).onChange(this.onChange)
          .fontSize(16).fontColor(palette(this.night).ink).placeholderColor(palette(this.night).muted)
          .backgroundColor(palette(this.night).surface).border({width: 1, color: palette(this.night).line})
          .borderRadius(4).padding(12).height(100).maxLength(1000)
      } else {
        TextInput({text: this.value, placeholder: this.placeholder}).id(this.inputId).onChange(this.onChange)
          .type(this.numeric ? InputType.Number : InputType.Normal).fontSize(16).fontColor(palette(this.night).ink)
          .placeholderColor(palette(this.night).muted).backgroundColor(palette(this.night).surface)
          .border({width: 1, color: palette(this.night).line}).borderRadius(4).height(50).maxLength(150)
      }
    }.width('100%').alignItems(HorizontalAlign.Start).margin({bottom: 16})
  }
}
