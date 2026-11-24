import { DateTimeField, TimePresets } from '../../components/DateTimeFields';
import { DraftNotice } from "../DraftNotice";
import { serviceLabel, serviceField, servicePrimary, serviceSecondary, serviceEmpty } from '../../components/ServiceParts';
import { User, Room, FileAsset, Hygiene } from '../../models/ApiModels';
import { beijingClock, dateText, gradeLabel, today } from '../../services/Labels';
import { palette } from '../../components/Palette';
import { SecureImage } from '../../components/SecureImage';
import { WorkspaceHost, emptyWorkspaceHost } from '../WorkspaceHost';
import { PhotoDraftController } from '../photos/PhotoDraftController';
import { PhotoAttachments } from '../photos/PhotoAttachments';
import { HygieneController, HygieneState } from './HygieneController';
interface ScoreArgs { label: string; hint: string; value: string; change: (value: string) => void; id: string; }
interface InspectionArgs { item: Hygiene; }

@Component
export struct HygieneWorkspace {
  @ObjectLink state: HygieneState;
  onRemediate: (item: Hygiene) => void = () => {};
  @Prop page: string = 'home';
  @Prop night: boolean = false;
  user: User | null = null;
  host: WorkspaceHost = emptyWorkspaceHost();
  photoDrafts: PhotoDraftController = new PhotoDraftController((error: Error): void => this.host.fail(error));
  controller: HygieneController = new HygieneController(this.host, this.photoDrafts);
  @Prop room: Room | null = null;
  private buildingOptions(): Room[] {
    return this.state.rooms.filter((r: Room, i: number, all: Room[]): boolean => all.findIndex((other: Room): boolean => other.buildingId === r.buildingId) === i);
  }
  private live: boolean = false;
  aboutToAppear(): void { this.live = true; }
  aboutToDisappear(): void { this.live = false; }
  private manager(): boolean { return this.user?.role === 'MANAGER'; }
  private surface(): string { return palette(this.night).surface; }

  private ink(): string { return palette(this.night).ink; }

  private muted(): string { return palette(this.night).muted; }

  private line(): string { return palette(this.night).line; }

  private soft(): string { return palette(this.night).soft; }

  private calendarDate(day: string): Date {
    return new Date(Number(day.slice(0, 4)), Number(day.slice(5, 7)) - 1, Number(day.slice(8, 10)));
  }

  private selectDate(): void {
    const date = this.state.inspectionDate;
    const room = this.state.inspectionRoomId;
    const correction = this.state.correctionId;
    this.getUIContext().showDatePickerDialog({
      selected: this.calendarDate(date), end: this.calendarDate(today()),
      onDateAccept: (selected: Date): void => {
        if (!this.live || this.page !== 'hygiene-create' || this.state.inspectionDate !== date ||
          this.state.inspectionRoomId !== room || this.state.correctionId !== correction) return;
        this.controller.chooseDate(selected.getFullYear() + '-' + String(selected.getMonth() + 1).padStart(2, '0') + '-' + String(selected.getDate()).padStart(2, '0'));
      }
    });
  }

  @Builder
  private Uploads(usage: string) {
    PhotoAttachments({usage: usage, controller: this.photoDrafts, night: this.night, disabled: this.state.busy, alreadyAttached: this.state.attachedImages.length})
  }

  @Builder
  private InspectionSummary($$: InspectionArgs) {
    Text($$.item.inspectionDate + ' · ' + ($$.item.round === 'ROUTINE' ? '日常巡检' : '整改复查 · 第 ' + ($$.item.round.slice(7) || '1') + ' 次') + ' · ' + $$.item.totalScore + ' 分')
      .fontSize(14).fontColor(this.ink()).lineHeight(24).width('100%')
    Text('地面 ' + $$.item.floorScore + ' · 桌面 ' + $$.item.deskScore + ' · 床铺 ' + $$.item.bedScore + ' · 卫生间 ' + $$.item.bathroomScore)
      .fontSize(14).fontColor(this.ink()).lineHeight(23).width('100%')
    Text('实际检查 · ' + dateText($$.item.inspectedAt)).fontSize(14).fontColor(this.muted()).lineHeight(22).width('100%')
  }

