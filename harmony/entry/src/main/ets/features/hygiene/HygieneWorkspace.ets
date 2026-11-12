import { serviceLabel, serviceField, servicePrimary, serviceSecondary, serviceEmpty } from '../../components/ServiceParts';
import { User, Room, Repair, FileAsset, Hygiene } from '../../models/ApiModels';
import { gradeLabel } from '../../services/Labels';
import { palette } from '../../components/Palette';
import { SecureImage } from '../../components/SecureImage';
import { WorkspaceHost, emptyWorkspaceHost } from '../WorkspaceHost';
import { PhotoDraftController } from '../photos/PhotoDraftController';
import { PhotoAttachments } from '../photos/PhotoAttachments';
import { DispatchController } from '../dispatch/DispatchController';
import { HygieneController, HygieneState } from './HygieneController';
interface ScoreArgs { label: string; hint: string; value: string; change: (value: string) => void; id: string; }

@Component
export struct HygieneWorkspace {
  @ObjectLink state: HygieneState;
  @Prop page: string = 'home';
  @Prop night: boolean = false;
  user: User | null = null;
  host: WorkspaceHost = emptyWorkspaceHost();
  photoDrafts: PhotoDraftController = new PhotoDraftController((error: Error): void => this.host.fail(error));
  controller: HygieneController = new HygieneController(this.host, this.photoDrafts);
  @Prop room: Room | null = null;
  private manager(): boolean { return this.user?.role === 'MANAGER'; }
  private surface(): string { return palette(this.night).surface; }

  private ink(): string { return palette(this.night).ink; }

  private muted(): string { return palette(this.night).muted; }

  private line(): string { return palette(this.night).line; }

  private soft(): string { return palette(this.night).soft; }

  @Builder
  private Uploads(usage: string) {
    PhotoAttachments({usage: usage, controller: this.photoDrafts, night: this.night, disabled: this.state.busy})
  }

