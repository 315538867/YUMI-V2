<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import { money } from '@/platform/services/money'
import type { EntryConfiguration } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import CodeTag from '@/shared/components/CodeTag.vue'

const router = useRouter()
const configuration = ref<EntryConfiguration | null>(null)
const loading = ref(true)
const errorText = ref('')
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
  loading.value = true
  try {
    configuration.value = await productService.entryConfiguration()
    defaults.glueLossRate = configuration.value.glueLossRate
    defaults.glueUnitPrice = configuration.value.glueUnitPrice.amount
    defaults.colorPasteUnitPrice = configuration.value.colorPasteUnitPrice.amount
  } catch (error) {
    errorText.value = userMessageOf(error, '录入设置加载失败')
  } finally {
    loading.value = false
  }
}

async function saveDefaults(): Promise<void> {
  if (configuration.value === null) {
    return
  }
  try {
    configuration.value = await productService.saveEntryDefaults({
      glueLossRate: defaults.glueLossRate,
      glueUnitPrice: money(defaults.glueUnitPrice),
      colorPasteUnitPrice: money(defaults.colorPasteUnitPrice),
      expectedRowVersion: configuration.value.rowVersion,
    })
    ElMessage.success('录入默认值已保存')
  } catch (error) {
    errorText.value = userMessageOf(error, '录入默认值保存失败')
  }
}

async function saveStar(star: EntryConfiguration['makingStars'][number]): Promise<void> {
  if (configuration.value === null) {
    return
  }
  try {
    configuration.value = await productService.saveMakingStar(star.starCode, {
      name: star.name,
      standardMinutes: Number(star.standardMinutes),
      sortOrder: Number(star.sortOrder),
      active: true,
      expectedRowVersion: configuration.value.rowVersion,
    })
    ElMessage.success('星级已保存')
  } catch (error) {
    errorText.value = userMessageOf(error, '星级保存失败')
  }
}

async function addStar(): Promise<void> {
  if (configuration.value === null) {
    return
  }
  if (newStar.starCode.trim() === '' || newStar.name.trim() === '') {
    errorText.value = '请填写星级代码与名称'
    return
  }
  try {
    configuration.value = await productService.saveMakingStar(newStar.starCode.trim(), {
      name: newStar.name.trim(),
      standardMinutes: Number(newStar.standardMinutes),
      sortOrder: Number(newStar.sortOrder),
      active: true,
      expectedRowVersion: configuration.value.rowVersion,
    })
    Object.assign(newStar, { starCode: '', name: '', standardMinutes: 0, sortOrder: 0 })
    ElMessage.success('星级已新增')
  } catch (error) {
    errorText.value = userMessageOf(error, '星级新增失败')
  }
}

async function saveOption(option: EntryConfiguration['processTimeOptions'][number]): Promise<void> {
  if (configuration.value === null) {
    return
  }
  try {
    configuration.value = await productService.saveProcessTimeOption(option.optionBusinessId, {
      processCode: option.processCode,
      optionCode: option.optionCode,
      name: option.name,
      standardMinutes: Number(option.standardMinutes),
      sortOrder: Number(option.sortOrder),
      active: true,
      expectedRowVersion: configuration.value.rowVersion,
    })
    ElMessage.success('工序标准时间已保存')
  } catch (error) {
    errorText.value = userMessageOf(error, '工序标准时间保存失败')
  }
}

async function addOption(): Promise<void> {
  if (configuration.value === null) {
    return
  }
  if (newOption.optionCode.trim() === '' || newOption.name.trim() === '') {
    errorText.value = '请填写选项代码与名称'
    return
  }
  try {
    configuration.value = await productService.saveProcessTimeOption(
      `PTO-${newIdempotencyKey('option')}`,
      {
        processCode: newOption.processCode,
        optionCode: newOption.optionCode.trim(),
        name: newOption.name.trim(),
        standardMinutes: Number(newOption.standardMinutes),
        sortOrder: Number(newOption.sortOrder),
        active: true,
        expectedRowVersion: configuration.value.rowVersion,
      },
    )
    Object.assign(newOption, {
      processCode: 'MAKING',
      optionCode: '',
      name: '',
      standardMinutes: 0,
      sortOrder: 0,
    })
    ElMessage.success('工序标准时间已新增')
  } catch (error) {
    errorText.value = userMessageOf(error, '工序标准时间新增失败')
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="entry-settings">
    <header class="head">
      <h1>商品录入设置</h1>
      <el-button @click="router.push('/products')">返回商品列表</el-button>
    </header>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />

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
  </section>
</template>

<style scoped>
.entry-settings {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

h1 {
  margin: 0;
  font-size: 20px;
}

h2 {
  margin: 24px 0 10px;
  font-size: 16px;
}

.short {
  width: 150px;
}

.tiny {
  width: 100px;
}

.add-row {
  margin-top: 10px;
}

.alert {
  margin-top: 12px;
}
</style>
