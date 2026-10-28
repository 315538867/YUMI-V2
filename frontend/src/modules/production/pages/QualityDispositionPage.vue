<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { DispositionView, ResponsibilityType } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'

const { errorText, run } = useAsyncLoad()
const { loading: saving, run: runAction } = useAsyncAction()

const taskItemId = ref('')
const dispositions = ref<DispositionView[]>([])
const dialogVisible = ref(false)
const form = reactive({
  dispositionFactBusinessId: '',
  responsibilityType: 'STUDIO' as ResponsibilityType,
  responsibleSubjectBusinessId: '',
  waivedReason: '',
  reasonText: '',
  expectedRowVersion: 1,
})

async function load(): Promise<void> {
  if (taskItemId.value.trim() === '') {
    errorText.value = '请输入任务明细标识'
    return
  }
  await run(async () => {
    dispositions.value = await productionService.dispositions(taskItemId.value.trim())
  }, '处置记录加载失败')
}

function openCorrection(dispositionFactBusinessId: string): void {
  form.dispositionFactBusinessId = dispositionFactBusinessId
  form.responsibilityType = 'STUDIO'
  form.responsibleSubjectBusinessId = ''
  form.waivedReason = ''
  form.reasonText = ''
  form.expectedRowVersion = 1
  dialogVisible.value = true
}

async function correct(): Promise<void> {
  await runAction(
    () =>
      productionService.correctResponsibility({
        dispositionFactBusinessId: form.dispositionFactBusinessId,
        responsibilityType: form.responsibilityType,
        responsibleSubjectBusinessId:
          form.responsibilityType === 'WAIVED' ? null : form.responsibleSubjectBusinessId,
        waivedReason: form.responsibilityType === 'WAIVED' ? form.waivedReason : null,
        reasonText: form.reasonText,
        expectedRowVersion: Number(form.expectedRowVersion),
      }),
    {
      success: '责任已更正',
      fallback: '责任更正失败',
      onSuccess: async () => {
        dialogVisible.value = false
        await load()
      },
    },
  )
}

function destinationText(value: string): string {
  return (
    {
      DESTROYED: '实际销毁',
      TRANSFERRED_OFFLINE_INVENTORY: '转线下库存',
      IN_SYSTEM: '系统内',
    }[value] ?? value
  )
}
</script>

<template>
  <PageShell title="质量处置与责任">
    <WorkspacePage class="disposition-page">
      <div class="yumi-toolbar">
        <el-input
          v-model="taskItemId"
          placeholder="任务明细标识"
          class="input"
          @keyup.enter="load"
        />
        <el-button type="primary" @click="load">查询</el-button>
      </div>
      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        :title="errorText"
        class="yumi-alert"
      />
      <el-empty v-if="dispositions.length === 0" description="暂无处置记录或尚未查询" />
      <el-card v-for="group in dispositions" :key="group.taskItemBusinessId" class="card">
        <template #header>
          <strong>{{ group.taskItemBusinessId }}</strong>
          <span class="yumi-meta"> · 来源 {{ group.productionSourceBusinessId }}</span>
        </template>
        <div v-for="defect in group.defects" :key="defect.defectFactBusinessId" class="defect">
          <p>
            <el-tag>{{ defect.defectReasonName }}</el-tag>
            数量 {{ defect.quantity }}（返工 {{ defect.reworkQuantity }} / 报废
            {{ defect.scrappedQuantity }}）
          </p>
          <el-table :data="defect.dispositions" border size="small">
            <el-table-column prop="dispositionFactBusinessId" label="处置标识" min-width="200" />
            <el-table-column label="处置" width="140">
              <template #default="{ row }"
                ><CodeTag category="production.defectDispositionType" :code="row.dispositionType"
              /></template>
            </el-table-column>
            <el-table-column label="去向" width="130">
              <template #default="{ row }">{{ destinationText(row.physicalDestination) }}</template>
            </el-table-column>
            <el-table-column prop="quantity" label="数量" width="80" />
            <el-table-column label="责任" width="120">
              <template #default="{ row }"
                ><CodeTag category="production.responsibilityType" :code="row.responsibilityType"
              /></template>
            </el-table-column>
            <el-table-column prop="responsibleSubjectBusinessId" label="责任主体" min-width="160" />
            <el-table-column prop="waivedReason" label="免除原因" min-width="160" />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  @click="openCorrection(row.dispositionFactBusinessId)"
                >
                  更正责任
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-card>

      <el-dialog v-model="dialogVisible" title="责任更正" width="460px">
        <el-form label-width="100px">
          <el-form-item label="责任类型">
            <el-select v-model="form.responsibilityType">
              <el-option label="员工" value="EMPLOYEE" />
              <el-option label="小组" value="TEAM" />
              <el-option label="工作室" value="STUDIO" />
              <el-option label="客户" value="CUSTOMER" />
              <el-option label="免除" value="WAIVED" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.responsibilityType !== 'WAIVED'" label="责任主体">
            <el-input v-model="form.responsibleSubjectBusinessId" />
          </el-form-item>
          <el-form-item v-else label="免除原因">
            <el-input v-model="form.waivedReason" />
          </el-form-item>
          <el-form-item label="更正原因">
            <el-input v-model="form.reasonText" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="期望行版本">
            <el-input v-model.number="form.expectedRowVersion" type="number" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="correct">提交更正</el-button>
        </template>
      </el-dialog>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.input {
  width: 280px;
}

.card {
  margin-bottom: var(--yumi-space-3);
}

.defect {
  margin-bottom: var(--yumi-space-3);
}
</style>