  @Builder
  private ScoreInput($$: ScoreArgs) {
    Row({space: 12}) {
      Column({space: 4}) { Text($$.label).fontSize(16).fontColor(this.ink()); Text($$.hint).fontSize(14).fontColor(this.muted()) }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Button('−').width(48).height(48).borderRadius(4).fontColor(this.ink()).backgroundColor(this.soft()).onClick(() => $$.change(String(Math.min(25,Math.max(0,Number($$.value)-1)))))
      TextInput({text: $$.value}).id($$.id).type(InputType.Number).width(56).height(50).fontSize(18).fontColor(this.ink())
        .backgroundColor(this.surface()).border({width: 1, color: palette(this.night).outline}).borderRadius(4).textAlign(TextAlign.Center).maxLength(2).onChange($$.change)
      Button('+').width(48).height(48).borderRadius(4).fontColor(this.ink()).backgroundColor(this.soft()).onClick(() => $$.change(String(Math.max(0,Math.min(25,Number($$.value)+1)))))
    }.width('100%')
    Row({space: 8}) {
      ForEach(['25','22','20','15'], (score: string) => {
        Button(score).height(48).layoutWeight(1).fontColor(this.ink()).backgroundColor($$.value === score ? this.soft() : this.surface()).borderRadius(4).onClick(() => $$.change(score))
      })
    }.width('100%').padding({top: 8, bottom: 14}).border({width: {bottom: 1}, color: this.line()})
  }

  @Builder
  private RoomChip(label: string, active: boolean, click: () => void) {
    Button(label).height(48).borderRadius(4).fontSize(14).margin({right: 4,bottom: 4})
      .fontColor(active ? palette(this.night).onPrimary : this.ink()).backgroundColor(active ? this.ink() : this.soft()).onClick(click)
  }

