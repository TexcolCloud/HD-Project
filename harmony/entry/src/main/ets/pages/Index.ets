import {
  serviceLabel,
  serviceField,
  servicePrimary,
  serviceSecondary,
  serviceDetail,
} from "../components/ServiceParts";
import { common } from "@kit.AbilityKit";
import { preferences } from "@kit.ArkData";
import { util } from "@kit.ArkTS";
import { Api, ApiError } from "../services/Api";
import { SubmissionKeys } from "../services/SubmissionKeys";
import { actionLabel } from "../services/Labels";
import { palette } from "../components/Palette";
import { DispatchController } from "../features/dispatch/DispatchController";
import { RoutePage } from "../features/dispatch/RoutePage";
import { MobilityController } from "../features/mobility/MobilityController";
import { MobilityPage } from "../features/mobility/MobilityPage";
import {
  PhotoDraftController,
  PhotoSnapshot,
} from "../features/photos/PhotoDraftController";
import {
  User,
  Repair,
  LoginRequest,
  LoginResult,
  PasswordRequest,
} from "../models/ApiModels";

import {
  RepairController,
  RepairState,
} from "../features/repairs/RepairController";
import { RepairWorkspace } from "../features/repairs/RepairWorkspace";
import {
  VisitorController,
  VisitorState,
} from "../features/visitors/VisitorController";
import { VisitorWorkspace } from "../features/visitors/VisitorWorkspace";
import {
  HygieneController,
  HygieneState,
} from "../features/hygiene/HygieneController";
import { HygieneWorkspace } from "../features/hygiene/HygieneWorkspace";
import { HomeController, HomeState } from "../features/home/HomeController";
import { HomeWorkspace } from "../features/home/HomeWorkspace";
import { WorkspaceHost } from "../features/WorkspaceHost";
interface EmptyRequest {}

