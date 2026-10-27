<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeeView, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const router = useRouter()
const teams = ref<TeamView[]>([])
const employees = ref<EmployeeView[]>([])
const loading = ref(false)
const errorText = ref('')
const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive<{ name: string; memberEmployeeBusinessIds: string[] }>({
  name: '',
  memberEmployeeBusinessIds: [],
})

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const [currentTeams, currentEmployees] = await Promise.all([
      workforceService.teams(),
      workforceService.employees(),
    ])
    teams.value = currentTeams
    employees.value = currentEmployees
  } catch (error) {
    errorText.value = userMessageOf(error, '小组列表加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  createError.value = ''
  form.name = ''
  form.memberEmployeeBusinessIds = []
  createVisible.value = true
}

async function create(): Promise<void> {
  if (form.name.trim() === '') {
    createError.value = '请填写小组名称'
    return
  }
  if (form.memberEmployeeBusinessIds.length === 0) {
    createError.value = '请至少选择一名成员'
    return
  }
  creating.value = true
  createError.value = ''
  try {
    await workforceService.createTeam({ ...form })
    createVisible.value = false
    ElMessage.success('小组已创建')
    await load()
  } catch (error) {
    createError.value = userMessageOf(error, '小组创建失败')
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="team-page">
    <div class="toolbar">
      <h1>小组</h1>
      <PermissionButton permission="workforce.team.manage" type="primary" @click="openCreate">
        新增小组
      </PermissionButton>
    </div>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-table :data="teams" empty-text="暂无小组" border>
        <el-table-column prop="teamNo" label="小组编号" width="130" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <CodeTag category="workforce.teamStatus" :code="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="成员" min-width="240">
          <template #default="{ row }">
            <el-tag v-for="member in row.members" :key="member.employeeBusinessId" class="tag">
              {{ member.name }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="efficiencyFactor" label="效率因子" width="110" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push(`/workforce/teams/${row.teamBusinessId}`)"
            >
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageState>

    <el-dialog v-model="createVisible" title="新增小组" width="520px">
      <el-alert
        v-if="createError"
        type="error"
        :closable="false"
        :title="createError"
        class="alert"
      />
      <el-form label-width="92px">
        <el-form-item label="小组名称" required>
          <el-input v-model="form.name" aria-label="小组名称" />
        </el-form-item>
        <el-form-item label="成员" required>
          <el-select
            v-model="form.memberEmployeeBusinessIds"
            multiple
            filterable
            class="members"
            aria-label="成员"
          >
            <el-option
              v-for="employee in employees"
              :key="employee.employeeBusinessId"
              :label="`${employee.name}（${employee.employeeNo}）`"
              :value="employee.employeeBusinessId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.team-page {
  background: #fff;
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

.members {
  width: 100%;
}

.alert {
  margin-bottom: 12px;
}
</style>
