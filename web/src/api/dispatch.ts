import {get,patch,post,request} from './client';
import type {Model,Building,Repair} from './types';

export type DispatchManager=Model<'DispatchManager'>;
export type DispatchTask=Model<'DispatchTask'>;
export type DispatchRoute=Model<'DispatchRoute'>;
export type DispatchBoard=Model<'DispatchBoard'>;
export type DispatchTravel=Model<'DispatchTravel'>;
export type DispatchTaskAction='depart'|'arrive'|'block'|'resume';

export const heartbeat=(signal?:AbortSignal)=>request<DispatchManager>('/dispatch/heartbeat',{method:'POST',body:{} satisfies Model<'DispatchHeartbeatRequest'>,signal,idempotent:false});
export const getDispatchBoard=(signal?:AbortSignal)=>request<DispatchBoard>('/dispatch/board',{signal});
export const saveDispatchSettings=(body:Model<'DispatchSettingsRequest'>)=>patch<DispatchManager>('/dispatch/me',body);
export const replanDispatch=(expectedRevision:number)=>post<DispatchBoard>('/dispatch/replan',{expectedRevision} satisfies Model<'DispatchReplanRequest'>);
export const claimDispatchTask=(task:DispatchTask,expectedRevision:number)=>post<Repair>(`/dispatch/tasks/${encodeURIComponent(task.orderId)}/claim`,{expectedOrderVersion:task.orderVersion,expectedRevision} satisfies Model<'DispatchClaimRequest'>);
export const actOnDispatchTask=(task:DispatchTask,action:DispatchTaskAction,reason?:string)=>post<DispatchTask>(`/dispatch/tasks/${encodeURIComponent(task.orderId)}/${action}`,{expectedTaskVersion:task.version,reason} satisfies Model<'DispatchTaskActionRequest'>);
export const handoffDispatchTask=(orderId:string,body:Model<'DispatchHandoffRequest'>)=>post<Repair>(`/dispatch/tasks/${encodeURIComponent(orderId)}/handoff`,body);
export const saveRepairVisit=(orderId:string,body:Model<'RepairVisitRequest'>)=>patch<Repair>(`/repairs/${encodeURIComponent(orderId)}/visit`,body);
export const getDispatchTravel=()=>get<DispatchTravel>('/dispatch/travel');
export const saveDispatchTravel=(fromId:string,toId:string,body:Model<'DispatchTravelRequest'>)=>patch<Model<'DispatchTravelLink'>>(`/dispatch/travel/${encodeURIComponent(fromId)}/${encodeURIComponent(toId)}`,body);
export async function getDispatchBuildings():Promise<Building[]>{return (await get<Model<'BuildingPage'>>('/buildings',{pageSize:100})).items;}
