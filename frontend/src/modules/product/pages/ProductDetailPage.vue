<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { ProductView, SchemeView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import CodeTag from '@/shared/components/CodeTag.vue'
import DetailPage from '@/shared/components/DetailPage.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const route = useRoute()
const router = useRouter()
const productId = String(route.params.productBusinessId ?? '')
const product = ref<ProductView | null>(null)
const schemes = ref<SchemeView[]>([])
const { loading, errorText, run } = useAsyncLoad()
const { run: runAction } = useAsyncAction()
const standardBudget = computed(
  () =>
    schemes.value.find((scheme) => scheme.schemeType === 'SYSTEM_STANDARD')?.latestBudget ?? null,
)

async function load(): Promise<void> {
  await run(async () => {
    const [current, schemeList] = await Promise.all([
      productService.get(productId),
      productService.schemes(productId),
    ])
    product.value = current
    schemes.value = schemeList
  }, '商品详情加载失败')
}

async function toggleStatus(): Promise<void> {
  if (product.value === null) {
    return
  }
  const target = product.value.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  await runAction(
    async () => {
      product.value = await productService.changeStatus(
        productId,
        target,
        product.value!.rowVersion,
      )
    },
    {
      success: target === 'ACTIVE' ? '商品已启用' : '商品已停用',
      fallback: '状态更新失败',
    },
  )
}

async function toggleScheme(scheme: SchemeView): Promise<void> {
  const activating = scheme.status === 'INACTIVE'
  const actionLabel = activating ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `${actionLabel}方案「${scheme.name}」？历史订单快照不受影响。`,
      `${actionLabel}方案`,
    )
  } catch {
    return
  }
  await runAction(
    () =>
      activating
        ? productService.activateScheme(scheme.schemeBusinessId, scheme.rowVersion)
        : productService.deactivateScheme(scheme.schemeBusinessId, scheme.rowVersion),
    {
      success: activating ? '方案已启用' : '方案已停用',
      fallback: activating ? '方案启用失败' : '方案停用失败',
      onSuccess: load,
    },
  )
}

function materialSourceText(source: string): string {
  return source === 'CUSTOMER' ? '客户提供' : '工作室提供'
}

function percent(rate: number | null): string {
  return rate === null ? '—' : `${(rate * 100).toFixed(2)}%`
}

onMounted(load)
</script>

