import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import type {
  AssignmentSubjectCurrentView,
  EmployeeView,
  TeamView,
  WorkforceStandardView,
} from '@/modules/workforce/contracts'

export const workforceService = {
  employees(): Promise<EmployeeView[]> {
    return api.query<EmployeeView[]>('/api/workforce/employees')
  },

  employee(employeeBusinessId: string): Promise<EmployeeView> {
    return api.query<EmployeeView>(
      `/api/workforce/employees/${encodeURIComponent(employeeBusinessId)}`,
    )
  },

  teams(): Promise<TeamView[]> {
    return api.query<TeamView[]>('/api/workforce/teams')
  },

  team(teamBusinessId: string): Promise<TeamView> {
    return api.query<TeamView>(`/api/workforce/teams/${encodeURIComponent(teamBusinessId)}`)
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

  deactivateTeam(teamBusinessId: string, expectedRowVersion: number): Promise<TeamView> {
    return api.command<TeamView, { expectedRowVersion: number }>(
      `/api/workforce/teams/${encodeURIComponent(teamBusinessId)}/deactivate`,
      {
        idempotencyKey: newIdempotencyKey('team-deactivate'),
        body: { expectedRowVersion },
      },
    )
  },

  standards(): Promise<WorkforceStandardView> {
    return api.query<WorkforceStandardView>('/api/workforce/standards')
  },

  updateWorkTime(input: {
    effectiveMinutes: number
    expectedRowVersion: number
  }): Promise<WorkforceStandardView> {
    return api.command<WorkforceStandardView, typeof input>('/api/workforce/standards/work-time', {
      method: 'PUT',
      idempotencyKey: newIdempotencyKey('work-time-standard'),
      body: input,
    })
  },

  updateEfficiency(input: {
    standardBusinessId: string
    memberCount: number
    efficiencyFactor: string
    expectedRowVersion: number
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
}
