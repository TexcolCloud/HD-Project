import { palette } from '../components/Palette';

@Component
export struct DraftNotice {
  @Prop restored: boolean = false;
  @Prop blocked: boolean = false;
  @Prop night: boolean = false;
  onDiscard: () => void = () => {};

  build() {
    Column({space: 8}) {
      if (this.blocked || this.restored) {
        Text(this.blocked ? '上次提交结果待核对，请先查看服务记录。' : '已恢复本机文字草稿。请核对信息并重新选择图片。')
          .fontSize(14).lineHeight(22).fontColor(palette(this.night).muted).width('100%')
      }
      Row() {
        Text('本机文字草稿').fontSize(14).fontColor(palette(this.night).muted).layoutWeight(1)
        Button('清除草稿').height(48).fontSize(14).fontColor(palette(this.night).ink).backgroundColor(Color.Transparent)
        .onClick(async () => {
          const result = await this.getUIContext().getPromptAction().showDialog({
            title: '清除本机草稿？', message: this.blocked ? '请先确认服务记录中没有本次提交。清除后再次提交会作为新记录。' : '清除后无法恢复当前文字内容。',
            buttons: [{text: '保留', color: palette(this.night).muted}, {text: '确认清除', color: palette(this.night).accent}]
          });
          if (result.index === 1) this.onDiscard();
        })
      }.width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start).margin({bottom: 16})
  }
}