<template>
  <DetailPage
    class="product-detail"
    :title="product?.name ?? '商品详情'"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #meta>
      <template v-if="product">
        {{ product.productNo }} · 日产能 {{ product.dailyCapacity }} · 内容版本
        {{ product.contentVersion }}
      </template>
    </template>
    <template #actions>
      <template v-if="product">
        <PermissionButton
          permission="product.update"
          @click="router.push(`/products/${productId}/edit`)"
        >
          编辑商品
        </PermissionButton>
        <PermissionButton
          permission="product.deactivate"
          :type="product.status === 'ACTIVE' ? 'warning' : 'success'"
          @click="toggleStatus"
        >
          {{ product.status === 'ACTIVE' ? '停用商品' : '启用商品' }}
        </PermissionButton>
      </template>
    </template>

    <template v-if="product">
      <div class="yumi-stat-grid">
        <div class="yumi-stat-card">
          <span class="yumi-stat-card__label">标准售价</span>
          <span class="yumi-stat-card__value"
            ><MoneyText :value="product.standardSalePrice"
          /></span>
          <span class="yumi-stat-card__note">商品标准方案</span>
        </div>
        <div class="yumi-stat-card">
          <span class="yumi-stat-card__label">工作室承担成本</span>
          <span class="yumi-stat-card__value">
            <MoneyText v-if="standardBudget" :value="standardBudget.studioBorneUnitCost" />
            <span v-else>—</span>
          </span>
          <span class="yumi-stat-card__note">按当前标准参数计算</span>
        </div>
        <div class="yumi-stat-card">
          <span class="yumi-stat-card__label">预计单件利润</span>
          <span class="yumi-stat-card__value">
            <MoneyText v-if="standardBudget" :value="standardBudget.expectedUnitProfit" />
            <span v-else>—</span>
          </span>
          <span class="yumi-stat-card__note">售价 − 工作室承担成本</span>
        </div>
        <div class="yumi-stat-card">
          <span class="yumi-stat-card__label">单日最大产能</span>
          <span class="yumi-stat-card__value"> {{ product.dailyCapacity }}<small>件</small> </span>
          <span class="yumi-stat-card__note">
            {{ product.mouldCount }} 个模具 × {{ product.mouldDailyBatches }} 批 / 日
          </span>
        </div>
      </div>

      <h2>基本规格</h2>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="商品编号">{{ product.productNo }}</el-descriptions-item>
        <el-descriptions-item label="单件标准克重">
          {{ product.standardWeightGrams }} g
        </el-descriptions-item>
        <el-descriptions-item label="制作星级">{{ product.makingStarName }}</el-descriptions-item>
        <el-descriptions-item label="制作标准分钟">
          {{ product.makingStandardMinutes }} 分钟
        </el-descriptions-item>
        <el-descriptions-item label="模具数量">{{ product.mouldCount }} 个</el-descriptions-item>
        <el-descriptions-item label="每日批次">
          {{ product.mouldDailyBatches }} 批
        </el-descriptions-item>
      </el-descriptions>

      <h2>基础材料</h2>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="胶水损耗率">{{ product.glueLossRate }}</el-descriptions-item>
        <el-descriptions-item label="胶水单价">
          <MoneyText :value="product.glueUnitPrice" />
        </el-descriptions-item>
        <el-descriptions-item label="色浆单价">
          <MoneyText :value="product.colorPasteUnitPrice" />
        </el-descriptions-item>
      </el-descriptions>

      <h2>工序参数</h2>
      <div class="yumi-card-grid yumi-card-grid--3">
        <div class="process-card">
          <h3>制作</h3>
          <dl>
            <dt>标准分钟</dt>
            <dd>{{ product.makingStandardMinutes }} 分钟 / 件</dd>
            <dt>制作提成</dt>
            <dd><MoneyText :value="product.makingCommission" /></dd>
            <dt>其他材料费用</dt>
            <dd><MoneyText :value="product.making.otherMaterialCost" /></dd>
          </dl>
        </div>
        <div class="process-card">
          <h3>捏毛装袋</h3>
          <dl>
            <dt>标准分钟</dt>
            <dd>{{ product.bagging.standardMinutes }} 分钟 / 件</dd>
            <dt>单件人工成本</dt>
            <dd><MoneyText :value="product.bagging.laborCost" /></dd>
            <dt>袋子成本</dt>
            <dd><MoneyText :value="product.bagging.bagUnitCost" /></dd>
            <dt>装饰品价格</dt>
            <dd><MoneyText :value="product.bagging.decorationUnitPrice" /></dd>
            <dt>其他费用</dt>
            <dd><MoneyText :value="product.bagging.otherCost" /></dd>
          </dl>
        </div>
        <div class="process-card">
          <h3>缝边剪袋</h3>
          <dl>
            <dt>标准分钟</dt>
            <dd>{{ product.seamCutting.standardMinutes }} 分钟 / 件</dd>
            <dt>单件人工成本</dt>
            <dd><MoneyText :value="product.seamCutting.laborCost" /></dd>
            <dt>综合材料成本</dt>
            <dd><MoneyText :value="product.seamCutting.combinedMaterialCost" /></dd>
            <dt>其他费用</dt>
            <dd><MoneyText :value="product.seamCutting.otherCost" /></dd>
          </dl>
        </div>
      </div>

      <h2>额外单件费用</h2>
      <div class="yumi-card-grid yumi-card-grid--3">
        <div class="fee-item">
          <small>其他任务人工费</small><MoneyText :value="product.extraCosts.otherTaskLaborCost" />
        </div>
        <div class="fee-item">
          <small>运输包装费</small><MoneyText :value="product.extraCosts.transportPackagingCost" />
        </div>
        <div class="fee-item">
          <small>日常杂费</small><MoneyText :value="product.extraCosts.dailyMiscCost" />
        </div>
        <div class="fee-item">
          <small>房租水电费</small><MoneyText :value="product.extraCosts.rentUtilitiesCost" />
        </div>
        <div class="fee-item">
          <small>模具单件摊销</small
          ><MoneyText :value="product.extraCosts.mouldUnitAmortizationCost" />
        </div>
        <div class="fee-item">
          <small>其他费用</small><MoneyText :value="product.extraCosts.otherCost" />
        </div>
      </div>

      <div class="yumi-section-head">
        <h2>方案与预算</h2>
        <PermissionButton
          permission="product.scheme.manage"
          size="small"
          @click="router.push(`/products/${productId}/schemes/new`)"
        >
          新建自定义方案
        </PermissionButton>
      </div>
      <el-table :data="schemes" border>
        <el-table-column prop="name" label="方案" min-width="150" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <CodeTag category="product.schemeType" :code="row.schemeType" />
          </template>
        </el-table-column>
        <el-table-column label="目标工序" width="120">
          <template #default="{ row }"
            ><CodeTag category="product.targetProcess" :code="row.targetProcess"
          /></template>
        </el-table-column>
        <el-table-column label="售价" width="130">
          <template #default="{ row }"><MoneyText :value="row.salePrice" /></template>
        </el-table-column>
        <el-table-column label="材料来源" min-width="220">
          <template #default="{ row }">
            <div>
              胶水{{ materialSourceText(row.glueSource) }} · 色浆{{
                materialSourceText(row.colorPasteSource)
              }}
            </div>
            <small v-if="row.targetProcess !== 'MAKING'">
              袋子{{ materialSourceText(row.bagging.bagSource) }} · 装饰品{{
                materialSourceText(row.bagging.decorationSource)
              }}
            </small>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <CodeTag category="common.status" :code="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="全资源成本" width="130">
          <template #default="{ row }">
            <MoneyText :value="row.latestBudget?.fullResourceUnitCost" />
          </template>
        </el-table-column>
        <el-table-column label="工作室成本" width="130">
          <template #default="{ row }">
            <MoneyText :value="row.latestBudget?.studioBorneUnitCost" />
          </template>
        </el-table-column>
        <el-table-column label="预计利润" width="130">
          <template #default="{ row }">
            <MoneyText :value="row.latestBudget?.expectedUnitProfit" />
          </template>
        </el-table-column>
        <el-table-column label="利润率" width="100">
          <template #default="{ row }">
            {{ percent(row.latestBudget?.expectedProfitRate ?? null) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <PermissionButton
              v-if="row.schemeType === 'CUSTOM'"
              permission="product.scheme.manage"
              link
              type="primary"
              @click="router.push(`/products/${productId}/schemes/${row.schemeBusinessId}`)"
            >
              编辑
            </PermissionButton>
            <PermissionButton
              v-if="row.schemeType === 'CUSTOM'"
              permission="product.scheme.manage"
              link
              :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
              @click="toggleScheme(row)"
            >
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </PermissionButton>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </DetailPage>
</template>

<style scoped>
.process-card {
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-md);
  padding: var(--yumi-space-4);
  background: var(--yumi-bg-muted);
}

.process-card h3 {
  margin: 0 0 var(--yumi-space-3);
  font-size: var(--yumi-font-size-md);
}

.process-card dl {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: var(--yumi-space-2) var(--yumi-space-3);
  margin: 0;
}

.process-card dt {
  color: var(--yumi-text-secondary);
  font-size: var(--yumi-font-size-sm);
}

.process-card dd {
  margin: 0;
  font-size: var(--yumi-font-size-base);
  font-weight: var(--yumi-font-weight-semibold);
}

.fee-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--yumi-space-3);
  padding: var(--yumi-space-3);
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-md);
  background: var(--yumi-bg-muted);
}
</style>
