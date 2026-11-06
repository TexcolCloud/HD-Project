import {request} from './client';
import type {Model} from './types';
export async function generateManagerAdvice(repairId:string|undefined,body:Model<'ManagerAssistRequest'>,signal?:AbortSignal){
  if(!body.consentToExternalAnalysis)throw new Error('请先同意发送本次内容进行外部分析');
  const input:Model<'ManagerAssistRequest'>={consentToExternalAnalysis:true,includeImages:!!repairId&&body.includeImages===true};
  return request<Model<'ManagerAdvice'>>(repairId?`/ai/manager/repairs/${encodeURIComponent(repairId)}`:'/ai/manager/brief',{method:'POST',body:input,signal});
}
