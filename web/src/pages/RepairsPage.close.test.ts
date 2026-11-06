import {afterEach,expect,it,vi} from 'vitest';
import {createRenderer,nextTick} from 'vue';
import type {Repair} from '../api/types';
import RepairsPage from './RepairsPage.vue';

const mocks=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),replace:vi.fn()}));
vi.mock('../api/client',()=>({...mocks,request:vi.fn(),ApiError:class extends Error {}}));
vi.mock('vue-router',()=>({useRoute:()=>({query:{},params:{id:'repair-1'}}),useRouter:()=>({replace:mocks.replace}),onBeforeRouteLeave:vi.fn()}));
// The test drives component model callbacks; native DOM input directives are outside this close-chain regression.
vi.mock('vue',async original=>({...await original<typeof import('vue')>(),vModelText:{},vModelSelect:{}}));

type Node={tag:string;text:string;props:Record<string,unknown>;children:Node[];parent:Node|null;showModal:()=>void};
const node=(tag:string,text=''):Node=>({tag,text,props:{},children:[],parent:null,showModal:()=>{}});
function remove(el:Node){if(el.parent){const siblings=el.parent.children;siblings.splice(siblings.indexOf(el),1);el.parent=null;}}
const renderer=createRenderer<Node,Node>({
  createElement:tag=>node(tag),createText:text=>node('#text',text),createComment:text=>node('#comment',text),
  setText:(el,text)=>{el.text=text;},setElementText:(el,text)=>{el.text=text;el.children=[];},
  parentNode:el=>el.parent,nextSibling:el=>el.parent?.children[el.parent.children.indexOf(el)+1]||null,
  insert:(el,parent,anchor)=>{remove(el);el.parent=parent;const i=anchor?parent.children.indexOf(anchor):-1;if(i<0)parent.children.push(el);else parent.children.splice(i,0,el);},
  remove,patchProp:(el,key,_previous,value)=>{el.props[key]=value;},
});
function find(el:Node,predicate:(candidate:Node)=>boolean):Node|undefined {if(predicate(el))return el;for(const child of el.children){const result=find(child,predicate);if(result)return result;}}
function text(el:Node):string{return el.text+el.children.map(text).join('');}

const repair:Repair={id:'repair-1',orderNo:'TEST-1',reporterStudentId:'student-1',reporterName:'测试学生',roomId:'room-1',roomNo:'302',buildingId:'building-1',buildingName:'A栋',title:'水龙头滴水',description:'测试故障描述',category:'UTILITIES',priority:'NORMAL',urgentReason:'',contactName:'测试学生',contactPhone:'13800000000',contactTimeSlot:'',status:'PROCESSING',handlerUserId:'manager-1',handlerName:'测试宿管',relatedOrderId:null,submittedAt:'2026-11-07T01:00:00Z',completedAt:null,version:2,allowedActions:['notes'],reportImages:[],resultImages:[],latestResult:null,windowStart:null,windowEnd:null,windowConfirmed:false,estimatedMinutes:30,visitRequired:true,waitingReason:''};
let unmount:undefined|(()=>void);
afterEach(()=>{unmount?.();unmount=undefined;vi.unstubAllGlobals();vi.clearAllMocks();});

it('工单有未保存说明时，关闭只确认一次；取消保留输入，确认后关闭',async()=>{
  const confirm=vi.fn().mockReturnValue(true);
  vi.stubGlobal('window',{confirm,addEventListener:vi.fn(),removeEventListener:vi.fn()});
  mocks.get.mockImplementation(async(path:string)=>path==='/repairs/repair-1'?repair:path.endsWith('/events')?[]:{items:path==='/repairs'?[repair]:[],total:1,page:1,pageSize:20});
  const root=node('root');const app=renderer.createApp(RepairsPage);app.mount(root);unmount=()=>app.unmount();
  await vi.waitFor(()=>expect(find(root,n=>n.tag==='button'&&text(n)==='追加进度')).toBeDefined());
  const begin=find(root,n=>n.tag==='button'&&text(n)==='追加进度')!;
  (begin.props.onClick as ()=>void)();await nextTick();
  const input=find(root,n=>n.tag==='textarea')!;
  (input.props['onUpdate:modelValue'] as (value:string)=>void)('仍在等待现场检查');await nextTick();
  const close=()=>{const button=find(root,n=>n.tag==='button'&&n.props['aria-label']==='关闭详情')!;(button.props.onClick as ()=>void)();};
  confirm.mockReturnValueOnce(false);close();await nextTick();
  expect(confirm).toHaveBeenCalledTimes(1);
  expect(find(root,n=>n.tag==='textarea')).toBeDefined();
  expect(mocks.replace).not.toHaveBeenCalled();
  confirm.mockClear();close();await nextTick();
  expect(confirm).toHaveBeenCalledTimes(1);
  expect(mocks.replace).toHaveBeenCalledWith('/repairs');
  expect(find(root,n=>n.tag==='dialog')).toBeUndefined();
});
