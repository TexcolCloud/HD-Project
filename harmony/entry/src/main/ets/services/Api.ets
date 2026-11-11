import { http } from '@kit.NetworkKit';
import { rcp } from '@kit.RemoteCommunicationKit';
import { util } from '@kit.ArkTS';
import { image } from '@kit.ImageKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { FileAsset } from '../models/ApiModels';

export interface ApiEnvelope<T> { data: T; traceId: string; }
interface ErrorDetail { code: string; message: string; }
interface ErrorEnvelope { error: ErrorDetail; traceId: string; }
interface Headers { Authorization: string; 'Content-Type': string; 'Idempotency-Key': string; }

export class ApiError extends Error {
  status: number;
  code: string;
  traceId: string;
  sessionEpoch: number;
  constructor(message: string, status: number = 0, code: string = '', traceId: string = '') {
    super(message);
    this.status = status;
    this.code = code;
    this.traceId = traceId;
    this.sessionEpoch = Api.epoch;
  }
}

export class UploadedPhoto {
  asset: FileAsset;
  uri: string;
  constructor(asset: FileAsset, uri: string) { this.asset = asset; this.uri = uri; }
}

export class Api {
  static base: string = 'http://127.0.0.1:8080/api/v1';
  // A session is deliberately held only in memory; no bearer token is written to preferences or logs.
  private static sessionToken: string = '';
  private static generation: number = 0;
  private static processingPhoto: boolean = false;
  static get token(): string { return Api.sessionToken; }
  static set token(value: string) {
    if (value !== Api.sessionToken) { Api.generation++; Api.sessionToken = value; }
  }
  static get epoch(): number { return Api.generation; }
  static assertCurrent(epoch: number): void {
    if (epoch !== Api.generation) throw new ApiError('会话已切换', 0, 'SESSION_REPLACED');
  }

  static normalizeBase(value: string): string {
    const trimmed = value.trim().replace(/\/+$/, '');
    if (!/^https?:\/\/[^/]+/.test(trimmed)) throw new ApiError('服务地址须以 http:// 或 https:// 开头');
    return trimmed.endsWith('/api/v1') ? trimmed : trimmed + '/api/v1';
  }

  static async request<T>(path: string, method: http.RequestMethod = http.RequestMethod.GET,
    body: string = '', idempotencyKey: string = ''): Promise<T> {
    const task = http.createHttp();
    const sessionToken = Api.token;
    const epoch = Api.epoch;
    const headers: Headers = {
      Authorization: sessionToken ? 'Bearer ' + sessionToken : '',
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey || util.generateRandomUUID()
    };
    try {
      const options: http.HttpRequestOptions = {
        method: method, header: headers, expectDataType: http.HttpDataType.STRING,
        connectTimeout: 10000, readTimeout: path.startsWith('/ai/') ? 20000 : 15000,
        usingCache: false
      };
      if (body) options.extraData = body;
      const response = await task.request(Api.base + path, options);
      const raw = response.result as string;
      Api.assertCurrent(epoch);
      return Api.decode<T>(raw, response.responseCode, sessionToken);
    } catch (error) {
      if (error instanceof ApiError && error.sessionEpoch === Api.epoch) throw error;
      Api.assertCurrent(epoch);
      throw new ApiError('暂时无法连接服务，请检查服务地址或网络；已保留填写内容');
    } finally { task.destroy(); }
  }

  private static decode<T>(raw: string, status: number, sessionToken: string): T {
    if (status < 200 || status >= 300) {
      let message = '服务请求失败（' + status + '）', code = '', trace = '';
      try { const response = JSON.parse(raw) as ErrorEnvelope; message = response.error.message || message; code = response.error.code; trace = response.traceId; }
      catch (_) { /* A gateway may return a non-JSON error. */ }
      if (status === 401 && Api.token === sessionToken) Api.token = '';
      throw new ApiError(message, status, code, trace);
    }
    return (JSON.parse(raw) as ApiEnvelope<T>).data;
  }

  // NetworkKit exposes PATCH only from API26; API24 uses the native RCP transport for this verb.
  static async patch<T>(path: string, body: Object, key: string = ''): Promise<T> {
    const sessionToken = Api.token;
    const epoch = Api.epoch;
    const session = rcp.createSession();
    try {
      const headers: rcp.RequestHeaders = {authorization: sessionToken ? 'Bearer ' + sessionToken : '',
        'content-type': 'application/json', 'Idempotency-Key': key || util.generateRandomUUID()};
      const request = new rcp.Request(Api.base + path, 'PATCH', headers, JSON.stringify(body));
      request.configuration = {transfer: {autoRedirect: false, timeout: {connectMs: 10000, transferMs: 15000}}};
      const response = await session.fetch(request);
      Api.assertCurrent(epoch);
      return Api.decode<T>(response.toString() || '', response.statusCode, sessionToken);
    } catch (error) {
      if (error instanceof ApiError && error.sessionEpoch === Api.epoch) throw error;
      Api.assertCurrent(epoch);
      throw new ApiError('暂时无法连接服务，请检查服务地址或网络；已保留填写内容');
    } finally { session.close(); }
  }

  static post<T>(path: string, body: Object, key: string = ''): Promise<T> {
    return Api.request<T>(path, http.RequestMethod.POST, JSON.stringify(body), key);
  }

