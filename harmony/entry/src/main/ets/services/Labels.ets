export function statusLabel(value: string): string {
  switch (value) {
    case 'SUBMITTED': return '待受理';
    case 'ACCEPTED': return '已接单';
    case 'PROCESSING': return '处理中';
    case 'PENDING_CONFIRMATION': return '待确认';
    case 'COMPLETED': return '已完成';
    case 'CANCELLED': return '已取消';
    case 'REJECTED': return '已驳回';
    default: return value;
  }
}
export function categoryLabel(value: string): string {
  switch (value) {
    case 'UTILITIES': return '水电';
    case 'FURNITURE': return '门窗家具';
    case 'NETWORK': return '网络';
    default: return '其他';
  }
}
export function gradeLabel(value: string): string {
  switch (value) {
    case 'EXCELLENT': return '优秀';
    case 'GOOD': return '良好';
    case 'PASS': return '合格';
    default: return '待整改';
  }
}
export function actionLabel(value: string): string {
  switch (value) {
    case 'accept': return '接单';
    case 'start': return '开始处理';
    case 'finish': return '提交完工';
    case 'confirm': return '确认已解决';
    case 'rework': return '反馈未解决';
    case 'cancel': return '取消报修';
    case 'reject': return '驳回并说明原因';
    case 'notes': return '补充进度';
    default: return value;
  }
}
export function dateText(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  // The school uses fixed UTC+8. Format UTC parts explicitly: some Harmony Intl
  // implementations choose h24 despite hour12:false and display midnight as 24:xx.
  const local = new Date(date.getTime() + 8 * 60 * 60 * 1000);
  return local.getUTCFullYear() + '/' + (local.getUTCMonth() + 1) + '/' + local.getUTCDate() + ' ' +
    String(local.getUTCHours()).padStart(2, '0') + ':' + String(local.getUTCMinutes()).padStart(2, '0') + ':' +
    String(local.getUTCSeconds()).padStart(2, '0');
}
export function today(): string {
  const d = new Date(Date.now() + 8 * 60 * 60 * 1000);
  return d.getUTCFullYear() + '-' + String(d.getUTCMonth() + 1).padStart(2, '0') + '-' + String(d.getUTCDate()).padStart(2, '0');
}

export function beijingClock(now: number = Date.now()): string {
  const date = new Date(now + 8 * 60 * 60 * 1000);
  return String(date.getUTCHours()).padStart(2, '0') + ':' + String(date.getUTCMinutes()).padStart(2, '0');
}

function beijingInstant(day: string, clock: string): string {
  const date = day.trim();
  const time = clock.trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('日期格式为YYYY-MM-DD');
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) throw new Error('时间格式为HH:mm，例如18:30');
  const value = date + 'T' + time + ':00+08:00';
  const parsed = new Date(value);
  const schoolDate = new Date(parsed.getTime() + 8 * 60 * 60 * 1000);
  const canonicalDay = schoolDate.getUTCFullYear() + '-' + String(schoolDate.getUTCMonth() + 1).padStart(2, '0') + '-' + String(schoolDate.getUTCDate()).padStart(2, '0');
  if (Number.isNaN(parsed.getTime()) || canonicalDay !== date) throw new Error('日期无效，请核对年月日');
  return value;
}

export function inspectionTimestamp(day: string, clock: string, now: number = Date.now()): string {
  const value = beijingInstant(day, clock);
  if (Date.parse(value) > now) throw new Error('实际检查时刻不能是未来');
  return value;
}

export interface LocalWindow { start: string; end: string; }
export interface LocalClock { day: string; clock: string; }
export function beijingParts(instant: string): LocalClock {
  const value = new Date(Date.parse(instant) + 8 * 60 * 60 * 1000);
  return {day: value.getUTCFullYear() + '-' + String(value.getUTCMonth() + 1).padStart(2, '0') + '-' + String(value.getUTCDate()).padStart(2, '0'),
    clock: String(value.getUTCHours()).padStart(2, '0') + ':' + String(value.getUTCMinutes()).padStart(2, '0')};
}
export function visitWindowRange(enabled: boolean, startDay: string, startClock: string,
  endDay: string, endClock: string, now: number = Date.now()): LocalWindow | null {
  if (!enabled) return null;
  if (!startDay || !startClock || !endDay || !endClock) throw new Error('请完整填写可上门的开始与结束时间');
  const start = beijingInstant(startDay, startClock), end = beijingInstant(endDay, endClock);
  if (Date.parse(end) <= Date.parse(start)) throw new Error('结束时间必须晚于开始时间');
  if (Date.parse(end) <= now) throw new Error('可上门时段已结束，请选择仍然有效的时段');
  return {start: start, end: end};
}
export function availabilityTimestamp(day: string, clock: string, now: number = Date.now()): string {
  const value = beijingInstant(day, clock);
  if (Date.parse(value) <= now) throw new Error('可用截止时间须晚于现在');
  return value;
}
