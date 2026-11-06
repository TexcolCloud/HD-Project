import {ref,onScopeDispose} from 'vue';
import {getDispatchBoard,replanDispatch,type DispatchBoard} from '../api/dispatch';
import {errorText} from '../ui';
import {selectBoardVersion,changedDispatchOrders,describeDispatchChanges,type DispatchRouteChange} from './dispatchPlan';

export function useDispatchBoard(){
  const board=ref<DispatchBoard|null>(null),busy=ref(false),error=ref(''),lastCheckedAt=ref<string|null>(null),changedOrders=ref(new Set<string>()),routeChanges=ref<DispatchRouteChange[]>([]);
  let controller:AbortController|undefined,disposed=false;
  function accept(incoming:DispatchBoard){const chosen=selectBoardVersion(board.value,incoming);if(chosen!==incoming)return;if(!board.value||incoming.revision>board.value.revision){changedOrders.value=changedDispatchOrders(board.value,incoming);routeChanges.value=describeDispatchChanges(board.value,incoming);}board.value=incoming;lastCheckedAt.value=new Date().toISOString();error.value='';}
  async function refresh(){if(busy.value||disposed||document.hidden)return;busy.value=true;controller=new AbortController();try{const incoming=await getDispatchBoard(controller.signal);if(!disposed)accept(incoming);}catch(e){if(!disposed&&!(e instanceof DOMException&&e.name==='AbortError'))error.value=errorText(e);}finally{if(!disposed)busy.value=false;controller=undefined;}}
  async function replan(){if(!board.value)return;const incoming=await replanDispatch(board.value.revision);if(!disposed)accept(incoming);}
  const timer=setInterval(()=>void refresh(),5_000);
  const visibility=()=>{if(!document.hidden)void refresh();};document.addEventListener('visibilitychange',visibility);
  void refresh();
  onScopeDispose(()=>{disposed=true;clearInterval(timer);controller?.abort();document.removeEventListener('visibilitychange',visibility);});
  return {board,busy,error,lastCheckedAt,changedOrders,routeChanges,refresh,replan};
}