@Entry
@Component
struct Index {
  @State user: User | null = null;
  @State page: string = "home";
  @State night: boolean = false;
  @State busy: boolean = false;
  @State error: string = "";
  @State username: string = "";
  @State password: string = "";
  @State server: string = Api.base;
  @State showServer: boolean = false;
  @State oldPassword: string = "";
  @State newPassword: string = "";
  private dispatch: DispatchController = new DispatchController();
  private repairReturnPage: string = "repairs";
  private scroller: Scroller = new Scroller();
  private submissionKeys: SubmissionKeys = new SubmissionKeys((): string =>
    util.generateRandomUUID(),
  );
  private mobility: MobilityController = new MobilityController();
  private photoDrafts: PhotoDraftController = new PhotoDraftController(
    (error: Error) => this.fail(error),
  );
  private unsubscribePhotos: () => void = () => {};
  private host: WorkspaceHost = {
    setError: (message: string): void => {
      this.error = message;
    },
    fail: (error: Error): void => this.fail(error),
    flash: (message: string): void => this.flash(message),
    navigate: (page: string): void => this.go(page),
    openRepair: (repair: Repair): Promise<void> => this.openRepair(repair),
    confirm: async (
      title: string,
      message: string,
      cancel: string,
      accept: string,
    ): Promise<boolean> => {
      const answer = await this.getUIContext()
        .getPromptAction()
        .showDialog({
          title: title,
          message: message,
          buttons: [
            { text: cancel, color: this.muted() },
            { text: accept, color: this.accent() },
          ],
        });
      return answer.index === 1;
    },
    inform: async (title: string, message: string): Promise<void> => {
      await this.getUIContext()
        .getPromptAction()
        .showDialog({
          title: title,
          message: message,
          buttons: [{ text: "知道了", color: this.accent() }],
        });
    },
  };
  private repairs: RepairController = new RepairController(
    this.host,
    this.photoDrafts,
  );
  private visitors: VisitorController = new VisitorController(this.host);
  private hygiene: HygieneController = new HygieneController(
    this.host,
    this.photoDrafts,
  );
  private home: HomeController = new HomeController(this.host);
  @State repairState: RepairState = this.repairs.state;
  @State visitorState: VisitorState = this.visitors.state;
  @State hygieneState: HygieneState = this.hygiene.state;
  @State homeState: HomeState = this.home.state;
  private clearSession(): void {
    this.mobility.detach();
    this.photoDrafts.reset();
    this.dispatch.stopSession();
    this.resetWorkspaces();
    this.submissionKeys.clear();
    Api.token = "";
    this.user = null;
    this.password = "";
    this.oldPassword = "";
    this.newPassword = "";
    this.busy = false;
    this.page = "home";
  }
  private resetWorkspaces(): void {
    this.repairs.reset();
    this.visitors.reset();
    this.hygiene.reset();
    this.home.reset();
    this.repairState = this.repairs.state;
    this.visitorState = this.visitors.state;
    this.hygieneState = this.hygiene.state;
    this.homeState = this.home.state;
    this.repairReturnPage = "repairs";
  }
  private currentLoading(): boolean {
    if (this.page === "home") return this.homeState.loading;
    if (this.page === "repairs") return this.repairState.listLoading;
    if (this.page === "detail" || this.page === "action")
      return this.repairState.detailLoading;
    if (this.page === "visitors") return this.visitorState.loading;
    if (this.page === "hygiene") return this.hygieneState.loading;
    return false;
  }
  private go(page: string): void {
    this.home.suspendReads();
    this.repairs.suspendReads();
    this.visitors.suspendReads();
    this.hygiene.suspendReads();
    this.page = page;
    this.error = "";
    this.scroller.scrollTo({ xOffset: 0, yOffset: 0 });
    if (page === "home") this.home.loadHome();
    else if (page === "repairs") this.repairs.loadRepairs();
    else if (page === "visitors") this.visitors.loadVisitors();
    else if (page === "visitor-create") this.visitors.loadStudents();
    else if (page === "hygiene") this.hygiene.loadHygiene();
    else if (page === "hygiene-create") {
      this.hygiene.beginDraft();
      this.hygiene.loadRooms();
    } else if (page === "room") this.home.loadResidences();
  }
  private async openRepair(order: Repair): Promise<void> {
    if (!["route", "detail", "action"].includes(this.page))
      this.repairReturnPage = "repairs";
    this.go("detail");
    await this.repairs.loadDetail(order);
  }
  aboutToAppear(): void {
    this.unsubscribePhotos = this.photoDrafts.subscribe(
      (snapshot: PhotoSnapshot) => {
        this.repairs.photoChanged(snapshot);
        this.hygiene.photoChanged(snapshot);
      },
    );
    this.getUIContext()
      .getFont()
      .registerFont({
        familyName: "DormSerif",
        familySrc: $rawfile("dorm-serif.ttf"),
      });
    this.restoreSettings();
  }

  aboutToDisappear(): void {
    this.unsubscribePhotos();
    this.photoDrafts.reset();
    this.resetWorkspaces();
    this.dispatch.stopSession();
    this.mobility.detach();
  }

  onBackPress(): boolean {
    if (this.user && this.page !== "home") {
      this.go(
        this.page === "action"
          ? "detail"
          : this.page === "detail"
            ? this.repairReturnPage
            : "home",
      );
      return true;
    }
    return false;
  }

  private bg(): string {
    return palette(this.night).canvas;
  }

  private surface(): string {
    return palette(this.night).surface;
  }

  private ink(): string {
    return palette(this.night).ink;
  }

  private muted(): string {
    return palette(this.night).muted;
  }

  private line(): string {
    return palette(this.night).line;
  }

  private accent(): string {
    return palette(this.night).accent;
  }

  private onPrimary(): string {
    return palette(this.night).onPrimary;
  }

  private manager(): boolean {
    return this.user?.role === "MANAGER";
  }

  private async restoreSettings(): Promise<void> {
    try {
      const context =
        this.getUIContext().getHostContext() as common.UIAbilityContext;
      const prefs = await preferences.getPreferences(
        context,
        "dormlink-settings",
      );
      this.server = (await prefs.get("server", Api.base)) as string;
      this.night = (await prefs.get("night", false)) as boolean;
      Api.base = Api.normalizeBase(this.server);
    } catch (_) {
      /* Defaults remain usable when settings have not been stored. */
    }
  }

  private async saveSettings(): Promise<void> {
    const context =
      this.getUIContext().getHostContext() as common.UIAbilityContext;
    const prefs = await preferences.getPreferences(
      context,
      "dormlink-settings",
    );
    await prefs.put("server", Api.base);
    await prefs.put("night", this.night);
    await prefs.flush();
  }

