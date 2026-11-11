import { abilityAccessCtrl, common, Permissions, wantAgent } from '@kit.AbilityKit';
import { geoLocationManager } from '@kit.LocationKit';
import { sensor } from '@kit.SensorServiceKit';
import { batteryInfo } from '@kit.BasicServicesKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { notificationManager } from '@kit.NotificationKit';
import { PositionObservation, StepBaseline } from './MobilityEstimator';

export interface DeviceMovement {
  position: PositionObservation;
  stepDelta?: number;
  moving: boolean;
}

// All subscriptions belong to one controller run. Permissions and native callbacks never write UI state.
export class SystemMobility {
  private generation: number = 0;
  private locationCallback: ((location: geoLocationManager.Location) => void) | undefined = undefined;
  private stepCallback: ((value: sensor.PedometerResponse) => void) | undefined = undefined;
  private accelerationCallback: ((value: sensor.AccelerometerResponse) => void) | undefined = undefined;
  private cancelCallback: ((value: backgroundTaskManager.ContinuousTaskCancelInfo) => void) | undefined = undefined;
  private suspendCallback: ((value: backgroundTaskManager.ContinuousTaskSuspendInfo) => void) | undefined = undefined;
  private permissionCallback: ((value: abilityAccessCtrl.PermissionStateChangeInfo) => void) | undefined = undefined;
  private enabledCallback: ((enabled: boolean) => void) | undefined = undefined;
  private locationError: ((error: geoLocationManager.LocationError) => void) | undefined = undefined;
  private access: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
  private watchedPermissions: Permissions[] = ['ohos.permission.APPROXIMATELY_LOCATION', 'ohos.permission.LOCATION',
    'ohos.permission.LOCATION_IN_BACKGROUND', 'ohos.permission.ACTIVITY_MOTION'];
  private context: common.UIAbilityContext | undefined = undefined;
  private steps: StepBaseline = new StepBaseline();
  private pendingSteps: number = 0;
  private hasStepDelta: boolean = false;
  private movingUntil: number = 0;
  private background: boolean = false;
  private taskId: number = -1;
  private lastEmit: number = 0;
  private requestInterval: number = 10;
  isBackgroundEnabled(): boolean { return this.background; }
  isCollecting(): boolean { return this.locationCallback !== undefined; }
  private async permission(context: common.UIAbilityContext, permissions: Permissions[]): Promise<boolean> {
    const result = await this.access.requestPermissionsFromUser(context, permissions);
    return result.authResults.every((status: number): boolean => status === 0);
  }
  async start(context: common.UIAbilityContext, background: boolean, callback: (value: DeviceMovement) => void,
    notice: (message: string, stopped?: boolean) => void): Promise<void> {
    this.stop(); this.context = context;
    const generation = this.generation;
    try {
      const positionAllowed = await this.permission(context, ['ohos.permission.APPROXIMATELY_LOCATION', 'ohos.permission.LOCATION']);
      if (generation !== this.generation) return;
      if (!positionAllowed || !geoLocationManager.isLocationEnabled()) {
        notice('定位未授权或系统定位关闭；可继续手工确认锚点与办理工单'); return;
      }
      this.permissionCallback = (value: abilityAccessCtrl.PermissionStateChangeInfo): void => {
        if (generation !== this.generation || value.change !== abilityAccessCtrl.PermissionStateChangeType.PERMISSION_REVOKED_OPER) return;
        this.stop(); notice('设备权限已撤回，采集与后台持续任务已停止；可手工办理', true);
      };
      this.enabledCallback = (enabled: boolean): void => {
        if (generation !== this.generation || enabled) return;
        this.stop(); notice('系统定位已关闭，采集已停止；可手工确认地点', true);
      };
      this.locationError = (_error: geoLocationManager.LocationError): void => {
        if (generation !== this.generation) return;
        this.stop(); notice('系统定位暂时不可用，采集已停止；可手工确认地点后重新开启', true);
      };
      this.access.on('selfPermissionStateChange', this.watchedPermissions, this.permissionCallback);
      geoLocationManager.on('locationEnabledChange', this.enabledCallback);
      geoLocationManager.on('locationError', this.locationError);
      const motionAllowed = await this.permission(context, ['ohos.permission.ACTIVITY_MOTION']);
      if (generation !== this.generation) return;
      if (!motionAllowed) notice('计步未授权，位置功能仍可使用；不会把缺失计步当成0步');
      if (motionAllowed) {
        try {
          await sensor.getSingleSensor(sensor.SensorId.PEDOMETER);
          if (generation !== this.generation) return;
          this.stepCallback = (value: sensor.PedometerResponse): void => {
            if (generation !== this.generation) return;
            const delta = this.steps.delta(value.steps, value.timestamp / 1000000);
            if (delta !== undefined) { this.pendingSteps = Math.min(240, this.pendingSteps + delta); this.hasStepDelta = true; }
            else { this.pendingSteps = 0; this.hasStepDelta = false; }
          };
          sensor.on(sensor.SensorId.PEDOMETER, this.stepCallback, { interval: 1000000000 });
        } catch (_) { notice('此设备不支持计步；速度保持按可用位置来源计算'); }
      }
      try {
        await sensor.getSingleSensor(sensor.SensorId.ACCELEROMETER);
        if (generation !== this.generation) return;
        this.accelerationCallback = (value: sensor.AccelerometerResponse): void => {
          if (generation !== this.generation) return;
          // Keep only a short-lived movement flag. Never integrate acceleration into a trajectory.
          const magnitude = Math.sqrt(value.x * value.x + value.y * value.y + value.z * value.z);
          if (Math.abs(magnitude - 9.81) > 1.5) this.movingUntil = Date.now() + 10000;
        };
        sensor.on(sensor.SensorId.ACCELEROMETER, this.accelerationCallback, { interval: 200000000 });
      } catch (_) { notice('运动传感器不可用，保留定位与手工位置'); }
      this.locationCallback = (location: geoLocationManager.Location): void => {
        if (generation !== this.generation) return;
        const now = Date.now(), lowBattery = batteryInfo.batterySOC >= 0 && batteryInfo.batterySOC < 20;
        const interval = lowBattery ? 30000 : now < this.movingUntil || this.pendingSteps > 0 ? 10000 : 20000;
        const requestSeconds = interval / 1000;
        if (requestSeconds !== this.requestInterval && this.locationCallback) {
          try {
            geoLocationManager.off('locationChange', this.locationCallback);
            this.requestInterval = requestSeconds;
            geoLocationManager.on('locationChange', { interval: requestSeconds, locationScenario: geoLocationManager.UserActivityScenario.DAILY_LIFE_SERVICE,
              needPoi: false }, this.locationCallback);
          } catch (_) { this.stop(); notice('系统位置订阅中断，已停止采集；可手工办理', true); return; }
        }
        if (now - this.lastEmit < interval) return;
        this.lastEmit = now;
        const stepDelta = this.hasStepDelta ? this.pendingSteps : undefined; this.pendingSteps = 0; this.hasStepDelta = false;
        callback({ position: { latitude: location.latitude, longitude: location.longitude, accuracyMeters: location.accuracy,
          observedMs: location.timeStamp, elapsedMs: location.timeSinceBoot / 1000000,
          speedMps: location.speed, speedAccuracyMps: location.speedAccuracy },
          stepDelta: stepDelta, moving: now < this.movingUntil });
      };
      try {
        geoLocationManager.on('locationChange', { interval: 10, locationScenario: geoLocationManager.UserActivityScenario.DAILY_LIFE_SERVICE,
          needPoi: false }, this.locationCallback);
        if (background) await this.startBackground(context, generation, notice);
      } catch (_) { this.stop(); notice('位置订阅未启动，可使用手工锚点；请检查系统定位和权限'); }
    } catch (_) {
      if (generation !== this.generation) return;
      this.stop(); notice('设备能力暂不可用，已停止部分启动的订阅；可手工确认地点');
    }
  }
  private async startBackground(context: common.UIAbilityContext, generation: number, notice: (message: string, stopped?: boolean) => void): Promise<void> {
    try {
      const allowed = await this.permission(context, ['ohos.permission.LOCATION_IN_BACKGROUND']);
      if (generation !== this.generation) return;
      if (!allowed) { notice('后台位置未授权；离开前台会暂停采集'); return; }
      if (!await notificationManager.isNotificationEnabled()) await notificationManager.requestEnableNotification(context);
      if (generation !== this.generation) return;
      const agent = await wantAgent.getWantAgent({ wants: [{ bundleName: 'com.dormlink.residence', abilityName: 'EntryAbility' }],
        operationType: wantAgent.OperationType.START_ABILITY, requestCode: 41, wantAgentFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG] });
      if (generation !== this.generation) return;
      const task = await backgroundTaskManager.startBackgroundRunning(context, ['location'], agent);
      if (task.continuousTaskId === undefined) {
        await backgroundTaskManager.stopBackgroundRunning(context); throw new Error('系统未返回可停止的持续任务标识');
      }
      if (generation !== this.generation) {
        if (task.continuousTaskId !== undefined) await backgroundTaskManager.stopBackgroundRunning(context, task.continuousTaskId);
        return;
      }
      this.taskId = task.continuousTaskId ?? -1;
      this.background = true;
      this.cancelCallback = (value: backgroundTaskManager.ContinuousTaskCancelInfo): void => {
        if (generation !== this.generation || value.id !== this.taskId) return;
        this.stop(); notice('系统已停止后台出行；请回到应用重新开启，或手工办理', true);
      };
      this.suspendCallback = (value: backgroundTaskManager.ContinuousTaskSuspendInfo): void => {
        if (generation !== this.generation || value.continuousTaskId !== this.taskId || !value.suspendState) return;
        this.stop(); notice('系统已挂起后台出行，设备采集已停止；请回到应用明确重新开启', true);
      };
      backgroundTaskManager.on('continuousTaskCancel', this.cancelCallback);
      backgroundTaskManager.on('continuousTaskSuspend', this.suspendCallback);
      notice('后台位置持续任务已开启；通知栏可返回出行页，点击暂停或结束停止采集');
    } catch (_) { this.background = false; notice('后台持续任务不可用；仅在前台采集，离开前台将暂停'); }
  }
  stop(): void {
    this.generation++;
    if (this.locationCallback) { try { geoLocationManager.off('locationChange', this.locationCallback); } catch (_) {} }
    if (this.stepCallback) { try { sensor.off(sensor.SensorId.PEDOMETER, this.stepCallback); } catch (_) {} }
    if (this.accelerationCallback) { try { sensor.off(sensor.SensorId.ACCELEROMETER, this.accelerationCallback); } catch (_) {} }
    if (this.cancelCallback) { try { backgroundTaskManager.off('continuousTaskCancel', this.cancelCallback); } catch (_) {} }
    if (this.suspendCallback) { try { backgroundTaskManager.off('continuousTaskSuspend', this.suspendCallback); } catch (_) {} }
    if (this.permissionCallback) { try { this.access.off('selfPermissionStateChange', this.watchedPermissions, this.permissionCallback); } catch (_) {} }
    if (this.enabledCallback) { try { geoLocationManager.off('locationEnabledChange', this.enabledCallback); } catch (_) {} }
    if (this.locationError) { try { geoLocationManager.off('locationError', this.locationError); } catch (_) {} }
    if (this.background && this.context && this.taskId >= 0) backgroundTaskManager.stopBackgroundRunning(this.context, this.taskId).catch(() => {});
    this.locationCallback = undefined; this.stepCallback = undefined; this.accelerationCallback = undefined; this.cancelCallback = undefined;
    this.permissionCallback = undefined; this.enabledCallback = undefined; this.locationError = undefined; this.suspendCallback = undefined;
    this.background = false; this.taskId = -1; this.pendingSteps = 0; this.hasStepDelta = false; this.movingUntil = 0; this.lastEmit = 0; this.requestInterval = 10; this.steps.reset();
  }
}