  static async upload(uri: string, usage: string): Promise<UploadedPhoto> {
    // Decode one selected resource at a time, directly from its descriptor. Do not copy a 40MB
    // original into an additional JS ArrayBuffer or decode multiple full images concurrently.
    if (Api.processingPhoto) throw new ApiError('正在处理上一张图片，请稍后重试');
    const epoch = Api.epoch, sessionToken = Api.token;
    Api.processingPhoto = true;
    let file: fs.File | undefined = undefined;
    let source: image.ImageSource | undefined = undefined;
    let pixels: image.PixelMap | undefined = undefined;
    let packer: image.ImagePacker | undefined = undefined;
    try {
      packer = image.createImagePacker();
      file = await fs.open(uri, fs.OpenMode.READ_ONLY);
      Api.assertCurrent(epoch);
      const stat = await fs.stat(file.fd);
      Api.assertCurrent(epoch);
      if (stat.size > 40 * 1024 * 1024) throw new ApiError('图片过大，请选择小于40MB的原图');
      source = image.createImageSource(file.fd);
      const info = await source.getImageInfo();
      Api.assertCurrent(epoch);
      const ratio = Math.min(1, 1800 / Math.max(info.size.width, info.size.height));
      pixels = await source.createPixelMap({ desiredSize: {
        width: Math.max(1, Math.round(info.size.width * ratio)),
        height: Math.max(1, Math.round(info.size.height * ratio))
      } });
      Api.assertCurrent(epoch);
      // Re-encode selected pixels, so camera EXIF / location metadata is not sent.
      const data = await packer.packToData(pixels, { format: 'image/jpeg', quality: 85, needsPackProperties: false });
      Api.assertCurrent(epoch);
      if (data.byteLength > 10 * 1024 * 1024) throw new ApiError('压缩后图片仍超过10MB，请选择较小的图片');
      const task = http.createHttp();
      try {
        const headers: Headers = { Authorization: 'Bearer ' + sessionToken,
          'Content-Type': 'multipart/form-data', 'Idempotency-Key': util.generateRandomUUID() };
        const response = await task.request(Api.base + '/files', {
          method: http.RequestMethod.POST, header: headers, expectDataType: http.HttpDataType.STRING,
          connectTimeout: 10000, readTimeout: 30000,
          multiFormDataList: [
            { name: 'usage', contentType: 'text/plain', data: usage },
            { name: 'file', contentType: 'image/jpeg', remoteFileName: 'fault-' + Date.now() + '.jpg', data: data }
          ]
        });
        Api.assertCurrent(epoch);
        const result = Api.decode<FileAsset>(response.result as string, response.responseCode, sessionToken);
        return new UploadedPhoto(result, uri);
      } finally { task.destroy(); }
    } catch (error) {
      if (error instanceof ApiError && error.sessionEpoch === Api.epoch) throw error;
      Api.assertCurrent(epoch);
      throw new ApiError('图片上传未完成，请检查网络后重试，或移除该图片继续填写');
    } finally {
      if (file) { try { await fs.close(file.fd); } catch (_) {} }
      if (source) { try { await source.release(); } catch (_) {} }
      if (pixels) { try { await pixels.release(); } catch (_) {} }
      if (packer) { try { await packer.release(); } catch (_) {} }
      Api.processingPhoto = false;
    }
  }

  static async choose(remaining: number): Promise<string[]> {
    if (remaining <= 0) throw new ApiError('最多上传6张图片');
    const picker = new photoAccessHelper.PhotoViewPicker();
    const selected = await picker.select({
      MIMEType: photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE, maxSelectNumber: remaining
    });
    return selected.photoUris;
  }

  static async image(path: string): Promise<image.PixelMap> {
    const epoch = Api.epoch, sessionToken = Api.token;
    const origin = Api.base.replace(/\/api\/v1$/, '');
    const address = path.startsWith('http') ? path : origin + path;
    // Attachment paths come from our API; do not forward a bearer to an external host.
    if (!address.startsWith(origin + '/api/v1/files/')) throw new ApiError('图片地址无效');
    const task = http.createHttp();
    try {
      const headers: Headers = { Authorization: 'Bearer ' + sessionToken,
        'Content-Type': 'application/json', 'Idempotency-Key': util.generateRandomUUID() };
      const response = await task.request(address, { method: http.RequestMethod.GET,
        header: headers, expectDataType: http.HttpDataType.ARRAY_BUFFER, usingCache: false,
        connectTimeout: 10000, readTimeout: 15000 });
      Api.assertCurrent(epoch);
      if (response.responseCode !== 200) throw new ApiError('图片暂时无法加载', response.responseCode);
      const source = image.createImageSource(response.result as ArrayBuffer);
      try {
        const info = await source.getImageInfo();
        const scale = Math.min(1, 1800 / Math.max(info.size.width, info.size.height));
        const pixels = await source.createPixelMap({ desiredSize: { width: Math.max(1, Math.round(info.size.width * scale)),
          height: Math.max(1, Math.round(info.size.height * scale)) } });
        if (epoch !== Api.epoch) { pixels.release(); Api.assertCurrent(epoch); }
        return pixels;
      } finally { source.release(); }
    } finally { task.destroy(); }
  }
}
