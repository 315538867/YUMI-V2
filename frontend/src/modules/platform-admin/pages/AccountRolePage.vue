<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { AccountView, RoleView } from '@/modules/platform-admin/services/administrationService'
import { administrationService } from '@/modules/platform-admin/services/administrationService'
import CodeTag from '@/shared/components/CodeTag.vue'
import { codeLabel } from '@/platform/display/codeCatalog'

const accounts = ref<AccountView[]>([])
const roles = ref<RoleView[]>([])
const permissions = ref<string[]>([])
const errorText = ref('')
const successText = ref('')
const assignVisible = ref(false)
const roleVisible = ref(false)
const activeAccount = ref<AccountView | null>(null)
const activeRole = ref<RoleView | null>(null)
const selectedRoles = ref<string[]>([])
const roleForm = reactive({
  roleCode: '',
  name: '',
  permissionCodes: [] as string[],
})

async function load(): Promise<void> {
  try {
    ;[accounts.value, roles.value, permissions.value] = await Promise.all([
      administrationService.accounts(),
      administrationService.roles(),
      administrationService.permissions(),
    ])
  } catch (error) {
    errorText.value = userMessageOf(error, '账号与角色加载失败')
  }
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
  try {
    await administrationService.assignRoles(
      activeAccount.value.accountBusinessId,
      selectedRoles.value,
      activeAccount.value.rowVersion,
    )
    assignVisible.value = false
    successText.value = '账号角色已更新'
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '账号授权失败')
  }
}

async function toggleStatus(account: AccountView): Promise<void> {
  try {
    const target = account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    await administrationService.changeAccountStatus(
      account.accountBusinessId,
      target,
      account.rowVersion,
    )
    successText.value = target === 'ACTIVE' ? '账号已启用' : '账号已停用，立即生效'
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '账号状态更新失败')
  }
}

function openRole(role: RoleView | null): void {
  activeRole.value = role
  roleForm.roleCode = role?.roleCode ?? ''
  roleForm.name = role?.name ?? ''
  roleForm.permissionCodes = role?.permissionCodes ?? []
  roleVisible.value = true
}

async function saveRole(): Promise<void> {
  try {
    await administrationService.saveRole({
      roleBusinessId: activeRole.value?.roleBusinessId ?? null,
      roleCode: roleForm.roleCode,
      name: roleForm.name,
      permissionCodes: roleForm.permissionCodes,
      expectedRowVersion: activeRole.value?.rowVersion ?? 0,
    })
    roleVisible.value = false
    successText.value = '角色已保存'
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '角色保存失败')
  }
}

function roleName(code: string): string {
  return roles.value.find((role) => role.roleCode === code)?.name ?? '未知角色'
}

onMounted(load)
</script>

<template>
  <section class="account-role-page">
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert v-if="successText" type="success" :closable="false" :title="successText" class="alert" />
    <h1>账号与角色</h1>
    <el-table :data="accounts" border>
      <el-table-column prop="loginName" label="登录名" width="160" />
      <el-table-column prop="displayName" label="显示名" width="160" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <CodeTag category="common.status" :code="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="code in row.roleCodes" :key="code" class="tag">{{ roleName(code) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190">
        <template #default="{ row }">
          <el-button link type="primary" @click="openAssign(row)">授权角色</el-button>
          <el-button link :type="row.status === 'ACTIVE' ? 'warning' : 'success'" @click="toggleStatus(row)">
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="roles-head">
      <h2>角色定义</h2>
      <el-button @click="openRole(null)">新增角色</el-button>
    </div>
    <el-table :data="roles" border>
      <el-table-column prop="roleCode" label="角色编码" width="160" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column label="权限" min-width="300">
        <template #default="{ row }">
          <CodeTag v-for="code in row.permissionCodes" :key="code" category="platform.permission" :code="code" class="tag" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRole(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="assignVisible" title="账号授权" width="460px">
      <el-form label-width="92px">
        <el-form-item label="账号">{{ activeAccount?.loginName }}</el-form-item>
        <el-form-item label="角色">
          <el-select v-model="selectedRoles" multiple>
            <el-option v-for="role in roles" :key="role.roleCode" :label="role.name" :value="role.roleCode" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" @click="assign">保存授权</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleVisible" :title="activeRole === null ? '新增角色' : '编辑角色'" width="560px">
      <el-form label-width="92px">
        <el-form-item label="角色编码"><el-input v-model="roleForm.roleCode" :disabled="activeRole !== null" /></el-form-item>
        <el-form-item label="角色名称"><el-input v-model="roleForm.name" /></el-form-item>
        <el-form-item label="权限">
          <el-select v-model="roleForm.permissionCodes" multiple filterable>
            <el-option v-for="code in permissions" :key="code" :label="codeLabel('platform.permission', code)" :value="code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.account-role-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 20px; }
h1 { margin: 0 0 12px; font-size: 18px; }
h2 { margin: 0; font-size: 16px; }
.roles-head { display: flex; align-items: center; justify-content: space-between; margin: 24px 0 10px; }
.tag { margin-right: 4px; }
.alert { margin-bottom: 12px; }
</style>