  private flash(message: string): void {
    this.getUIContext()
      .getPromptAction()
      .showToast({ message: message, duration: 3000 });
  }

  private fail(error: Error): void {
    const apiError = error as ApiError;
    if (
      apiError.sessionEpoch !== undefined &&
      apiError.sessionEpoch !== Api.epoch
    )
      return;
    if (apiError.code === "SESSION_REPLACED") return;
    this.error = apiError.message || "操作未完成，请稍后重试";
    if (apiError.traceId) this.error += "\n查询编号：" + apiError.traceId;
    if (apiError.status === 401) {
      this.clearSession();
    }
  }

  private requestKey(body: Object, scope: string): string {
    return this.submissionKeys.reserve(scope, JSON.stringify(body));
  }

  private async login(): Promise<void> {
    let epoch = Api.epoch;
    if (this.busy) return;
    this.error = "";
    if (!this.username.trim() || !this.password) {
      this.error = "请填写账号和密码";
      return;
    }
    this.busy = true;
    try {
      Api.base = Api.normalizeBase(this.server);
      const request: LoginRequest = {
        username: this.username.trim(),
        password: this.password,
        clientType: "MOBILE",
      };
      const result = await Api.post<LoginResult>("/auth/login", request);
      Api.assertCurrent(epoch);
      this.password = "";
      request.password = "";
      if (!result.token) throw new ApiError("未获得移动端会话，请联系管理员");
      Api.token = result.token;
      epoch = Api.epoch;
      this.user = result.user;
      this.dispatch.startSession(result.user, result.token, (error: Error) =>
        this.fail(error),
      );
      this.mobility.attach(
        result.user,
        this.getUIContext().getHostContext() as common.UIAbilityContext,
        (error: Error) => this.fail(error),
      );
      this.repairs.state.contactName = result.user.displayName;
      this.home.initialize(result.user);
      this.hygiene.manager = this.home.manager;
      this.page = "home";
      await this.saveSettings();
      Api.assertCurrent(epoch);
      await this.home.loadHome();
    } catch (error) {
      this.fail(error as Error);
    } finally {
      if (epoch === Api.epoch) this.busy = false;
    }
  }

  private async logout(): Promise<void> {
    const epoch = Api.epoch;
    this.dispatch.stopSession();
    this.mobility.detach();
    this.password = "";
    this.oldPassword = "";
    this.newPassword = "";
    this.busy = true;
    try {
      const request: EmptyRequest = {};
      await Api.post<EmptyRequest>("/auth/logout", request);
    } catch (_) {
      /* Clear local session even after network loss. Server sessions have a fixed expiry. */
    } finally {
      if (epoch === Api.epoch || !Api.token) {
        this.clearSession();
        this.error = "";
        this.busy = false;
      }
    }
  }

  private async changePassword(): Promise<void> {
    const epoch = Api.epoch;
    if (this.busy) return;
    if (this.newPassword.length < 8 || !this.oldPassword) {
      this.error = "请填写原密码，新密码至少8位";
      return;
    }
    this.busy = true;
    try {
      const request: PasswordRequest = {
        oldPassword: this.oldPassword,
        newPassword: this.newPassword,
      };
      await Api.post<EmptyRequest>(
        "/auth/change-password",
        request,
        this.requestKey(request, "password"),
      );
      Api.assertCurrent(epoch);
      this.submissionKeys.complete("password");
      this.clearSession();
      this.flash("密码已修改，请重新登录");
    } catch (error) {
      this.fail(error as Error);
    } finally {
      if (epoch === Api.epoch) this.busy = false;
    }
  }

