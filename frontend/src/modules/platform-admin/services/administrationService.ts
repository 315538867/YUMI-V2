import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'

export interface AccountView {
  accountBusinessId: string
  loginName: string
  displayName: string
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED'
  rowVersion: number
  roleCodes: string[]
  createdAt: string
}

export interface AuditEntry {
  auditBusinessId: string
  requestId: string
  accountBusinessId: string
  moduleCode: string
  actionCode: string
  targetType: string
  targetBusinessId: string
  resultCode: string
  noteOfAuditBusinessId: string | null
  detail: Record<string, string>
  occurredAt: string
}

export interface AfterCommitTask {
  taskBusinessId: string
  taskType: string
  deduplicationKey: string
  status: string
  attempts: number
  maxAttempts: number
  nextRunAt: string | null
  leaseUntil: string | null
  leaseOwner: string | null
  lastError: string | null
  payload: Record<string, string>
  createdAt: string
  updatedAt: string
}

export interface RoleView {
  roleBusinessId: string
  roleCode: string
  name: string
  status: string
  permissionCodes: string[]
  rowVersion: number
}

export const administrationService = {
  accounts(): Promise<AccountView[]> {
    return api.query<AccountView[]>('/api/platform/identity/accounts')
  },

  createAccount(input: {
    loginName: string
    initialPassword: string
    displayName: string
  }): Promise<{
    accountBusinessId: string
    loginName: string
    status: string
    rowVersion: number
  }> {
    return api.command<
      { accountBusinessId: string; loginName: string; status: string; rowVersion: number },
      typeof input
    >('/api/platform/identity/accounts', {
      idempotencyKey: newIdempotencyKey('account-create'),
      body: input,
    })
  },

  changeAccountStatus(
    accountBusinessId: string,
    targetStatus: 'ACTIVE' | 'DISABLED',
    expectedRowVersion: number,
  ): Promise<AccountView> {
    return api.command<AccountView, { targetStatus: string; expectedRowVersion: number }>(
      `/api/platform/identity/accounts/${encodeURIComponent(accountBusinessId)}/status`,
      {
        idempotencyKey: newIdempotencyKey('account-status'),
        body: { targetStatus, expectedRowVersion },
      },
    )
  },

  roles(): Promise<RoleView[]> {
    return api.query<RoleView[]>('/api/platform/authorization/roles')
  },

  permissions(): Promise<string[]> {
    return api.query<string[]>('/api/platform/authorization/permissions')
  },

  assignRoles(
    accountBusinessId: string,
    roleCodes: string[],
    expectedAccountRowVersion: number,
  ): Promise<void> {
    return api.command<void, { roleCodes: string[]; expectedAccountRowVersion: number }>(
      `/api/platform/authorization/accounts/${encodeURIComponent(accountBusinessId)}/roles`,
      {
        method: 'PUT',
        idempotencyKey: newIdempotencyKey('account-roles'),
        body: { roleCodes, expectedAccountRowVersion },
      },
    )
  },

  auditByTarget(targetType: string, targetBusinessId: string): Promise<AuditEntry[]> {
    return api.query<AuditEntry[]>(
      `/api/platform/audit?targetType=${encodeURIComponent(targetType)}&targetBusinessId=${encodeURIComponent(targetBusinessId)}&limit=50`,
    )
  },

  tasks(status: string): Promise<AfterCommitTask[]> {
    return api.query<AfterCommitTask[]>(
      `/api/platform/tasks?status=${encodeURIComponent(status)}&limit=50`,
    )
  },

  retryTask(taskBusinessId: string): Promise<void> {
    return api.command(`/api/platform/tasks/${encodeURIComponent(taskBusinessId)}/retry`, {
      idempotencyKey: newIdempotencyKey('task-retry'),
      body: {},
    })
  },

  abandonTask(taskBusinessId: string, reason: string): Promise<AfterCommitTask> {
    return api.command(`/api/platform/tasks/${encodeURIComponent(taskBusinessId)}/abandon`, {
      idempotencyKey: newIdempotencyKey('task-abandon'),
      body: { reason },
    })
  },

  saveRole(input: {
    roleBusinessId: string | null
    roleCode: string
    name: string
    permissionCodes: string[]
    expectedRowVersion: number
  }): Promise<RoleView> {
    const path =
      input.roleBusinessId === null
        ? '/api/platform/authorization/roles'
        : `/api/platform/authorization/roles/${encodeURIComponent(input.roleBusinessId)}`
    return api.command<RoleView, typeof input>(path, {
      method: input.roleBusinessId === null ? 'POST' : 'PUT',
      idempotencyKey: newIdempotencyKey('role-save'),
      body: input,
    })
  },
}
