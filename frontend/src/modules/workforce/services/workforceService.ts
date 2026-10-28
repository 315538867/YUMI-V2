import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import type {
  AssignmentSubjectCurrentView,
  EmployeeHistoryView,
  EmployeeView,
  EmploymentStatus,
  TeamStatus,
  TeamHistoryView,
  TeamView,
  WorkforceActivityReferenceView,
  WorkforceStandardVersionView,
  WorkforceStandardView,
} from '@/modules/workforce/contracts'

export const workforceService = {
  employees(): Promise<EmployeeView[]> {
    return api.query<EmployeeView[]>('/api/workforce/employees')
  },

  searchEmployees(
    keyword: string,
    status: EmploymentStatus | '',
    workTypeCode: string,
    page = 1,
    size = 20,
  ): Promise<EmployeeView[]> {
    const search = new URLSearchParams({ page: String(page), size: String(size) })
    if (keyword.trim() !== '') {
      search.set('keyword', keyword.trim())
    }
    if (status !== '') {
      search.set('status', status)
    }
    if (workTypeCode !== '') {
      search.set('workTypeCode', workTypeCode)
    }
    return api.query<EmployeeView[]>(`/api/workforce/employees?${search.toString()}`)
  },

  employee(employeeBusinessId: string): Promise<EmployeeView> {
    return api.query<EmployeeView>(
      `/api/workforce/employees/${encodeURIComponent(employeeBusinessId)}`,
    )
  },

  employeeHistory(employeeBusinessId: string): Promise<EmployeeHistoryView> {
    return api.query<EmployeeHistoryView>(
      `/api/workforce/employees/${encodeURIComponent(employeeBusinessId)}/history`,
    )
  },

  teams(): Promise<TeamView[]> {
    return api.query<TeamView[]>('/api/workforce/teams')
  },

  searchTeams(keyword: string, status: TeamStatus | '', page = 1, size = 20): Promise<TeamView[]> {
    const search = new URLSearchParams({ page: String(page), size: String(size) })
    if (keyword.trim() !== '') {
      search.set('keyword', keyword.trim())
    }
    if (status !== '') {
      search.set('status', status)
    }
    return api.query<TeamView[]>(`/api/workforce/teams?${search.toString()}`)
  },

  team(teamBusinessId: string): Promise<TeamView> {
    return api.query<TeamView>(`/api/workforce/teams/${encodeURIComponent(teamBusinessId)}`)
  },

  teamHistory(teamBusinessId: string): Promise<TeamHistoryView> {
    return api.query<TeamHistoryView>(
      `/api/workforce/teams/${encodeURIComponent(teamBusinessId)}/history`,
    )
  },

  hire(input: { name: string; phone: string; effectiveDate: string }): Promise<EmployeeView> {
    return api.command<EmployeeView, typeof input>('/api/workforce/employees', {
      idempotencyKey: newIdempotencyKey('employee-hire'),
      body: input,
    })
  },

  updateEmployee(
    employeeBusinessId: string,
    input: { name: string; phone: string; expectedRowVersion: number },
  ): Promise<EmployeeView> {
    return api.command<EmployeeView, typeof input>(
      `/api/workforce/employees/${encodeURIComponent(employeeBusinessId)}`,
      {
        method: 'PUT',
        idempotencyKey: newIdempotencyKey('employee-update'),
        body: input,
      },
    )
  },

  leave(
    employeeBusinessId: string,
    input: { effectiveDate: string; reason: string; expectedRowVersion: number },
  ): Promise<EmployeeView> {
    return api.command<EmployeeView, typeof input>(
      `/api/workforce/employees/${encodeURIComponent(employeeBusinessId)}/leave`,
      {
        idempotencyKey: newIdempotencyKey('employee-leave'),
        body: input,
      },
    )
  },

  rehire(
    employeeBusinessId: string,
    input: { effectiveDate: string; expectedRowVersion: number },
  ): Promise<EmployeeView> {
    return api.command<EmployeeView, typeof input>(
      `/api/workforce/employees/${encodeURIComponent(employeeBusinessId)}/rehire`,
      {
        idempotencyKey: newIdempotencyKey('employee-rehire'),
        body: input,
      },
    )
  },

  changeQualification(
    employeeBusinessId: string,
    action: 'grant' | 'revoke',
    input: { workTypeCode: string; effectiveDate: string },
  ): Promise<EmployeeView> {
    return api.command<EmployeeView, typeof input>(
      `/api/workforce/employees/${encodeURIComponent(employeeBusinessId)}/qualifications/${action}`,
      {
        idempotencyKey: newIdempotencyKey(`qualification-${action}`),
        body: input,
      },
    )
  },

  createTeam(input: { name: string; memberEmployeeBusinessIds: string[] }): Promise<TeamView> {
    return api.command<TeamView, typeof input>('/api/workforce/teams', {
      idempotencyKey: newIdempotencyKey('team-create'),
      body: input,
    })
  },

  updateTeam(
    teamBusinessId: string,
    input: { name: string; memberEmployeeBusinessIds: string[]; expectedRowVersion: number },
  ): Promise<TeamView> {
    return api.command<TeamView, typeof input>(
      `/api/workforce/teams/${encodeURIComponent(teamBusinessId)}`,
      {
        method: 'PUT',
        idempotencyKey: newIdempotencyKey('team-update'),
        body: input,
      },
    )
  },

  deactivateTeam(
    teamBusinessId: string,
    expectedRowVersion: number,
    reason: string,
  ): Promise<TeamView> {
    return api.command<TeamView, { expectedRowVersion: number; reason: string }>(
      `/api/workforce/teams/${encodeURIComponent(teamBusinessId)}/deactivate`,
      {
        idempotencyKey: newIdempotencyKey('team-deactivate'),
        body: { expectedRowVersion, reason },
      },
    )
  },

  activateTeam(
    teamBusinessId: string,
    expectedRowVersion: number,
    reason: string,
  ): Promise<TeamView> {
    return api.command<TeamView, { expectedRowVersion: number; reason: string }>(
      `/api/workforce/teams/${encodeURIComponent(teamBusinessId)}/activate`,
      {
        idempotencyKey: newIdempotencyKey('team-activate'),
        body: { expectedRowVersion, reason },
      },
    )
  },

  standards(): Promise<WorkforceStandardView> {
    return api.query<WorkforceStandardView>('/api/workforce/standards')
  },

  updateWorkTime(input: {
    effectiveMinutes: number
    expectedRowVersion: number
    reason: string
  }): Promise<WorkforceStandardView> {
    return api.command<WorkforceStandardView, typeof input>('/api/workforce/standards/work-time', {
      method: 'PUT',
      idempotencyKey: newIdempotencyKey('work-time-standard'),
      body: input,
    })
  },

  createEfficiency(input: {
    memberCount: number
    efficiencyFactor: string
    reason: string
  }): Promise<WorkforceStandardView> {
    return api.command<WorkforceStandardView, typeof input>('/api/workforce/standards/efficiency', {
      idempotencyKey: newIdempotencyKey('efficiency-standard-create'),
      body: input,
    })
  },

  updateEfficiency(input: {
    standardBusinessId: string
    memberCount: number
    efficiencyFactor: string
    expectedRowVersion: number
    reason: string
  }): Promise<WorkforceStandardView> {
    return api.command<WorkforceStandardView, typeof input>('/api/workforce/standards/efficiency', {
      method: 'PUT',
      idempotencyKey: newIdempotencyKey('efficiency-standard'),
      body: input,
    })
  },

  subject(
    subjectType: 'EMPLOYEE' | 'TEAM',
    businessId: string,
  ): Promise<AssignmentSubjectCurrentView> {
    return api.query<AssignmentSubjectCurrentView>(
      `/api/workforce/standards/subjects/${subjectType}/${encodeURIComponent(businessId)}`,
    )
  },

  subjectReferences(
    subjectType: 'EMPLOYEE' | 'TEAM',
    businessId: string,
  ): Promise<WorkforceActivityReferenceView[]> {
    return api.query<WorkforceActivityReferenceView[]>(
      `/api/workforce/subjects/${subjectType}/${encodeURIComponent(businessId)}/references`,
    )
  },

  standardHistory(
    standardType: 'EFFICIENCY' | 'DAILY_WORK_TIME',
    standardBusinessId: string,
  ): Promise<WorkforceStandardVersionView> {
    return api.query<WorkforceStandardVersionView>(
      `/api/workforce/standards/${standardType}/${encodeURIComponent(standardBusinessId)}/versions`,
    )
  },
}
