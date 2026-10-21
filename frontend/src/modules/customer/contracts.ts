export type CustomerStatus = 'ACTIVE' | 'INACTIVE'

export interface CustomerView {
  customerBusinessId: string
  customerNo: string
  name: string
  contactName: string
  phone: string
  defaultAddress: string
  status: CustomerStatus
  contentVersion: number
  rowVersion: number
  updatedAt: string
}

export interface CustomerChangeView {
  factBusinessId: string
  versionBefore: number
  versionAfter: number
  operationType: string
  changedFieldsJson: string
  reasonText: string | null
  occurredAt: string
  operatorId: string
}

export interface CreateCustomerInput {
  name: string
  contactName: string
  phone: string
  defaultAddress: string
}

export interface UpdateCustomerInput extends CreateCustomerInput {
  expectedRowVersion: number
}
