<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import { money } from '@/platform/services/money'
import type { EntryConfiguration } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import CodeTag from '@/shared/components/CodeTag.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { loading, errorText, run } = useAsyncLoad(true)
const { run: runAction } = useAsyncAction()

const router = useRouter()
const configuration = ref<EntryConfiguration | null>(null)
const defaults = reactive({
  glueLossRate: '',
  glueUnitPrice: '',
  colorPasteUnitPrice: '',
})
const newStar = reactive({ starCode: '', name: '', standardMinutes: 0, sortOrder: 0 })
const newOption = reactive({
  processCode: 'MAKING',
  optionCode: '',
  name: '',
  standardMinutes: 0,
  sortOrder: 0,
})

async function load(): Promise<void> {
  await run(async () => {
    configuration.value = await productService.entryConfiguration()
    defaults.glueLossRate = configuration.value.glueLossRate
    defaults.glueUnitPrice = configuration.value.glueUnitPrice.amount
    defaults.colorPasteUnitPrice = configuration.value.colorPasteUnitPrice.amount
  }, '录入设置加载失败')
}

async function saveDefaults(): Promise<void> {
  if (configuration.value === null) {
    return
  }
  await runAction(
    () =>
      productService.saveEntryDefaults({
        glueLossRate: defaults.glueLossRate,
        glueUnitPrice: money(defaults.glueUnitPrice),
        colorPasteUnitPrice: money(defaults.colorPasteUnitPrice),
        expectedRowVersion: configuration.value!.rowVersion,
      }),
    {
      success: '录入默认值已保存',
      fallback: '录入默认值保存失败',
      onSuccess: (saved) => {
        configuration.value = saved
      },
    },
  )
}

async function saveStar(star: EntryConfiguration['makingStars'][number]): Promise<void> {
  if (configuration.value === null) {
    return
  }
  await runAction(
    () =>
      productService.saveMakingStar(star.starCode, {
        name: star.name,
        standardMinutes: Number(star.standardMinutes),
        sortOrder: Number(star.sortOrder),
        active: true,
        expectedRowVersion: configuration.value!.rowVersion,
      }),
    {
      success: '星级已保存',
      fallback: '星级保存失败',
      onSuccess: (saved) => {
        configuration.value = saved
      },
    },
  )
}

async function addStar(): Promise<void> {
  if (configuration.value === null) {
    return
  }
  if (newStar.starCode.trim() === '' || newStar.name.trim() === '') {
    errorText.value = '请填写星级代码与名称'
    return
  }
  await runAction(
    () =>
      productService.saveMakingStar(newStar.starCode.trim(), {
        name: newStar.name.trim(),
        standardMinutes: Number(newStar.standardMinutes),
        sortOrder: Number(newStar.sortOrder),
        active: true,
        expectedRowVersion: configuration.value!.rowVersion,
      }),
    {
      success: '星级已新增',
      fallback: '星级新增失败',
      onSuccess: (saved) => {
        configuration.value = saved
        Object.assign(newStar, { starCode: '', name: '', standardMinutes: 0, sortOrder: 0 })
      },
    },
  )
}

async function saveOption(option: EntryConfiguration['processTimeOptions'][number]): Promise<void> {
  if (configuration.value === null) {
    return
  }
  await runAction(
    () =>
      productService.saveProcessTimeOption(option.optionBusinessId, {
        processCode: option.processCode,
        optionCode: option.optionCode,
        name: option.name,
        standardMinutes: Number(option.standardMinutes),
        sortOrder: Number(option.sortOrder),
        active: true,
        expectedRowVersion: configuration.value!.rowVersion,
      }),
    {
      success: '工序标准时间已保存',
      fallback: '工序标准时间保存失败',
      onSuccess: (saved) => {
        configuration.value = saved
      },
    },
  )
}

async function addOption(): Promise<void> {
  if (configuration.value === null) {
    return
  }
  if (newOption.optionCode.trim() === '' || newOption.name.trim() === '') {
    errorText.value = '请填写选项代码与名称'
    return
  }
  await runAction(
    () =>
      productService.saveProcessTimeOption(`PTO-${newIdempotencyKey('option')}`, {
        processCode: newOption.processCode,
        optionCode: newOption.optionCode.trim(),
        name: newOption.name.trim(),
        standardMinutes: Number(newOption.standardMinutes),
        sortOrder: Number(newOption.sortOrder),
        active: true,
        expectedRowVersion: configuration.value!.rowVersion,
      }),
    {
      success: '工序标准时间已新增',
      fallback: '工序标准时间新增失败',
      onSuccess: (saved) => {
        configuration.value = saved
        Object.assign(newOption, {
          processCode: 'MAKING',
          optionCode: '',
          name: '',
          standardMinutes: 0,
          sortOrder: 0,
        })
      },
    },
  )
}

