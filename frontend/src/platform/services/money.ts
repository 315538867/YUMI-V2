export interface MoneyWire {
  amount: string
  currency: string
}

export function money(amount: string): MoneyWire {
  return { amount, currency: 'CNY' }
}

/** 只读展示：保留服务端十进制字符串，不转 number 参与计算。 */
export function formatMoney(value: MoneyWire | null | undefined): string {
  return value === null || value === undefined ? '-' : `${value.amount} ${value.currency}`
}

export function amountText(value: MoneyWire | null | undefined): string {
  return value?.amount ?? ''
}
