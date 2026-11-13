import { isSessionExpiry } from "../ReadScope";
import { util } from '@kit.ArkTS';
import { Api } from '../../services/Api';
import { SubmissionKeys } from '../../services/SubmissionKeys';
import { Repair, DispatchBoard, DispatchTask, DispatchUnassigned, DispatchRoute, DispatchManager, DispatchHandoffRequest, DispatchTakeoverRequest } from '../../models/ApiModels';
import { FormInput } from '../../components/FormInput';
import { palette } from '../../components/Palette';

@Component
export struct TaskTransfer {
  @Prop @Watch('contextChanged') repair: Repair;
  @Prop userId: string = '';
  @Prop night: boolean = false;
  onUpdated: (repair: Repair) => void = () => {};
  onError: (error: Error) => void = () => {};
  @State opened: boolean = false;
  @State busy: boolean = false;
  @State error: string = '';
  @State reason: string = '';
  @State target: string = '';
  @State board: DispatchBoard | null = null;
  private generation: number = 0;
  private live: boolean = false;
  private keys: SubmissionKeys = new SubmissionKeys((): string => util.generateRandomUUID());
  private contextChanged(): void {
    this.generation++; this.board = null; this.reason = ''; this.target = ''; this.busy = false; this.opened = false; this.error = ''; this.keys.clear();
  }
  aboutToAppear(): void { this.live = true; }
  aboutToDisappear(): void { this.live = false; this.generation++; }
  private own(): boolean { return this.repair.handlerUserId === this.userId; }
  private task(): DispatchTask | null {
    if (!this.board) return null;
    for (const route of this.board.routes) {
      const task = route.tasks.find((t: DispatchTask): boolean => t.orderId === this.repair.id);
      if (task) return task;
    }
    return this.board.unassigned.find((u: DispatchUnassigned): boolean => u.task.orderId === this.repair.id)?.task || null;
  }
  private candidates(): DispatchManager[] {
    return this.board?.routes.map((route: DispatchRoute): DispatchManager => route.manager).filter((m: DispatchManager): boolean => m.userId !== this.userId && m.online && m.acceptingNewTasks && m.buildingIds.includes(this.repair.buildingId)) || [];
  }
  private async open(): Promise<void> {
    const epoch = Api.epoch, id = this.repair.id, generation = this.generation;
    this.opened = true; this.busy = true; this.error = '';
    try {
      const board = await Api.request<DispatchBoard>('/dispatch/board');
      if (this.live && epoch === Api.epoch && id === this.repair.id && generation === this.generation) this.board = board;
    } catch (error) { if ((this.live && epoch === Api.epoch && generation === this.generation) || isSessionExpiry(error as Error)) this.onError(error as Error); }
    finally { if (this.live && epoch === Api.epoch && generation === this.generation) this.busy = false; }
  }
  private async submit(): Promise<void> {
    const task = this.task();
    if (!task || this.busy || !this.reason.trim()) return;
    const epoch = Api.epoch, id = this.repair.id, generation = this.generation;
    this.busy = true; this.error = '';
    try {
      const action = this.own() ? 'handoff' : 'takeover';
      const path = '/dispatch/tasks/' + id + '/' + action;
      let updated: Repair;
      if (this.own()) {
        const body: DispatchHandoffRequest = {targetManagerId: this.target, reason: this.reason.trim(), expectedOrderVersion: this.repair.version, expectedTaskVersion: task.version};
        updated = await Api.post<Repair>(path, body, this.keys.reserve(path, JSON.stringify(body)));
      } else {
        const body: DispatchTakeoverRequest = {reason: this.reason.trim(), expectedOrderVersion: this.repair.version, expectedTaskVersion: task.version};
        updated = await Api.post<Repair>(path, body, this.keys.reserve(path, JSON.stringify(body)));
      }
      if (!this.live || epoch !== Api.epoch || id !== this.repair.id || generation !== this.generation) return;
      this.keys.complete(path); this.onUpdated(updated);
    } catch (error) { if ((this.live && epoch === Api.epoch && generation === this.generation) || isSessionExpiry(error as Error)) this.onError(error as Error); }
    finally { if (this.live && epoch === Api.epoch && generation === this.generation) this.busy = false; }
  }
  build() {
    if (['ACCEPTED', 'PROCESSING'].includes(this.repair.status) && (!this.own() || this.repair.status === 'ACCEPTED')) {
      Column({space: 12}) {
        Button(this.opened ? '收起责任接续' : this.own() ? '交接办理责任' : '申请异常接续').id('task-transfer-open').height(48).fontColor(palette(this.night).accent).backgroundColor(Color.Transparent).enabled(!this.busy).onClick(() => { if (this.opened) this.opened = false; else this.open(); })
        if (this.opened) {
          Text(this.own() ? '仅未开始、未出发的工单可以正常交接。' : '仅原办理人停用、失去负责范围或持续离线时可接续。接续后先核对现场，再恢复路线。').fontSize(13).lineHeight(23).fontColor(palette(this.night).muted).width('100%')
          if (this.own()) {
            Select(this.candidates().map((m: DispatchManager): SelectOption => ({value: m.displayName}))).value(this.candidates().find((m: DispatchManager): boolean => m.userId === this.target)?.displayName || '选择接续宿管')
              .width('100%').height(48).onSelect((index: number) => this.target = this.candidates()[index].userId)
          }
          FormInput({label: '交接或接续原因', value: this.reason, multiline: true, night: this.night, onChange: (value: string) => this.reason = value})
          Button('确认责任接续').id('task-transfer-submit').height(48).width('100%').fontColor(palette(this.night).onPrimary).backgroundColor(palette(this.night).ink).borderRadius(4)
            .enabled(!this.busy && !!this.reason.trim() && !!this.task() && !this.board?.dirty && (!this.own() || !!this.target)).onClick(() => this.submit())
        }
      }.padding(12).width('100%').backgroundColor(palette(this.night).soft).margin({top: 16, bottom: 16}).borderRadius(4)
    }
  }
}
