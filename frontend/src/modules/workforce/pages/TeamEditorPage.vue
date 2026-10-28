<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import { useEditorForm } from '@/platform/composables/useEditorForm'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { EmployeeView, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import EditorPage from '@/shared/components/EditorPage.vue'
import FormErrorAlert from '@/shared/components/FormErrorAlert.vue'

const { loading, errorText, run } = useAsyncLoad(true)

const route = useRoute()
const router = useRouter()
const teamId = typeof route.params.teamBusinessId === 'string' ? route.params.teamBusinessId : ''
const editing = teamId !== ''
const team = ref<TeamView | null>(null)
const employees = ref<EmployeeView[]>([])
const { saving, validationErrors, formAlert, fieldError, validate, submit } = useEditorForm(
  ['name', 'memberEmployeeBusinessIds'],
  '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖',
)

const form = reactive({
  name: '',
  memberEmployeeBusinessIds: [] as string[],
})
const initialSnapshot = ref(JSON.stringify(form))
const isDirty = computed(() => JSON.stringify(form) !== initialSnapshot.value)
const { allowLeave } = useUnsavedGuard(() => isDirty.value)
const memberNames = computed(() =>
  form.memberEmployeeBusinessIds
    .map((id) => employees.value.find((employee) => employee.employeeBusinessId === id)?.name ?? id)
    .join('、'),
)

function previewStatus(): string {
  return editing ? (team.value?.status ?? 'ACTIVE') : 'ACTIVE'
}

async function load(): Promise<void> {
  await run(async () => {
    const people = await workforceService.employees()
    employees.value = people
    if (editing) {
      const current = await workforceService.team(teamId)
      team.value = current
      form.name = current.name
      form.memberEmployeeBusinessIds = current.members.map((member) => member.employeeBusinessId)
    }
    initialSnapshot.value = JSON.stringify(form)
  }, '小组资料加载失败')
}

async function save(): Promise<void> {
  const valid = validate([
    { field: 'name', invalid: form.name.trim() === '', message: '请填写小组名称' },
    {
      field: 'memberEmployeeBusinessIds',
      invalid: form.memberEmployeeBusinessIds.length === 0,
      message: '请至少选择一名成员',
    },
  ])
  if (!valid) {
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
  await submit(action, {
    success: editing ? '小组已保存' : '小组已创建',
    fallback: '保存失败',
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
  <EditorPage
    :title="editing ? '编辑小组' : '新增小组'"
    preview-title="小组资料预览"
    preview-hint="编号由服务端生成；预览只用于核对。"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #actions>
      <el-button @click="cancel">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
    <el-form v-if="!loading" label-width="96px" @submit.prevent>
      <FormErrorAlert :error="formAlert" class="yumi-alert" />
      <el-form-item label="小组名称" required :error="validationErrors.name || fieldError('name')">
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

    <template #preview>
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
    </template>
  </EditorPage>
</template>

<style scoped>
.members {
  width: 100%;
}
</style>
