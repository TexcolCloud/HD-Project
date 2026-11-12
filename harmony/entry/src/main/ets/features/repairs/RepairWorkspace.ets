import { TaskTransfer } from "../dispatch/TaskTransfer";
import { DraftNotice } from "../DraftNotice";
import { serviceLabel, serviceField, servicePrimary, serviceSecondary, serviceSection, serviceEmpty, serviceDetail, repairStatusColor } from '../../components/ServiceParts';
import { User, Room, Repair, RepairEvent, FileAsset } from '../../models/ApiModels';
import { statusLabel, categoryLabel, actionLabel, dateText } from '../../services/Labels';
import { palette } from '../../components/Palette';
import { SecureImage } from '../../components/SecureImage';
import { WorkspaceHost, emptyWorkspaceHost } from '../WorkspaceHost';
import { PhotoDraftController } from '../photos/PhotoDraftController';
import { PhotoAttachments } from '../photos/PhotoAttachments';
import { DispatchController } from '../dispatch/DispatchController';
import { VisitWindowFields, VisitWindowDraft } from '../visits/VisitWindowFields';
import { VisitArrangement } from '../visits/VisitArrangement';
import { ManagerAssist } from '../assist/ManagerAssist';
import { StudentPlaceCheck } from '../mobility/StudentPlaceCheck';
import { RepairReminders } from '../mobility/RepairReminders';
import { RepairController, RepairState } from './RepairController';
interface RepairCardArgs { order: Repair; }

@Component
export struct RepairWorkspace {
  @ObjectLink state: RepairState;
  @Prop page: string = 'home';
  @Prop night: boolean = false;
  user: User | null = null;
  host: WorkspaceHost = emptyWorkspaceHost();
  photoDrafts: PhotoDraftController = new PhotoDraftController((error: Error): void => this.host.fail(error));
  controller: RepairController = new RepairController(this.host, this.photoDrafts);
  dispatch: DispatchController = new DispatchController();
  @Prop room: Room | null = null;
  private manager(): boolean { return this.user?.role === 'MANAGER'; }
  private surface(): string { return palette(this.night).surface; }

  private ink(): string { return palette(this.night).ink; }

  private muted(): string { return palette(this.night).muted; }

  private line(): string { return palette(this.night).line; }

  private soft(): string { return palette(this.night).soft; }

  private accent(): string { return palette(this.night).accent; }

  private onPrimary(): string { return palette(this.night).onPrimary; }

