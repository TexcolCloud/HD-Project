import { common } from '@kit.AbilityKit';
import { UploadedPhoto } from '../../services/Api';
import { palette } from '../../components/Palette';
import { PhotoDraft, PhotoDraftController, PhotoSnapshot } from './PhotoDraftController';

@Component
export struct PhotoAttachments {
  usage: string = 'REPORT';
  controller: PhotoDraftController = new PhotoDraftController(() => {});
  @Prop night: boolean = false;
  @Prop disabled: boolean = false;
  @State draft: PhotoDraft = new PhotoDraft();
  @State busy: boolean = false;
  private live: boolean = false;
  private unsubscribe: () => void = () => {};
  aboutToAppear(): void {
    this.live = true;
    this.unsubscribe = this.controller.subscribe((value: PhotoSnapshot): void => {
      if (this.live && value.usage === this.usage) { this.draft = value.draft; this.busy = value.busy; }
    });
  }
  aboutToDisappear(): void { this.live = false; this.unsubscribe(); }
  private count(): number { return this.draft.uploaded.length + this.draft.failed.length; }
  build() {
    Column({space: 8}) {
      Row() {
        Text(this.usage === 'REPORT' ? '故障图片' : this.usage === 'RESULT' ? '处理后图片' : '现场图片').fontSize(15).fontColor(palette(this.night).ink).layoutWeight(1)
        Text(this.draft.uploaded.length + ' 已上传 / ' + this.count() + ' 已选').fontSize(12).fontColor(palette(this.night).muted)
      }.width('100%')
      ForEach(this.draft.uploaded, (photo: UploadedPhoto) => {
        Row({space: 12}) {
          Image(photo.uri).width(66).height(66).objectFit(ImageFit.Cover).borderRadius(3)
          Column({space: 4}) { Text('图片已上传').fontSize(14).fontColor(palette(this.night).ink); Text(Math.ceil(photo.asset.sizeBytes / 1024) + ' KB').fontSize(12).fontColor(palette(this.night).muted) }
            .alignItems(HorizontalAlign.Start).layoutWeight(1)
          Button('移除').fontSize(13).height(48).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).enabled(!this.disabled && !this.busy)
            .onClick(() => this.controller.remove(photo, this.usage))
        }.width('100%').padding(10).backgroundColor(palette(this.night).surface).border({width: 1, color: palette(this.night).line}).borderRadius(4)
      }, (photo: UploadedPhoto) => photo.asset.id)
      ForEach(this.draft.failed, (uri: string) => {
        Row({space: 10}) {
          Image(uri).width(56).height(56).objectFit(ImageFit.Cover).borderRadius(3)
          Text('上传失败').fontSize(13).fontColor(palette(this.night).danger).layoutWeight(1)
          Button('重试').height(48).fontSize(13).fontColor(palette(this.night).ink).backgroundColor(Color.Transparent).enabled(!this.busy).onClick(() => this.controller.retry(uri, this.usage))
          Button('移除').height(48).fontSize(13).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).enabled(!this.busy).onClick(() => this.controller.removeFailed(uri, this.usage))
        }.width('100%').padding(8).backgroundColor(palette(this.night).surface).border({width: 1, color: palette(this.night).line}).borderRadius(4)
      }, (uri: string) => uri)
      Button(this.busy ? '正在上传，请稍候…' : '＋ 从相册选择图片').id(this.usage === 'REPORT' ? 'repair-add-image' : this.usage === 'RESULT' ? 'result-add-image' : 'hygiene-add-image').width('100%').height(54).fontSize(15)
        .fontColor(palette(this.night).ink).backgroundColor(palette(this.night).surface).borderRadius(4).border({width: 1, color: palette(this.night).line, style: BorderStyle.Dashed})
        .enabled(!this.busy && !this.disabled && this.count() < 6).onClick(() => this.controller.choose(this.usage, this.getUIContext().getHostContext() as common.UIAbilityContext))
      Button('打开系统相机拍照').id('system-camera-' + this.usage).width('100%').height(48).fontSize(14).fontColor(palette(this.night).accent)
        .backgroundColor(palette(this.night).surface).borderRadius(4).enabled(!this.busy && !this.disabled && this.count() < 6)
        .onClick(() => this.controller.choose(this.usage, this.getUIContext().getHostContext() as common.UIAbilityContext, true))
      Text('最多6张，每张处理后不超过10MB；上传失败会保留已填内容。').fontSize(12).fontColor(palette(this.night).muted).lineHeight(21).width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start).margin({bottom: 22})
  }
}
