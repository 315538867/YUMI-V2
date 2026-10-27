<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useListQuery } from '@/platform/composables/useListQuery'
import { codeLabel, codesOf } from '@/platform/display/codeCatalog'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { EmployeeView, EmploymentStatus } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import ListPage from '@/shared/components/ListPage.vue'
import ListPager from '@/shared/components/ListPager.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const { loading, errorText, run } = useAsyncLoad()

const router = useRouter()
const employees = ref<EmployeeView[]>([])
const workTypeOptions = codesOf('workforce.workType')
const { filters, page, size, sizes, search, reset, goPage, changeSize } = useListQuery(
  { keyword: '', status: '' as EmploymentStatus | '', workType: '' },
  { onSearch: () => void load() },
)

async function load(): Promise<void> {
  await run(async () => {
    employees.value = await workforceService.searchEmployees(
      filters.keyword,
      filters.status,
      filters.workType,
      page.value,
      size.value,
    )
  }, '员工列表加载失败')
}

void load()
</script>

<template>
  <ListPage
    class="employee-page"
    title="员工与资格"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #filters>
      <el-input
        v-model="filters.keyword"
        placeholder="姓名、编号或电话"
        clearable
        class="keyword"
        aria-label="姓名、编号或电话"
        @keyup.enter="search"
      />
      <el-select v-model="filters.status" placeholder="全部状态" clearable aria-label="在职状态">
        <el-option label="在职" value="EMPLOYED" />
        <el-option label="离职" value="LEFT" />
      </el-select>
      <el-select v-model="filters.workType" placeholder="全部工种" clearable aria-label="工种资格">
        <el-option
          v-for="code in workTypeOptions"
          :key="code"
          :label="codeLabel('workforce.workType', code)"
          :value="code"
        />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </template>
    <template #actions>
      <PermissionButton
        permission="workforce.employee.manage"
        type="primary"
        @click="router.push('/workforce/employees/new')"
      >
        新增员工
      </PermissionButton>
    </template>
    <el-table :data="employees" empty-text="没有匹配的员工" border>
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
    <template #pager>
      <ListPager
        :page="page"
        :size="size"
        :count="employees.length"
        :sizes="sizes"
        @update:page="goPage"
        @update:size="changeSize"
      />
    </template>
  </ListPage>
</template>

<style scoped>
.keyword {
  width: 220px;
}

.tag {
  margin-right: 4px;
}
</style>
