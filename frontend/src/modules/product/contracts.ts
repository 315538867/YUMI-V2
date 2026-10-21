import type { MoneyWire } from '@/platform/services/money'

export type ProductStatus = 'ACTIVE' | 'INACTIVE'
export type MaterialSource = 'STUDIO' | 'CUSTOMER'
export type ProcessCode = 'MAKING' | 'BAGGING' | 'SEAM_CUTTING'
export type SchemeType = 'SYSTEM_STANDARD' | 'CUSTOM'

export interface BudgetLineView {
  componentCode: string
  processCode: string | null
  costNature: string
  bearerType: string
  quantity: string
  unitPrice: MoneyWire
  fullResourceCost: MoneyWire
  studioBorneCost: MoneyWire
}

export interface SchemeBudgetView {
  fullResourceUnitCost: MoneyWire
  studioBorneUnitCost: MoneyWire
  customerSuppliedUnitValue: MoneyWire
  expectedUnitProfit: MoneyWire
  expectedProfitRate: number | null
  formulaRuleVersion: string
  roundingVersion: string
  inputFingerprint: string
  lines: BudgetLineView[]
}

export interface SchemeView {
  schemeBusinessId: string
  productBusinessId: string
  schemeType: SchemeType
  name: string
  standardWeightGrams: string
  targetProcess: ProcessCode
  salePrice: MoneyWire
  glueSource: MaterialSource
  colorPasteSource: MaterialSource
  status: ProductStatus
  sourceProductContentVersion: number
  contentVersion: number
  rowVersion: number
  making: { standardMinutes: number; makingCommission: MoneyWire; otherMaterialCost: MoneyWire }
  bagging: {
    standardMinutes: number
    laborCost: MoneyWire
    bagUnitCost: MoneyWire
    bagSource: MaterialSource
    decorationUnitPrice: MoneyWire
    decorationSource: MaterialSource
    otherCost: MoneyWire
  }
  seamCutting: {
    standardMinutes: number
    laborCost: MoneyWire
    combinedMaterialCost: MoneyWire
    otherCost: MoneyWire
  }
  extraCosts: Record<string, MoneyWire>
  latestBudget: SchemeBudgetView | null
}

export interface ProductView {
  productBusinessId: string
  productNo: string
  name: string
  description: string
  standardWeightGrams: string
  makingStarCode: string
  makingStarName: string
  makingStandardMinutes: number
  standardSalePrice: MoneyWire
  glueLossRate: string
  glueUnitPrice: MoneyWire
  colorPasteUnitPrice: MoneyWire
  makingCommission: MoneyWire
  mouldCount: number
  mouldDailyBatches: number
  dailyCapacity: number
  status: ProductStatus
  contentVersion: number
  rowVersion: number
  making: { otherMaterialCost: MoneyWire }
  bagging: {
    standardMinutes: number
    laborCost: MoneyWire
    bagUnitCost: MoneyWire
    bagSource: MaterialSource
    decorationUnitPrice: MoneyWire
    decorationSource: MaterialSource
    otherCost: MoneyWire
  }
  seamCutting: {
    standardMinutes: number
    laborCost: MoneyWire
    combinedMaterialCost: MoneyWire
    otherCost: MoneyWire
  }
  extraCosts: {
    otherTaskLaborCost: MoneyWire
    transportPackagingCost: MoneyWire
    dailyMiscCost: MoneyWire
    rentUtilitiesCost: MoneyWire
    mouldUnitAmortizationCost: MoneyWire
    otherCost: MoneyWire
  }
}

export interface ProductDefinition {
  name: string
  description: string
  standardWeightGrams: string
  makingStarCode: string
  makingStandardMinutes: number
  standardSalePrice: MoneyWire
  glueLossRate: string
  glueUnitPrice: MoneyWire
  colorPasteUnitPrice: MoneyWire
  makingCommission: MoneyWire
  mouldCount: number
  mouldDailyBatches: number
  makingOtherMaterialCost: MoneyWire
  baggingStandardMinutes: number
  baggingLaborCost: MoneyWire
  baggingBagUnitCost: MoneyWire
  baggingDecorationUnitPrice: MoneyWire
  baggingOtherCost: MoneyWire
  seamStandardMinutes: number
  seamLaborCost: MoneyWire
  seamCombinedMaterialCost: MoneyWire
  seamOtherCost: MoneyWire
  extraOtherTaskLaborCost: MoneyWire
  extraTransportPackagingCost: MoneyWire
  extraDailyMiscCost: MoneyWire
  extraRentUtilitiesCost: MoneyWire
  extraMouldAmortizationCost: MoneyWire
  extraOtherCost: MoneyWire
}

export interface SchemeInput {
  name: string
  targetProcess: ProcessCode
  salePrice: MoneyWire
  glueSource: MaterialSource
  colorPasteSource: MaterialSource
  makingOtherMaterialCost: MoneyWire
  baggingBagUnitCost: MoneyWire
  baggingBagSource: MaterialSource
  baggingDecorationUnitPrice: MoneyWire
  baggingDecorationSource: MaterialSource
  baggingOtherCost: MoneyWire
  seamCombinedMaterialCost: MoneyWire
  seamOtherCost: MoneyWire
}

export interface EntryConfiguration {
  glueLossRate: string
  glueUnitPrice: MoneyWire
  colorPasteUnitPrice: MoneyWire
  rowVersion: number
  makingStars: Array<{ starCode: string; name: string; standardMinutes: number; sortOrder: number }>
  processTimeOptions: Array<{
    optionBusinessId: string
    processCode: string
    optionCode: string
    name: string
    standardMinutes: number
    sortOrder: number
  }>
}
