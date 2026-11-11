import type { Model } from '../api/types';

export function contextSummary(context: Model<'MobilityContext'> | null, now: number) {
  const fresh = !!context && context.status === 'VALID' &&
    !!context.expiresAt && Date.parse(context.expiresAt) > now;
  const place = context?.buildingName
    ? `${context.buildingName} · ${context.floorNo === null ? '楼层待确认' : `${context.floorNo}层`}`
    : '尚未确认位置';
  const speed = !fresh || context?.speedMps === null || context?.speedMps === undefined
    ? '暂无可靠速度'
    : context.motion === 'STILL' && context.speedMps === 0
      ? '静止' : `${context.speedMps.toFixed(1)} 米/秒`;
  return { place, speed, fresh };
}