  @Builder
  private ScoreInput($$: ScoreArgs) {
    Row({space: 12}) {
      Column({space: 4}) { Text($$.label).fontSize(16).fontColor(this.ink()); Text($$.hint).fontSize(12).fontColor(this.muted()) }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
      TextInput({text: $$.value}).id($$.id).type(InputType.Number).width(80).height(50).fontSize(18).fontColor(this.ink())
        .backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4).textAlign(TextAlign.Center).maxLength(2).onChange($$.change)
    }.width('100%').padding({top: 14, bottom: 14}).border({width: {bottom: 1}, color: this.line()})
  }

  @Builder
  private HygieneCreate() {
    Column({space: 0}) {
      Row() {
        Column({space: 6}) { Text('卫生总分').fontSize(17).fontWeight(FontWeight.Medium).fontColor(this.ink()); Text('四项各25分 · 满分100分').fontSize(12).fontColor(this.muted()) }
          .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(String(this.controller.totalScore())).id('hygiene-total').fontSize(36).fontColor(this.ink()).fontWeight(FontWeight.Medium)
        Text(this.controller.scoreGrade()).fontSize(13).fontColor(this.muted()).margin({left: 6})
      }.width('100%').padding(18).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 24})
      serviceLabel('检查宿舍 *', this.night)
      Row({space: 8}) {
        TextInput({text: this.state.roomSearch, placeholder: '按房间号查找'}).id('hygiene-room-search').height(48).layoutWeight(1)
          .fontSize(14).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.state.roomSearch = v).onSubmit(() => this.controller.loadRooms())
        serviceSecondary('查找', () => this.controller.loadRooms(), this.night, this.state.busy)
      }.width('100%').margin({top: 8, bottom: 4})
      if (this.state.rooms.length) {
        Select(this.state.rooms.map((r: Room): SelectOption => { return {value: r.buildingName + ' ' + r.roomNo}; })).id('hygiene-room')
          .selected(this.state.rooms.findIndex((r: Room): boolean => r.id === this.state.inspectionRoomId)).value(this.controller.roomChoice()).onSelect((i: number) => this.state.inspectionRoomId = this.state.rooms[i].id)
          .width('100%').height(50).fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4).margin({top: 8, bottom: 20})
      } else { serviceEmpty('暂无可巡检的宿舍，请刷新或联系管理员。', this.night) }
      serviceField({label: '检查日期 *', value: this.state.inspectionDate, change: (v: string) => this.state.inspectionDate = v, placeholder: 'YYYY-MM-DD'}, this.night, this.page)
      serviceField({label: '实际检查时间 *', value: this.state.inspectionTime, change: (v: string) => this.state.inspectionTime = v, placeholder: 'HH:mm，例如18:30'}, this.night, this.page)
      Text('北京时间。补录时填写实际检查的日期与时刻，不使用保存时间代替。').fontSize(12).fontColor(this.muted()).lineHeight(21).margin({bottom: 18})
      serviceLabel('巡检轮次 *', this.night)
      if (this.state.inspectionRelated) { Text('关联复查轮次：' + this.state.inspectionRound).fontSize(13).fontColor(this.muted()).margin({top: 8}) }
      else { Text('需要复查时，请从卫生记录选择“录入整改复查”以关联原记录。').fontSize(12).fontColor(this.muted()).lineHeight(21).margin({top: 8}) }
      Select(this.state.inspectionRelated ? [{value: '日常巡检'}, {value: '整改复查'}] : [{value: '日常巡检'}]).selected(this.state.inspectionRound.startsWith('RECHECK') ? 1 : 0).value(this.state.inspectionRound.startsWith('RECHECK') ? '整改复查' : '日常巡检')
        .onSelect((i: number) => { if (i) { if (!this.state.inspectionRound.startsWith('RECHECK')) this.state.inspectionRound = 'RECHECK'; } else { this.state.inspectionRound = 'ROUTINE'; this.state.inspectionRelated = ''; } }).width('100%').height(50)
        .fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4).margin({top: 8, bottom: 20})
      this.ScoreInput({label: '地面', hint: '无垃圾、无积水', value: this.state.floorScore, change: (v: string) => this.state.floorScore = v, id: 'hygiene-floor'})
      this.ScoreInput({label: '桌面', hint: '物品摆放整齐', value: this.state.deskScore, change: (v: string) => this.state.deskScore = v, id: 'hygiene-desk'})
      this.ScoreInput({label: '床铺', hint: '床铺整洁、无杂物', value: this.state.bedScore, change: (v: string) => this.state.bedScore = v, id: 'hygiene-bed'})
      this.ScoreInput({label: '卫生间', hint: '洁净无异味', value: this.state.bathroomScore, change: (v: string) => this.state.bathroomScore = v, id: 'hygiene-bathroom'})
      Blank().height(22)
      serviceField({label: '问题与扣分说明', value: this.state.issueNote, change: (v: string) => this.state.issueNote = v, placeholder: '低于60分时必须说明问题', multiline: true}, this.night, this.page)
      serviceField({label: '整改建议', value: this.state.remediationNote, change: (v: string) => this.state.remediationNote = v, placeholder: '写明可执行的改进建议', multiline: true}, this.night, this.page)
      this.Uploads('INSPECTION')
      servicePrimary('保存评分', () => this.controller.submitHygiene(), this.night, this.page, this.state.busy, this.state.uploading)
      Text('同一宿舍、日期和轮次只保留一条有效记录。更正成绩请在Web中操作并说明原因。').fontSize(12).fontColor(this.muted()).lineHeight(21).margin({top: 12})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private HygieneList() {
    Column({space: 0}) {
      if (this.manager()) { servicePrimary('＋ 录入卫生评分', () => this.host.navigate('hygiene-create'), this.night, this.page, this.state.busy, this.state.uploading); Blank().height(18) }
      Text(this.manager() ? '授权楼栋内的巡检记录' : '仅展示本人有效入住期间的宿舍检查记录').fontSize(13).fontColor(this.muted()).lineHeight(22).width('100%').margin({bottom: 18})
      if (!this.state.hygiene.length && !this.state.loading) { serviceEmpty('暂时没有可查看的卫生记录。', this.night) }
      ForEach(this.state.hygiene, (item: Hygiene) => {
        Column({space: 12}) {
          Row() {
            Column({space: 6}) { Text(item.buildingName + ' ' + item.roomNo).fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink());
              Text(item.inspectionDate + ' · ' + (item.round === 'ROUTINE' ? '日常巡检' : '整改复查')).fontSize(12).fontColor(this.muted()) }
              .layoutWeight(1).alignItems(HorizontalAlign.Start)
            Column({space: 2}) { Text(String(item.totalScore)).fontSize(30).fontWeight(FontWeight.Medium).fontColor(this.ink()); Text(gradeLabel(item.grade)).fontSize(12).fontColor(this.muted()) }
          }.width('100%')
          Text('地面 ' + item.floorScore + '  ·  桌面 ' + item.deskScore + '  ·  床铺 ' + item.bedScore + '  ·  卫生间 ' + item.bathroomScore)
            .fontSize(13).fontColor(this.ink()).lineHeight(23).width('100%')
          if (item.issueNote) { Text('检查说明：' + item.issueNote).fontSize(14).fontColor(this.ink()).lineHeight(24).width('100%') }
          if (item.remediationNote) { Text('整改建议：' + item.remediationNote).fontSize(14).fontColor(this.muted()).lineHeight(24).width('100%') }
          Text('检查人：' + item.inspectorName).fontSize(12).fontColor(this.muted()).width('100%')
          ForEach(item.images, (asset: FileAsset) => { SecureImage({url: asset.url, night: this.night}) }, (asset: FileAsset) => asset.id)
          if (this.manager()) { serviceSecondary('录入整改复查', () => this.controller.recheck(item), this.night, this.state.busy) }
        }.padding(18).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(6).margin({bottom: 14})
      }, (item: Hygiene) => item.id + ':' + item.version)
      Row({space: 12}) {
        Button('上一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.hygienePage > 1 && !this.state.loading).onClick(() => { this.state.hygienePage--; this.controller.loadHygiene(); })
        Text(String(this.state.hygienePage)).fontSize(14).fontColor(this.muted())
        Button('下一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.hygienePage * 10 < this.state.hygieneTotal && !this.state.loading).onClick(() => { this.state.hygienePage++; this.controller.loadHygiene(); })
      }.width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  build() {
    Column() {
      if (this.page === 'hygiene-create') { this.HygieneCreate() } else { this.HygieneList() }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
