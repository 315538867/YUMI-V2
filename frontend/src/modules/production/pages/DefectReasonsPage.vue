<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import type { DefectReasonView } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'

const auth = useAuthStore()
const reasons = ref<DefectReasonView[]>([])
const errorText = ref('')
const dialogVisible = ref(false)
const form = reactive({ defectReasonCode: '', name: '', other: false })
const canManage = auth.hasPermission('production.defect-reason.manage')

async function load(): Promise<void> {
  try {
    reasons.value = await productionService.defectReasons()
  } catch (error) {
    errorText.value = userMessageOf(error, '缺陷原因加载失败')
  }
}

async function save(): Promise<void> {
  try {
    await productionService.saveDefectReason({
      defectReasonCode: form.defectReasonCode.trim(),
      name: form.name.trim(),
      other: form.other,
      expectedRowVersion: 0,
    })
    dialogVisible.value = false
    ElMessage.success('缺陷原因已保存')
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '缺陷原因保存失败')
  }
}

async function toggle(reason: DefectReasonView): Promise<void> {
  try {
    await productionService.changeDefectReasonStatus(
      reason.defectReasonCode,
      reason.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      reason.rowVersion,
    )
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '状态更新失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="reasons-page">
    <div class="toolbar">
      <h1>缺陷原因</h1>
      <el-button v-if="canManage" type="primary" @click="dialogVisible = true">新增原因</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-table :data="reasons" border>
      <el-table-column prop="defectReasonCode" label="代码" width="180" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="其他" width="80">
        <template #default="{ row }">{{ row.other ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
          </el-tag>
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

    <el-dialog v-model="dialogVisible" title="新增缺陷原因" width="420px">
      <el-form label-width="92px">
        <el-form-item label="代码"><el-input v-model="form.defectReasonCode" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="其他原因"><el-switch v-model="form.other" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.reasons-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

h1 {
  margin: 0;
  font-size: 18px;
}

.alert {
  margin-bottom: 12px;
}
</style>