  @Builder
  private RepairCard($$: RepairCardArgs) {
    Column({space: 10}) {
      Row() {
        Text($$.order.orderNo).fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Text(statusLabel($$.order.status)).fontSize(12).fontColor(repairStatusColor($$.order.status, this.night)).padding({left: 8, right: 8, top: 3, bottom: 3})
          .backgroundColor(this.soft()).borderRadius(3)
      }.width('100%')
      Text($$.order.title).fontSize(17).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%')
      Text($$.order.buildingName + ' ' + $$.order.roomNo + ' · ' + categoryLabel($$.order.category)).fontSize(13).fontColor(this.muted()).width('100%')
      Row() {
        Text(dateText($$.order.submittedAt)).fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Text('查看进度 →').fontSize(13).fontColor(this.accent())
      }.width('100%')
    }.padding(18).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.surface())
      .border({width: 1, color: this.line()}).borderRadius(6).margin({bottom: 12}).onClick(() => this.host.openRepair($$.order))
  }

  @Builder
  private Uploads(usage: string) {
    PhotoAttachments({usage: usage, controller: this.photoDrafts, night: this.night, disabled: this.state.busy})
  }

  @Builder
  private RepairCreate() {
    Column({space: 0}) {
      DraftNotice({restored: this.state.draftRestored, blocked: this.state.draftBlocked, night: this.night, onDiscard: () => this.controller.discardDraft()})
      Text('报修房间依据当前入住信息关联，请描述现象和发生位置。').fontSize(14).fontColor(this.muted()).lineHeight(24)
        .width('100%').padding(14).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 22})
      serviceDetail({label: '报修宿舍', value: this.room ? this.room.buildingName + ' ' + this.room.roomNo : '尚未关联宿舍'}, this.night)
      StudentPlaceCheck({room: this.room, night: this.night, onUnauthorized: (error: Error) => this.host.fail(error)})
      Blank().height(20)
      serviceField({label: '报修标题 *', value: this.state.repairTitle, change: (v: string)  => { this.state.repairTitle = v; this.controller.saveDraft(); }, placeholder: '例如：洗手池排水缓慢'}, this.night, this.page)
      serviceField({label: '故障描述 *', value: this.state.repairDescription, change: (v: string)  => { this.state.repairDescription = v; this.controller.saveDraft(); }, placeholder: '说明发生位置、现象和持续时间', multiline: true}, this.night, this.page)
      serviceLabel('故障分类', this.night)
      Select([{value: '其他'}, {value: '水电'}, {value: '门窗家具'}, {value: '网络'}])
        .selected(['OTHER', 'UTILITIES', 'FURNITURE', 'NETWORK'].indexOf(this.state.repairCategory)).value(categoryLabel(this.state.repairCategory)).width('100%').height(50)
        .fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4)
        .onSelect((index: number) => { this.state.repairCategory = ['OTHER', 'UTILITIES', 'FURNITURE', 'NETWORK'][index]; this.controller.saveDraft(); }).margin({top: 8, bottom: 20})
      serviceField({label: '联系人 *', value: this.state.contactName, change: (v: string)  => { this.state.contactName = v; this.controller.saveDraft(); }, placeholder: '方便宿管联系的姓名'}, this.night, this.page)
      serviceField({label: '联系电话 *', value: this.state.contactPhone, change: (v: string)  => { this.state.contactPhone = v; this.controller.saveDraft(); }, placeholder: '请输入方便接听的电话'}, this.night, this.page)
      serviceField({label: '方便联系时段', value: this.state.contactTime, change: (v: string)  => { this.state.contactTime = v; this.controller.saveDraft(); }, placeholder: '例如：工作日18:00后'}, this.night, this.page)
      VisitWindowFields({draft: this.state.visitDraft, night: this.night, onChange: (draft: VisitWindowDraft) => { this.state.visitDraft = draft; this.controller.saveDraft(); }})
      serviceLabel('紧急程度', this.night)
      Select([{value: '一般'}, {value: '较急'}]).selected(this.state.repairPriority === 'URGENT' ? 1 : 0).value(this.state.repairPriority === 'URGENT' ? '较急' : '一般').width('100%').height(50)
        .fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4)
        .onSelect((index: number) => { this.state.repairPriority = index === 1 ? 'URGENT' : 'NORMAL'; this.controller.saveDraft(); }).margin({top: 8, bottom: 20})
      if (this.state.repairPriority === 'URGENT') { serviceField({label: '较急原因 *', value: this.state.repairUrgentReason, change: (v: string)  => { this.state.repairUrgentReason = v; this.controller.saveDraft(); }, placeholder: '说明需要优先处理的情况', multiline: true}, this.night, this.page) }
      this.Uploads('REPORT')
      Column({space: 12}) {
        Text('报修填写助手').fontSize(17).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%')
        Text('根据本次描述和最多3张所选图片整理建议，核对后再采纳。').fontSize(13).fontColor(this.muted()).lineHeight(22).width('100%')
        Row({space: 8}) {
          Checkbox({name: 'analysis-consent'}).select(this.state.aiConsent).selectedColor(this.accent()).onChange((v: boolean) => this.state.aiConsent = v)
          Text('同意将本次故障描述与所选图片发送给AI服务分析').fontSize(13).fontColor(this.ink()).layoutWeight(1).lineHeight(22)
        }.width('100%')
        Button(this.state.aiBusy ? '正在整理…' : 'AI 帮我完善').id('repair-ai').height(48).width('100%').fontSize(15)
          .backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).enabled(!this.state.aiBusy && !this.state.uploading && !this.state.busy).onClick(() => this.controller.suggest())
        if (this.state.aiError) { Text(this.state.aiError).fontSize(13).fontColor(this.night ? '#F1A99C' : '#A13E37').lineHeight(22).width('100%') }
        if (this.state.ai) {
          Text('AI建议，请核对').fontSize(14).fontColor(this.accent()).width('100%')
          Text(this.state.ai.title + '\n' + categoryLabel(this.state.ai.category) + '\n' + this.state.ai.summary).fontSize(14).fontColor(this.ink()).lineHeight(24).width('100%')
          if (this.state.ai.uncertainty) { Text(this.state.ai.uncertainty).fontSize(13).fontColor(this.muted()).width('100%') }
          ForEach(this.state.ai.questions, (question: string) => { Text('待补充：' + question).fontSize(13).fontColor(this.muted()).width('100%') })
          Button(this.state.aiAdopted ? '已采纳，可继续修改' : '采纳建议').height(48).width('100%').fontSize(15).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
            .onClick(() => { if (this.state.ai) { this.state.repairTitle = this.state.ai.title; this.state.repairDescription = this.state.ai.summary; this.state.repairCategory = this.state.ai.category; this.state.aiAdopted = true; } })
        }
      }.padding(16).width('100%').alignItems(HorizontalAlign.Start).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 24})
      servicePrimary('提交报修', () => this.controller.submitRepair(), this.night, this.page, this.state.busy, this.state.uploading, !!this.room)
      Text('确认内容后提交。AI暂不可用时，仍可手动完成报修。').fontSize(12).fontColor(this.muted()).lineHeight(21).margin({top: 12})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Repairs() {
    Column({space: 0}) {
      Row({space: 8}) {
        TextInput({text: this.state.repairSearch, placeholder: '搜索工单或宿舍'}).id('repair-search').layoutWeight(1).height(48)
          .fontSize(15).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.state.repairSearch = v).onSubmit(() => { this.state.repairPage = 1; this.controller.loadRepairs(); })
        serviceSecondary('查询', () => { this.state.repairPage = 1; this.controller.loadRepairs(); }, this.night, this.state.busy)
      }.width('100%').margin({bottom: 12})
      Select([{value: '全部状态'}, {value: '待受理'}, {value: '已接单'}, {value: '处理中'}, {value: '待确认'}, {value: '已完成'}, {value: '已取消'}, {value: '已驳回'}])
        .selected(['', 'SUBMITTED', 'ACCEPTED', 'PROCESSING', 'PENDING_CONFIRMATION', 'COMPLETED', 'CANCELLED', 'REJECTED'].indexOf(this.state.repairFilter))
        .value(this.state.repairFilter ? statusLabel(this.state.repairFilter) : '全部状态').height(48).width('100%').backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
        .onSelect((index: number) => {
          this.state.repairFilter = ['', 'SUBMITTED', 'ACCEPTED', 'PROCESSING', 'PENDING_CONFIRMATION', 'COMPLETED', 'CANCELLED', 'REJECTED'][index];
          this.state.repairPage = 1; this.controller.loadRepairs();
        }).margin({bottom: 18})
      if (!this.manager()) { servicePrimary('＋ 提交报修', () => this.host.navigate('repair-create'), this.night, this.page, this.state.busy, this.state.uploading, !!this.room); Blank().height(18) }
      Text('共 ' + this.state.repairTotal + ' 条记录').fontSize(12).fontColor(this.muted()).width('100%').margin({bottom: 12})
      if (!this.state.repairs.length && !this.state.listLoading) { serviceEmpty('暂无符合条件的工单，请调整筛选条件。', this.night) }
      ForEach(this.state.repairs, (item: Repair) => { this.RepairCard({order: item}) }, (item: Repair) => item.id + ':' + item.version)
      Row({space: 12}) {
        Button('上一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.repairPage > 1 && !this.state.listLoading).onClick(() => { this.state.repairPage--; this.controller.loadRepairs(); })
        Text(String(this.state.repairPage)).fontSize(14).fontColor(this.muted())
        Button('下一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.repairPage * 10 < this.state.repairTotal && !this.state.listLoading).onClick(() => { this.state.repairPage++; this.controller.loadRepairs(); })
      }.width('100%').margin({top: 12})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private RepairDetail() {
    if (this.state.selected) {
    Column({space: 0}) {
      Row() {
        Text(this.state.selected.orderNo).fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Text(statusLabel(this.state.selected.status)).fontSize(13).fontColor(repairStatusColor(this.state.selected.status, this.night)).padding(6).backgroundColor(this.soft()).borderRadius(3)
      }.width('100%')
      Text(this.state.selected.title).fontFamily('DormSerif').fontSize(27).fontWeight(FontWeight.Medium).fontColor(this.ink()).lineHeight(38)
        .width('100%').margin({top: 16, bottom: 20})
      serviceDetail({label: '报修宿舍', value: this.state.selected.buildingName + ' ' + this.state.selected.roomNo}, this.night)
      serviceDetail({label: '报修学生', value: this.state.selected.reporterName}, this.night)
      serviceDetail({label: '故障分类', value: categoryLabel(this.state.selected.category)}, this.night)
      serviceDetail({label: '紧急程度', value: this.state.selected.priority === 'URGENT' ? '较急' : '一般'}, this.night)
      if (this.state.selected.urgentReason) { serviceDetail({label: '较急原因', value: this.state.selected.urgentReason}, this.night) }
      serviceDetail({label: '联系电话', value: this.state.selected.contactPhone}, this.night)
      serviceDetail({label: '联系时段', value: this.state.selected.contactTimeSlot}, this.night)
      serviceDetail({label: '办理宿管', value: this.state.selected.handlerName || '等待接单'}, this.night)
      serviceDetail({label: '提交时间', value: dateText(this.state.selected.submittedAt)}, this.night)
      VisitArrangement({repair: this.state.selected, manager: this.manager(), night: this.night,
        onSaved: (repair: Repair) => { this.host.openRepair(repair); this.dispatch.refresh(false); }, onUnauthorized: (error: Error) => this.host.fail(error)})
      if (this.manager()) { TaskTransfer({repair: this.state.selected!, userId: this.user?.id || '', night: this.night, onUpdated: (repair: Repair) => this.host.openRepair(repair), onError: (error: Error) => this.host.fail(error)}) }
      RepairReminders({repair: this.state.selected, night: this.night})
      if (this.manager()) { ManagerAssist({orderId: this.state.selected.id, night: this.night, onOpenRepair: (repair: Repair) => this.host.openRepair(repair), onUnauthorized: (error: Error) => this.host.fail(error)}) }
      serviceSection('问题描述', this.night)
      Text(this.state.selected.description).fontSize(16).fontColor(this.ink()).lineHeight(27).width('100%').copyOption(CopyOptions.InApp)
      if (this.state.selected.reportImages.length) {
        serviceSection('故障图片', this.night)
        ForEach(this.state.selected.reportImages, (asset: FileAsset) => { SecureImage({url: asset.url, night: this.night}).margin({bottom: 10}) }, (asset: FileAsset) => asset.id)
      }
      if (this.state.selected.latestResult) {
        serviceSection('处理结果', this.night)
        Text(this.state.selected.latestResult).fontSize(16).fontColor(this.ink()).lineHeight(27).width('100%')
      }
      if (this.state.selected.resultImages.length) {
        ForEach(this.state.selected.resultImages, (asset: FileAsset) => { SecureImage({url: asset.url, night: this.night}).margin({bottom: 10, top: 10}) }, (asset: FileAsset) => asset.id)
      }
      serviceSection('处理进度', this.night)
      ForEach(this.state.events, (event: RepairEvent) => {
        Row({space: 12}) {
          Text('●').fontSize(9).fontColor(this.accent()).margin({top: 5})
          Column({space: 6}) {
            Text(statusLabel(event.toStatus) + ' · ' + event.operatorName).fontSize(15).fontColor(this.ink()).width('100%')
            if (event.note) { Text(event.note).fontSize(14).fontColor(this.ink()).lineHeight(24).width('100%') }
            Text(dateText(event.createdAt)).fontSize(12).fontColor(this.muted()).width('100%')
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        }.width('100%').alignItems(VerticalAlign.Top).padding({top: 8, bottom: 18})
      }, (event: RepairEvent) => event.id)
      if (!this.state.events.length && !this.state.detailLoading) { serviceEmpty('暂无进度记录', this.night) }
      ForEach(this.state.selected.allowedActions, (action: string) => {
        Button(action === 'rework' && this.manager() ? '撤回结果并返修' : actionLabel(action)).id('repair-action-' + action)
          .width('100%').height(50).fontSize(16).fontColor(['reject', 'cancel'].includes(action) ? this.accent() : this.onPrimary())
          .backgroundColor(['reject', 'cancel'].includes(action) ? this.surface() : this.ink()).borderRadius(4)
          .enabled(!this.state.busy).onClick(() => this.controller.beginAction(action)).margin({top: 10})
      }, (action: string) => action)
      serviceSecondary('刷新进度', () => this.state.selected && this.host.openRepair(this.state.selected), this.night, this.state.busy)
    }.width('100%').alignItems(HorizontalAlign.Start)
    }
  }

  @Builder
  private ActionForm() {
    Column({space: 0}) {
      Text(this.state.selected?.title || '').fontSize(19).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%').margin({bottom: 20})
      serviceField({label: this.state.action === 'finish' ? '处理结果 *' : this.state.action === 'notes' ? '进度说明 *' : '具体原因 *', value: this.state.actionNote, change: (v: string) => this.state.actionNote = v, placeholder: '记录现场情况，便于后续跟进', multiline: true}, this.night, this.page)
      if (this.state.action === 'finish') {
        this.Uploads('RESULT')
        Text('提交后进入待确认，由报修学生确认是否解决。').fontSize(13).fontColor(this.muted()).lineHeight(23).margin({bottom: 20})
      }
      servicePrimary(this.state.action === 'finish' ? '提交完工结果' : '确认提交', () => this.controller.runAction(), this.night, this.page, this.state.busy, this.state.uploading)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  build() {
    Column() {
      if (this.page === 'repair-create') { this.RepairCreate() } else if (this.page === 'detail') { this.RepairDetail() } else if (this.page === 'action') { this.ActionForm() } else { this.Repairs() }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
