import { DraftNotice } from "../DraftNotice";
import { serviceLabel, serviceField, servicePrimary, serviceSecondary, serviceEmpty, serviceDetail } from '../../components/ServiceParts';
import { Student, Building, Visitor } from '../../models/ApiModels';
import { dateText } from '../../services/Labels';
import { palette } from '../../components/Palette';
import { WorkspaceHost, emptyWorkspaceHost } from '../WorkspaceHost';
import { VisitorController, VisitorState } from './VisitorController';
interface VisitorArgs { visitor: Visitor; }

@Component
export struct VisitorWorkspace {
  @ObjectLink state: VisitorState;
  @Prop page: string = 'home';
  @Prop night: boolean = false;
  host: WorkspaceHost = emptyWorkspaceHost();
  controller: VisitorController = new VisitorController(this.host);
  private surface(): string { return palette(this.night).surface; }

  private ink(): string { return palette(this.night).ink; }

  private muted(): string { return palette(this.night).muted; }

  private line(): string { return palette(this.night).line; }

  private soft(): string { return palette(this.night).soft; }

  private accent(): string { return palette(this.night).accent; }

  private onPrimary(): string { return palette(this.night).onPrimary; }

  @Builder
  private VisitorCreate() {
    Column({space: 0}) {
      DraftNotice({restored: this.state.draftRestored, blocked: this.state.draftBlocked, night: this.night, onDiscard: () => this.controller.discardDraft()})
      serviceField({label: '访客姓名 *', value: this.state.visitorName, change: (v: string)  => { this.state.visitorName = v; this.controller.saveDraft(); }, placeholder: '请输入姓名', night: this.night, page: this.page})
      serviceField({label: '联系电话 *', value: this.state.visitorPhone, change: (v: string) => {
        this.state.visitorPhone = v; this.state.phoneError = ''; this.controller.saveDraft();
      }, invalidPhonePaste: () => { this.state.phoneError = '请输入11位手机号'; }, error: this.state.phoneError,
        placeholder: '请输入11位手机号', inputType: InputType.PhoneNumber, night: this.night, page: this.page})
      serviceLabel({text: '被访学生 / 宿舍 *', night: this.night})
      Row({space: 8}) {
        TextInput({text: this.state.targetSearch, placeholder: '按学号或姓名查找'}).id('visitor-target-search').height(48).layoutWeight(1)
          .fontSize(14).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.state.targetSearch = v).onSubmit(() => this.controller.loadStudents())
        serviceSecondary({label: '查找', click: () => this.controller.loadStudents(), night: this.night, busy: this.state.busy})
      }.width('100%').margin({top: 8, bottom: 4})
      if (this.state.students.length) {
        Select(this.state.students.map((s: Student): SelectOption => { return {value: (s.buildingName || '') + ' ' + (s.roomNo || '') + ' · ' + s.name}; }))
          .id('visitor-target').selected(this.state.students.findIndex((s: Student): boolean => s.id === this.state.visitedStudentId)).value(this.controller.studentChoice())
          .onSelect((index: number) => { this.state.visitedStudentId = this.state.students[index].id; this.controller.saveDraft(); }).width('100%').height(50)
          .fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4).margin({top: 8, bottom: 20})
      } else { serviceEmpty({message: '暂无可登记的在住学生，请先核对入住信息。', night: this.night}) }
      serviceField({label: '来访事由 *', value: this.state.visitorPurpose, change: (v: string)  => { this.state.visitorPurpose = v; this.controller.saveDraft(); }, placeholder: '例如：家属探访', night: this.night, page: this.page})
      serviceField({label: '备注', value: this.state.visitorRemark, change: (v: string)  => { this.state.visitorRemark = v; this.controller.saveDraft(); }, placeholder: '可补充说明', multiline: true, night: this.night, page: this.page})
      Text('到访时间以保存时的服务端时间记录。').fontSize(14).fontColor(this.muted()).margin({bottom: 20})
      servicePrimary({label: '保存来访登记', click: () => this.controller.submitVisitor(), night: this.night, page: this.page, busy: this.state.busy, uploading: false})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Visitors() {
    Column({space: 0}) {
      servicePrimary({label: '登记访客', click: () => this.host.navigate('visitor-create'), night: this.night, page: this.page, busy: this.state.busy, uploading: false})
      Row({space: 8}) {
        TextInput({text: this.state.visitorSearch, placeholder: '访客姓名、电话或房间'}).id('visitor-search').height(48).layoutWeight(1)
          .fontSize(16).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.state.visitorSearch = v).onSubmit(() => { this.state.visitorPage = 1; this.controller.loadVisitors(); })
        serviceSecondary({label: '查询', click: () => { this.state.visitorPage = 1; this.controller.loadVisitors(); }, night: this.night, busy: this.state.busy})
      }.width('100%').margin({top: 18, bottom: 12})
      if (this.state.buildings.length > 1) {
        Select([{value: '全部负责楼栋'}, ...this.state.buildings.map((building: Building): SelectOption => { return {value: building.name}; })])
          .id('visitor-building').selected(this.state.buildings.findIndex((building: Building): boolean => building.id === this.state.buildingId) + 1)
          .value(this.controller.buildingChoice()).onSelect((index: number) => this.controller.selectBuilding(index ? this.state.buildings[index - 1].id : ''))
          .height(48).width('100%').backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).margin({bottom: 8})
      } else if (this.state.buildings.length === 1) {
        Text('负责楼栋 · ' + this.state.buildings[0].name).fontSize(14).fontColor(this.muted()).width('100%').margin({bottom: 8})
      }
      Select([{value: '全部来访'}, {value: '当前在访'}, {value: '已离访'}]).selected(['', 'VISITING', 'LEFT'].indexOf(this.state.visitorFilter)).value(this.state.visitorFilter === 'VISITING' ? '当前在访' : this.state.visitorFilter === 'LEFT' ? '已离访' : '全部来访')
        .onSelect((index: number) => { this.state.visitorFilter = ['', 'VISITING', 'LEFT'][index]; this.state.visitorPage = 1; this.controller.loadVisitors(); })
        .height(48).width('100%').backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).margin({bottom: 18})
      Text('共 ' + this.state.visitorTotal + ' 条记录').fontSize(14).fontColor(this.muted()).width('100%').margin({bottom: 12})
      if (!this.state.visitors.length && !this.state.loading) { serviceEmpty({message: '暂无符合条件的访客记录。', night: this.night}) }
      ForEach(this.state.visitors, (visitor: Visitor) => {
        Column({space: 8}) {
          Row() {
            Text(visitor.visitorName).fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink()).layoutWeight(1)
            this.Status({visitor: visitor})
          }.width('100%')
          Text(visitor.buildingName + ' ' + visitor.roomNo + ' · ' + visitor.studentName).fontSize(14).fontColor(this.ink()).width('100%')
          Text(visitor.purpose).fontSize(14).fontColor(this.muted()).width('100%')
          Text('到访：' + dateText(visitor.arrivedAt)).fontSize(14).fontColor(this.muted()).width('100%')
          if (visitor.leftAt) { Text('离访：' + dateText(visitor.leftAt)).fontSize(14).fontColor(this.muted()).width('100%') }
          this.Actions({visitor: visitor})
          Button('来访详情').id('visitor-detail-' + visitor.id).height(48).fontSize(14).fontColor(this.accent()).backgroundColor(Color.Transparent)
            .onClick(() => this.controller.open(visitor))
        }.padding({top: 18, bottom: 18}).width('100%').border({width: {bottom: 1}, color: this.line()})
      }, (visitor: Visitor) => visitor.id + ':' + visitor.version + ':' + String(visitor.overdue))
      Row({space: 12}) {
        Button('上一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.visitorPage > 1 && !this.state.loading).onClick(() => { this.state.visitorPage--; this.controller.loadVisitors(); })
        Text(String(this.state.visitorPage)).fontColor(this.muted()).fontSize(14)
        Button('下一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.visitorPage * 10 < this.state.visitorTotal && !this.state.loading).onClick(() => { this.state.visitorPage++; this.controller.loadVisitors(); })
      }.width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private Status($$: VisitorArgs) {
    Text($$.visitor.status === 'VISITING' ? ($$.visitor.overdue ? '超24小时未离访' : '在访') : '已离访').fontSize(14)
      .fontColor($$.visitor.status === 'VISITING' ? ($$.visitor.overdue ? palette(this.night).danger : this.accent()) : this.muted())
  }

  @Builder
  private Actions($$: VisitorArgs) {
    Row({space: 8}) {
      Button($$.visitor.phone + '  查看').fontSize(14).fontColor(this.ink()).height(48).backgroundColor(this.soft()).borderRadius(4).layoutWeight(1).onClick(() => this.controller.contact($$.visitor))
      if ($$.visitor.status === 'VISITING') {
        Button('登记离访').id('visitor-leave-' + $$.visitor.id).fontSize(14).height(48).fontColor(this.onPrimary()).backgroundColor(this.ink()).borderRadius(4)
          .enabled(!this.state.busy && !this.state.loading).onClick(() => this.controller.leave($$.visitor))
      }
    }.width('100%')
  }

  @Builder
  private VisitorDetail($$: VisitorArgs) {
    Column({space: 12}) {
      Row() {
        Text($$.visitor.visitorName).fontSize(24).fontWeight(FontWeight.Medium).fontColor(this.ink()).layoutWeight(1)
        this.Status({visitor: $$.visitor})
      }.width('100%')
      serviceDetail({label: '被访学生', value: $$.visitor.studentName, night: this.night})
      serviceDetail({label: '宿舍', value: $$.visitor.buildingName + ' ' + $$.visitor.roomNo, night: this.night})
      serviceDetail({label: '来访事由', value: $$.visitor.purpose, night: this.night})
      serviceDetail({label: '到访时间', value: dateText($$.visitor.arrivedAt), night: this.night})
      if ($$.visitor.leftAt) { serviceDetail({label: '离访时间', value: dateText($$.visitor.leftAt), night: this.night}) }
      if ($$.visitor.remark) { serviceDetail({label: '备注', value: $$.visitor.remark, night: this.night}) }
      this.Actions({visitor: $$.visitor})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  build() {
    Column() {
      if (this.page === 'visitor-create') { this.VisitorCreate() }
      else if (this.page === 'visitor-detail') {
        if (this.state.selected) { this.VisitorDetail({visitor: this.state.selected}) }
        else if (!this.state.loading) { serviceEmpty({message: '当前访客记录不可用。', night: this.night}) }
      } else { this.Visitors() }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
