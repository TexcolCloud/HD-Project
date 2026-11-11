import { expect, it } from 'vitest';
import { contextSummary } from './mobilityContext';
import type { Model } from '../api/types';

const context: Model<'MobilityContext'> = {
  userId:'1',sessionId:'1',status:'VALID',source:'MANUAL',buildingId:'1',buildingName:'A栋',floorNo:3,
  latitude:null,longitude:null,accuracyMeters:null,speedMps:null,motion:'UNKNOWN',quality:'LOW',
  observedAt:'2026-11-11T10:00:00Z',expiresAt:'2026-11-11T10:01:00Z',contextVersion:1,explanation:'手工确认',
};

it('没有速度证据时显示未知，0速度和过期数据分开处理', () => {
  const now = Date.parse('2026-11-11T10:00:30Z');
  expect(contextSummary(context, now)).toMatchObject({place:'A栋 · 3层',speed:'暂无可靠速度',fresh:true});
  expect(contextSummary({...context,speedMps:0,motion:'STILL'}, now).speed).toBe('静止');
  expect(contextSummary({...context,speedMps:1.2}, now + 60_000)).toMatchObject({fresh:false,speed:'暂无可靠速度'});
  expect(contextSummary({...context,buildingId:'2',buildingName:'B栋',floorNo:null}, now).place).toBe('B栋 · 楼层待确认');
  expect(contextSummary(null, now).place).toBe('尚未确认位置');
});
