import { BackPanels } from '../NavigationStack';
import { palette } from '../../components/Palette';
import { serviceLabel, serviceDetail } from '../../components/ServiceParts';
import {
  AccountSecurityResult, ActivateRequest, PasswordRecoveryChallengeRequest, PasswordRecoveryCompleteRequest,
  PasswordRecoveryVerifyRequest, PasswordRecoveryVerifyResult, PhoneChallengeRequest, PhoneChallengeResult,
  PhoneChangeRequest, PhoneCodeRequest, User
} from '../../models/ApiModels';
import { Api } from '../../services/Api';
import { isMobileNumber } from '../../services/Labels';
import { accountCode, cooldownSeconds } from './AccountInput';

interface AccountFieldArgs {
  id: string;
  label: string;
  value: string;
  change: (value: string) => void;
  type: InputType;
  maximum: number;
}

@Component
export struct AccountWorkspace {
  @Consume backPanels: BackPanels;
  private releaseBack: () => void = () => {};
  @Prop screen: string = '';
  @Prop night: boolean = false;
  @Prop user: User | null = null;
  @Link busy: boolean;
  onExit: () => void = () => {};
  onError: (message: string) => void = () => {};
  onFail: (error: Error) => void = () => {};
  onComplete: (result: AccountSecurityResult, revoked: boolean, message: string) => void = () => {};
  @State operation: string = '';
  @State phone: string = '';
  @State currentPassword: string = '';
  @State newPassword: string = '';
  @State firstCode: string = '';
  @State secondCode: string = '';
  @State firstImage: string = '';
  @State firstId: string = '';
  @State secondImage: string = '';
  @State secondId: string = '';
  @State resetToken: string = '';
  @State recoveryRequired: boolean = false;
  @State recoveryInput: string = '';
  @State firstWait: number = 0;
  @State secondWait: number = 0;
  private firstSent: number = 0;
  private secondSent: number = 0;
  private timer: number = -1;

  aboutToAppear(): void {
    this.releaseBack = this.backPanels.register((): boolean => {
      if (this.busy) return true;
      if (this.resetToken || this.operation) { this.choose(''); return true; }
      return false;
    });
    this.timer = setInterval(() => this.updateCountdown(), 1000);
  }

  aboutToDisappear(): void {
    this.releaseBack();
    clearInterval(this.timer);
    this.clearInputs();
  }

