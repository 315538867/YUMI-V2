<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type {
  CashFactDetailView,
  CashFactView,
  CashPeriodSummary,
} from '@/modules/finance/cash/contracts'
import { cashService } from '@/modules/finance/cash/services/cashService'
import { fileService } from '@/platform/services/fileService'
import CodeTag from '@/shared/components/CodeTag.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import ListPage from '@/shared/components/ListPage.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import BusinessFileUpload from '@/shared/components/BusinessFileUpload.vue'

const { loading, errorText, run } = useAsyncLoad()

const facts = ref<CashFactView[]>([])
const summary = ref<CashPeriodSummary | null>(null)
const { filters, search, reset } = useListQuery(
  {
    startDate: new Date().toISOString().slice(0, 8) + '01',
    endDate: new Date().toISOString().slice(0, 10),
    cashType: '',
  },
  { paged: false, onSearch: () => void load() },
)
const successText = ref('')
const voucherId = ref<string | null>(null)
const { loading: saving, run: runAction } = useAsyncAction()
const { loading: detailLoading, errorText: detailError, run: runDetail } = useAsyncLoad()
const detailVisible = ref(false)
const detail = ref<CashFactDetailView | null>(null)
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
  await run(async () => {
    ;[facts.value, summary.value] = await Promise.all([
      cashService.facts(filters.startDate, filters.endDate, filters.cashType),
      cashService.periodSummary(filters.startDate, filters.endDate),
    ])
  }, '现金流水加载失败')
}

function open(kind: 'reverse' | 'return', row: CashFactView): void {
  action.value = kind
  form.cashFactBusinessId = row.cashFactBusinessId
  form.amount = String(row.amount)
  form.resultBusinessId = ''
  form.businessDate = new Date().toISOString().slice(0, 10)
  form.reasonText = ''
  voucherId.value = null
  dialogVisible.value = true
}

async function openDetail(row: CashFactView): Promise<void> {
  detailVisible.value = true
  detail.value = null
  await runDetail(async () => {
    detail.value = await cashService.factDetail(row.cashFactBusinessId)
  }, '现金详情加载失败')
}

async function openVoucher(fileId: string): Promise<void> {
  await runAction(
    async () => {
      const blob = await fileService.readContent(fileId, 'finance.read')
      window.open(URL.createObjectURL(blob), '_blank')
    },
    { fallback: '凭证读取失败' },
  )
}

async function submit(): Promise<void> {
  const message = action.value === 'reverse' ? '现金已整笔冲销' : '真实工资退回已登记；不改变原应付'
  await runAction(
    async () => {
      if (action.value === 'reverse') {
        await cashService.reverse(
          form.cashFactBusinessId,
          form.businessDate,
          form.reasonText,
          voucherId.value === null ? [] : [voucherId.value],
        )
      } else {
        await cashService.returnPayroll({
          originalCashFactBusinessId: form.cashFactBusinessId,
          resultBusinessId: form.resultBusinessId,
          amount: form.amount,
          businessDate: form.businessDate,
          reasonText: form.reasonText,
          voucherFileRefs: voucherId.value === null ? [] : [voucherId.value],
        })
      }
    },
    {
      success: message,
      fallback: '现金更正失败',
      onSuccess: async () => {
        successText.value = message
        dialogVisible.value = false
        await load()
      },
    },
  )
}

void load()
</script>

