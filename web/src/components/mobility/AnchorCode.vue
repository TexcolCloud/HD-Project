<script setup lang="ts">
import { computed, ref } from "vue";
import type { CampusAnchor } from "../../api/mobility";
import { qrSvg } from "../../utils/qrcode";
import { copyText } from "../../utils/copyText";
import { notify } from "../../ui";
const props = defineProps<{ anchor: CampusAnchor }>();
const open = ref(false),
  graphic = ref<HTMLElement>();
const payload = computed(() => "dormlink-anchor:" + props.anchor.scanCode);
const svg = computed(() => {
  try {
    return qrSvg(payload.value);
  } catch (error) {
    if (error instanceof RangeError) return "";
    throw error;
  }
});
function print() {
  if (!graphic.value) return;
  const popup = window.open("", "_blank", "width=520,height=600");
  if (!popup) {
    notify("无法打开打印窗口，请检查浏览器设置", "error");
    return;
  }
  popup.opener = null;
  const doc = popup.document;
  doc.title = props.anchor.name + " · 地点二维码";
  const header = doc.createElement("h1"),
    location = doc.createElement("p"),
    code = doc.createElement("code");
  header.textContent = props.anchor.name;
  location.textContent =
    props.anchor.buildingName +
    " · " +
    (props.anchor.floorNo === null
      ? "入口／楼层未知"
      : props.anchor.floorNo + "层");
  code.textContent = payload.value;
  const symbol = graphic.value.cloneNode(true) as HTMLElement;
  symbol.style.width = "60mm";
  doc.body.append(header, location, symbol, code);
  popup.focus();
  popup.print();
}
</script>
<template>
  <details
    class="mobility-code"
    @toggle="open = ($event.target as HTMLDetailsElement).open"
  >
    <summary>查看地点二维码</summary>
    <template v-if="open">
      <div v-if="svg" ref="graphic" class="anchor-qr" v-html="svg"></div>
      <p v-else class="form-note">
        内容较长，暂时无法生成二维码，可复制下方内容核对。
      </p>
      <code>{{ payload }}</code>
      <div class="button-row">
        <button
          class="btn"
          type="button"
          @click="copyText(payload, '扫码内容已复制')"
        >
          复制扫码内容
        </button>
        <button class="btn" type="button" :disabled="!svg" @click="print">
          打印二维码
        </button>
      </div>
    </template>
  </details>
</template>
<style scoped>
.anchor-qr {
  width: 160px;
  line-height: 0;
  margin: 8px 0;
}
.anchor-qr :deep(svg) {
  display: block;
  width: 100%;
  height: auto;
  stroke: none;
}
.mobility-code code {
  display: block;
  white-space: normal;
  overflow-wrap: anywhere;
  margin-bottom: 8px;
}
</style>