  private ink(): string { return palette(this.night).ink; }
  private kind(): string {
    if (this.screen === 'activate' || this.screen === 'recover') return this.screen;
    return this.user?.phoneMasked ? this.operation : 'bind';
  }
  private needsPhone(kind: string): boolean {
    return kind === 'activate' || kind === 'recover' || kind === 'bind' || kind === 'change';
  }
  private updateCountdown(): void {
    const now = Date.now();
    this.firstWait = cooldownSeconds(this.firstSent, now);
    this.secondWait = cooldownSeconds(this.secondSent, now);
  }
  private clearInputs(): void {
    this.phone = '';
    this.currentPassword = '';
    this.newPassword = '';
    this.firstCode = '';
    this.secondCode = '';
    this.firstImage = '';
    this.firstId = '';
    this.secondImage = '';
    this.secondId = '';
    this.resetToken = '';
    this.recoveryRequired = false;
    this.recoveryInput = '';
    this.firstSent = 0;
    this.secondSent = 0;
    this.updateCountdown();
  }
  private choose(operation: string): void {
    if (this.busy) return;
    this.clearInputs();
    this.operation = operation;
    this.onError('');
  }
  private editPhone(value: string): void {
    value = value.replace(/\D/g, '').slice(0, 11);
    if (value === this.phone) return;
    this.phone = value;
    if (this.kind() === 'change') {
      this.secondImage = '';
      this.secondId = '';
      this.secondCode = '';
      this.secondSent = 0;
    } else {
      this.firstImage = '';
      this.firstId = '';
      this.firstCode = '';
      this.firstSent = 0;
    }
    this.updateCountdown();
  }
  private purpose(kind: string, second: boolean): string {
    if (kind === 'activate') return 'ACTIVATE';
    if (kind === 'bind') return 'BIND';
    if (kind === 'replace') return 'REPLACE_RECOVERY_CODE';
    return second ? 'CHANGE_PHONE_NEW' : 'CHANGE_PHONE_OLD';
  }
  private async sendCode(second: boolean): Promise<void> {
    if (this.busy || (second ? this.secondWait : this.firstWait) > 0) return;
    const kind = this.kind();
    const phone = this.phone.trim();
    const needsDestination = this.needsPhone(kind) && (kind !== 'change' || second);
    if (needsDestination && !isMobileNumber(phone)) { this.onError('请输入11位手机号'); return; }
    if (kind !== 'activate' && kind !== 'recover' && !this.currentPassword) {
      this.onError('请输入当前密码');
      return;
    }
    const epoch = Api.epoch;
    this.onError('');
    this.busy = true;
    try {
      let result: PhoneChallengeResult;
      if (kind === 'recover') {
        const body: PasswordRecoveryChallengeRequest = { phone: phone, clientType: 'MOBILE' };
        result = await Api.post<PhoneChallengeResult>('/auth/password-recovery/challenges', body);
      } else {
        const body: PhoneChallengeRequest = { purpose: this.purpose(kind, second) };
        if (needsDestination) body.phone = phone;
        if (kind !== 'activate') body.currentPassword = this.currentPassword;
        result = await Api.post<PhoneChallengeResult>('/auth/phone/challenges', body);
      }
      Api.assertCurrent(epoch);
      if (second) {
        this.secondId = result.challengeId;
        this.secondCode = '';
        this.secondImage = result.delivery === 'GRAPHIC' ? result.captchaImage || '' : '';
        this.secondSent = this.secondImage ? 0 : Date.now();
      } else {
        this.firstId = result.challengeId;
        this.firstCode = '';
        this.firstImage = result.delivery === 'GRAPHIC' ? result.captchaImage || '' : '';
        this.firstSent = this.firstImage ? 0 : Date.now();
      }
      this.updateCountdown();
    } catch (error) {
      this.onFail(error as Error);
    } finally {
      if (epoch === Api.epoch) this.busy = false;
    }
  }
  private async submit(): Promise<void> {
    if (this.busy) return;
    const kind = this.kind();
    const first = accountCode(this.firstCode);
    const second = accountCode(this.secondCode);
    const verifyRecovery = kind === 'recover' && !this.resetToken;
    const completeRecovery = kind === 'recover' && !!this.resetToken;
    const graphicChange = kind === 'change' && !!this.secondImage;
    if (!completeRecovery && !graphicChange && (!this.firstId || !first)) {
      this.onError('请先获取并填写验证码');
      return;
    }
    if (kind === 'change' && (!this.secondId || !second)) {
      this.onError('请获取并填写新手机号验证码');
      return;
    }
    if ((kind === 'activate' || completeRecovery) && this.newPassword.trim().length < 10) {
      this.onError('请设置至少10位的新密码');
      return;
    }
    if (completeRecovery && this.recoveryRequired && !this.recoveryInput.trim()) {
      this.onError('请输入离线保存的安全恢复码');
      return;
    }
    const epoch = Api.epoch;
    this.onError('');
    this.busy = true;
    try {
      if (verifyRecovery) {
        const body: PasswordRecoveryVerifyRequest = { challengeId: this.firstId, code: first, clientType: 'MOBILE' };
        const result = await Api.post<PasswordRecoveryVerifyResult>('/auth/password-recovery/verify', body);
        Api.assertCurrent(epoch);
        this.firstImage = '';
        this.firstId = '';
        this.firstCode = '';
        this.resetToken = result.resetToken;
        this.recoveryRequired = result.recoveryCodeRequired;
        return;
      }
      if (completeRecovery) {
        const body: PasswordRecoveryCompleteRequest = {
          resetToken: this.resetToken, newPassword: this.newPassword, clientType: 'MOBILE'
        };
        if (this.recoveryRequired) body.recoveryCode = this.recoveryInput.trim();
        await Api.post<null>('/auth/password-recovery/complete', body);
        Api.assertCurrent(epoch);
        const result: AccountSecurityResult = {};
        this.onComplete(result, true, '密码已重设，请重新登录');
        return;
      }
      let result: AccountSecurityResult;
      let message: string;
      if (kind === 'activate') {
        const body: ActivateRequest = { challengeId: this.firstId, code: first, newPassword: this.newPassword };
        result = await Api.post<AccountSecurityResult>('/auth/activate', body);
        message = '账号已激活，请重新登录';
      } else if (kind === 'change') {
        const body: PhoneChangeRequest = {
          newChallengeId: this.secondId, newCode: second
        };
        if (!graphicChange) { body.oldChallengeId = this.firstId; body.oldCode = first; }
        result = await Api.post<AccountSecurityResult>('/auth/phone/change', body);
        message = '手机号已更换，请重新登录';
      } else {
        const body: PhoneCodeRequest = { challengeId: this.firstId, code: first };
        const replace = kind === 'replace';
        result = await Api.post<AccountSecurityResult>(replace ? '/auth/recovery-code/replace' : '/auth/phone/bind', body);
        message = replace ? '安全恢复码已生成' : '手机号已绑定';
      }
      Api.assertCurrent(epoch);
      this.onComplete(result, kind === 'activate' || kind === 'change', message);
    } catch (error) {
      this.onFail(error as Error);
    } finally {
      if (epoch === Api.epoch) this.busy = false;
    }
  }

