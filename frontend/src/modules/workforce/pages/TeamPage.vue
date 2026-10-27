<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import type { EmployeeView, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const auth = useAuthStore()
const teams = ref<TeamView[]>([])
const employees = ref<EmployeeView[]>([])
const loading = ref(false)
const errorText = ref('')
const dialogVisible = ref(false)
const editing = ref<TeamView | null>(null)
const form = reactive<{ name: string; memberEmployeeBusinessIds: string[] }>({
  name: '',
  memberEmployeeBusinessIds: [],
})
const canManage = auth.hasPermission('workforce.team.manage')

async function load(): Promise<void> {
  loading.value = true
  try {
    ;[teams.value, employees.value] = await Promise.all([
      workforceService.teams(),
      workforceService.employees(),
    ])
  } catch (error) {
    errorText.value = userMessageOf(error, '小组加载失败')
  } finally {
    loading.value = false
  }
}

function open(team: TeamView | null): void {
  editing.value = team
  form.name = team?.name ?? ''
  form.memberEmployeeBusinessIds = team?.members.map((member) => member.employeeBusinessId) ?? []
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  try {
    if (editing.value === null) {
      await workforceService.createTeam({ ...form })
      ElMessage.success('小组已创建')
    } else {
      await workforceService.updateTeam(editing.value.teamBusinessId, {
        ...form,
        expectedRowVersion: editing.value.rowVersion,
      })
      ElMessage.success('小组已保存')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '小组保存失败')
  }
}

async function deactivate(team: TeamView): Promise<void> {
  try {
    await ElMessageBox.confirm(`停用小组「${team.name}」？活动引用未释放时后端会拒绝。`, '停用小组')
    await workforceService.deactivateTeam(team.teamBusinessId, team.rowVersion)
    ElMessage.success('小组已停用')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      errorText.value = userMessageOf(error, '小组停用失败')
    }
  }
}

onMounted(load)
</script>

<template>
  <section class="team-page">
    <div class="toolbar">
      <h1>小组</h1>
      <PermissionButton permission="workforce.team.manage" type="primary" @click="open(null)"
        >新增小组</PermissionButton
      >
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-table v-loading="loading" :data="teams" empty-text="暂无小组" border>
      <el-table-column prop="teamNo" label="小组编号" width="130" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <CodeTag category="common.status" :code="row.status" />
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
      <el-table-column v-if="canManage" label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">编辑</el-button>
          <el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="deactivate(row)">
            停用
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="editing === null ? '新增小组' : '编辑小组'"
      width="520px"
    >
      <el-form label-width="92px">
        <el-form-item label="小组名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="成员">
          <el-select v-model="form.memberEmployeeBusinessIds" multiple filterable>
            <el-option
              v-for="employee in employees"
              :key="employee.employeeBusinessId"
              :label="`${employee.name}（${employee.employeeNo}）`"
              :value="employee.employeeBusinessId"
              :disabled="employee.employmentStatus !== 'EMPLOYED'"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
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

.alert {
  margin-bottom: 12px;
}
</style>
