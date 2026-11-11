// Pure local calculations. No raw acceleration, hardware identifiers or backend DTOs here.
export interface PositionObservation {
  observedMs: number;
  elapsedMs: number;
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  speedMps?: number;
  speedAccuracyMps?: number;
}
export interface MovementEstimate {
  position: PositionObservation;
  speedMps?: number;
  motion: string;
  quality: string;
  explanation: string;
}
export class StepBaseline {
  private count: number = -1;
  private elapsed: number = -1;
  reset(): void { this.count = -1; this.elapsed = -1; }
  delta(count: number, elapsedMs: number): number | undefined {
    if (!Number.isFinite(count) || !Number.isInteger(count) || count < 0 || !Number.isFinite(elapsedMs) || elapsedMs < 0) {
      this.reset(); return undefined;
    }
    const previous = this.count, gap = elapsedMs - this.elapsed;
    this.count = count; this.elapsed = elapsedMs;
    if (previous < 0 || count < previous || gap <= 0 || gap > 60000) return undefined;
    const difference = count - previous;
    return difference >= 0 && difference / (gap / 1000) <= 4 ? difference : undefined;
  }
}
export class MobilityEstimator {
  private previous: PositionObservation | undefined = undefined;
  reset(): void { this.previous = undefined; }
  accept(point: PositionObservation, now: number): MovementEstimate | null {
    if (!Number.isFinite(point.observedMs) || !Number.isFinite(point.elapsedMs) || !Number.isFinite(point.latitude) || !Number.isFinite(point.longitude) || Math.abs(point.latitude) > 90 ||
      Math.abs(point.longitude) > 180 || !Number.isFinite(point.accuracyMeters) || point.accuracyMeters <= 0 ||
      point.observedMs > now + 5000 || now - point.observedMs > 60000 || point.elapsedMs < 0) return null;
    const previous = this.previous;
    if (previous && (point.observedMs <= previous.observedMs || point.elapsedMs <= previous.elapsedMs)) return null;
    this.previous = point;
    let speed: number | undefined = undefined;
    let quality = 'LOW', explanation = '速度未知；位置不足以证明楼层或房间';
    if (point.speedMps !== undefined && (!Number.isFinite(point.speedMps) || point.speedMps > 3.5)) {
      return { position: point, motion: 'UNKNOWN', quality: 'LOW', explanation: '系统速度不适用步行模型，继续采用通行基线' };
    }
    if (point.accuracyMeters <= 30 && point.speedMps !== undefined && point.speedMps >= 0 && point.speedMps <= 3.5 &&
      point.speedAccuracyMps !== undefined && point.speedAccuracyMps >= 0 && point.speedAccuracyMps <= 1) {
      speed = point.speedMps; quality = 'MEDIUM'; explanation = '系统速度通过步行范围与精度检查';
    } else if (previous && point.accuracyMeters <= 30 && previous.accuracyMeters <= 30) {
      const seconds = (point.elapsedMs - previous.elapsedMs) / 1000;
      const distance = this.distance(previous, point);
      if (seconds >= 5 && seconds <= 30 && distance > point.accuracyMeters + previous.accuracyMeters) {
        const candidate = distance / seconds;
        if (candidate <= 3.5) { speed = candidate; quality = 'MEDIUM'; explanation = '位移大于两次定位误差，按单调时间估计'; }
      }
    }
    return { position: point, speedMps: speed, motion: speed === undefined ? 'UNKNOWN' : speed < 0.3 ? 'STILL' : 'WALKING', quality, explanation };
  }
  private distance(a: PositionObservation, b: PositionObservation): number {
    const rad = Math.PI / 180, lat = (b.latitude - a.latitude) * rad, lon = (b.longitude - a.longitude) * rad;
    const value = Math.sin(lat / 2) * Math.sin(lat / 2) + Math.cos(a.latitude * rad) * Math.cos(b.latitude * rad) * Math.sin(lon / 2) * Math.sin(lon / 2);
    return 6371000 * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(Math.max(0, 1 - value)));
  }
}