  @Builder
  private Field($$: AccountFieldArgs) {
    Column({space: 8}) {
      serviceLabel({text: $$.label, night: this.night})
      TextInput({text: $$.value, placeholder: $$.label}).id($$.id).type($$.type)
        .inputFilter($$.type === InputType.PhoneNumber || $$.type === InputType.Number ? '[0-9]' : '')
        .maxLength($$.maximum).enabled(!this.busy).onChange($$.change)
        .fontSize(16).fontColor(this.ink()).placeholderColor(palette(this.night).muted)
        .height(50).padding({left: 12, right: 12}).borderRadius(4)
        .backgroundColor(palette(this.night).surface).border({width: 1, color: palette(this.night).outline})
    }.alignItems(HorizontalAlign.Start).width('100%')
  }
  @Builder
  private SendCode(second: boolean) {
    if (second ? this.secondImage : this.firstImage) {
      Image(second ? this.secondImage : this.firstImage).width(160).height(56).objectFit(ImageFit.Contain)
        .accessibilityText('图形验证码，点击换一张').enabled(!this.busy).onClick(() => this.sendCode(second))
    }
    Button((second ? this.secondWait : this.firstWait) > 0
      ? '重新发送（' + (second ? this.secondWait : this.firstWait) + '秒）'
      : (second ? this.secondImage : this.firstImage) ? '换一张' : second ? '获取新手机号验证码' : this.kind() === 'change' ? '获取原手机号验证码' : '获取验证码', {type: ButtonType.Normal})
      .id(second ? 'account-send-new' : 'account-send').fontSize(14).height(48)
      .fontColor(this.ink()).backgroundColor(palette(this.night).surface).borderRadius(4)
      .border({width: 1, color: palette(this.night).outline})
      .enabled(!this.busy && (second ? this.secondWait === 0 : this.firstWait === 0))
      .onClick(() => this.sendCode(second))
  }
  @Builder
  private Form() {
    if (this.kind() !== 'activate' && this.kind() !== 'recover') {
      this.Field({id: 'account-current-password', label: '当前密码', value: this.currentPassword,
        change: (value: string) => this.currentPassword = value, type: InputType.Password, maximum: 128})
    }
    if (this.needsPhone(this.kind()) && !this.resetToken) {
      this.Field({id: 'account-phone', label: this.kind() === 'change' ? '新手机号' : '手机号', value: this.phone,
        change: (value: string) => this.editPhone(value), type: InputType.PhoneNumber, maximum: 11})
    }
    if (this.kind() === 'change') {
      this.SendCode(true)
      if (this.secondId) {
        this.Field({id: 'account-new-code', label: this.secondImage ? '图形验证码' : '新手机号短信验证码', value: this.secondCode,
          change: (value: string) => this.secondCode = value, type: this.secondImage ? InputType.Normal : InputType.Number,
          maximum: this.secondImage ? 4 : 6})
      }
    }
    if (!this.resetToken && (this.kind() !== 'change' || (this.secondId && !this.secondImage))) {
      this.SendCode(false)
      if (this.firstId) {
        this.Field({id: 'account-code', label: this.firstImage ? '图形验证码' : this.kind() === 'change' ? '原手机号短信验证码' : '短信验证码',
          value: this.firstCode, change: (value: string) => this.firstCode = value,
          type: this.firstImage ? InputType.Normal : InputType.Number, maximum: this.firstImage ? 4 : 6})
      }
    }
    if (this.kind() === 'activate' || this.resetToken) {
      this.Field({id: 'account-new-password', label: '新密码', value: this.newPassword,
        change: (value: string) => this.newPassword = value, type: InputType.NEW_PASSWORD, maximum: 128})
      Text('至少10位').fontSize(14).fontColor(palette(this.night).muted)
    }
    if (this.resetToken && this.recoveryRequired) {
      this.Field({id: 'account-recovery-input', label: '安全恢复码', value: this.recoveryInput,
        change: (value: string) => this.recoveryInput = value, type: InputType.Password, maximum: 128})
    }
    Button(this.busy ? '正在处理…' : this.kind() === 'recover' && !this.resetToken ? '验证并继续' : '确认提交', {type: ButtonType.Normal})
      .id('account-submit').width('100%').height(50).fontSize(16).borderRadius(4)
      .fontColor(palette(this.night).onPrimary).backgroundColor(this.ink()).enabled(!this.busy)
      .onClick(() => this.submit())
  }
  build() {
    Column({space: 16}) {
      if (this.screen === 'recover') {
        Text('找回密码').fontSize(24).fontWeight(FontWeight.Medium).fontColor(this.ink()).width('100%')
      }
      if (this.screen === 'account') {
        serviceDetail({label: '登录手机号', value: this.user?.phoneMasked || '未绑定', night: this.night})
        serviceDetail({label: '安全恢复码', value: this.user?.recoveryCodeConfigured ? '已设置' : '未设置', night: this.night})
        if (this.user?.phoneMasked) {
          Button('更换手机号', {type: ButtonType.Normal}).id('account-change-mode').height(48).fontSize(16).borderRadius(4)
            .fontColor(this.operation === 'change' ? palette(this.night).onPrimary : this.ink())
            .backgroundColor(this.operation === 'change' ? this.ink() : palette(this.night).surface)
            .enabled(!this.busy).onClick(() => this.choose('change'))
          Button(this.user?.recoveryCodeConfigured ? '重签安全恢复码' : '生成安全恢复码', {type: ButtonType.Normal})
            .id('account-replace-mode').height(48).fontSize(16).borderRadius(4)
            .fontColor(this.operation === 'replace' ? palette(this.night).onPrimary : this.ink())
            .backgroundColor(this.operation === 'replace' ? this.ink() : palette(this.night).surface)
            .enabled(!this.busy).onClick(() => this.choose('replace'))
        }
      }
      if (this.kind()) { this.Form() }
      if (this.screen === 'activate' || this.screen === 'recover') {
        Text('无法收码、没有初始凭据或遗失恢复码时，请联系学校账号维护人员核验身份。')
          .fontSize(14).fontColor(palette(this.night).muted).lineHeight(22).width('100%')
      }
      Button(this.screen === 'activate' ? '改用密码首次登录' : this.screen === 'recover' ? '返回登录' : '返回我的')
        .id('account-back').height(48).fontSize(14).fontColor(this.ink()).backgroundColor(Color.Transparent)
        .enabled(!this.busy).onClick(this.onExit)
    }.width('100%').alignItems(HorizontalAlign.Start)
      .padding({left: this.screen === 'recover' ? 24 : 0, right: this.screen === 'recover' ? 24 : 0,
        top: this.screen === 'recover' ? 32 : 0, bottom: 24})
  }
}

@Component
export struct RecoveryCodeView {
  @Prop night: boolean = false;
  @Prop code: string = '';
  @Prop message: string = '';
  done: () => void = () => {};
  build() {
    Column({space: 16}) {
      Text(this.message).fontSize(24).fontWeight(FontWeight.Medium).fontColor(palette(this.night).ink).width('100%')
      Text('将恢复码离线保存。找回密码时需同时提供验证码与这枚恢复码，本页关闭后无法再次查看。')
        .fontSize(15).fontColor(palette(this.night).muted).lineHeight(24).width('100%')
      Text(this.code).id('account-recovery-code').fontSize(18).fontColor(palette(this.night).ink).copyOption(CopyOptions.LocalDevice)
        .width('100%').wordBreak(WordBreak.BREAK_ALL).padding(16)
        .backgroundColor(palette(this.night).surface).border({width: 1, color: palette(this.night).line})
      Button('已保存恢复码', {type: ButtonType.Normal}).id('account-recovery-done').width('100%').height(50).borderRadius(4)
        .backgroundColor(palette(this.night).ink).fontColor(palette(this.night).onPrimary).onClick(this.done)
    }.width('100%').alignItems(HorizontalAlign.Start).padding(24)
  }
}
