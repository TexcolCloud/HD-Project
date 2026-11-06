import {defineConfig} from 'vitest/config';
import vue from '@vitejs/plugin-vue';

// These component tests use Vue's in-memory renderer, so compile interactive templates rather than SSR output.
const plugin=vue();
const transform=plugin.transform;
if(transform&&typeof transform!=='function'){
  const handler=transform.handler;
  plugin.transform={...transform,handler(code,id,options){return handler.call(this,code,id,{...options,ssr:false});}};
}
export default defineConfig({plugins:[plugin]});
