import { ServiceCenterController, ServiceCenterState } from './ServiceCenterController';
import { WorkspaceHost, emptyWorkspaceHost } from '../WorkspaceHost';
import { ServiceMessage, Remediation, Hygiene, Student, FileAsset } from '../../models/ApiModels';
import { palette } from '../../components/Palette';
import { FormInput } from '../../components/FormInput';
import { SecureImage } from '../../components/SecureImage';
import { Api, UploadedPhoto } from "../../services/Api";
import { dateText } from '../../services/Labels';
import { PhotoAttachments } from '../photos/PhotoAttachments';
import { PhotoDraftController } from '../photos/PhotoDraftController';

@Component
export struct ServiceCenterWorkspace {
  @ObjectLink state: ServiceCenterState;
  @Prop page: string = 'messages';
  @Prop night: boolean = false;
  @Prop manager: boolean = false;
  host: WorkspaceHost = emptyWorkspaceHost();
  controller: ServiceCenterController = new ServiceCenterController(this.host);
  photos: PhotoDraftController = new PhotoDraftController((error: Error): void => this.host.fail(error));

  private status(item: Remediation): string {
    return item.status === 'CLOSED' ? '已验收' : item.status === 'CANCELLED' ? '已取消' : item.status === 'SUBMITTED' ? '待复查' : item.overdue ? '已逾期 · 待整改' : '待整改';
  }
  private async feedback(): Promise<void> {
    const snapshot = this.photos.snapshot('REMEDIATION');
    if (snapshot.busy || snapshot.draft.failed.length) { this.host.setError('请等待图片上传完成，或移除上传失败的图片'); return; }
    const id = this.state.selected?.id, epoch = Api.epoch, generation = this.photos.draftVersion('REMEDIATION');
    const saved = await this.controller.feedback(snapshot.draft.uploaded.map((p: UploadedPhoto): string => p.asset.id));
    if (saved && this.state.selected?.id === id && epoch === Api.epoch && generation === this.photos.draftVersion('REMEDIATION')) this.photos.clear('REMEDIATION');
  }
  @Builder
  private Messages() {
    Row({space: 10}) {
      Checkbox().select(this.state.unreadOnly).selectedColor(palette(this.night).accent).onChange((value: boolean) => { this.state.unreadOnly = value; this.state.messagePage = 1; this.controller.loadMessages(); })
      Text('只看未读').fontSize(14).fontColor(palette(this.night).ink).layoutWeight(1)
      Button('刷新').height(44).backgroundColor(Color.Transparent).fontColor(palette(this.night).accent).onClick(() => this.controller.loadMessages())
    }.width('100%')
    Text('服务消息会保留在收件箱。应用关闭时的新消息，请重新打开后查看。').fontSize(13).lineHeight(22).fontColor(palette(this.night).muted).margin({bottom: 16})
    if (!this.state.messages.length && !this.state.loading) { Text('暂时没有服务消息').fontColor(palette(this.night).muted).padding(20) }
    ForEach(this.state.messages, (message: ServiceMessage) => {
      Column({space: 10}) {
        Text((message.readAt ? '' : '● ') + message.title).fontSize(17).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%')
        Text(message.body).fontSize(14).lineHeight(23).fontColor(palette(this.night).muted).width('100%')
        Text(dateText(message.createdAt)).fontSize(12).fontColor(palette(this.night).muted).width('100%')
        Row({space: 12}) {
          Button('查看事项').height(44).fontSize(14).backgroundColor(Color.Transparent).fontColor(palette(this.night).accent).enabled(!this.state.busy).onClick(() => this.controller.openMessage(message))
          if (!message.readAt) { Button('标为已读').height(44).fontSize(14).backgroundColor(Color.Transparent).fontColor(palette(this.night).muted).enabled(!this.state.busy).onClick(() => this.controller.readMessage(message)) }
        }
      }.width('100%').padding(16).backgroundColor(palette(this.night).surface).border({width: 1, color: palette(this.night).line}).borderRadius(4).margin({bottom: 12})
    }, (message: ServiceMessage) => message.id + ':' + message.version)
    Row({space: 12}) {
      Button('上一页').enabled(this.state.messagePage > 1 && !this.state.loading).onClick(() => { this.state.messagePage--; this.controller.loadMessages(); })
      Text(String(this.state.messagePage)).fontColor(palette(this.night).muted)
      Button('下一页').enabled(this.state.messagePage * 10 < this.state.messageTotal && !this.state.loading).onClick(() => { this.state.messagePage++; this.controller.loadMessages(); })
    }.height(48).width('100%').justifyContent(FlexAlign.SpaceBetween)
  }
  @Builder
  private List() {
    Button('刷新整改任务').height(44).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).onClick(() => this.controller.loadRemediations())
    if (!this.state.remediations.length && !this.state.loading) { Text('暂时没有整改任务').fontColor(palette(this.night).muted).padding(20) }
    ForEach(this.state.remediations, (item: Remediation) => {
      Column({space: 10}) {
        Text(item.buildingName + ' ' + item.roomNo + ' · ' + this.status(item)).fontSize(17).fontColor(palette(this.night).ink).width('100%')
        Text(item.issueNote).fontSize(14).lineHeight(24).fontColor(palette(this.night).muted).width('100%')
        Text('责任学生：' + item.responsibleName + '\n截止：' + dateText(item.dueAt)).fontSize(13).lineHeight(22).fontColor(palette(this.night).muted).width('100%')
        Button('查看整改 →').height(44).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).onClick(() => { this.controller.openRemediation(item.id); })
      }.width('100%').padding(16).backgroundColor(palette(this.night).surface).border({width: 1, color: palette(this.night).line}).borderRadius(4).margin({bottom: 12})
    }, (item: Remediation) => item.id + ':' + item.version)
    Row({space: 12}) {
      Button('上一页').enabled(this.state.remediationPage > 1 && !this.state.loading).onClick(() => { this.state.remediationPage--; this.controller.loadRemediations(); })
      Text(String(this.state.remediationPage)).fontColor(palette(this.night).muted)
      Button('下一页').enabled(this.state.remediationPage * 10 < this.state.remediationTotal && !this.state.loading).onClick(() => { this.state.remediationPage++; this.controller.loadRemediations(); })
    }.height(48).width('100%').justifyContent(FlexAlign.SpaceBetween)
  }
  @Builder
  private Create() {
    Text(this.page === 'remediation-assignment' ? '调整责任与期限' : (this.state.inspection?.buildingName || '') + ' ' + (this.state.inspection?.roomNo || '') + ' · 卫生整改').fontSize(18).fontColor(palette(this.night).ink).margin({bottom: 16})
    if (this.page !== 'remediation-assignment') { FormInput({label: '整改要求', value: this.state.issue, multiline: true, night: this.night, onChange: (value: string) => this.state.issue = value}) }
    Text('责任学生').fontSize(14).fontColor(palette(this.night).ink)
    Select(this.state.students.map((student: Student): SelectOption => ({value: student.name}))).value(this.state.students.find((student: Student): boolean => student.id === this.state.responsibleStudentId)?.name || '请选择')
      .width('100%').height(50).onSelect((index: number) => this.state.responsibleStudentId = this.state.students[index].id).margin({bottom: 16})
    FormInput({label: '截止日期（年-月-日）', value: this.state.dueDay, night: this.night, onChange: (value: string) => this.state.dueDay = value})
    FormInput({label: '截止时间（时:分）', value: this.state.dueClock, night: this.night, onChange: (value: string) => this.state.dueClock = value})
    if (this.page === 'remediation-assignment') { FormInput({label: '调整原因', value: this.state.assignmentReason, multiline: true, night: this.night, onChange: (value: string) => this.state.assignmentReason = value}) }
    Button(this.page === 'remediation-assignment' ? '保存责任与期限' : '发送整改任务').id('remediation-create-submit').height(48).width('100%').backgroundColor(palette(this.night).ink).fontColor(palette(this.night).onPrimary).borderRadius(4)
      .enabled(!this.state.busy && !!this.state.responsibleStudentId).onClick(() => { if (this.page === "remediation-assignment") this.controller.saveAssignment(); else this.controller.create(); })
  }
  @Builder
  private Detail() {
    if (this.state.selected) {
      Text(this.state.selected.buildingName + ' ' + this.state.selected.roomNo + ' · ' + this.status(this.state.selected)).fontSize(19).fontColor(palette(this.night).ink).width('100%').margin({bottom: 16})
      Text(this.state.selected.issueNote).fontSize(15).lineHeight(26).fontColor(palette(this.night).ink).width('100%')
      Text('责任学生：' + this.state.selected.responsibleName + '\n整改截止：' + dateText(this.state.selected.dueAt)).fontSize(14).lineHeight(24).fontColor(palette(this.night).muted).margin({top: 12, bottom: 20})
      if (this.state.selected.feedbackNote) { Text('整改反馈：' + this.state.selected.feedbackNote).fontSize(14).lineHeight(24).fontColor(palette(this.night).ink).width('100%').margin({bottom: 12}) }
      ForEach(this.state.selected.evidenceImages, (asset: FileAsset) => { SecureImage({url: asset.url, night: this.night}) }, (asset: FileAsset) => asset.id)
      if (this.state.selected.reviewNote) { Text('验收意见：' + this.state.selected.reviewNote).fontSize(14).lineHeight(24).fontColor(palette(this.night).ink).width('100%').margin({bottom: 16}) }
      if (!this.manager && this.state.selected.status === 'OPEN') {
        FormInput({label: '整改情况', value: this.state.feedback, multiline: true, night: this.night, onChange: (value: string) => this.state.feedback = value})
        PhotoAttachments({usage: 'REMEDIATION', controller: this.photos, night: this.night, disabled: this.state.busy})
        Button('提交整改反馈').id('remediation-feedback-submit').height(48).width('100%').backgroundColor(palette(this.night).ink).fontColor(palette(this.night).onPrimary).borderRadius(4).enabled(!this.state.busy).onClick(() => this.feedback())
      }
      if (this.manager && this.state.selected.status === 'SUBMITTED') {
        Text('先录入关联原检查的合格复查，再确认验收。').fontSize(13).lineHeight(22).fontColor(palette(this.night).muted).margin({bottom: 12})
        Button('刷新可关联复查').height(44).backgroundColor(Color.Transparent).fontColor(palette(this.night).accent).onClick(() => this.controller.loadRechecks())
        Select(this.state.rechecks.map((h: Hygiene): SelectOption => ({value: h.inspectionDate + ' · ' + h.totalScore + '分'}))).value(this.state.recheckId ? '已选择复查' : '选择合格复查')
          .width('100%').height(50).onSelect((index: number) => this.state.recheckId = this.state.rechecks[index].id).margin({bottom: 16})
        FormInput({label: '验收意见', value: this.state.reviewNote, multiline: true, night: this.night, onChange: (value: string) => this.state.reviewNote = value})
        Button('确认验收通过').id('remediation-review-approve').height(48).width('100%').backgroundColor(palette(this.night).ink).fontColor(palette(this.night).onPrimary).borderRadius(4).enabled(!this.state.busy && !!this.state.recheckId).onClick(() => this.controller.review(true))
        Button('退回继续整改').height(48).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).enabled(!this.state.busy).onClick(() => this.controller.review(false))
      }
      if (this.manager && ['OPEN', 'SUBMITTED'].includes(this.state.selected.status)) {
        Button('调整责任与期限').height(48).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).enabled(!this.state.busy).onClick(() => this.controller.editAssignment())
        FormInput({label: '取消原因（仅确需终止时填写）', value: this.state.cancelReason, multiline: true, night: this.night, onChange: (value: string) => this.state.cancelReason = value})
        Button('取消整改任务并留痕').height(48).fontColor(palette(this.night).danger).backgroundColor(Color.Transparent).enabled(!this.state.busy && !!this.state.cancelReason.trim()).onClick(() => this.controller.cancel())
      }
      Button('返回整改列表').height(48).fontColor(palette(this.night).muted).backgroundColor(Color.Transparent).onClick(() => this.host.navigate('remediations'))
    }
  }
  build() {
    Column({space: 0}) {
      if (this.state.loading) { LoadingProgress().width(24).height(24).color(palette(this.night).accent) }
      if (this.page === 'messages') { this.Messages() }
      else if (['remediation-create', 'remediation-assignment'].includes(this.page)) { this.Create() }
      else if (this.page === 'remediation-detail') { this.Detail() }
      else { this.List() }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
