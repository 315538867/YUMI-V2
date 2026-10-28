<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { CorrectionFeedItem, PhysicalDestination } from '@/modules/production/contracts'
import {
  productionService,
  type VerifyItemInput,
} from '@/modules/production/services/productionService'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { errorText, run } = useAsyncLoad()
const { loading: saving, run: runAction } = useAsyncAction()

const feed = ref<CorrectionFeedItem[]>([])
const form = reactive({
  taskItemBusinessId: '',
  unfinishedQuantity: 0,
  qualifiedQuantity: 0,
  reworkQuantity: 0,
  scrappedQuantity: 0,
  destination: 'DESTROYED' as PhysicalDestination,
  reason: '',
})

async function loadFeed(): Promise<void> {
  await run(async () => {
    feed.value = await productionService.corrections(0, 100)
  }, '更正记录加载失败')
}

function corrected(): VerifyItemInput {
  const defectQuantity = form.reworkQuantity + form.scrappedQuantity
  return {
    taskItemBusinessId: form.taskItemBusinessId.trim(),
    unfinishedQuantity: Number(form.unfinishedQuantity),
    qualifiedQuantity: Number(form.qualifiedQuantity),
    reworkQuantity: Number(form.reworkQuantity),
    scrappedQuantity: Number(form.scrappedQuantity),
    defects:
      defectQuantity === 0
        ? []
        : [
            {
              defectReasonCode: 'OTHER',
              defectReasonText: '更正登记',
              quantity: defectQuantity,
              reworkQuantity: Number(form.reworkQuantity),
              scrapParts:
                form.scrappedQuantity === 0
                  ? []
                  : [
                      {
                        quantity: Number(form.scrappedQuantity),
                        physicalDestination: form.destination,
                        responsibilityType: 'WAIVED' as const,
                        responsibleSubjectBusinessId: null,
                        waivedReason: '更正登记免除',
                      },
                    ],
            },
          ],
    resultNote: '更正登记',
  }
}

async function submit(): Promise<void> {
  if (form.taskItemBusinessId.trim() === '' || form.reason.trim() === '') {
    errorText.value = '请填写任务明细与更正原因'
    return
  }
  await runAction(
    () =>
      productionService.correct({
        taskItemBusinessId: form.taskItemBusinessId.trim(),
        correctedResult: corrected(),
        reason: form.reason.trim(),
      }),
    {
      success: '更正已提交，历史事实保留',
      fallback: '生产事实更正失败',
      onSuccess: async () => {
        form.reason = ''
        await loadFeed()
      },
    },
  )
}

onMounted(loadFeed)
</script>

<template>
  <PageShell title="生产事实更正">
    <WorkspacePage class="correction-page">
      <el-alert
        type="info"
        :closable="false"
        title="只更正已核验明细；历史事实保留，原工作日期与负载不搬移。"
        class="yumi-alert"
      />
      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        :title="errorText"
        class="yumi-alert"
      />
      <el-form label-width="110px" class="form">
        <el-form-item label="任务明细" required>
          <el-input v-model="form.taskItemBusinessId" />
        </el-form-item>
        <el-form-item label="未完成">
          <el-input v-model.number="form.unfinishedQuantity" type="number" />
        </el-form-item>
        <el-form-item label="合格">
          <el-input v-model.number="form.qualifiedQuantity" type="number" />
        </el-form-item>
        <el-form-item label="返工/修补">
          <el-input v-model.number="form.reworkQuantity" type="number" />
        </el-form-item>
        <el-form-item label="线上报废">
          <el-input v-model.number="form.scrappedQuantity" type="number" />
        </el-form-item>
        <el-form-item label="报废去向">
          <el-select v-model="form.destination">
            <el-option label="实际销毁" value="DESTROYED" />
            <el-option label="转线下库存" value="TRANSFERRED_OFFLINE_INVENTORY" />
          </el-select>
        </el-form-item>
        <el-form-item label="更正原因" required>
          <el-input v-model="form.reason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submit">提交更正</el-button>
        </el-form-item>
      </el-form>

      <h2>更正 Feed</h2>
      <el-table :data="feed" empty-text="暂无更正记录" border size="small">
        <el-table-column prop="feedSequence" label="序号" width="90" />
        <el-table-column prop="correctionCommandBusinessId" label="命令批次" min-width="200" />
        <el-table-column prop="taskItemBusinessId" label="任务明细" min-width="200" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }"
            ><CodeLabel
              category="production.correctionItemKind"
              :code="row.itemKind"
              fallback="其他"
          /></template>
        </el-table-column>
        <el-table-column label="效果" width="100">
          <template #default="{ row }"
            ><CodeLabel
              category="production.correctionEffectType"
              :code="row.effectType"
              fallback="其他"
          /></template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="businessDate" label="业务日期" width="120" />
      </el-table>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.form {
  max-width: 620px;
}
</style>
