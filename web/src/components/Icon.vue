<script setup lang="ts">
import {
  ArrowRight,
  Bell,
  Building2,
  ChartNoAxesCombined,
  ClipboardList,
  House,
  LogOut,
  MapPin,
  Moon,
  Plus,
  Route,
  Search,
  ShieldCheck,
  Sun,
  Users,
  Wrench,
  X,
} from "lucide-vue-next";

defineProps<{ name: string }>();
const icons = {
  home: House,
  building: Building2,
  people: Users,
  tool: Wrench,
  shield: ShieldCheck,
  clipboard: ClipboardList,
  chart: ChartNoAxesCombined,
  plus: Plus,
  close: X,
  arrow: ArrowRight,
  sun: Sun,
  moon: Moon,
  search: Search,
  logout: LogOut,
  route: Route,
  location: MapPin,
  message: Bell,
} as const;
</script>
<template>
  <component
    :is="icons[name as keyof typeof icons] || ClipboardList"
    :size="20"
    :stroke-width="1.75"
    aria-hidden="true"
    focusable="false"
  />
</template>
