import { ServiceCenterController, ServiceCenterState } from "../features/service/ServiceCenterController";
import { ServiceCenterWorkspace } from "../features/service/ServiceCenterWorkspace";
import { InboxPoller } from "../features/service/InboxPoller";
import { DeviceActions } from "../features/mobility/DeviceActions";
import { AccountWorkspace, RecoveryCodeView } from "../features/account/AccountWorkspace";
import { LocalDrafts, DraftStorage } from "../services/LocalDrafts";
import {
  serviceLabel,
  serviceField,
  servicePrimary,
  serviceSecondary,
  serviceDetail,
} from "../components/ServiceParts";
import { common } from "@kit.AbilityKit";
import { window } from "@kit.ArkUI";
import { preferences } from "@kit.ArkData";
import { util } from "@kit.ArkTS";
import { Api, ApiError } from "../services/Api";
import { SubmissionKeys } from "../services/SubmissionKeys";
import { actionLabel, isMobileNumber } from "../services/Labels";
import { palette } from "../components/Palette";
import { DispatchController } from "../features/dispatch/DispatchController";
import { AppVisibility } from "../features/dispatch/AppVisibility";
import { RoutePage } from "../features/dispatch/RoutePage";
import { MobilityController } from "../features/mobility/MobilityController";
import { MobilityPage } from "../features/mobility/MobilityPage";
import {
  PhotoDraftController,
  PhotoSnapshot,
} from "../features/photos/PhotoDraftController";
import {
  AccountSecurityResult,
  User,
  Repair,
  Hygiene,
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
import { NavigationStack, BackPanels, TabLink, bottomTabs, activeTab, rootTab, requiresLogoutOnBack } from "../features/NavigationStack";
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
  @State loginIdentifier: string = "USERNAME";
  @State activationSignIn: boolean = false;
  @State recoverEntry: boolean = false;
  @State displayedRecoveryCode: string = "";
  @State accountMessage: string = "";
  @State server: string = Api.base;
  @State showServer: boolean = false;
  @State oldPassword: string = "";
  @State newPassword: string = "";
  private dispatch: DispatchController = new DispatchController();
  @State hasPrevious: boolean = false;
  private navigation: NavigationStack = new NavigationStack();
  @Provide backPanels: BackPanels = new BackPanels();
  private navigationVersion: number = 0;
  @State refreshing: boolean = false;
  private scroller: Scroller = new Scroller();
  private submissionKeys: SubmissionKeys = new SubmissionKeys((): string =>
    util.generateRandomUUID(),
  );
  private mobility: MobilityController = new MobilityController();
  private photoDrafts: PhotoDraftController = new PhotoDraftController(
    (error: Error) => this.fail(error),
  );
  private unsubscribePhotos: () => void = () => {};
  private unsubscribeVisibility: () => void = () => {};
  private host: WorkspaceHost = {
    setError: (message: string): void => {
      this.error = message;
    },
    fail: (error: Error): void => this.fail(error),
    flash: (message: string): void => this.flash(message),
    navigate: (page: string): void => this.go(page),
    scrollTop: (): void => this.scroller.scrollTo({ xOffset: 0, yOffset: 0 }),
    messageRead: (): void => {
      this.inbox.markRead();
      this.unreadCount = Math.max(0, this.unreadCount - 1);
    },
    openVisitor: (id: string): void => {
      if (!this.manager()) return;
      this.visitors.state.detailId = id;
      this.visitors.state.selected = null;
      this.go('visitor-detail');
    },
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
  private serviceCenter: ServiceCenterController = new ServiceCenterController(this.host, this.photoDrafts);
  private inbox: InboxPoller = new InboxPoller();
  @State serviceState: ServiceCenterState = this.serviceCenter.state;
  @State unreadCount: number = 0;
  @State messageNotifications: boolean = false;
  private home: HomeController = new HomeController(this.host);
  @State repairState: RepairState = this.repairs.state;
  @State visitorState: VisitorState = this.visitors.state;
  @State hygieneState: HygieneState = this.hygiene.state;
  @State homeState: HomeState = this.home.state;
  private saveDrafts(): void {
    if (this.page === "repair-create") this.repairs.saveDraft();
    if (this.page === "visitor-create") this.visitors.saveDraft();
    if (this.page === "hygiene-create") this.hygiene.saveDraft();
  }
  private bindDrafts(): void {
    if (!this.user) return;
    try {
      const prefs = preferences.getPreferencesSync(this.getUIContext().getHostContext() as common.UIAbilityContext, {name: "dormlink-private-drafts"});
      const storage: DraftStorage = {
        get: (key: string): string => prefs.getSync(key, "") as string,
        put: (key: string, value: string): void => { prefs.putSync(key, value); prefs.flushSync(); },
        remove: (key: string): void => { prefs.deleteSync(key); prefs.flushSync(); }
      };
      const drafts = new LocalDrafts(storage, Api.base, this.user.id);
      this.repairs.bindDrafts(drafts);
      this.visitors.bindDrafts(drafts);
      this.hygiene.bindDrafts(drafts);
    } catch (_) { this.error = "本机草稿存储暂不可用，请保留填写内容并及时提交"; }
  }
  onPageHide(): void { this.saveDrafts(); }
  private clearSession(): void {
    this.saveDrafts();
    this.repairs.bindDrafts(null);
    this.visitors.bindDrafts(null);
    this.hygiene.bindDrafts(null);
    this.inbox.stop();
    this.unreadCount = 0;
    this.mobility.detach();
    this.photoDrafts.reset();
    this.dispatch.stopSession();
    this.resetWorkspaces();
    this.submissionKeys.clear();
    Api.token = "";
    this.user = null;
    this.username = "";
    this.password = "";
    this.oldPassword = "";
    this.newPassword = "";
    this.activationSignIn = false;
    this.recoverEntry = false;
    this.displayedRecoveryCode = "";
    this.accountMessage = "";
    this.busy = false;
    this.page = "home";
  }
  private resetWorkspaces(): void {
    this.repairs.reset();
    this.visitors.reset();
    this.hygiene.reset();
    this.home.reset();
    this.serviceCenter.reset();
    this.serviceState = this.serviceCenter.state;
    this.repairState = this.repairs.state;
    this.visitorState = this.visitors.state;
    this.hygieneState = this.hygiene.state;
    this.homeState = this.home.state;
    this.hasPrevious = false;
    this.navigation.reset("home");
    this.navigationVersion++;
  }
  private currentLoading(): boolean {
    if (this.page === "home") return this.homeState.loading;
    if (this.page === "repairs") return this.repairState.listLoading;
    if (this.page === "detail" || this.page === "action")
      return this.repairState.detailLoading;
    if (this.page === "visitors" || this.page === "visitor-detail") return this.visitorState.loading;
    if (this.page === "hygiene") return this.hygieneState.loading;
    if (this.page === "messages" || this.page === "remediations") return this.serviceState.loading;
    return false;
  }
  private switchTab(page: string): void {
    this.navigation.reset(page);
    this.showPage(page);
  }
  private go(page: string): void {
    if (this.busy && (this.page === "activate" || this.page === "account") && this.page !== page) return;
    if (this.user?.mustChangePassword && page !== "password" && page !== "activate") { this.page = "password"; return; }
    if (page === this.page) return;
    const completesForm = (['remediation-create','remediation-assignment'].includes(this.page) && page === 'remediation-detail') ||
      (this.page === 'repair-create' && page === 'detail') ||
      (this.page === 'hygiene-create' && ['remediation-detail','hygiene'].includes(page)) ||
      (this.page === 'visitor-create' && page === 'visitors');
    if (completesForm) {
      if (this.navigation.previous() === page) this.popPage();
      else { this.navigation.replace(page); this.showPage(page); }
      return;
    }
    let recordId = '';
    if (this.page === 'detail') recordId = this.repairState.selected?.id || '';
    if (this.page === 'visitor-detail') recordId = this.visitorState.detailId;
    if (this.page === 'remediation-detail') recordId = this.serviceState.selected?.id || '';
    let resume: (() => void) | undefined = undefined;
    if (this.page === 'action') resume = this.repairs.preserveAction();
    if (['remediation-assignment', 'remediation-create'].includes(this.page)) resume = this.serviceCenter.preserveAssignment();
    this.navigation.enter(page, this.page, this.scroller.currentOffset()?.yOffset || 0, recordId, resume);
    this.showPage(page);
  }
  private popPage(): boolean {
    const entry = this.navigation.back();
    if (!entry) return false;
    if (this.page === "hygiene-create") this.hygiene.returnRemediationId = "";
    entry.resume?.();
    if (entry.page === 'visitor-detail' && entry.recordId) this.visitorState.detailId = entry.recordId;
    this.showPage(entry.page, entry.scrollY);
    if (entry.page === 'remediation-detail' && entry.recordId && this.serviceState.selected?.id !== entry.recordId) {
      this.serviceState.selected = null;
      this.serviceCenter.openRemediation(entry.recordId);
    }
    if (entry.page === 'detail' && entry.recordId) this.repairs.restoreDetail(entry.recordId);
    return true;
  }
  private showPage(page: string, offset: number = 0): void {
    this.saveDrafts();
    this.home.suspendReads();
    this.repairs.suspendReads();
    this.visitors.suspendReads();
    this.hygiene.suspendReads();
    this.serviceCenter.suspend();
    this.hasPrevious = !!this.navigation.previous();
    this.page = page;
    this.error = "";
    const version = ++this.navigationVersion;
    setTimeout(() => {
      if (version === this.navigationVersion) this.scroller.scrollTo({xOffset: 0, yOffset: offset});
    }, 0);
    if (page === "messages") this.serviceCenter.loadMessages();
    else if (page === "remediations") this.serviceCenter.loadRemediations();
    else if (["remediation-create", "remediation-assignment"].includes(page)) this.serviceCenter.findResidents();
    else if (page === "home") this.home.loadHome();
    else if (page === "repairs") this.repairs.loadRepairs();
    else if (page === "visitors") {
      this.visitors.loadVisitors();
      this.visitors.loadBuildings();
    }
    else if (page === "visitor-detail") this.visitors.loadDetail();
    else if (page === "visitor-create") this.visitors.loadStudents();
    else if (page === "hygiene") this.hygiene.loadHygiene();
    else if (page === "hygiene-create") {
      this.hygiene.beginDraft();
      this.hygiene.loadRooms();
    } else if (page === "room") this.home.loadResidences();
  }
  private showsBottomTabs(): boolean {
    return !!this.user && rootTab(this.page, this.manager()) && !this.user.mustChangePassword && !this.displayedRecoveryCode;
  }

  private canRefreshWorkspace(): boolean {
    if (this.user?.mustChangePassword || this.displayedRecoveryCode) return false;
    if (this.page === 'route' || this.page === 'mobility') return this.manager();
    return ['home','messages','remediations','visitors', 'visitor-detail', 'repairs', 'detail', 'hygiene', 'room', 'remediation-detail'].includes(this.page);
  }
  private async refreshWorkspace(): Promise<void> {
    if (!this.user || this.currentLoading() || this.visitorState.busy || this.serviceState.busy || this.hygieneState.busy || this.repairState.busy) return;
    this.error = "";
    if (this.page === 'home') await this.home.loadHome();
    else if (this.page === 'route' && this.manager()) await this.dispatch.refresh();
    else if (this.page === 'messages') await this.serviceCenter.loadMessages();
    else if (this.page === 'remediations') await this.serviceCenter.loadRemediations();
    else if (this.page === 'visitors') await Promise.all([this.visitors.loadVisitors(),this.visitors.loadBuildings()]);
    else if (this.page === 'visitor-detail') await this.visitors.loadDetail();
    else if (this.page === 'repairs') await this.repairs.loadRepairs();
    else if (this.page === 'detail' && this.repairState.selected) await this.repairs.loadDetail(this.repairState.selected);
    else if (this.page === 'hygiene') await this.hygiene.loadHygiene();
    else if (this.page === 'room') await this.home.loadResidences();
    else if (this.page === 'remediation-detail') await this.serviceCenter.refreshRemediation(this.manager());
    else if (this.page === 'mobility' && this.manager()) await this.mobility.refresh();
  }
  private async openRepair(order: Repair): Promise<void> {
    if (this.page === 'action' && this.navigation.previous() === 'route') { this.popPage(); return; }
    if (this.page === 'action' && this.navigation.previous() === 'detail') {
      const entry = this.navigation.back();
      this.showPage('detail', entry?.scrollY || 0);
    }
    this.go("detail");
    await this.repairs.loadDetail(order);
  }
  private async returnToReview(taskId: string, saved: Hygiene): Promise<void> {
    if (this.serviceState.selected?.id === taskId) this.go('remediation-detail');
    else await this.serviceCenter.openRemediation(taskId);
    if (this.page === 'remediation-detail' && this.serviceState.selected?.id === taskId)
      await this.serviceCenter.loadRechecks(saved);
  }
  aboutToAppear(): void {
    this.syncSystemBars();
    this.hygiene.onRecheckSaved = (taskId: string, saved: Hygiene): void => {
      if (this.page !== 'hygiene-create') return;
      this.returnToReview(taskId, saved);
    };
    this.unsubscribePhotos = this.photoDrafts.subscribe(
      (snapshot: PhotoSnapshot) => {
        this.repairs.photoChanged(snapshot);
        this.hygiene.photoChanged(snapshot);
      },
    );
    this.unsubscribeVisibility = AppVisibility.subscribe((foreground: boolean): void => {
      if (!this.user) return;
      if (foreground && this.canRefreshWorkspace()) this.refreshWorkspace();
      if (!foreground) {
        this.visitors.suspendReads();
        this.hygiene.suspendReads();
      }
    });
    this.getUIContext()
      .getFont()
      .registerFont({
        familyName: "DormSerif",
        familySrc: $rawfile("dorm-serif.ttf"),
      });
    this.restoreSettings();
  }

  aboutToDisappear(): void {
    this.saveDrafts();
    this.inbox.stop();
    this.unsubscribePhotos();
    this.unsubscribeVisibility();
    this.photoDrafts.reset();
    this.resetWorkspaces();
    this.dispatch.stopSession();
    this.mobility.detach();
  }

  onBackPress(): boolean {
    if (this.displayedRecoveryCode || this.backPanels.close()) return true;
    if (requiresLogoutOnBack(this.page, this.user?.mustChangePassword === true)) {
      this.confirmForcedLogout();
      return true;
    }
    if (this.recoverEntry) {
      if (!this.busy) this.exitAccount("recover");
      return true;
    }
    if (!this.user && this.activationSignIn) {
      this.setLoginMode("USERNAME", false);
      return true;
    }
    if (!this.user) return false;
    if (this.repairState.busy || this.serviceState.busy || this.hygieneState.busy || this.visitorState.busy || this.busy) return true;
    if (this.popPage()) return true;
    if (this.page === 'activate' && this.user.mustChangePassword) { this.switchTab('password'); return true; }
    return false;
  }
  private async confirmForcedLogout(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      if (await this.host.confirm('退出登录', '退出当前账号并返回登录页？', '继续修改', '退出登录')) await this.logout();
    } finally { this.busy = false; }
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

  private syncSystemBars(): void {
    try {
      const context = this.getUIContext().getHostContext();
      if (!context) return;
      window.getLastWindow(context).then((mainWindow: window.Window) => {
        const ink = palette(this.night).ink;
        return mainWindow.setWindowSystemBarProperties({
          statusBarColor: '#00000000',
          navigationBarColor: '#00000000',
          statusBarContentColor: ink,
          navigationBarContentColor: ink,
          isStatusBarLightIcon: this.night,
          isNavigationBarLightIcon: this.night
        });
      }).catch(() => {});
    } catch (_) {
      // A preview can render without a host window.
    }
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
      this.syncSystemBars();
      this.messageNotifications = (await prefs.get("messageNotifications",false)) as boolean;
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
    await prefs.put("messageNotifications",this.messageNotifications);
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
    if (apiError.code === "PASSWORD_CHANGE_REQUIRED") this.page = "password";
    if (apiError.status === 401) {
      if (this.user) this.clearSession();
      else this.password = "";
    }
  }

  private requestKey(body: Object, scope: string): string {
    return this.submissionKeys.reserve(scope, JSON.stringify(body));
  }

  private setLoginMode(identifier: string, activate: boolean): void {
    if (this.busy) return;
    if (this.activationSignIn !== activate) this.password = "";
    this.loginIdentifier = identifier;
    if (identifier === 'PHONE') this.username = this.username.replace(/\D/g, '').slice(0, 11);
    this.activationSignIn = activate;
    this.error = "";
  }

  private openRecovery(): void {
    if (this.busy) return;
    this.error = "";
    this.password = "";
    try {
      Api.base = Api.normalizeBase(this.server);
      this.recoverEntry = true;
      this.scroller.scrollTo({ xOffset: 0, yOffset: 0 });
    } catch (error) {
      this.fail(error as Error);
    }
  }

  private exitAccount(mode: string): void {
    if (mode === "recover") {
      this.recoverEntry = false;
      this.error = "";
    } else {
      if (!this.popPage()) this.switchTab(mode === "activate" ? "password" : "profile");
    }
  }

  private async completeAccount(result: AccountSecurityResult, revoked: boolean, message: string): Promise<void> {
    this.error = "";
    if (revoked) {
      this.clearSession();
    } else {
      this.switchTab("profile");
    }
    this.displayedRecoveryCode = result.recoveryCode || "";
    this.accountMessage = this.displayedRecoveryCode ? message : "";
    this.scroller.scrollTo({ xOffset: 0, yOffset: 0 });
    if (!this.displayedRecoveryCode) this.flash(message);
    if (!revoked) {
      const epoch = Api.epoch;
      try {
        const profile = await Api.request<User>("/auth/me");
        Api.assertCurrent(epoch);
        this.user = profile;
      } catch (error) {
        this.fail(error as Error);
      }
    }
  }

  private async login(): Promise<void> {
    let epoch = Api.epoch;
    if (this.busy) return;
    this.error = "";
    const identifier = this.username.trim();
    if (!identifier || !this.password || (this.loginIdentifier === "PHONE" && !isMobileNumber(identifier))) {
      this.error = this.loginIdentifier === "PHONE" ? "请填写11位手机号和密码" : "请填写账号和密码";
      return;
    }
    this.busy = true;
    try {
      Api.base = Api.normalizeBase(this.server);
      const request: LoginRequest = {
        username: identifier,
        password: this.password,
        clientType: "MOBILE",
        identifierType: this.loginIdentifier,
      };
      const result = await Api.post<LoginResult>("/auth/login", request);
      Api.assertCurrent(epoch);
      this.password = "";
      request.password = "";
      if (!result.token) throw new ApiError("未获得移动端会话，请联系管理员");
      Api.token = result.token;
      epoch = Api.epoch;
      this.user = result.user;
      if (result.user.mustChangePassword) {
        this.page = this.activationSignIn && !result.user.phoneMasked ? "activate" : "password";
        await this.saveSettings();
        return;
      }
      if (this.activationSignIn) this.flash("账号已激活，可在账号安全中绑定或更换手机号");
      this.activationSignIn = false;

      this.dispatch.startSession(result.user, result.token, (error: Error) =>
        this.fail(error),
      );
      this.mobility.attach(
        result.user,
        this.getUIContext().getHostContext() as common.UIAbilityContext,
        (error: Error) => this.fail(error),
      );
      this.repairs.initializeContact(result.user.displayName);
      this.bindDrafts();
      this.inbox.start((count: number): void => { this.unreadCount = count; }, async (): Promise<void> => {
        if (this.messageNotifications) await DeviceActions.serviceMessage();
      }, (error: Error): void => { if ((error as ApiError).status === 401) this.fail(error); });
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
    if (this.newPassword.length < 10 || !this.oldPassword) {
      this.error = "请填写原密码，新密码至少10位";
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
      case "messages": return "服务消息";
      case "remediations": return "卫生整改";
      case "remediation-create": return "安排整改";
      case "remediation-assignment": return "调整责任与期限";
      case "remediation-detail": return "整改详情";
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
      case "visitor-detail":
        return "来访详情";
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
      case "activate":
        return "激活账号";
      case "account":
        return "账号安全";
      case "credits":
        return "图片来源";
      default:
        return "寓见";
    }
  }
  @Builder
  private Login() {
    Column({space: 0}) {
      Text('寓见').fontFamily('DormSerif').fontWeight(FontWeight.Medium).fontSize(28).fontColor(this.ink()).width('100%')
      Text(this.activationSignIn ? '注册 / 激活' : '登录').fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink())
        .width('100%').margin({top: 32, bottom: 24})
      if (!this.activationSignIn) {
        Row({space: 8}) {
          Button('账号', {type: ButtonType.Normal}).id('login-account-mode').layoutWeight(1).height(48).fontSize(15).borderRadius(4)
            .backgroundColor(this.loginIdentifier === 'USERNAME' ? this.ink() : this.surface())
            .fontColor(this.loginIdentifier === 'USERNAME' ? this.onPrimary() : this.ink())
            .enabled(!this.busy).onClick(() => this.setLoginMode('USERNAME', false))
          Button('手机号', {type: ButtonType.Normal}).id('login-phone-mode').layoutWeight(1).height(48).fontSize(15).borderRadius(4)
            .backgroundColor(this.loginIdentifier === 'PHONE' ? this.ink() : this.surface())
            .fontColor(this.loginIdentifier === 'PHONE' ? this.onPrimary() : this.ink())
            .enabled(!this.busy).onClick(() => this.setLoginMode('PHONE', false))
        }.width('100%').margin({bottom: 22})
      }
      serviceLabel({text: this.loginIdentifier === 'PHONE' ? '手机号' : '账号', night: this.night})
      TextInput({text: this.username, placeholder: this.loginIdentifier === 'PHONE' ? '请输入11位手机号' : '请输入学校提供的账号'})
        .id('login-username').enterKeyType(EnterKeyType.Next).type(this.loginIdentifier === 'PHONE' ? InputType.PhoneNumber : InputType.Normal)
        .inputFilter(this.loginIdentifier === 'PHONE' ? '[0-9]' : '')
        .onChange((value: string) => this.username = this.loginIdentifier === 'PHONE' ? value.replace(/\D/g, '').slice(0, 11) : value).height(52).fontSize(16).fontColor(this.ink())
        .placeholderColor(this.muted()).backgroundColor(this.surface()).border({width: 1, color: palette(this.night).outline})
        .borderRadius(4).margin({top: 8, bottom: 22}).maxLength(this.loginIdentifier === 'PHONE' ? 11 : 64)
      serviceLabel({text: this.activationSignIn ? '学校发放的临时密码' : '密码', night: this.night})
      TextInput({text: this.password, placeholder: '请输入密码'}).id('login-password').type(InputType.Password)
        .enterKeyType(EnterKeyType.Go).onSubmit(() => this.login())
        .onChange((value: string) => this.password = value).height(52).fontSize(16).fontColor(this.ink())
        .placeholderColor(this.muted()).backgroundColor(this.surface()).border({width: 1, color: palette(this.night).outline})
        .borderRadius(4).margin({top: 8, bottom: 28}).maxLength(100)
      Button(this.busy ? '正在验证…' : this.activationSignIn ? '验证初始账号' : '登录', {type: ButtonType.Normal}).id('login-submit').width('100%').height(52).fontSize(16)
        .backgroundColor(this.ink()).fontColor(this.onPrimary()).borderRadius(4).enabled(!this.busy).onClick(() => this.login())
      Row() {
        Button(this.activationSignIn ? '返回登录' : '注册 / 激活').id('login-activate').height(48).fontSize(14).fontColor(this.ink())
          .backgroundColor(Color.Transparent).enabled(!this.busy).onClick(() => this.setLoginMode('USERNAME', !this.activationSignIn))
        if (!this.activationSignIn) {
          Button('忘记密码').id('login-recover').height(48).fontSize(14).fontColor(this.ink())
            .backgroundColor(Color.Transparent).enabled(!this.busy).onClick(() => this.openRecovery())
        }
        Blank().layoutWeight(1)
        Button(this.night ? '日间' : '夜间').height(48).fontSize(14).fontColor(this.ink())
          .backgroundColor(Color.Transparent).onClick(() => { this.night = !this.night; this.syncSystemBars(); this.saveSettings(); })
      }.margin({top: 12})
      if (this.activationSignIn) {
        Text('使用学校发放的临时账号和密码。未收到凭据，请联系学校账号维护人员。')
          .fontSize(14).fontColor(this.muted()).lineHeight(21).margin({top: 12})
      }
      Button(this.showServer ? '收起服务连接设置' : '服务连接设置').fontSize(14).fontColor(this.muted())
        .backgroundColor(Color.Transparent).height(48).onClick(() => this.showServer = !this.showServer)
      if (this.showServer) {
        serviceField({label: '服务地址', value: this.server, change: (value: string) => this.server = value, placeholder: 'http://电脑地址:8080/api/v1', night: this.night, page: this.page})
        Text('模拟器默认通过本机端口转发连接；真机请填写可访问的服务地址。').fontSize(14).fontColor(this.muted()).lineHeight(21)
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding({left: 24, right: 24, top: 32, bottom: 36})
  }

  @Builder
  private Profile() {
    Column({space: 0}) {
      Text(this.user?.displayName || '').fontSize(28).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%').margin({bottom: 18})
      serviceDetail({label: '当前身份', value: this.manager() ? '宿管' : '学生', night: this.night})
      serviceDetail({label: '登录账号', value: this.user?.username || '', night: this.night})
      Blank().height(16)
      serviceSecondary({label: '卫生整改任务', click: () => this.go('remediations'), night: this.night, busy: this.busy})
      Blank().height(8)
      serviceSecondary({label: this.messageNotifications ? '关闭前台新消息系统提醒' : '开启前台新消息系统提醒', click: async () => {
        if (this.messageNotifications) { this.messageNotifications = false; await this.saveSettings(); return; }
        try { await DeviceActions.notify(this.getUIContext().getHostContext() as common.UIAbilityContext); this.messageNotifications = true; await this.saveSettings(); }
        catch (error) { this.fail(error as Error); }
      }, night: this.night, busy: this.busy})
      Blank().height(16)
      if (!this.manager()) { serviceDetail({label: '当前宿舍', value: this.homeState.room ? this.homeState.room.buildingName + ' ' + this.homeState.room.roomNo : '尚未入住', night: this.night}) }
      Blank().height(24)
      if (!this.manager()) { servicePrimary({label: '查看入住信息', click: () => this.go('room'), night: this.night, page: this.page, busy: this.busy, uploading: false}); Blank().height(12) }
      serviceSecondary({label: '修改密码', click: () => this.go('password'), night: this.night, busy: this.busy})
      Blank().height(8)
      Button('账号安全', {type: ButtonType.Normal}).id('profile-account-security').height(48).fontSize(14).fontColor(this.ink()).backgroundColor(this.surface())
        .border({width: 1, color: palette(this.night).outline}).borderRadius(4).enabled(!this.busy).onClick(() => this.go('account'))
      Row() {
        Text('夜间模式').fontSize(16).fontColor(this.ink()).layoutWeight(1)
        Toggle({type: ToggleType.Switch, isOn: this.night}).selectedColor(this.accent()).onChange((v: boolean) => { this.night = v; this.syncSystemBars(); this.saveSettings(); })
      }.width('100%').height(72).margin({top: 14}).border({width: {top: 1, bottom: 1}, color: this.line()})
      Text('数据范围：本人或负责楼栋').fontSize(14).fontColor(this.muted()).width('100%').margin({top: 20, bottom: 24})
      Button('退出登录', {type: ButtonType.Normal}).id('profile-logout').width('100%').height(50).fontSize(16).fontColor(this.accent()).backgroundColor(this.surface())
        .border({width: 1, color: palette(this.night).outline}).borderRadius(4).enabled(!this.busy).onClick(() => this.logout())
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Password() {
    Column({space: 18}) {
      serviceLabel({text: '原密码 *', night: this.night})
      TextInput({text: this.oldPassword, placeholder: '请输入原密码'}).type(InputType.Password).height(50).backgroundColor(this.surface()).fontColor(this.ink()).border({width: 1, color: palette(this.night).outline}).borderRadius(4).onChange((v: string) => this.oldPassword = v)
      serviceLabel({text: '新密码 *', night: this.night})
      TextInput({text: this.newPassword, placeholder: '至少10位'}).type(InputType.Password)
        .enterKeyType(EnterKeyType.Done).onSubmit(() => this.changePassword()).height(50).backgroundColor(this.surface()).fontColor(this.ink()).border({width: 1, color: palette(this.night).outline}).borderRadius(4).onChange((v: string) => this.newPassword = v)
      Text(this.user?.mustChangePassword ? '首次使用或密码重置后，请先设置个人密码。修改成功后重新登录。' : '修改成功后需要重新登录。').fontSize(14).fontColor(this.muted())
      servicePrimary({label: '确认修改密码', click: () => this.changePassword(), night: this.night, page: this.page, busy: this.busy, uploading: false})
      if (this.user?.mustChangePassword && !this.user?.phoneMasked) {
        Button('验证手机号并激活', {type: ButtonType.Normal}).id('password-phone-activate').height(48).fontSize(14).fontColor(this.ink())
          .backgroundColor(this.surface()).border({width: 1, color: palette(this.night).outline}).borderRadius(4)
          .enabled(!this.busy).onClick(() => this.go('activate'))
      }
      serviceSecondary({label: '退出当前账号', click: () => this.logout(), night: this.night, busy: this.busy})
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Credits() {
    Column({space: 18}) {
      Image($rawfile('campus-courtyard.jpg')).width('100%').height(200).objectFit(ImageFit.Cover)
      Text('校园氛围示意').fontSize(28).fontWeight(FontWeight.Medium).fontColor(this.ink())
      Text('摄影：Nick Allen（Wikimedia Commons 用户 Nickknack00）\n作品：Lanman Wright courtyard elevated.JPG\n许可：CC BY-SA 3.0\n本地处理：缩放为1200×801并压缩；未调色或合成。').fontSize(16).fontColor(this.ink()).lineHeight(26)
      Text('原始来源：https://commons.wikimedia.org/wiki/File:Lanman_Wright_courtyard_elevated.JPG\n\n许可：https://creativecommons.org/licenses/by-sa/3.0/').fontSize(14).fontColor(this.muted()).lineHeight(24).copyOption(CopyOptions.LocalDevice)
      Text('图片仅表达校园生活氛围，不代表本平台服务学校的真实宿舍。').fontSize(14).fontColor(this.muted()).lineHeight(24)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private Header() {
    Row({space: 12}) {
      if (this.hasPrevious || this.user?.mustChangePassword) {
        Button('‹').id('page-back').fontSize(28).fontColor(this.ink()).backgroundColor(Color.Transparent).width(48).height(48)
          .onClick(() => this.onBackPress())
      }
      Text(this.pageTitle()).fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.ink()).layoutWeight(1)
      if (!this.user?.mustChangePassword) { Button('消息' + (this.unreadCount ? ' ' + this.unreadCount : '')).id('service-messages').enabled(!this.busy && !this.hygieneState.busy && !this.repairState.busy && !this.serviceState.busy && !this.visitorState.busy).height(48).fontSize(14).backgroundColor(Color.Transparent).fontColor(this.accent()).onClick(() => this.go('messages')) }
      if (this.manager() && !this.user?.mustChangePassword && !this.displayedRecoveryCode) {
        Button('我的').id('open-profile').enabled(!this.busy && !this.hygieneState.busy && !this.repairState.busy && !this.serviceState.busy && !this.visitorState.busy).height(48).fontSize(14).backgroundColor(Color.Transparent).fontColor(this.page === 'profile' ? this.accent() : this.ink()).onClick(() => this.switchTab('profile'))
      }
    }.width('100%').padding({left: 16, right: 16, top: 4, bottom: 8}).backgroundColor(this.surface()).expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP]).border({width: {bottom: 1}, color: this.line()})
  }

  private tabSymbol(page: string): Resource {
    switch (page) {
      case 'home': return $r('sys.symbol.house');
      case 'route': return $r('sys.symbol.route_plan');
      case 'repairs': return $r('sys.symbol.doc_text');
      case 'hygiene': return $r('sys.symbol.checkmark');
      default: return $r('sys.symbol.person');
    }
  }

  @Builder
  private BottomTabs() {
    Row() {
      ForEach(bottomTabs(this.manager()), (tab: TabLink) => {
        Button({type: ButtonType.Normal}) {
          Column({space: 2}) {
            SymbolGlyph(this.tabSymbol(tab.page)).fontSize(18).fontColor([activeTab(this.page) === tab.page ? this.accent() : this.muted()])
            Text(tab.label).fontSize(14).fontColor(activeTab(this.page) === tab.page ? this.accent() : this.muted())
          }
        }.id(tab.id).layoutWeight(1).height(58).backgroundColor(Color.Transparent).borderRadius(0)
          .onClick(() => this.switchTab(tab.page))
      }, (tab: TabLink) => tab.id + tab.label)
    }.width('100%').backgroundColor(this.surface()).expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.BOTTOM]).border({width: {top: 1}, color: this.line()})
  }

  build() {
    Column() {
      if (this.user && !this.displayedRecoveryCode) { this.Header() }
      if (this.error) {
        Row({space: 8}) {
          Text(this.error).id('error-message').fontSize(14).lineHeight(22).fontColor(this.night ? '#F1A99C' : '#A13E37').layoutWeight(1)
          Button('×').width(48).height(48).fontColor(this.ink()).backgroundColor(Color.Transparent).onClick(() => this.error = '')
        }.width('100%').padding({left: 16, right: 8, top: 8, bottom: 8}).backgroundColor(this.night ? '#442E2B' : '#F7EAE6')
      }
      if (this.currentLoading()) {
        Row({space: 8}) { LoadingProgress().width(20).height(20).color(this.accent()); Text('正在更新…').fontSize(14).fontColor(this.muted()) }
          .width('100%').padding(8).justifyContent(FlexAlign.Center)
      }
      if (this.user && this.canRefreshWorkspace()) {
        Refresh({refreshing: $$this.refreshing}) { this.WorkspaceScroll() }
          .layoutWeight(1).width('100%').onRefreshing(async () => {
            try { await this.refreshWorkspace(); } finally { this.refreshing = false; }
          })
      } else {
        Column(){ this.WorkspaceScroll() }.layoutWeight(1).width('100%')
      }
      if (this.showsBottomTabs()) { this.BottomTabs() }
    }.width('100%').height('100%').backgroundColor(this.bg()).expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
  @Builder
  private WorkspaceScroll() {
      Scroll(this.scroller) {
        Column() {
          if (this.displayedRecoveryCode) {
            RecoveryCodeView({night: this.night, code: this.displayedRecoveryCode, message: this.accountMessage,
              done: () => { this.displayedRecoveryCode = ''; this.accountMessage = ''; }})
          } else if (this.user) { this.Content() }
          else if (this.recoverEntry) { this.AccountArea('recover') }
          else { this.Login() }
        }.width('100%').constraintSize({maxWidth: 720}).padding({left: this.user ? 16 : 0, right: this.user ? 16 : 0, top: this.user ? 16 : 0, bottom: this.showsBottomTabs() ? 28 : 48}).alignItems(HorizontalAlign.Start)
      }.id('page-scroll').height('100%').width('100%').scrollBar(BarState.Auto).align(Alignment.Top)
  }
  @Builder
  private AccountArea(mode: string) {
    AccountWorkspace({screen: mode, night: this.night, user: this.user, busy: $busy,
      onExit: () => this.exitAccount(mode), onError: (message: string) => this.error = message,
      onFail: (error: Error) => this.fail(error),
      onComplete: (result: AccountSecurityResult, revoked: boolean, message: string) => this.completeAccount(result, revoked, message)})
  }
  @Builder
  private Content() {
    if (this.manager() && ['hygiene','remediations','visitors'].includes(this.page)) {
      Row() {
        ForEach(['hygiene','remediations','visitors'], (name: string, index: number) => {
          Button(['卫生巡检','卫生整改','访客登记'][index], {type: ButtonType.Normal}).layoutWeight(1).height(48).borderRadius(4)
            .fontColor(this.page === name ? this.onPrimary() : this.ink()).backgroundColor(this.page === name ? this.ink() : this.surface())
            .onClick(() => this.switchTab(name))
        })
      }.width('100%').margin({bottom: 16})
    }
    if (['messages', 'remediations', 'remediation-create', 'remediation-detail', 'remediation-assignment'].includes(this.page)) {
      ServiceCenterWorkspace({state: this.serviceState, page: this.page, night: this.night, manager: this.manager(), host: this.host, controller: this.serviceCenter, photos: this.photoDrafts, onRecheck: (source: Hygiene, taskId: string) => this.hygiene.recheck(source, taskId)})
    } else if (this.page === 'home' || this.page === 'room') {
      HomeWorkspace({state: this.homeState, page: this.page, night: this.night, user: this.user, host: this.host,
        onRemediation: (id: string) => this.serviceCenter.openRemediation(id), onRepairListReset: () => { this.repairState.repairPage = 1; this.repairState.repairFilter = ''; }, onHygieneListReset: () => { this.hygieneState.hygienePage = 1; }})
    } else if (['repairs', 'repair-create', 'detail', 'action'].includes(this.page)) {
      RepairWorkspace({state: this.repairState, page: this.page, night: this.night, user: this.user, room: this.homeState.room, host: this.host, controller: this.repairs, photoDrafts: this.photoDrafts, dispatch: this.dispatch})
    } else if (['visitors', 'visitor-detail', 'visitor-create'].includes(this.page) && this.manager()) {
      VisitorWorkspace({state: this.visitorState, page: this.page, night: this.night, host: this.host, controller: this.visitors})
    } else if (['hygiene', 'hygiene-create'].includes(this.page)) {
      HygieneWorkspace({state: this.hygieneState, page: this.page, night: this.night, user: this.user, host: this.host, controller: this.hygiene, photoDrafts: this.photoDrafts, onRemediate: (item: Hygiene): void => { this.serviceCenter.prepare(item); }})
    } else if (this.page === 'route' && this.manager()) {
      RoutePage({onCalibrate: () => this.go('mobility'), controller: this.dispatch, night: this.night, onOpenRepair: (repair: Repair) => this.openRepair(repair), onFinish: (repair: Repair) => { this.repairState.selected = repair; this.repairs.beginAction('finish'); }})
    } else if (this.page === 'mobility' && this.manager()) {
      MobilityPage({controller: this.mobility, dispatch: this.dispatch, night: this.night, onOpenRoute: () => this.go('route')})
    } else if (this.page === 'password') { this.Password() }
    else if (this.page === 'activate') { this.AccountArea('activate') }
    else if (this.page === 'account') { this.AccountArea('account') }
    else if (this.page === 'credits') { this.Credits() }
    else { this.Profile() }
  }
}
