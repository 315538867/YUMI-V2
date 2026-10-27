import raw from './domain-codes.json'

export type CodeTagType = 'success' | 'warning' | 'info' | 'danger' | 'primary' | ''

interface CodeEntry {
  label: string
  tag: CodeTagType
}

interface CodeCatalog {
  version: number
  categories: Record<string, Record<string, CodeEntry>>
}

const catalog = raw as CodeCatalog

/** 展示层唯一编码词典：后端只返回稳定 code，中文与标签样式集中在这里。 */
export function codeLabel(category: string, code: string | null | undefined, fallback = '未知'): string {
  if (code === null || code === undefined || code === '') {
    return '—'
  }
  return catalog.categories[category]?.[code]?.label ?? fallback
}

export function codeTagType(category: string, code: string | null | undefined): CodeTagType {
  if (code === null || code === undefined || code === '') {
    return ''
  }
  return catalog.categories[category]?.[code]?.tag ?? 'info'
}

export function hasCode(category: string, code: string): boolean {
  return catalog.categories[category]?.[code] !== undefined
}

export function categories(): string[] {
  return Object.keys(catalog.categories)
}

export function codesOf(category: string): string[] {
  return Object.keys(catalog.categories[category] ?? {})
}

export { catalog }
