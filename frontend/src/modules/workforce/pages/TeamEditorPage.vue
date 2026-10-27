<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { isErrorType, userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeeView, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import PageState from '@/shared/components/PageState.vue'

const route = useRoute()
const router = useRouter()
const teamId = String(route.params.teamBusinessId ?? '')
const team = ref<TeamView | null>(null)
const employees = ref<EmployeeView[]>([])
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')
const formError = ref('')

const form = reactive({
  name: '',
  memberEmployeeBusinessIds: [] as string[],
})

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const [current, people] = await Promise.all([
      workforceService.team(teamId),
      workforceService.employees(),
    ])
    team.value = current
    employees.value = people
    form.name = current.name
    form.memberEmployeeBusinessIds = current.members.map((member) => member.employeeBusinessId)
  } catch (error) {
    errorText.value = userMessageOf(error, '小组资料加载失败')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  if (team.value === null) {
    return
  }
  if (form.name.trim() === '') {
    formError.value = '请填写小组名称'
    return
  }
  if (form.memberEmployeeBusinessIds.length === 0) {
    formError.value = '请至少选择一名成员'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    await workforceService.updateTeam(teamId, {
      name: form.name,
      memberEmployeeBusinessIds: form.memberEmployeeBusinessIds,
      expectedRowVersion: team.value.rowVersion,
    })
    ElMessage.success('小组已保存')
    await router.push(`/workforce/teams/${teamId}`)
  } catch (error) {
    formError.value = isErrorType(error, 'CONCURRENT_CONFLICT')
      ? '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖'
      : userMessageOf(error, '保存失败')
  } finally {
    saving.value = false
  }
}

function cancel(): void {
  void router.push(`/workforce/teams/${teamId}`)
}

onMounted(load)
</script>

<template>
  <section class="team-editor">
    <h1>编辑小组</h1>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-form v-if="!loading" label-width="96px" class="form" @submit.prevent>
        <el-alert
          v-if="formError"
          type="error"
          :closable="false"
          :title="formError"
          class="alert"
        />
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
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button @click="cancel">取消</el-button>
        </el-form-item>
      </el-form>
    </PageState>
  </section>
</template>

<style scoped>
.team-editor {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.team-editor h1 {
  margin: 0 0 12px;
  font-size: 18px;
}

.form {
  max-width: 720px;
}

.members {
  width: 100%;
}

.alert {
  margin-bottom: 12px;
}
</style>
