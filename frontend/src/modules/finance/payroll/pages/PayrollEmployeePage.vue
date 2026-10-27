<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { isErrorCode, userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeePayrollView } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import CodeTag from '@/shared/components/CodeTag.vue'
import CodeLabel from '@/shared/components/CodeLabel.vue'

const route = useRoute()
const router = useRouter()
const periodId = String(route.query.period ?? '')
const employeeId = String(route.query.employee ?? '')
const result = ref<EmployeePayrollView | null>(null)
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')
const emptyText = ref('')
const successText = ref('')
const confirmedAmount = ref('')
const note = ref('')
const manual = reactive<Record<string, { amount: string; note: string }>>({})
const adjustVisible = ref(false)
const adjustment = reactive({ delta: '', reason: '' })

async function load(): Promise<void> {
  errorText.value = ''
  emptyText.value = ''
  if (periodId.trim() === '' || employeeId.trim() === '') {
    result.value = null
    emptyText.value = '请从工资期间页选择员工后再查看工资结果'
    loading.value = false
    return
  }
  loading.value = true
  try {
    result.value = await payrollService.result(periodId, employeeId)
    confirmedAmount.value = result.value.confirmedAmount?.amount ?? ''
    for (const line of result.value.lines) {
      manual[line.lineBusinessId] = { amount: '', note: '' }
    }
  } catch (error) {
    if (isErrorCode(error, 'PAYROLL_RESULT_NOT_FOUND')) {
      result.value = null
      emptyText.value = '该员工在当前工资期间暂无工资结果；请先在期间页刷新参考'
    } else {
      errorText.value = userMessageOf(error, '工资结果加载失败')
    }
  } finally {
    loading.value = false
  }
}

async function confirm(): Promise<void> {
  if (result.value === null) {
    return
  }
  if (confirmedAmount.value.trim() === '') {
    errorText.value = '请填写最终工资；系统参考不会自动成为实际工资'
    return
  }
  if (note.value.trim() === '') {
    errorText.value = '请填写核定说明'
    return
  }
  saving.value = true
  errorText.value = ''
  try {
    await payrollService.confirm({
      resultBusinessId: result.value.resultBusinessId,
      expectedRowVersion: result.value.rowVersion,
      referenceFingerprint: result.value.referenceFingerprint,
      confirmedAmount: confirmedAmount.value,
      note: note.value,
      processingItems: result.value.lines.map((line) => ({
        referenceLineBusinessId: line.lineBusinessId,
        manualAmount:
          manual[line.lineBusinessId]?.amount.trim() === ''
            ? null
            : manual[line.lineBusinessId]!.amount,
        note: manual[line.lineBusinessId]?.note.trim() === '' ? null : manual[line.lineBusinessId]!.note,
      })),
    })
    successText.value = '工资已核定；处理金额未改变最终工资'
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '工资核定失败')
  } finally {
    saving.value = false
  }
}

async function review(): Promise<void> {
  if (result.value === null) {
    return
  }
  try {
    await payrollService.review(result.value.resultBusinessId)
    successText.value = '已生成只读复核运行，不改变原核定与应付'
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '工资复核失败')
  }
}

async function adjust(): Promise<void> {
  if (result.value === null) {
    return
  }
  try {
    await payrollService.adjust({
      resultBusinessId: result.value.resultBusinessId,
      amountDelta: adjustment.delta,
      reasonText: adjustment.reason,
      referenceRunBusinessId: result.value.referenceRunBusinessId,
    })
    adjustVisible.value = false
    successText.value = '人工差额调整已追加，不产生现金'
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '工资调整失败')
  }
}

function goPay(): void {
  void router.push(`/payroll/payments?period=${periodId}`)
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="employee-payroll">
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert v-if="successText" type="success" :closable="false" :title="successText" class="alert" />
    <el-empty v-if="emptyText" :description="emptyText" class="empty">
      <el-button type="primary" @click="router.push('/payroll/periods')">返回工资期间</el-button>
    </el-empty>
    <template v-if="result">
      <header class="head">
        <div>
          <h1>逐人工资核定</h1>
          <span class="meta">
            {{ result.employeeBusinessId }} · <CodeTag category="finance.payrollResultStatus" :code="result.status" /> · 参考指纹
            {{ result.referenceFingerprint.slice(0, 12) }}…
          </span>
        </div>
        <div class="actions">
          <el-button v-if="result.status === 'CONFIRMED'" @click="review">只读复核</el-button>
          <el-button v-if="result.status === 'CONFIRMED'" @click="adjustVisible = true">人工调整</el-button>
          <el-button
            v-if="result.status === 'CONFIRMED'"
            @click="goPay"
          >
            付款
          </el-button>
        </div>
      </header>

      <h2>逐项成本参考</h2>
      <el-table :data="result.lines" border size="small">
        <el-table-column label="成本项" width="160">
          <template #default="{ row }"><CodeLabel category="finance.costComponent" :code="row.componentCode" /></template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }"><CodeLabel category="finance.costLineType" :code="row.lineType" /></template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="unitAmount" label="单价" width="100" />
        <el-table-column label="参考金额" width="130">
          <template #default="{ row }">{{ row.referenceAmount?.amount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="160">
          <template #default="{ row }">
            {{ row.occurred ? '已发生' : '未发生' }}
            {{ row.estimated ? ' / 预计分摊' : '' }}
            {{ row.incomplete ? ' / 依据不完整' : '' }}
          </template>
        </el-table-column>
        <el-table-column label="人工处理金额" width="150">
          <template #default="{ row }">
            <el-input v-model="manual[row.lineBusinessId]!.amount" placeholder="可空" />
          </template>
        </el-table-column>
        <el-table-column label="处理说明" min-width="150">
          <template #default="{ row }">
            <el-input v-model="manual[row.lineBusinessId]!.note" placeholder="可选" />
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">人工处理金额留空与填 0 分别保存；不会自动抵扣最终工资，系统不生成扣款合计。</p>

      <h2>人工决定</h2>
      <el-form label-width="110px" class="form">
        <el-form-item label="最终工资">
          <el-input v-model="confirmedAmount" :disabled="result.status === 'CONFIRMED'" />
        </el-form-item>
        <el-form-item label="核定说明">
          <el-input v-model="note" type="textarea" :rows="2" :disabled="result.status === 'CONFIRMED'" />
        </el-form-item>
        <el-form-item v-if="result.status !== 'CONFIRMED'">
          <el-button type="primary" :loading="saving" @click="confirm">确认工资</el-button>
        </el-form-item>
      </el-form>
    </template>

    <el-dialog v-model="adjustVisible" title="人工差额调整" width="440px">
      <el-form label-width="92px">
        <el-form-item label="差额">
          <el-input v-model="adjustment.delta" placeholder="可带负号，精确到分" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="adjustment.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" @click="adjust">提交调整</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.employee-payroll { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 20px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
h1 { margin: 0 0 4px; font-size: 20px; }
.meta { color: var(--el-text-color-secondary); font-size: 12px; }
.actions { display: flex; gap: 8px; }
h2 { margin: 20px 0 10px; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.form { max-width: 560px; }
.alert { margin-bottom: 12px; }
</style>
