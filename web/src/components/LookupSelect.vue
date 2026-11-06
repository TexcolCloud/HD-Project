<script setup lang="ts">
import {ref,onMounted} from 'vue';import {get} from '../api/client';import type {Room,Student,Model} from '../api/types';import {errorText} from '../ui';
const props=defineProps<{modelValue:string;kind:'room'|'student';label:string;available?:boolean;requireResidence?:boolean;initialLabel?:string}>();defineEmits<{'update:modelValue':[value:string]}>();
const q=ref(''),items=ref<(Room|Student)[]>([]),error=ref(''),loading=ref(false);let timer:ReturnType<typeof setTimeout>;
async function load(){loading.value=true;error.value='';try{const result=props.kind==='room'?await get<Model<'RoomPage'>>('/rooms',{q:q.value,pageSize:30}):await get<Model<'StudentPage'>>('/students',{q:q.value,pageSize:30});items.value=result.items;}catch(e){error.value=errorText(e);}finally{loading.value=false;}}
function search(){clearTimeout(timer);timer=setTimeout(load,250);}
function text(item:Room|Student){return 'studentNo' in item?`${item.studentNo} · ${item.name}${item.roomNo?' · '+item.buildingName+' '+item.roomNo:' · 未入住'}`:`${item.buildingName} ${item.roomNo} · ${item.occupiedCount}/${item.capacity}人${!item.enabled?' · 已停用':''}`;}
function disabled(item:Room|Student){return 'studentNo' in item?(props.available?!!item.roomId:props.requireResidence?!item.roomId:false):props.available?(!item.enabled||item.occupiedCount>=item.capacity):false;}
onMounted(load);
</script>
<template><label>{{label}}<input v-model="q" :aria-label="'搜索'+label" :placeholder="kind==='room'?'输入房号或楼栋搜索':'输入姓名或学号搜索'" @input="search"><select :value="modelValue" required :aria-label="label" @change="$emit('update:modelValue',($event.target as HTMLSelectElement).value)"><option value="">{{loading?'正在查找…':'请选择'+label}}</option><option v-if="modelValue&&!items.some(i=>i.id===modelValue)" :value="modelValue">{{initialLabel||'当前已选记录'}}</option><option v-for="item in items" :key="item.id" :value="item.id" :disabled="disabled(item)">{{text(item)}}</option></select><small v-if="error" class="field-error">{{error}}</small><small v-else>显示前30条匹配结果，可输入关键词继续查找。</small></label></template>
