<script setup lang="ts">
defineProps<{ name: string }>();
const paths: Record<string, string> = {
  home: "M3 10 12 3l9 7M5 9v12h14V9M9 21v-7h6v7",
  building: "M4 21V5h11v16M15 10h5v11M8 9h3M8 13h3M8 17h3M2 21h20",
  people:
    "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 3a4 4 0 0 1 0 8M22 21v-2a4 4 0 0 0-3-3.8M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  tool: "m14.7 6.3 3 3M14 3a6 6 0 0 0-6.6 8.6L3 16a3 3 0 0 0 4 4l4.4-4.4A6 6 0 0 0 20 9l-4 4-4-4 4-4Z",
  shield: "M12 3 3 7v5c0 5 9 9 9 9s9-4 9-9V7l-9-4ZM8 12l3 3 5-6",
  clipboard: "M8 5H5v16h14V5h-3M8 3h8v4H8ZM8 12h8M8 16h5",
  chart: "M4 3v18h17M9 16V9M14 16V5M19 16v-4",
  plus: "M12 5v14M5 12h14",
  close: "m6 6 12 12M6 18 18 6",
  arrow: "M4 12h16m-6-6 6 6-6 6",
  sun: "M12 3v2M12 19v2M3 12h2M19 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5 19 5M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  moon: "M21 13a9 9 0 1 1-10-10A7 7 0 0 0 21 13",
  search: "m21 21-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0",
  logout: "M9 4H4v16h5M12 12h10m-4-4 4 4-4 4",
};
</script>
<template>
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path
      :d="
        name === 'route'
          ? 'M6 5h10a4 4 0 0 1 0 8H8a4 4 0 0 0 0 8h10M6 2v6M18 18v5'
          : paths[name] || paths.clipboard
      "
    />
  </svg>
</template>