  private pageTitle(): string {
    switch (this.page) {
      case "repair-create":
        return "提交报修";
      case "repairs":
        return this.manager() ? "报修工单" : "我的报修";
      case "detail":
        return "工单详情";
      case "action":
        return this.repairState.action === "rework" && this.manager()
          ? "返工处理"
          : actionLabel(this.repairState.action);
      case "visitor-create":
        return "登记访客";
      case "visitors":
        return "访客记录";
      case "hygiene-create":
        return "卫生评分";
      case "hygiene":
        return "卫生记录";
      case "route":
        return "本次路线";
      case "mobility":
        return "出行辅助";
      case "profile":
        return "我的";
      case "room":
        return "入住信息";
      case "password":
        return "修改密码";
      case "credits":
        return "图片来源";
      default:
        return "寓见";
    }
  }
  @Builder
  private Login() {
    Column({space: 0}) {
      Text('寓见').fontFamily('DormSerif').fontWeight(FontWeight.Medium).fontSize(24).fontColor(this.ink()).width('100%')
      Text('公寓里的日常，\n清楚，也从容。').fontFamily('DormSerif').fontWeight(FontWeight.Medium).fontSize(32)
        .fontColor(this.ink()).lineHeight(48).width('100%').margin({top: 40, bottom: 16})
      Text('报修、来访与巡检，从这里开始。').fontSize(14).fontColor(this.muted()).width('100%').margin({bottom: 38})
      serviceLabel('账号', this.night)
      TextInput({text: this.username, placeholder: '请输入学校提供的账号'}).id('login-username')
        .onChange((value: string) => this.username = value).height(52).fontSize(16).fontColor(this.ink())
        .placeholderColor(this.muted()).backgroundColor(this.surface()).border({width: 1, color: this.line()})
        .borderRadius(4).margin({top: 8, bottom: 22}).maxLength(64)
      serviceLabel('密码', this.night)
      TextInput({text: this.password, placeholder: '请输入密码'}).id('login-password').type(InputType.Password)
        .onChange((value: string) => this.password = value).height(52).fontSize(16).fontColor(this.ink())
        .placeholderColor(this.muted()).backgroundColor(this.surface()).border({width: 1, color: this.line()})
        .borderRadius(4).margin({top: 8, bottom: 28}).maxLength(100)
      Button(this.busy ? '正在登录…' : '登录').id('login-submit').width('100%').height(52).fontSize(16)
        .backgroundColor(this.ink()).fontColor(this.onPrimary()).borderRadius(4).enabled(!this.busy).onClick(() => this.login())
      Row() {
        Text('由账号决定学生或宿管身份').fontSize(12).fontColor(this.muted()).layoutWeight(1)
        Button(this.night ? '日间' : '夜间').height(48).fontSize(13).fontColor(this.ink())
          .backgroundColor(Color.Transparent).onClick(() => { this.night = !this.night; this.saveSettings(); })
      }.margin({top: 12})
      Button(this.showServer ? '收起服务连接设置' : '服务连接设置').fontSize(12).fontColor(this.muted())
        .backgroundColor(Color.Transparent).height(48).onClick(() => this.showServer = !this.showServer)
      if (this.showServer) {
        serviceField({label: '服务地址', value: this.server, change: (value: string) => this.server = value, placeholder: 'http://电脑地址:8080/api/v1'}, this.night, this.page)
        Text('模拟器默认通过本机端口转发连接；真机请填写可访问的服务地址。').fontSize(12).fontColor(this.muted()).lineHeight(21)
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding({left: 24, right: 24, top: 32, bottom: 36})
  }

  @Builder
  private Profile() {
    Column({space: 0}) {
      Text(this.user?.displayName || '').fontFamily('DormSerif').fontSize(30).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%').margin({bottom: 18})
      serviceDetail({label: '当前身份', value: this.manager() ? '宿管' : '学生'}, this.night)
      serviceDetail({label: '登录账号', value: this.user?.username || ''}, this.night)
      if (!this.manager()) { serviceDetail({label: '当前宿舍', value: this.homeState.room ? this.homeState.room.buildingName + ' ' + this.homeState.room.roomNo : '尚未入住'}, this.night) }
      Blank().height(24)
      if (!this.manager()) { servicePrimary('查看入住信息', () => this.go('room'), this.night, this.page, this.busy, false); Blank().height(12) }
      serviceSecondary('修改密码', () => this.go('password'), this.night, this.busy)
      Row() {
        Text('暖黑夜间').fontSize(16).fontColor(this.ink()).layoutWeight(1)
        Toggle({type: ToggleType.Switch, isOn: this.night}).selectedColor(this.accent()).onChange((v: boolean) => { this.night = v; this.saveSettings(); })
      }.width('100%').height(72).margin({top: 14}).border({width: {top: 1, bottom: 1}, color: this.line()})
      Text('数据仅限本人或负责楼栋范围。').fontSize(13).fontColor(this.muted()).width('100%').margin({top: 20, bottom: 24})
      Button('退出登录').id('profile-logout').width('100%').height(50).fontSize(16).fontColor(this.accent()).backgroundColor(this.surface())
        .border({width: 1, color: this.line()}).borderRadius(4).enabled(!this.busy).onClick(() => this.logout())
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Password() {
    Column({space: 18}) {
      serviceLabel('原密码 *', this.night)
      TextInput({text: this.oldPassword, placeholder: '请输入原密码'}).type(InputType.Password).height(50).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).onChange((v: string) => this.oldPassword = v)
      serviceLabel('新密码 *', this.night)
      TextInput({text: this.newPassword, placeholder: '至少8位'}).type(InputType.Password).height(50).backgroundColor(this.surface()).fontColor(this.ink()).borderRadius(4).onChange((v: string) => this.newPassword = v)
      Text('修改成功后需要重新登录。').fontSize(13).fontColor(this.muted())
      servicePrimary('确认修改密码', () => this.changePassword(), this.night, this.page, this.busy, false)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Credits() {
    Column({space: 18}) {
      Image($rawfile('campus-courtyard.jpg')).width('100%').height(200).objectFit(ImageFit.Cover)
      Text('校园氛围示意').fontFamily('DormSerif').fontSize(24).fontWeight(FontWeight.Medium).fontColor(this.ink())
      Text('摄影：Nick Allen（Wikimedia Commons 用户 Nickknack00）\n作品：Lanman Wright courtyard elevated.JPG\n许可：CC BY-SA 3.0\n本地处理：缩放为1200×801并压缩；未调色或合成。').fontSize(15).fontColor(this.ink()).lineHeight(26)
      Text('原始来源：https://commons.wikimedia.org/wiki/File:Lanman_Wright_courtyard_elevated.JPG\n\n许可：https://creativecommons.org/licenses/by-sa/3.0/').fontSize(13).fontColor(this.muted()).lineHeight(24).copyOption(CopyOptions.LocalDevice)
      Text('图片仅表达校园生活氛围，不代表本平台服务学校的真实宿舍。').fontSize(13).fontColor(this.muted()).lineHeight(24)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Header() {
    Row({space: 12}) {
      if (this.page !== 'home') {
        Button('‹').id('page-back').fontSize(28).fontColor(this.ink()).backgroundColor(Color.Transparent).width(40).height(48)
          .onClick(() => this.go(this.page === 'action' ? 'detail' : this.page === 'detail' ? this.repairReturnPage : 'home'))
      }
      Text(this.pageTitle()).fontFamily('DormSerif').fontSize(24).fontWeight(FontWeight.Medium).fontColor(this.ink()).layoutWeight(1)
      Button(this.night ? '日间' : '夜间').id('theme-toggle').fontSize(13).fontColor(this.muted()).height(48).backgroundColor(Color.Transparent)
        .onClick(() => { this.night = !this.night; this.saveSettings(); })
    }.width('100%').padding({left: 16, right: 16, top: 4, bottom: 8}).backgroundColor(this.surface()).border({width: {bottom: 1}, color: this.line()})
  }

  @Builder
  private BottomTabs() {
    Row() {
      Button() { Column({space: 2}) { SymbolGlyph($r('sys.symbol.house')).fontSize(23).fontColor([this.page === 'home' ? this.accent() : this.muted()]); Text('首页').fontSize(12).fontColor(this.page === 'home' ? this.accent() : this.muted()) } }
        .id('tab-home').layoutWeight(1).height(58).fontColor(this.page === 'home' ? this.accent() : this.muted())
        .backgroundColor(Color.Transparent).borderRadius(0).onClick(() => this.go('home'))
      Button() { Column({space: 2}) { SymbolGlyph($r('sys.symbol.doc_text')).fontSize(21).fontColor([['repairs', 'detail', 'action'].includes(this.page) ? this.accent() : this.muted()]); Text(this.manager() ? '工单' : '我的报修').fontSize(12).fontColor(['repairs', 'detail', 'action'].includes(this.page) ? this.accent() : this.muted()) } }
        .id('tab-repairs').layoutWeight(1).height(58).fontColor(['repairs', 'detail', 'action'].includes(this.page) ? this.accent() : this.muted())
        .backgroundColor(Color.Transparent).borderRadius(0).onClick(() => { this.repairState.repairPage = 1; this.repairState.repairFilter = ''; this.go('repairs'); })
      Button() { Column({space: 2}) { SymbolGlyph($r('sys.symbol.person')).fontSize(21).fontColor([this.page === 'profile' ? this.accent() : this.muted()]); Text('我的').fontSize(12).fontColor(this.page === 'profile' ? this.accent() : this.muted()) } }
        .id('tab-profile').layoutWeight(1).height(58).fontColor(this.page === 'profile' ? this.accent() : this.muted())
        .backgroundColor(Color.Transparent).borderRadius(0).onClick(() => this.go('profile'))
    }.width('100%').backgroundColor(this.surface()).border({width: {top: 1}, color: this.line()})
  }

  build() {
    Column() {
      if (this.user) { this.Header() }
      if (this.error) {
        Row({space: 8}) {
          Text(this.error).id('error-message').fontSize(13).lineHeight(22).fontColor(this.night ? '#F1A99C' : '#A13E37').layoutWeight(1)
          Button('×').width(40).height(48).fontColor(this.ink()).backgroundColor(Color.Transparent).onClick(() => this.error = '')
        }.width('100%').padding({left: 16, right: 8, top: 8, bottom: 8}).backgroundColor(this.night ? '#442E2B' : '#F7EAE6')
      }
      if (this.currentLoading()) {
        Row({space: 8}) { LoadingProgress().width(20).height(20).color(this.accent()); Text('正在更新…').fontSize(12).fontColor(this.muted()) }
          .width('100%').padding(8).justifyContent(FlexAlign.Center)
      }
      Scroll(this.scroller) {
        Column() {
          if (this.user) { this.Content() } else { this.Login() }
        }.width('100%').constraintSize({maxWidth: 720}).padding({left: this.user ? 16 : 0, right: this.user ? 16 : 0, top: this.user ? 16 : 0, bottom: 28}).alignItems(HorizontalAlign.Start)
      }.id('page-scroll').layoutWeight(1).width('100%').scrollBar(BarState.Auto).align(Alignment.Top)
      if (this.user) { this.BottomTabs() }
    }.width('100%').height('100%').backgroundColor(this.bg())
  }
  @Builder
  private Content() {
    if (this.page === 'home' || this.page === 'room') {
      HomeWorkspace({state: this.homeState, page: this.page, night: this.night, user: this.user, host: this.host,
        onRepairListReset: () => { this.repairState.repairPage = 1; this.repairState.repairFilter = ''; }, onHygieneListReset: () => { this.hygieneState.hygienePage = 1; }})
    } else if (['repairs', 'repair-create', 'detail', 'action'].includes(this.page)) {
      RepairWorkspace({state: this.repairState, page: this.page, night: this.night, user: this.user, room: this.homeState.room, host: this.host, controller: this.repairs, photoDrafts: this.photoDrafts, dispatch: this.dispatch})
    } else if (['visitors', 'visitor-create'].includes(this.page) && this.manager()) {
      VisitorWorkspace({state: this.visitorState, page: this.page, night: this.night, user: this.user, host: this.host, controller: this.visitors})
    } else if (['hygiene', 'hygiene-create'].includes(this.page)) {
      HygieneWorkspace({state: this.hygieneState, page: this.page, night: this.night, user: this.user, host: this.host, controller: this.hygiene, photoDrafts: this.photoDrafts})
    } else if (this.page === 'route' && this.manager()) {
      RoutePage({controller: this.dispatch, night: this.night, onOpenRepair: (repair: Repair) => { this.repairReturnPage = 'route'; this.openRepair(repair); }})
    } else if (this.page === 'mobility' && this.manager()) {
      MobilityPage({controller: this.mobility, dispatch: this.dispatch, night: this.night, onOpenRoute: () => this.go('route')})
    } else if (this.page === 'password') { this.Password() }
    else if (this.page === 'credits') { this.Credits() }
    else { this.Profile() }
  }
}
