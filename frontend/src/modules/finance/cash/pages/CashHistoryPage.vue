<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { CashFactView, CashPeriodSummary } from '@/modules/finance/cash/contracts'
import { cashService } from '@/modules/finance/cash/services/cashService'
import CodeTag from '@/shared/components/CodeTag.vue'

const startDate = ref(new Date().toISOString().slice(0, 8) + '01')
const endDate = ref(new Date().toISOString().slice(0, 10))
const cashType = ref('')
const facts = ref<CashFactView[]>([])
const summary = ref<CashPeriodSummary | null>(null)
const errorText = ref('')
const successText = ref('')
const dialogVisible = ref(false)
const action = ref<'reverse' | 'return'>('reverse')
const form = reactive({
  cashFactBusinessId: '',
  resultBusinessId: '',
  amount: '',
  businessDate: new Date().toISOString().slice(0, 10),
  reasonText: '',
})

async function load(): Promise<void> {
  try {
    ;[facts.value, summary.value] = await Promise.all([
      cashService.facts(startDate.value, endDate.value, cashType.value),
      cashService.periodSummary(startDate.value, endDate.value),
    ])
  } catch (error) {
    errorText.value = userMessageOf(error, '现金流水加载失败')
  }
}

function open(kind: 'reverse' | 'return', row: CashFactView): void {
  action.value = kind
  form.cashFactBusinessId = row.cashFactBusinessId
  form.amount = row.amount
  form.resultBusinessId = ''
  form.businessDate = new Date().toISOString().slice(0, 10)
  form.reasonText = ''
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  try {
    if (action.value === 'reverse') {
      await cashService.reverse(form.cashFactBusinessId, form.businessDate, form.reasonText)
      successText.value = '现金已整笔冲销'
    } else {
      await cashService.returnPayroll({
        originalCashFactBusinessId: form.cashFactBusinessId,
        resultBusinessId: form.resultBusinessId,
        amount: form.amount,
        businessDate: form.businessDate,
        reasonText: form.reasonText,
      })
      successText.value = '真实工资退回已登记；不改变原应付'
    }
    dialogVisible.value = false
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '现金更正失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="history-page">
    <h1>现金流水</h1>
    <div class="toolbar">
      <el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" />
      <el-date-picker v-model="endDate" type="date" value-format="YYYY-MM-DD" />
      <el-input v-model="cashType" placeholder="业务类型（可选）" class="type" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert v-if="successText" type="success" :closable="false" :title="successText" class="alert" />
    <el-descriptions v-if="summary" :column="3" border class="summary">
      <el-descriptions-item label="有效流入">{{ summary.income }}</el-descriptions-item>
      <el-descriptions-item label="有效流出">{{ summary.outflow }}</el-descriptions-item>
      <el-descriptions-item label="现金净额">{{ summary.netAmount }}</el-descriptions-item>
    </el-descriptions>
    <el-table :data="facts" border empty-text="期间内无现金流水">
      <el-table-column prop="cashNo" label="现金单号" width="140" />
      <el-table-column prop="businessDate" label="业务日期" width="120" />
      <el-table-column label="类型" width="180">
        <template #default="{ row }"><CodeTag category="finance.cashType" :code="row.cashType" /></template>
      </el-table-column>
      <el-table-column label="方向" width="80">
        <template #default="{ row }"><CodeTag category="finance.cashDirection" :code="row.direction" /></template>
      </el-table-column>
      <el-table-column prop="amount" label="金额" width="130" />
      <el-table-column label="有效" width="80">
        <template #default="{ row }">{{ row.valid ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190">
        <template #default="{ row }">
          <el-button link type="danger" :disabled="!row.valid" @click="open('reverse', row)">整笔冲销</el-button>
          <el-button
            v-if="row.cashType === 'PAYROLL_PAYMENT'"
            link
            type="primary"
            :disabled="!row.valid"
            @click="open('return', row)"
          >
            工资退回
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">现金净额不是利润；工资退回不充当经营收入。</p>

    <el-dialog v-model="dialogVisible" :title="action === 'reverse' ? '整笔冲销' : '真实工资退回'" width="460px">
      <el-form label-width="110px">
        <el-form-item v-if="action === 'return'" label="工资结果"><el-input v-model="form.resultBusinessId" /></el-form-item>
        <el-form-item label="金额"><el-input v-model="form.amount" :disabled="action === 'reverse'" /></el-form-item>
        <el-form-item label="业务日期">
          <el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="原因"><el-input v-model="form.reasonText" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确认</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.history-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 16px; }
h1 { margin: 0 0 12px; font-size: 18px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.type { width: 220px; }
.summary { margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.alert { margin-bottom: 12px; }
</style>
