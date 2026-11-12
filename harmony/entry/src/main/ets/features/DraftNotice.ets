import { palette } from '../components/Palette';

@Component
export struct DraftNotice {
  @Prop restored: boolean = false;
  @Prop blocked: boolean = false;
  @Prop night: boolean = false;
  onDiscard: () => void = () => {};

  build() {
    Column({space: 8}) {
      Text(this.blocked ? '上次提交结果待核对，请先查看服务记录。' : this.restored ? '已恢复本机文字草稿。请核对信息并重新选择图片。' : '文字草稿保存在本机，重新登录同一账号可恢复。图片请重新选择。')
        .fontSize(13).lineHeight(22).fontColor(palette(this.night).muted).width('100%')
      Button('清除本机草稿').height(44).fontSize(13).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent)
        .onClick(async () => {
          const result = await this.getUIContext().getPromptAction().showDialog({
            title: '清除本机草稿？', message: this.blocked ? '请先确认服务记录中没有本次提交。清除后再次提交会作为新记录。' : '清除后无法恢复当前文字内容。',
            buttons: [{text: '保留', color: palette(this.night).muted}, {text: '确认清除', color: palette(this.night).accent}]
          });
          if (result.index === 1) this.onDiscard();
        })
    }.padding(12).backgroundColor(palette(this.night).soft).width('100%').margin({bottom: 16}).borderRadius(4)
  }
}