<template>
  <ListPage
    class="history-page"
    title="现金流水"
    :heading="false"
    panel-title="现金流水"
    panel-hint="按业务日期查看有效与冲销流水"
    :loading="loading"
    :error="errorText"
    :empty="!loading && errorText === '' && facts.length === 0"
    empty-text="期间内无现金流水"
    @retry="load"
  >
    <template #filters>
      <el-date-picker
        v-model="filters.startDate"
        type="date"
        value-format="YYYY-MM-DD"
        aria-label="开始日期"
      />
      <el-date-picker
        v-model="filters.endDate"
        type="date"
        value-format="YYYY-MM-DD"
        aria-label="结束日期"
      />
      <el-input
        v-model="filters.cashType"
        placeholder="业务类型（可选）"
        class="type"
        aria-label="业务类型"
      />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </template>
    <template #notice>
      <el-alert v-if="successText" type="success" :closable="false" :title="successText" />
    </template>
    <el-descriptions v-if="summary" :column="3" border class="summary">
      <el-descriptions-item label="有效流入">{{ summary.income }}</el-descriptions-item>
      <el-descriptions-item label="有效流出">{{ summary.outflow }}</el-descriptions-item>
      <el-descriptions-item label="现金净额">{{ summary.netAmount }}</el-descriptions-item>
    </el-descriptions>
    <el-table :data="facts" border empty-text="期间内无现金流水">
      <el-table-column prop="cashNo" label="现金单号" width="140" />
      <el-table-column prop="businessDate" label="业务日期" width="120" />
      <el-table-column label="类型" width="180">
        <template #default="{ row }"
          ><CodeTag category="finance.cashType" :code="row.cashType"
        /></template>
      </el-table-column>
      <el-table-column label="方向" width="80">
        <template #default="{ row }"
          ><CodeTag category="finance.cashDirection" :code="row.direction"
        /></template>
      </el-table-column>
      <el-table-column label="金额" width="130">
        <template #default="{ row }"><MoneyText :value="row.amount" /></template>
      </el-table-column>
      <el-table-column label="有效" width="80">
        <template #default="{ row }">{{ row.valid ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="230">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="danger" :disabled="!row.valid" @click="open('reverse', row)"
            >整笔冲销</el-button
          >
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
    <template #footer>
      <p class="yumi-hint">现金净额不是利润；工资退回不充当经营收入。</p>
    </template>
  </ListPage>

  <el-drawer v-model="detailVisible" title="现金记录详情" size="440px">
    <el-alert
      v-if="detailError"
      type="error"
      :closable="false"
      :title="detailError"
      class="yumi-alert"
    />
    <div v-loading="detailLoading">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item label="现金单号">{{ detail.cashNo }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <CodeTag category="finance.cashType" :code="detail.cashType" />
        </el-descriptions-item>
        <el-descriptions-item label="方向">
          <CodeTag category="finance.cashDirection" :code="detail.direction" />
        </el-descriptions-item>
        <el-descriptions-item label="金额">
          <MoneyText :value="detail.amount" /> {{ detail.currencyCode }}
        </el-descriptions-item>
        <el-descriptions-item label="业务日期">{{ detail.businessDate }}</el-descriptions-item>
        <el-descriptions-item label="有效">{{ detail.valid ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="冲销原事实">
          {{ detail.reversesFactBusinessId || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="凭证">
          <template v-if="detail.voucherFileRefs.length > 0">
            <el-button
              v-for="(fileId, index) in detail.voucherFileRefs"
              :key="fileId"
              link
              type="primary"
              @click="openVoucher(fileId)"
            >
              凭证 {{ index + 1 }}
            </el-button>
          </template>
          <span v-else>—</span>
        </el-descriptions-item>
      </el-descriptions>
    </div>
  </el-drawer>

  <el-dialog
    v-model="dialogVisible"
    :title="action === 'reverse' ? '整笔冲销' : '真实工资退回'"
    width="460px"
  >
    <el-form label-width="110px">
      <el-form-item v-if="action === 'return'" label="工资结果"
        ><el-input v-model="form.resultBusinessId"
      /></el-form-item>
      <el-form-item label="金额"
        ><el-input v-model="form.amount" :disabled="action === 'reverse'"
      /></el-form-item>
      <el-form-item label="业务日期">
        <el-date-picker
          v-model="form.businessDate"
          type="date"
          value-format="YYYY-MM-DD"
          :disabled-date="(date: Date) => date.getTime() > Date.now()"
        />
      </el-form-item>
      <el-form-item label="凭证（可选）">
        <BusinessFileUpload v-model="voucherId" permission-code="finance.read" label="上传凭证" />
      </el-form-item>
      <el-form-item label="原因"
        ><el-input v-model="form.reasonText" type="textarea" :rows="2"
      /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确认</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.type {
  width: 220px;
}
.summary {
  margin-bottom: var(--yumi-space-3);
}
</style>
