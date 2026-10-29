<script setup lang="ts">
import UiCapacityBar from './UiCapacityBar.vue'

withDefaults(
  defineProps<{
    weekday: string
    date: string
    today?: boolean
    meta?: string
    capacityValue?: number
    capacityMax?: number
    capacityLabel?: string
    dropLabel?: string
    dropInteractive?: boolean
    showDrop?: boolean
  }>(),
  {
    today: false,
    meta: '',
    capacityValue: 0,
    capacityMax: 0,
    capacityLabel: '',
    dropLabel: '拖入任务',
    dropInteractive: false,
    showDrop: true,
  },
)

defineEmits<{ add: [] }>()
</script>

<template>
  <section
    class="yui-daycol"
    :class="{ 'yui-daycol--today': today, 'yui-daycol--no-drop': !showDrop }"
  >
    <div class="yui-daycol__head">
      <div>
        <div class="yui-daycol__dow">{{ weekday }}</div>
        <div class="yui-daycol__date">{{ date }}</div>
        <div v-if="meta" class="yui-daycol__meta">{{ meta }}</div>
      </div>
      <button type="button" class="yui-daycol__add" aria-label="添加任务" @click="$emit('add')">
        ＋
      </button>
    </div>
    <div class="yui-daycol__cards">
      <slot />
    </div>
    <template v-if="showDrop">
      <button
        v-if="dropInteractive"
        type="button"
        class="yui-daycol__drop yui-daycol__drop--interactive"
        @click="$emit('add')"
      >
        {{ dropLabel }}
      </button>
      <div v-else class="yui-daycol__drop">{{ dropLabel }}</div>
    </template>
    <div class="yui-daycol__capacity">
      <UiCapacityBar
        v-if="capacityMax > 0"
        :value="capacityValue"
        :max="capacityMax"
        :label="capacityLabel || `已排 ${capacityValue} / ${capacityMax}h`"
      />
      <span v-else-if="capacityLabel" class="yui-capacity__label">{{ capacityLabel }}</span>
    </div>
  </section>
</template>
