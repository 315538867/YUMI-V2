<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { costLedgerService } from '@/modules/finance/costledger/services/costLedgerService'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const entryId = ref('')
const errorText = ref('')
const successText = ref('')
const allocations = reactive<
  Array<{ subjectType: string; subjectBusinessId: string; amount: string }>
>([{ subjectType: 'ORDER', subjectBusinessId: '', amount: '' }])

function add(): void {
  allocations.push({ subjectType: 'ORDER', subjectBusinessId: '', amount: '' })
}

async function submit(): Promise<void> {
  if (entryId.value.trim() === '') {
    errorText.value = '请输入成本事实标识'
    return
  }
  try {
    await costLedgerService.allocate(entryId.value.trim(), allocations)
    successText.value = '显式分摊已保存；分摊合计必须等于成本金额'
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '成本分摊失败')
  }
}
</script>

<template>
  <WorkspacePage class="allocation-page">
    <h1>成本显式分摊</h1>
    <el-alert
      type="info"
      :closable="false"
      title="分摊只保存人工依据，不另生成成本；未分摊成本在报表提示不完整。"
      class="alert"
    />
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="alert"
    />
    <el-form label-width="110px" class="form">
      <el-form-item label="成本事实"><el-input v-model="entryId" /></el-form-item>
      <el-form-item v-for="(item, index) in allocations" :key="index" :label="`分摊 ${index + 1}`">
        <div class="row">
          <el-select v-model="item.subjectType" class="type" aria-label="成本主体类型">
            <el-option label="订单" value="ORDER" />
            <el-option label="员工" value="EMPLOYEE" />
            <el-option label="商品" value="PRODUCT" />
          </el-select>
          <el-input v-model="item.subjectBusinessId" placeholder="主体标识" />
          <el-input v-model="item.amount" placeholder="金额" class="amount" />
        </div>
      </el-form-item>
      <el-button @click="add">增加分摊行</el-button>
      <el-button type="primary" @click="submit">保存分摊</el-button>
    </el-form>
  </WorkspacePage>
</template>

<style scoped>
h1 {
  margin: 0 0 12px;
  font-size: 18px;
}
.form {
  max-width: 720px;
}
.row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.type {
  width: 120px;
}
.amount {
  width: 140px;
}
.alert {
  margin-bottom: 12px;
}
</style>
