<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeeView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const router = useRouter()
const employees = ref<EmployeeView[]>([])
const loading = ref(false)
const errorText = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    employees.value = await workforceService.employees()
  } catch (error) {
    errorText.value = userMessageOf(error, '员工列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="employee-page">
    <div class="toolbar">
      <h1>员工与资格</h1>
      <PermissionButton
        permission="workforce.employee.manage"
        type="primary"
        @click="router.push('/workforce/employees/new')"
      >
        新增员工
      </PermissionButton>
    </div>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-table :data="employees" empty-text="暂无员工" border>
        <el-table-column prop="employeeNo" label="员工编号" width="130" />
        <el-table-column prop="name" label="姓名" width="130" />
        <el-table-column prop="phone" label="电话" width="150" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <CodeTag category="workforce.employmentStatus" :code="row.employmentStatus" />
          </template>
        </el-table-column>
        <el-table-column label="资格" min-width="200">
          <template #default="{ row }">
            <CodeTag
              v-for="item in row.qualifications"
              :key="item.workTypeCode"
              category="workforce.workType"
              :code="item.workTypeCode"
              class="tag"
            />
          </template>
        </el-table-column>
        <el-table-column prop="latestEffectiveDate" label="最近生效日" width="130" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push(`/workforce/employees/${row.employeeBusinessId}`)"
            >
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageState>
  </section>
</template>

<style scoped>
.employee-page {
  background: var(--yumi-surface);
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

.tag {
  margin-right: 4px;
}

.alert {
  margin-bottom: 12px;
}
</style>
