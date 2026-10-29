<script setup lang="ts">
import type { TableColumn } from './types'

withDefaults(
  defineProps<{
    columns: TableColumn[]
    rows: Record<string, unknown>[]
    rowKey?: string
    maxHeight?: number | string
    emptyText?: string
    compact?: boolean
    loading?: boolean
  }>(),
  {
    rowKey: 'id',
    maxHeight: '',
    emptyText: '暂无数据',
    compact: false,
    loading: false,
  },
)
</script>

<template>
  <div
    v-loading="loading"
    class="yui-data-table"
    :class="{ 'yui-data-table--compact': compact }"
  >
    <el-table :data="rows" :row-key="rowKey" :max-height="maxHeight" :empty-text="emptyText">
      <el-table-column
        v-for="column in columns"
        :key="column.key"
        :prop="column.key"
        :label="column.label"
        :width="column.width"
        :min-width="column.minWidth"
        :align="column.align ?? 'left'"
      >
        <template #default="scope">
          <slot :name="column.key" :row="scope.row" :value="scope.row[column.key]">
            {{ scope.row[column.key] }}
          </slot>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
