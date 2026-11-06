<script setup lang="ts">import { statusNames } from '../ui';defineProps<{status:string}>();</script>
<template><span class="status" :class="status">{{statusNames[status]||status}}</span></template>
