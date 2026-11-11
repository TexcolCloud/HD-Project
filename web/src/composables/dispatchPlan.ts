import type {DispatchBoard,DispatchTask} from '../api/dispatch';
import {dateTime} from '../ui';
export function dispatchPositionFields(buildingId:string,floorNo:number|string,changed:boolean) {
  if (!changed) return {};
  if (!buildingId) return {positionBuildingId:null,positionFloorNo:null};
  if (floorNo === '' || !Number.isInteger(Number(floorNo)) || Number(floorNo) < 1) throw new Error('请确认当前楼层');
  return {positionBuildingId:buildingId,positionFloorNo:Number(floorNo)};
}
export function selectBoardVersion(current:DispatchBoard|null,incoming:DispatchBoard):DispatchBoard{return current&&incoming.revision<current.revision?current:incoming;}
export function currentDispatchTask(tasks:DispatchTask[]):DispatchTask|undefined{return tasks.find(task=>['EN_ROUTE','ON_SITE'].includes(task.state));}
export type DispatchRouteChange=Pick<DispatchTask,'orderId'|'title'>&{detail:string};
export function describeDispatchChanges(previous:DispatchBoard|null,next:DispatchBoard):DispatchRouteChange[]{
  if(!previous||next.revision<=previous.revision)return [];
  const before=new Map(previous.routes.flatMap(route=>route.tasks.map(task=>[task.orderId,{task,managerId:route.manager.userId,managerName:route.manager.displayName}] as const)));
  return next.routes.flatMap(route=>route.tasks.flatMap(task=>{
    const old=before.get(task.orderId),parts:string[]=[];
    if(!old)parts.push(`新增至${route.manager.displayName}的第${task.sequence}站`);
    else{
      if(old.managerId!==route.manager.userId)parts.push(`路线推荐：${old.managerName} → ${route.manager.displayName}`);
      if(old.task.sequence!==task.sequence)parts.push(`顺序：第${old.task.sequence}站 → 第${task.sequence}站`);
      const oldArrival=dateTime(old.task.estimatedArrival),nextArrival=dateTime(task.estimatedArrival);
      if(oldArrival!==nextArrival)parts.push(`预计到达：${oldArrival} → ${nextArrival}`);
      if(old.task.state!==task.state)parts.push(`${dispatchTaskNames[old.task.state]} → ${dispatchTaskNames[task.state]}`);
    }
    return parts.length?[{orderId:task.orderId,title:task.title,detail:parts.join('；')}]:[];
  }));
}

export function changedDispatchOrders(previous:DispatchBoard|null,next:DispatchBoard):Set<string>{
  return new Set(describeDispatchChanges(previous,next).map(change=>change.orderId));
}

export function datetimeInput(value:string|null|undefined):string{
  if(!value)return '';
  const parts=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(new Date(value));
  const read=(key:Intl.DateTimeFormatPartTypes)=>parts.find(p=>p.type===key)?.value||'';
  return `${read('year')}-${read('month')}-${read('day')}T${read('hour')}:${read('minute')}`;
}
export const dispatchTaskNames:Record<string,string>={RECOMMENDED:'待接推荐',ASSIGNED:'已安排',EN_ROUTE:'前往现场',ON_SITE:'已到现场',BLOCKED:'暂时受阻'};
