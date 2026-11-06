import { reactive } from 'vue';
import { ApiError } from './api/client';
export const notices = reactive<{message:string;kind:'success'|'error'}[]>([]);
export function notify(message:string,kind:'success'|'error'='success') { const item={message,kind}; notices.push(item); setTimeout(()=>{const i=notices.indexOf(item);if(i>=0)notices.splice(i,1);},6000); }
export function errorText(error:unknown) { return error instanceof ApiError ? `${error.message}${error.traceId?`（追踪号 ${error.traceId}）`:''}` : error instanceof Error ? error.message : '操作未完成，请重试'; }
export function dateTime(value?:string|null) { return value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',dateStyle:'short',timeStyle:'short',hour12:false}).format(new Date(value)):'—'; }
export function localDate(value=new Date()) { return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(value); }
export function isoTime(local:string) { return local ? new Date(`${local}+08:00`).toISOString() : undefined; }
export const statusNames:Record<string,string>={SUBMITTED:'待受理',ACCEPTED:'已接单',PROCESSING:'处理中',PENDING_CONFIRMATION:'待确认',COMPLETED:'已完成',CANCELLED:'已取消',REJECTED:'已驳回',VISITING:'在访',LEFT:'已离访',EXCELLENT:'优秀',GOOD:'良好',PASS:'合格',RECTIFY:'待整改',ACTIVE:'在校',INACTIVE:'停用',ROUTINE:'日常巡检',RECHECK:'整改复查'};
export const categoryNames:Record<string,string>={UTILITIES:'水电',FURNITURE:'门窗家具',NETWORK:'网络',OTHER:'其他'};
export const actionNames:Record<string,string>={accept:'接单',reject:'驳回工单',start:'开始处理',finish:'提交完工',notes:'追加进度',rework:'返修处理',confirm:'确认解决',cancel:'取消报修'};
