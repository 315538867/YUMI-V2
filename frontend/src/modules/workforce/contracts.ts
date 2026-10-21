export type EmploymentStatus = 'EMPLOYED' | 'LEFT'
export type TeamStatus = 'ACTIVE' | 'INACTIVE'
export type QualificationStatus = 'ACTIVE' | 'REVOKED'

export interface QualificationView {
  workTypeCode: string
  status: QualificationStatus
  effectiveFrom: string
}

export interface EmployeeView {
  employeeBusinessId: string
  employeeNo: string
  name: string
  phone: string
  employmentStatus: EmploymentStatus
  latestEffectiveDate: string
  contentVersion: number
  rowVersion: number
  qualifications: QualificationView[]
  updatedAt: string
}

export interface TeamMemberView {
  employeeBusinessId: string
  employeeNo: string
  name: string
  employmentStatus: EmploymentStatus
}

export interface TeamView {
  teamBusinessId: string
  teamNo: string
  name: string
  status: TeamStatus
  contentVersion: number
  rowVersion: number
  members: TeamMemberView[]
  efficiencyFactor: string
  efficiencyStandardContentVersion: number
}

export interface EfficiencyStandardView {
  standardBusinessId: string
  memberCount: number
  efficiencyFactor: string
  contentVersion: number
  rowVersion: number
}

export interface WorkforceStandardView {
  dailyStandardBusinessId: string
  effectiveMinutes: number
  dailyContentVersion: number
  dailyRowVersion: number
  efficiencyStandards: EfficiencyStandardView[]
}

export interface AssignmentSubjectCurrentView {
  subject: { subjectType: 'EMPLOYEE' | 'TEAM'; businessId: string }
  name: string
  employmentStatus: EmploymentStatus | null
  teamStatus: TeamStatus | null
  qualifications: QualificationView[]
  memberEmployeeBusinessIds: string[]
  efficiencyFactor: string | null
  contentVersion: number
}