onMounted(load)
</script>

<template>
  <PageShell title="商品录入设置" hint="维护录入默认值、制作星级与工序标准时间">
    <template #actions>
      <el-button @click="router.push('/products')">返回商品列表</el-button>
    </template>
    <WorkspacePage v-loading="loading" class="entry-settings">
      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        :title="errorText"
        class="yumi-alert"
      />

      <template v-if="configuration">
        <h2>胶水与色浆默认值</h2>
        <el-form inline label-width="110px">
          <el-form-item label="胶水损耗率">
            <el-input v-model="defaults.glueLossRate" class="short" />
          </el-form-item>
          <el-form-item label="胶水单价/克">
            <el-input v-model="defaults.glueUnitPrice" class="short" />
          </el-form-item>
          <el-form-item label="色浆单价/克">
            <el-input v-model="defaults.colorPasteUnitPrice" class="short" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveDefaults">保存默认值</el-button>
          </el-form-item>
        </el-form>

        <h2>制作星级选项</h2>
        <el-table :data="configuration.makingStars" border size="small">
          <el-table-column prop="starCode" label="星级代码" width="120" />
          <el-table-column label="名称" min-width="150">
            <template #default="{ row }"><el-input v-model="row.name" /></template>
          </el-table-column>
          <el-table-column label="标准分钟" width="130">
            <template #default="{ row }">
              <el-input v-model.number="row.standardMinutes" type="number" />
            </template>
          </el-table-column>
          <el-table-column label="排序" width="100">
            <template #default="{ row }">
              <el-input v-model.number="row.sortOrder" type="number" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="saveStar(row)">保存</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-form inline class="add-row">
          <el-input v-model="newStar.starCode" placeholder="星级代码" class="short" />
          <el-input v-model="newStar.name" placeholder="名称" class="short" />
          <el-input
            v-model.number="newStar.standardMinutes"
            type="number"
            placeholder="分钟"
            class="tiny"
          />
          <el-input
            v-model.number="newStar.sortOrder"
            type="number"
            placeholder="排序"
            class="tiny"
          />
          <el-button @click="addStar">新增星级</el-button>
        </el-form>

        <h2>工序标准时间选项</h2>
        <el-table :data="configuration.processTimeOptions" border size="small">
          <el-table-column label="工序" width="130">
            <template #default="{ row }"
              ><CodeTag category="product.targetProcess" :code="row.processCode"
            /></template>
          </el-table-column>
          <el-table-column prop="optionCode" label="选项代码" width="150" />
          <el-table-column label="名称" min-width="140">
            <template #default="{ row }"><el-input v-model="row.name" /></template>
          </el-table-column>
          <el-table-column label="标准分钟" width="130">
            <template #default="{ row }">
              <el-input v-model.number="row.standardMinutes" type="number" />
            </template>
          </el-table-column>
          <el-table-column label="排序" width="100">
            <template #default="{ row }">
              <el-input v-model.number="row.sortOrder" type="number" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="saveOption(row)">保存</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-form inline class="add-row">
          <el-select v-model="newOption.processCode" class="short">
            <el-option label="制作" value="MAKING" />
            <el-option label="捏毛装袋" value="BAGGING" />
            <el-option label="缝边剪袋" value="SEAM_CUTTING" />
          </el-select>
          <el-input v-model="newOption.optionCode" placeholder="选项代码" class="short" />
          <el-input v-model="newOption.name" placeholder="名称" class="short" />
          <el-input
            v-model.number="newOption.standardMinutes"
            type="number"
            placeholder="分钟"
            class="tiny"
          />
          <el-input
            v-model.number="newOption.sortOrder"
            type="number"
            placeholder="排序"
            class="tiny"
          />
          <el-button @click="addOption">新增选项</el-button>
        </el-form>
      </template>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.short {
  width: 150px;
}

.tiny {
  width: 100px;
}

.add-row {
  margin-top: var(--yumi-space-3);
}
</style>
