import { common } from '@kit.AbilityKit';
import { camera, cameraPicker } from '@kit.CameraKit';
import { scanBarcode } from '@kit.ScanKit';
import { calendarManager } from '@kit.CalendarKit';
import { notificationManager } from '@kit.NotificationKit';
import { Api, ApiError } from '../../services/Api';
import { CampusAnchor, CampusAnchors, Repair } from '../../models/ApiModels';

export class DeviceActions {
  static async takePhoto(context: common.UIAbilityContext): Promise<string> {
    const epoch = Api.epoch;
    try {
      const selected = await cameraPicker.pick(context, [cameraPicker.PickerMediaType.PHOTO], { cameraPosition: camera.CameraPosition.CAMERA_POSITION_BACK });
      Api.assertCurrent(epoch);
      return selected.resultCode === 0 ? selected.resultUri : '';
    } catch (_) {
      Api.assertCurrent(epoch); throw new ApiError('系统拍照未完成，可从相册选择或稍后重试；填写内容已保留');
    }
  }
  static async scanAnchor(context: common.UIAbilityContext): Promise<CampusAnchor | null> {
    const epoch = Api.epoch;
    let result: scanBarcode.ScanResult;
    try { result = await scanBarcode.startScanForResult(context); }
    catch (_) { Api.assertCurrent(epoch); throw new ApiError('系统扫码未完成，可手工选择地点或稍后重试'); }
    Api.assertCurrent(epoch);
    // An unknown URL is neither opened nor interpreted as an authorized place.
    if (!/^dormlink-anchor:[A-Za-z0-9_-]{1,128}$/.test(result.originalValue)) throw new ApiError('仅支持本项目已登记地点二维码，不会打开陌生链接');
    const code = result.originalValue.substring('dormlink-anchor:'.length);
    const anchors = await Api.request<CampusAnchors>('/mobility/anchors');
    Api.assertCurrent(epoch);
    return anchors.items.find((anchor: CampusAnchor): boolean => anchor.enabled && anchor.scanCode === code) || null;
  }
  static async calendar(context: common.UIAbilityContext, repair: Repair): Promise<void> {
    if (!repair.windowStart || !repair.windowEnd) throw new ApiError('请先确认上门时间，再添加预约提醒');
    // The system editor asks the user to save; no full-calendar read/write permission is requested.
    await calendarManager.getCalendarManager(context).editEvent({ type: calendarManager.EventType.NORMAL,
      title: '公寓服务预约', startTime: Date.parse(repair.windowStart), endTime: Date.parse(repair.windowEnd),
      description: '请在寓见公寓登录后查看工单与地点', reminderTime: [15] });
  }
  static async serviceMessage(): Promise<void> {
    const epoch = Api.epoch;
    const enabled = await notificationManager.isNotificationEnabled();
    Api.assertCurrent(epoch);
    if (!enabled) return;
    await notificationManager.publish({id: 43, content: {notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
      normal: {title: '公寓服务有新消息', text: '请打开寓见并登录查看服务事项'}}});
  }
  static async notify(context: common.UIAbilityContext): Promise<void> {
    if (!await notificationManager.isNotificationEnabled()) await notificationManager.requestEnableNotification(context);
    await notificationManager.publish({ id: 42, content: { notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
      normal: { title: '公寓服务提醒已启用', text: '预约可添加到系统日历；工单详情请登录后查看' } } });
  }
}
