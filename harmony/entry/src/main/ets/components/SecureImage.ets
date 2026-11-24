import { image } from '@kit.ImageKit';
import { palette } from './Palette';
import { embeddedImage, previewImage } from '../services/ImageSource';
import { Api } from '../services/Api';
import { ResourceSlot } from '../services/ResourceSlot';

@Component
export struct SecureImage {
  url: string = '';
  thumbnailUrl: string = '';
  @Prop night: boolean = false;
  @State pixels: image.PixelMap | undefined = undefined;
  @State full: image.PixelMap | undefined = undefined;
  @State previewFailed: boolean = false;
  private fullResources: ResourceSlot<image.PixelMap> = new ResourceSlot<image.PixelMap>((pixels: image.PixelMap) => { pixels.release(); });
  @State failed: boolean = false;
  @State expanded: boolean = false;
  private resources: ResourceSlot<image.PixelMap> = new ResourceSlot<image.PixelMap>((pixels: image.PixelMap) => { pixels.release(); });
  aboutToAppear(): void { this.load(); }
  async load(): Promise<void> {
    const generation = this.resources.begin();
    try {
      const pixels = await Api.image(embeddedImage(this.url, this.thumbnailUrl));
      if (this.resources.accept(generation, pixels)) this.pixels = pixels;
    } catch (_) { if (this.resources.current(generation)) this.failed = true; }
  }
  private async loadPreview(): Promise<void> {
    this.previewFailed = false;
    const source = previewImage(this.url, this.thumbnailUrl);
    if (!source) return;
    const generation = this.fullResources.begin();
    try {
      const pixels = await Api.image(source);
      if (this.fullResources.accept(generation, pixels)) this.full = pixels;
    } catch (_) { if (this.fullResources.current(generation)) this.previewFailed = true; }
  }
  private releasePreview(): void { this.full = undefined; this.fullResources.clear(); }
  aboutToDisappear(): void { this.releasePreview(); this.resources.clear(); this.pixels = undefined; this.expanded = false; }
  @Builder
  private Preview() {
    Column({space: 16}) {
      Row() {
        Text('故障与现场图片').fontSize(18).fontColor(palette(this.night).ink).layoutWeight(1)
        Button('关闭').height(48).fontSize(16).fontColor(palette(this.night).ink)
          .backgroundColor(Color.Transparent).onClick(() => this.expanded = false)
      }.width('100%')
      if (this.previewFailed) { Button('高清图加载失败，点击重试').height(48).fontColor(palette(this.night).ink).backgroundColor(palette(this.night).soft).onClick(() => this.loadPreview()) }
      if (this.pixels) { Image(this.full || this.pixels).objectFit(ImageFit.Contain).width('100%').layoutWeight(1) }
    }.width('100%').height('100%').padding(20).backgroundColor(palette(this.night).surface)
  }
  build() {
    Stack() {
      if (this.pixels) { Image(this.pixels).objectFit(ImageFit.Cover).width('100%').height(100) }
      else { Text(this.failed ? '图片加载失败，点击重试' : '加载图片…').fontSize(14).fontColor(palette(this.night).muted) }
    }.width('100%').height(100).backgroundColor(palette(this.night).soft).borderRadius(4).accessibilityText('查看现场图片')
      .onClick(() => {
        if (this.failed) { this.failed = false; this.load(); }
        else if (this.pixels) { this.expanded = true; this.loadPreview(); }
      })
      .bindSheet($$this.expanded, this.Preview, {height: SheetSize.LARGE, showClose: false, onDisappear: () => this.releasePreview()})
  }
}
