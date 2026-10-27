<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/platform/stores/auth'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { DefectReasonView } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import ListPage from '@/shared/components/ListPage.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const { loading, errorText, run } = useAsyncLoad()
const { loading: saving, run: runAction } = useAsyncAction()

const auth = useAuthStore()
const reasons = ref<DefectReasonView[]>([])
const dialogVisible = ref(false)
const form = reactive({ defectReasonCode: '', name: '', other: false })
const canManage = auth.hasPermission('production.defect-reason.manage')

async function load(): Promise<void> {
  await run(async () => {
    reasons.value = await productionService.defectReasons()
  }, '缺陷原因加载失败')
}

async function save(): Promise<void> {
  await runAction(
    () =>
      productionService.saveDefectReason({
        defectReasonCode: form.defectReasonCode.trim(),
        name: form.name.trim(),
        other: form.other,
        expectedRowVersion: 0,
      }),
    {
      success: '缺陷原因已保存',
      fallback: '缺陷原因保存失败',
      onSuccess: async () => {
        dialogVisible.value = false
        await load()
      },
    },
  )
}

async function toggle(reason: DefectReasonView): Promise<void> {
  await runAction(
    () =>
      productionService.changeDefectReasonStatus(
        reason.defectReasonCode,
        reason.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
        reason.rowVersion,
      ),
    { fallback: '状态更新失败', onSuccess: load },
  )
}

onMounted(load)
</script>

<template>
  <ListPage
    class="reasons-page"
    title="缺陷原因"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #actions>
      <PermissionButton
        permission="production.defect-reason.manage"
        type="primary"
        @click="dialogVisible = true"
        >新增原因</PermissionButton
      >
    </template>
    <el-table :data="reasons" empty-text="暂无缺陷原因" border>
      <el-table-column prop="defectReasonCode" label="代码" width="180" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="其他" width="80">
        <template #default="{ row }">{{ row.other ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <CodeTag category="common.status" :code="row.status" />
        </template>
      </el-table-column>
      <el-table-column v-if="canManage" label="操作" width="110">
        <template #default="{ row }">
          <el-button link type="primary" @click="toggle(row)">
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </ListPage>

  <el-dialog v-model="dialogVisible" title="新增缺陷原因" width="420px">
    <el-form label-width="92px">
      <el-form-item label="代码"><el-input v-model="form.defectReasonCode" /></el-form-item>
      <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="其他原因"><el-switch v-model="form.other" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
