<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { AccountView, RoleView } from '@/modules/platform-admin/services/administrationService'
import { administrationService } from '@/modules/platform-admin/services/administrationService'
import CodeTag from '@/shared/components/CodeTag.vue'
import { codeLabel } from '@/platform/display/codeCatalog'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { errorText, run } = useAsyncLoad()
const { loading: saving, run: runAction } = useAsyncAction()

const accounts = ref<AccountView[]>([])
const roles = ref<RoleView[]>([])
const permissions = ref<string[]>([])
const successText = ref('')
const assignVisible = ref(false)
const roleVisible = ref(false)
const createVisible = ref(false)
const activeAccount = ref<AccountView | null>(null)
const activeRole = ref<RoleView | null>(null)
const selectedRoles = ref<string[]>([])
const createForm = reactive({ loginName: '', initialPassword: '', displayName: '' })
const roleForm = reactive({
  roleCode: '',
  name: '',
  permissionCodes: [] as string[],
})

async function load(): Promise<void> {
  await run(async () => {
    ;[accounts.value, roles.value, permissions.value] = await Promise.all([
      administrationService.accounts(),
      administrationService.roles(),
      administrationService.permissions(),
    ])
  }, '账号与角色加载失败')
}

function openCreate(): void {
  createForm.loginName = ''
  createForm.initialPassword = ''
  createForm.displayName = ''
  createVisible.value = true
}

async function createAccount(): Promise<void> {
  await runAction(
    () =>
      administrationService.createAccount({
        loginName: createForm.loginName.trim(),
        initialPassword: createForm.initialPassword,
        displayName: createForm.displayName.trim(),
      }),
    {
      success: '账号已创建；请分配角色',
      fallback: '账号创建失败',
      onSuccess: async (created) => {
        createVisible.value = false
        await load()
        const account = accounts.value.find(
          (row) => row.accountBusinessId === created.accountBusinessId,
        )
        if (account !== undefined) {
          openAssign(account)
        }
      },
    },
  )
}

function openAssign(account: AccountView): void {
  activeAccount.value = account
  selectedRoles.value = [...account.roleCodes]
  assignVisible.value = true
}

async function assign(): Promise<void> {
  if (activeAccount.value === null) {
    return
  }
  await runAction(
    () =>
      administrationService.assignRoles(
        activeAccount.value!.accountBusinessId,
        selectedRoles.value,
        activeAccount.value!.rowVersion,
      ),
    {
      success: '账号角色已更新',
      fallback: '账号授权失败',
      onSuccess: async () => {
        assignVisible.value = false
        successText.value = '账号角色已更新'
        await load()
      },
    },
  )
}

async function toggleStatus(account: AccountView): Promise<void> {
  const target = account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const message = target === 'ACTIVE' ? '账号已启用' : '账号已停用，立即生效'
  await runAction(
    () =>
      administrationService.changeAccountStatus(
        account.accountBusinessId,
        target,
        account.rowVersion,
      ),
    {
      success: message,
      fallback: '账号状态更新失败',
      onSuccess: async () => {
        successText.value = message
        await load()
      },
    },
  )
}

function openRole(role: RoleView | null): void {
  activeRole.value = role
  roleForm.roleCode = role?.roleCode ?? ''
  roleForm.name = role?.name ?? ''
  roleForm.permissionCodes = role?.permissionCodes ?? []
  roleVisible.value = true
}

async function saveRole(): Promise<void> {
  await runAction(
    () =>
      administrationService.saveRole({
        roleBusinessId: activeRole.value?.roleBusinessId ?? null,
        roleCode: roleForm.roleCode,
        name: roleForm.name,
        permissionCodes: roleForm.permissionCodes,
        expectedRowVersion: activeRole.value?.rowVersion ?? 0,
      }),
    {
      success: '角色已保存',
      fallback: '角色保存失败',
      onSuccess: async () => {
        roleVisible.value = false
        successText.value = '角色已保存'
        await load()
      },
    },
  )
}

function roleName(code: string): string {
  return roles.value.find((role) => role.roleCode === code)?.name ?? '未知角色'
}

onMounted(load)
</script>

<template>
  <WorkspacePage class="account-role-page" title="账号与角色" hint="管理登录账号、角色与权限">
    <template #actions>
      <el-button type="primary" @click="openCreate">新增账号</el-button>
    </template>
    <el-alert
      v-if="errorText"
      type="error"
      :closable="false"
      :title="errorText"
      class="yumi-alert"
    />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="yumi-alert"
    />
    <el-table :data="accounts" border>
      <el-table-column prop="loginName" label="登录名" width="220" />
      <el-table-column prop="displayName" label="显示名" width="160" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <CodeTag category="platform.accountStatus" :code="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="code in row.roleCodes" :key="code" class="tag">{{
            roleName(code)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190">
        <template #default="{ row }">
          <el-button link type="primary" @click="openAssign(row)">授权角色</el-button>
          <el-button
            link
            :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
            @click="toggleStatus(row)"
          >
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="yumi-section-head">
      <h2>角色定义</h2>
      <el-button @click="openRole(null)">新增角色</el-button>
    </div>
    <el-table :data="roles" border>
      <el-table-column prop="roleCode" label="角色编码" width="160" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column label="权限" min-width="300">
        <template #default="{ row }">
          <CodeTag
            v-for="code in row.permissionCodes"
            :key="code"
            category="platform.permission"
            :code="code"
            class="tag"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRole(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新增账号" width="460px">
      <el-form label-width="92px">
        <el-form-item label="登录名" required>
          <el-input
            v-model="createForm.loginName"
            placeholder="3—64 位字母、数字、点、下划线或连字符"
          />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="createForm.displayName" placeholder="留空时使用登录名" />
        </el-form-item>
        <el-form-item label="初始口令" required>
          <el-input
            v-model="createForm.initialPassword"
            type="password"
            show-password
            placeholder="至少 8 位"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createAccount">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="assignVisible" title="账号授权" width="460px">
      <el-form label-width="92px">
        <el-form-item label="账号">{{ activeAccount?.loginName }}</el-form-item>
        <el-form-item label="角色">
          <el-select v-model="selectedRoles" multiple>
            <el-option
              v-for="role in roles"
              :key="role.roleCode"
              :label="role.name"
              :value="role.roleCode"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="assign">保存授权</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="roleVisible"
      :title="activeRole === null ? '新增角色' : '编辑角色'"
      width="560px"
    >
      <el-form label-width="92px">
        <el-form-item label="角色编码"
          ><el-input v-model="roleForm.roleCode" :disabled="activeRole !== null"
        /></el-form-item>
        <el-form-item label="角色名称"><el-input v-model="roleForm.name" /></el-form-item>
        <el-form-item label="权限">
          <el-select v-model="roleForm.permissionCodes" multiple filterable>
            <el-option
              v-for="code in permissions"
              :key="code"
              :label="codeLabel('platform.permission', code)"
              :value="code"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>
  </WorkspacePage>
</template>

<style scoped>
.tag {
  margin-right: var(--yumi-space-1);
}
</style>
