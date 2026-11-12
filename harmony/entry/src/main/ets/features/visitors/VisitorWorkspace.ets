import { DraftNotice } from "../DraftNotice";
import { serviceLabel, serviceField, servicePrimary, serviceSecondary, serviceEmpty } from '../../components/ServiceParts';
import { User, Room, Student, Repair, Visitor } from '../../models/ApiModels';
import { dateText } from '../../services/Labels';
import { palette } from '../../components/Palette';
import { WorkspaceHost, emptyWorkspaceHost } from '../WorkspaceHost';
import { VisitorController, VisitorState } from './VisitorController';

@Component
export struct VisitorWorkspace {
  @ObjectLink state: VisitorState;
  @Prop page: string = 'home';
  @Prop night: boolean = false;
  user: User | null = null;
  host: WorkspaceHost = emptyWorkspaceHost();
  controller: VisitorController = new VisitorController(this.host);
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
  private VisitorCreate() {
    Column({space: 0}) {
      DraftNotice({restored: this.state.draftRestored, blocked: this.state.draftBlocked, night: this.night, onDiscard: () => this.controller.discardDraft()})
      Text('仅登记本次来访所需信息，离开时补记离访时间。').fontSize(14).fontColor(this.muted()).lineHeight(24)
        .width('100%').padding(14).backgroundColor(this.soft()).borderRadius(4).margin({bottom: 22})
      serviceField({label: '访客姓名 *', value: this.state.visitorName, change: (v: string)  => { this.state.visitorName = v; this.controller.saveDraft(); }, placeholder: '请输入姓名'}, this.night, this.page)
      serviceField({label: '联系电话 *', value: this.state.visitorPhone, change: (v: string)  => { this.state.visitorPhone = v; this.controller.saveDraft(); }, placeholder: '请输入联系电话'}, this.night, this.page)
      serviceLabel('被访学生 / 宿舍 *', this.night)
      Row({space: 8}) {
        TextInput({text: this.state.targetSearch, placeholder: '按学号或姓名查找'}).id('visitor-target-search').height(48).layoutWeight(1)
          .fontSize(14).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.state.targetSearch = v).onSubmit(() => this.controller.loadStudents())
        serviceSecondary('查找', () => this.controller.loadStudents(), this.night, this.state.busy)
      }.width('100%').margin({top: 8, bottom: 4})
      if (this.state.students.length) {
        Select(this.state.students.map((s: Student): SelectOption => { return {value: (s.buildingName || '') + ' ' + (s.roomNo || '') + ' · ' + s.name}; }))
          .id('visitor-target').selected(this.state.students.findIndex((s: Student): boolean => s.id === this.state.visitedStudentId)).value(this.controller.studentChoice())
          .onSelect((index: number) => { this.state.visitedStudentId = this.state.students[index].id; this.controller.saveDraft(); }).width('100%').height(50)
          .fontColor(this.ink()).backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(4).margin({top: 8, bottom: 20})
      } else { serviceEmpty('暂无可登记的在住学生，请先核对入住信息。', this.night) }
      serviceField({label: '来访事由 *', value: this.state.visitorPurpose, change: (v: string)  => { this.state.visitorPurpose = v; this.controller.saveDraft(); }, placeholder: '例如：家属探访'}, this.night, this.page)
      serviceField({label: '备注', value: this.state.visitorRemark, change: (v: string)  => { this.state.visitorRemark = v; this.controller.saveDraft(); }, placeholder: '可补充说明', multiline: true}, this.night, this.page)
      Text('到访时间以保存时的服务端时间记录。').fontSize(12).fontColor(this.muted()).margin({bottom: 20})
      servicePrimary('保存来访登记', () => this.controller.submitVisitor(), this.night, this.page, this.state.busy, false)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Visitors() {
    Column({space: 0}) {
      servicePrimary('＋ 登记访客', () => this.host.navigate('visitor-create'), this.night, this.page, this.state.busy, false)
      Row({space: 8}) {
        TextInput({text: this.state.visitorSearch, placeholder: '访客姓名、电话或房间'}).id('visitor-search').height(48).layoutWeight(1)
          .fontSize(15).fontColor(this.ink()).placeholderColor(this.muted()).backgroundColor(this.surface()).borderRadius(4)
          .onChange((v: string) => this.state.visitorSearch = v).onSubmit(() => { this.state.visitorPage = 1; this.controller.loadVisitors(); })
        serviceSecondary('查询', () => { this.state.visitorPage = 1; this.controller.loadVisitors(); }, this.night, this.state.busy)
      }.width('100%').margin({top: 18, bottom: 12})
      Select([{value: '全部来访'}, {value: '当前在访'}, {value: '已离访'}]).selected(['', 'VISITING', 'LEFT'].indexOf(this.state.visitorFilter)).value(this.state.visitorFilter === 'VISITING' ? '当前在访' : this.state.visitorFilter === 'LEFT' ? '已离访' : '全部来访')
        .onSelect((index: number) => { this.state.visitorFilter = ['', 'VISITING', 'LEFT'][index]; this.state.visitorPage = 1; this.controller.loadVisitors(); })
        .height(48).width('100%').backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).margin({bottom: 18})
      Text('共 ' + this.state.visitorTotal + ' 条记录').fontSize(12).fontColor(this.muted()).width('100%').margin({bottom: 12})
      if (!this.state.visitors.length && !this.state.loading) { serviceEmpty('暂无符合条件的访客记录。', this.night) }
      ForEach(this.state.visitors, (visitor: Visitor) => {
        Column({space: 8}) {
          Row() {
            Text(visitor.visitorName).fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink()).layoutWeight(1)
            Text(visitor.status === 'VISITING' ? '在访' : '已离访').fontSize(12).fontColor(visitor.status === 'VISITING' ? this.accent() : this.muted())
          }.width('100%')
          Text(visitor.buildingName + ' ' + visitor.roomNo + ' · ' + visitor.studentName).fontSize(14).fontColor(this.ink()).width('100%')
          Text(visitor.purpose).fontSize(14).fontColor(this.muted()).width('100%')
          Text('到访：' + dateText(visitor.arrivedAt)).fontSize(12).fontColor(this.muted()).width('100%')
          if (visitor.leftAt) { Text('离访：' + dateText(visitor.leftAt)).fontSize(12).fontColor(this.muted()).width('100%') }
          Row({space: 8}) {
            Button(visitor.phone + '  查看').fontSize(13).fontColor(this.ink()).height(48).backgroundColor(this.soft()).borderRadius(4).layoutWeight(1).onClick(() => this.controller.contact(visitor))
            if (visitor.status === 'VISITING') {
              Button('登记离开').fontSize(14).height(48).fontColor(this.onPrimary()).backgroundColor(this.ink()).borderRadius(4)
                .enabled(!this.state.busy).onClick(() => this.controller.leave(visitor))
            }
          }.width('100%')
        }.padding(18).width('100%').backgroundColor(this.surface()).border({width: 1, color: this.line()}).borderRadius(6).margin({bottom: 12})
      }, (visitor: Visitor) => visitor.id + ':' + visitor.version)
      Row({space: 12}) {
        Button('上一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.visitorPage > 1 && !this.state.loading).onClick(() => { this.state.visitorPage--; this.controller.loadVisitors(); })
        Text(String(this.state.visitorPage)).fontColor(this.muted()).fontSize(14)
        Button('下一页').height(48).layoutWeight(1).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4)
          .enabled(this.state.visitorPage * 10 < this.state.visitorTotal && !this.state.loading).onClick(() => { this.state.visitorPage++; this.controller.loadVisitors(); })
      }.width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  build() {
    Column() {
      if (this.page === 'visitor-create') { this.VisitorCreate() } else { this.Visitors() }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
