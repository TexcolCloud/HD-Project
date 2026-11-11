import { image } from '@kit.ImageKit';
import { Api } from '../services/Api';
import { ResourceSlot } from '../services/ResourceSlot';

@Component
export struct SecureImage {
  url: string = '';
  @Prop night: boolean = false;
  @State pixels: image.PixelMap | undefined = undefined;
  @State failed: boolean = false;
  @State expanded: boolean = false;
  private resources: ResourceSlot<image.PixelMap> = new ResourceSlot<image.PixelMap>((pixels: image.PixelMap) => { pixels.release(); });
  aboutToAppear(): void { this.load(); }
  async load(): Promise<void> {
    const generation = this.resources.begin();
    try {
      const pixels = await Api.image(this.url);
      if (this.resources.accept(generation, pixels)) this.pixels = pixels;
    } catch (_) { if (this.resources.current(generation)) this.failed = true; }
  }
  aboutToDisappear(): void { this.resources.clear(); this.pixels = undefined; this.expanded = false; }
  @Builder
  private Preview() {
    Column({space: 16}) {
      Row() {
        Text('故障与现场图片').fontSize(18).fontColor(this.night ? '#ECE6D9' : '#242622').layoutWeight(1)
        Button('关闭').height(48).fontSize(15).fontColor(this.night ? '#ECE6D9' : '#242622')
          .backgroundColor(Color.Transparent).onClick(() => this.expanded = false)
      }.width('100%')
      if (this.pixels) { Image(this.pixels).objectFit(ImageFit.Contain).width('100%').layoutWeight(1) }
    }.width('100%').height('100%').padding(20).backgroundColor(this.night ? '#1E211E' : '#F5F1E8')
  }
  build() {
    Stack() {
      if (this.pixels) { Image(this.pixels).objectFit(ImageFit.Cover).width('100%').height(100) }
      else { Text(this.failed ? '图片加载失败，点击重试' : '加载图片…').fontSize(12).fontColor('#62665C') }
    }.width('100%').height(100).backgroundColor('#EDE8DF').borderRadius(4).accessibilityText('查看现场图片')
      .onClick(() => {
        if (this.failed) { this.failed = false; this.load(); }
        else if (this.pixels) this.expanded = true;
      })
      .bindSheet($$this.expanded, this.Preview, {height: SheetSize.LARGE, showClose: false})
  }
}
