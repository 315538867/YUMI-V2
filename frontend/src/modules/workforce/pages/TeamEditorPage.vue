<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { isErrorType, userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeeView, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'

const route = useRoute()
const router = useRouter()
const teamId = typeof route.params.teamBusinessId === 'string' ? route.params.teamBusinessId : ''
const editing = teamId !== ''
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

const memberNames = computed(() =>
  form.memberEmployeeBusinessIds
    .map((id) => employees.value.find((employee) => employee.employeeBusinessId === id)?.name ?? id)
    .join('、'),
)

function previewStatus(): string {
  return editing ? (team.value?.status ?? 'ACTIVE') : 'ACTIVE'
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const people = await workforceService.employees()
    employees.value = people
    if (editing) {
      const current = await workforceService.team(teamId)
      team.value = current
      form.name = current.name
      form.memberEmployeeBusinessIds = current.members.map((member) => member.employeeBusinessId)
    }
  } catch (error) {
    errorText.value = userMessageOf(error, '小组资料加载失败')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
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
    if (editing && team.value !== null) {
      await workforceService.updateTeam(teamId, {
        name: form.name,
        memberEmployeeBusinessIds: form.memberEmployeeBusinessIds,
        expectedRowVersion: team.value.rowVersion,
      })
      ElMessage.success('小组已保存')
      await router.push(`/workforce/teams/${teamId}`)
    } else {
      const created = await workforceService.createTeam({ ...form })
      ElMessage.success('小组已创建')
      await router.push(`/workforce/teams/${created.teamBusinessId}`)
    }
  } catch (error) {
    formError.value = isErrorType(error, 'CONCURRENT_CONFLICT')
      ? '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖'
      : userMessageOf(error, '保存失败')
  } finally {
    saving.value = false
  }
}

function cancel(): void {
  void router.push(editing ? `/workforce/teams/${teamId}` : '/workforce/teams')
}

onMounted(load)
</script>

<template>
  <section class="team-editor">
    <header class="head">
      <h1>{{ editing ? '编辑小组' : '新增小组' }}</h1>
      <div>
        <el-button @click="cancel">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </header>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <div class="editor-grid">
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
        </el-form>

        <aside class="preview">
          <h2>小组资料预览</h2>
          <dl>
            <dt>小组名称</dt>
            <dd>{{ form.name || '未填写' }}</dd>
            <dt>成员</dt>
            <dd>{{ memberNames || '未选择' }}</dd>
            <dt>人数</dt>
            <dd>{{ form.memberEmployeeBusinessIds.length }} 人</dd>
            <dt>状态</dt>
            <dd><CodeTag category="workforce.teamStatus" :code="previewStatus()" /></dd>
          </dl>
          <p class="hint">编号由服务端生成；预览只用于核对。</p>
        </aside>
      </div>
    </PageState>
  </section>
</template>

<style scoped>
.team-editor {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

h1 {
  margin: 0;
  font-size: 18px;
}

h2 {
  margin: 0 0 10px;
  font-size: 16px;
}

.editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
}

.form {
  max-width: 720px;
}

.members {
  width: 100%;
}

.preview {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
  align-self: start;
}

.preview dl {
  display: grid;
  grid-template-columns: 84px 1fr;
  gap: 8px 12px;
  margin: 0;
}

.preview dt {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.preview dd {
  margin: 0;
  font-size: 13px;
  word-break: break-all;
}

.hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.alert {
  margin-bottom: 12px;
}
</style>