  @Builder
  private HygieneCreate() {
    Column({space: 0}) {
      DraftNotice({restored: this.state.draftRestored, blocked: this.state.draftBlocked, night: this.night, onDiscard: () => this.controller.discardDraft()})
      Row() {
        Column({space: 6}) { Text('卫生总分').fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink()); Text('四项各25分 · 满分100分').fontSize(14).fontColor(this.muted()) }
          .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Button('一键满分').height(48).fontSize(14).fontColor(this.ink()).backgroundColor(this.surface()).borderRadius(4).enabled(!this.state.busy && !this.state.draftBlocked).onClick(() => this.controller.fullScore())
        Text(String(this.controller.totalScore())).id('hygiene-total').fontSize(28).fontColor(this.ink()).fontWeight(FontWeight.Medium)
        Text(this.controller.scoreGrade()).fontSize(14).fontColor(this.muted()).margin({left: 6})
      }.width('100%').padding(18).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 24})
      serviceLabel({text: '检查宿舍 *', night: this.night})
      if (this.state.correctionId || this.state.inspectionRelated) {
        Text(this.controller.roomChoice()).fontSize(16).fontColor(this.ink()).width('100%').margin({top: 8, bottom: 18})
      } else {
        Row({space: 8}) {
        TextInput({text: this.state.roomSearch, placeholder: '按房间号查找'}).id('hygiene-room-search').height(48).layoutWeight(1)
          .fontSize(14).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).border({width: 1, color: palette(this.night).outline}).borderRadius(4)
          .onChange((v: string) => this.state.roomSearch = v)
      }.width('100%').margin({top: 8, bottom: 4})
      if (this.buildingOptions().length > 1) {
        Flex({wrap: FlexWrap.Wrap}) {
          ForEach(this.buildingOptions(), (r: Room) => {
            this.RoomChip(r.buildingName, this.state.buildingChoice === r.buildingId, () => this.controller.browseFloor(r.buildingId, 0))
          }, (r: Room) => r.buildingId)
        }
      }
      Flex({wrap: FlexWrap.Wrap}) {
        ForEach(this.controller.floors(), (floor: number) => {
          this.RoomChip(floor + 'F', this.state.floorChoice === floor, () => this.controller.browseFloor(this.state.buildingChoice, floor))
        })
      }
      Flex({wrap: FlexWrap.Wrap}) {
        ForEach(this.controller.visibleRooms(), (r: Room) => {
          this.RoomChip((this.state.roomSearch ? r.buildingName + ' ' : '') + r.roomNo, r.id === this.state.inspectionRoomId, () => this.controller.chooseRoom(r.id))
        }, (r: Room) => r.id)
      }
      Select(this.controller.visibleRooms().map((r: Room): SelectOption => { return {value:r.buildingName + ' ' + r.roomNo}; }))
        .id('hygiene-room').selected(this.controller.visibleRooms().findIndex((r: Room): boolean => r.id === this.state.inspectionRoomId))
        .value(this.controller.roomChoice()).fontColor(this.ink()).backgroundColor(this.surface()).optionBgColor(this.surface()).optionFontColor(this.ink())
        .width('100%').height(48).border({width: 1, color: palette(this.night).outline}).borderRadius(4).margin({bottom:16})
        .onSelect((index: number) => { const r = this.controller.visibleRooms()[index]; if (r) this.controller.chooseRoom(r.id); })
      }
      serviceLabel({text: '检查日期 *', night: this.night})
      Button(this.state.inspectionDate + '  更改日期').id('hygiene-date').height(50).width('100%').fontSize(16).fontColor(this.ink())
        .backgroundColor(this.surface()).border({width: 1, color: palette(this.night).outline}).borderRadius(4).margin({top: 8, bottom: 18})
        .enabled(!this.state.busy && !this.state.draftBlocked && !this.state.uploading).onClick(() => this.selectDate())
      DateTimeField({label: '实际检查时间 *', inputId:'hygiene-inspection-time', value: this.state.inspectionTime, clock: true, night: this.night,
        disabled: this.state.busy || this.state.uploading || this.state.draftBlocked,
        onChange: (value: string) => { this.state.inspectionTime = value; this.controller.editDraft(); }})
      TimePresets({labels: ['当前时间'], night: this.night, disabled: this.state.busy || this.state.uploading || this.state.draftBlocked,
        onChoose: () => { this.state.inspectionTime = beijingClock(); this.controller.editDraft(); }})
      Text('北京时间。补录时填写实际检查的日期与时刻，不使用保存时间代替。').fontSize(14).fontColor(this.muted()).lineHeight(21).margin({bottom: 18})
      serviceLabel({text: '巡检轮次 *', night: this.night})
      Text(this.state.inspectionRound === 'ROUTINE' ? '日常巡检' : '关联复查 · 第 ' + (this.state.inspectionRound.slice(7) || '1') + ' 次')
        .fontSize(14).fontColor(this.muted()).lineHeight(21).width('100%').margin({top: 8, bottom: 18})
      if (this.state.checking) { Text('正在查找已有评分…').fontSize(14).fontColor(this.muted()).width('100%') }
      if (this.state.comparison) {
        Column({space: 8}) {
          serviceLabel({text: this.state.alreadyRecorded ? '同轮已有评分' : '上次巡检评分', night: this.night})
          this.InspectionSummary({item: this.state.comparison})
          if (this.state.alreadyRecorded) {
            serviceSecondary({label: '更正评分', click: () => { if (this.state.comparison) this.controller.correct(this.state.comparison); }, night: this.night, busy: this.state.busy || this.state.draftBlocked})
          } else {
            serviceSecondary({label: '带入四项分数', click: () => this.controller.usePreviousScores(), night: this.night, busy: this.state.busy || this.state.draftBlocked})
          }
        }.width('100%').alignItems(HorizontalAlign.Start).padding(14).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 18})
      }
      if (this.state.correctionConflict) {
        Column({space: 8}) {
          Text('另一位宿管已更新这条评分。请核对最新值。').fontSize(14).fontColor(palette(this.night).danger).width('100%')
          if (this.state.currentCorrection) {
            this.InspectionSummary({item: this.state.currentCorrection})
            if (!this.state.currentCorrection.voidedAt) {
              serviceSecondary({label: '使用最新版本继续', click: () => this.controller.adoptCorrectionVersion(), night: this.night, busy: this.state.busy})
            } else { Text('该记录已作废。').fontSize(14).fontColor(this.muted()).width('100%') }
          } else { serviceSecondary({label: '重新读取记录', click: () => this.controller.loadCorrectionConflict(), night: this.night, busy: this.state.busy}) }
        }.width('100%').alignItems(HorizontalAlign.Start).padding(14).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 18})
      }
      if (!this.state.alreadyRecorded) {
      this.ScoreInput({label: '地面', hint: '无垃圾、无积水', value: this.state.floorScore, change: (v: string)  => { this.state.floorScore = v; this.controller.editDraft(); }, id: 'hygiene-floor'})
      this.ScoreInput({label: '桌面', hint: '物品摆放整齐', value: this.state.deskScore, change: (v: string)  => { this.state.deskScore = v; this.controller.editDraft(); }, id: 'hygiene-desk'})
      this.ScoreInput({label: '床铺', hint: '床铺整洁、无杂物', value: this.state.bedScore, change: (v: string)  => { this.state.bedScore = v; this.controller.editDraft(); }, id: 'hygiene-bed'})
      this.ScoreInput({label: '卫生间', hint: '洁净无异味', value: this.state.bathroomScore, change: (v: string)  => { this.state.bathroomScore = v; this.controller.editDraft(); }, id: 'hygiene-bathroom'})
      Blank().height(22)
      serviceField({label: '问题与扣分说明', value: this.state.issueNote, change: (v: string)  => { this.state.issueNote = v; this.controller.editDraft(); }, placeholder: '低于60分时必须说明问题', multiline: true, night: this.night, page: this.page})
      serviceField({label: '整改建议', value: this.state.remediationNote, change: (v: string)  => { this.state.remediationNote = v; this.controller.editDraft(); }, placeholder: '写明可执行的改进建议', multiline: true, night: this.night, page: this.page})
      if (this.state.correctionId) {
        serviceField({label: '更正原因 *', value: this.state.correctionReason, change: (v: string) => { this.state.correctionReason = v; this.controller.editDraft(); }, placeholder: '说明更正依据', multiline: true, night: this.night, page: this.page})
        if (this.state.attachedImages.length) {
          serviceLabel({text: '原现场图片 · ' + this.state.attachedImages.length + ' 张', night: this.night})
          ForEach(this.state.attachedImages, (asset: FileAsset) => { SecureImage({url: asset.url, thumbnailUrl:asset.thumbnailUrl, night: this.night}) }, (asset: FileAsset) => asset.id)
        }
      }
      this.Uploads('INSPECTION')
      servicePrimary({label: this.state.correctionId ? '保存更正' : '保存评分', click: () => this.controller.submitHygiene(), night: this.night, page: this.page, busy: this.state.busy, uploading: this.state.uploading,
        enabled: !this.state.draftBlocked && !this.state.correctionConflict && (!!this.state.correctionId || this.state.targetChecked)})
      if (!this.state.correctionId && this.state.inspectionRound === 'ROUTINE') {
        serviceSecondary({label: '保存并查下一间', click: () => this.controller.submitHygiene(true), night: this.night,
          busy: this.state.busy || this.state.uploading || this.state.draftBlocked || !this.state.targetChecked || this.state.alreadyRecorded})
      }
      if (this.state.inspectionRoomId && !this.state.targetChecked && !this.state.checking && !this.state.correctionId) {
        serviceSecondary({label: '重新查找已有评分', click: () => this.controller.loadComparison(), night: this.night, busy: this.state.busy})
      }
      }
      if (this.state.correctionId || this.state.inspectionRelated) {
        Blank().height(10)
        serviceSecondary({label: '放弃当前草稿', click: () => this.controller.abandon(), night: this.night, busy: this.state.busy})
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private HygieneList() {
    Column({space: 0}) {
      if (this.manager()) { servicePrimary({label: '＋ 录入卫生评分', click: () => this.host.navigate('hygiene-create'), night: this.night, page: this.page, busy: this.state.busy, uploading: this.state.uploading}); Blank().height(18) }
      if (this.manager()) {
        Row({space: 8}) {
          TextInput({text: this.state.listKeyword, placeholder: '搜索房号'}).layoutWeight(1).height(48).fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: palette(this.night).outline}).borderRadius(4)
            .onChange((v:string) => this.state.listKeyword = v).onSubmit(() => { this.state.hygienePage = 1; this.controller.loadHygiene(); })
          Select([{value:'全部'},{value:'待整改'},{value:'合格'},{value:'良好'},{value:'优秀'}])
            .selected(['','RECTIFY','PASS','GOOD','EXCELLENT'].indexOf(this.state.listGrade)).value(this.state.listGrade ? gradeLabel(this.state.listGrade) : '全部')
            .height(48).fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: palette(this.night).outline}).borderRadius(4).optionBgColor(this.surface()).optionFontColor(this.ink())
            .onSelect((i: number) => { this.state.listGrade = ['','RECTIFY','PASS','GOOD','EXCELLENT'][i]; this.state.hygienePage = 1; this.controller.loadHygiene(); })
          Button('查询').height(48).borderRadius(4).fontColor(this.ink()).backgroundColor(this.soft()).onClick(() => { this.state.hygienePage = 1; this.controller.loadHygiene(); })
        }.width('100%')
      }
      Text(this.manager() ? '授权楼栋内的巡检记录' : '仅展示本人有效入住期间的宿舍检查记录').fontSize(14).fontColor(this.muted()).lineHeight(22).width('100%').margin({bottom: 18})
      if (!this.state.hygiene.length && !this.state.loading) { serviceEmpty({message: '暂时没有可查看的卫生记录。', night: this.night}) }
      ForEach(this.state.hygiene, (item: Hygiene) => {
        Column({space: 12}) {
          Row() {
            Column({space: 6}) { Text(item.buildingName + ' ' + item.roomNo).fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink());
              Text(item.inspectionDate + ' · ' + (item.round === 'ROUTINE' ? '日常巡检' : '整改复查')).fontSize(14).fontColor(this.muted()) }
              .layoutWeight(1).alignItems(HorizontalAlign.Start)
            Column({space: 2}) { Text(String(item.totalScore)).fontSize(28).fontWeight(FontWeight.Medium).fontColor(this.ink()); Text(gradeLabel(item.grade)).fontSize(14).fontColor(this.muted()) }
          }.width('100%')
          Text('地面 ' + item.floorScore + '  ·  桌面 ' + item.deskScore + '  ·  床铺 ' + item.bedScore + '  ·  卫生间 ' + item.bathroomScore)
            .fontSize(14).fontColor(this.ink()).lineHeight(23).width('100%')
          if (item.issueNote) { Text('检查说明：' + item.issueNote).fontSize(14).fontColor(this.ink()).lineHeight(24).width('100%') }
          if (item.remediationNote) { Text('整改建议：' + item.remediationNote).fontSize(14).fontColor(this.muted()).lineHeight(24).width('100%') }
          Text('检查人：' + item.inspectorName).fontSize(14).fontColor(this.muted()).width('100%')
          ForEach(item.images, (asset: FileAsset) => { SecureImage({url: asset.url, thumbnailUrl:asset.thumbnailUrl, night: this.night}) }, (asset: FileAsset) => asset.id)
          if (this.manager()) {
            serviceSecondary({label: '更正评分', click: () => this.controller.correct(item), night: this.night, busy: this.state.busy})
            serviceSecondary({label: '查看更正记录', click: () => this.controller.history(item), night: this.night, busy: this.state.busy})
            serviceSecondary({label: '录入整改复查', click: () => this.controller.recheck(item), night: this.night, busy: this.state.busy})
            serviceSecondary({label: '安排整改任务', click: () => this.onRemediate(item), night: this.night, busy: this.state.busy})
          }
        }.padding(18).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(6).margin({bottom: 14})
      }, (item: Hygiene) => item.id + ':' + item.version)
      Row({space: 12}) {
        Button('上一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.hygienePage > 1 && !this.state.loading).onClick(() => { this.state.hygienePage--; this.host.scrollTop?.(); this.controller.loadHygiene(); })
        Text(String(this.state.hygienePage)).fontSize(14).fontColor(this.muted())
        Button('下一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.hygienePage * 10 < this.state.hygieneTotal && !this.state.loading).onClick(() => { this.state.hygienePage++; this.host.scrollTop?.(); this.controller.loadHygiene(); })
      }.width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  build() {
    Column() {
      if (this.page === 'hygiene-create') { this.HygieneCreate() } else { this.HygieneList() }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
