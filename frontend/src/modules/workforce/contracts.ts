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
  activeReferenceCount: number
}

export interface WorkforceStandardView {
  dailyStandardBusinessId: string
  effectiveMinutes: number
  dailyContentVersion: number
  dailyRowVersion: number
  dailyActiveReferenceCount: number
  efficiencyStandards: EfficiencyStandardView[]
}

export interface EmploymentFactView {
  factBusinessId: string
  factType: 'HIRE' | 'LEAVE' | 'REHIRE'
  effectiveDate: string
  reason: string | null
  occurredAt: string
  operatorId: string
}

export interface QualificationFactView {
  factBusinessId: string
  workTypeCode: string
  factType: 'GRANT' | 'REVOKE'
  effectiveDate: string
  occurredAt: string
  operatorId: string
}

export interface EmployeeHistoryView {
  employment: EmploymentFactView[]
  qualifications: QualificationFactView[]
}

export interface TeamMemberFactView {
  factBusinessId: string
  employeeBusinessId: string
  employeeNo: string | null
  employeeName: string | null
  factType: 'JOIN' | 'LEAVE'
  occurredAt: string
  operatorId: string
}

export interface TeamStatusFactView {
  factBusinessId: string
  factType: string
  status: TeamStatus
  reason: string | null
  occurredAt: string
  operatorId: string
}

export interface TeamHistoryView {
  members: TeamMemberFactView[]
  statuses: TeamStatusFactView[]
}

export interface StandardVersionView {
  versionBusinessId: string
  contentVersion: number
  memberCount: number | null
  efficiencyFactor: string | null
  effectiveMinutes: number | null
  reason: string | null
  occurredAt: string
  operatorId: string
}

export interface WorkforceStandardVersionView {
  standardType: 'EFFICIENCY' | 'DAILY_WORK_TIME'
  standardBusinessId: string
  versions: StandardVersionView[]
}

export interface WorkforceActivityReferenceView {
  reservationBusinessId: string
  sourceModule: string
  sourceType: string
  sourceBusinessId: string
  workTypeCode: string | null
  workDate: string | null
  status: string
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
