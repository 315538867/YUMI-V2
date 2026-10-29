<script setup lang="ts">
import ProductionOverviewCard from '@/modules/production/pages/ProductionOverviewCard.vue'
import UiPageBar from '@/shared/ui/UiPageBar.vue'
</script>

<template>
  <div class="overview-page">
    <UiPageBar :breadcrumb="['生产', '总览']" range="本周" meta="本周生产进度" />
    <ProductionOverviewCard />
  </div>
</template>

<style scoped>
.overview-page {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-md);
}
</style>
