<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isErrorType, userMessageOf } from '@/platform/services/errorMessage'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
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
const errorText = ref('')
const conflictError = ref('')
const validationErrors = reactive<Record<string, string>>({})
const { loading: saving, fieldError, otherFieldErrors, run } = useAsyncAction()

const form = reactive({
  name: '',
  memberEmployeeBusinessIds: [] as string[],
})
const initialSnapshot = ref(JSON.stringify(form))
const isDirty = computed(() => JSON.stringify(form) !== initialSnapshot.value)
const { allowLeave } = useUnsavedGuard(() => isDirty.value)
const formAlert = computed(
  () => conflictError.value || otherFieldErrors('name', 'memberEmployeeBusinessIds').join('；'),
)

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
    initialSnapshot.value = JSON.stringify(form)
  } catch (error) {
    errorText.value = userMessageOf(error, '小组资料加载失败')
  } finally {
    loading.value = false
  }
}

function validate(): boolean {
  for (const key of Object.keys(validationErrors)) {
    delete validationErrors[key]
  }
  if (form.name.trim() === '') {
    validationErrors.name = '请填写小组名称'
  }
  if (form.memberEmployeeBusinessIds.length === 0) {
    validationErrors.memberEmployeeBusinessIds = '请至少选择一名成员'
  }
  return Object.keys(validationErrors).length === 0
}

async function save(): Promise<void> {
  conflictError.value = ''
  if (!validate()) {
    return
  }
  const action =
    editing && team.value !== null
      ? () =>
          workforceService.updateTeam(teamId, {
            name: form.name,
            memberEmployeeBusinessIds: form.memberEmployeeBusinessIds,
            expectedRowVersion: team.value!.rowVersion,
          })
      : () => workforceService.createTeam({ ...form })
  await run(action, {
    success: editing ? '小组已保存' : '小组已创建',
    fallback: '保存失败',
    handled: (error) => {
      if (isErrorType(error, 'CONCURRENT_CONFLICT')) {
        conflictError.value = '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖'
        return true
      }
      return false
    },
    onSuccess: async (saved) => {
      allowLeave()
      await router.push(
        editing ? `/workforce/teams/${teamId}` : `/workforce/teams/${saved.teamBusinessId}`,
      )
    },
  })
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
            v-if="formAlert"
            type="error"
            :closable="false"
            :title="formAlert"
            class="alert"
          />
          <el-form-item
            label="小组名称"
            required
            :error="validationErrors.name || fieldError('name')"
          >
            <el-input v-model="form.name" aria-label="小组名称" />
          </el-form-item>
          <el-form-item
            label="成员"
            required
            :error="
              validationErrors.memberEmployeeBusinessIds || fieldError('memberEmployeeBusinessIds')
            "
          >
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
