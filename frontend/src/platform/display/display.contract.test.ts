import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/** 结构守卫：模板不得直接渲染领域编码字段，必须经 CodeLabel/CodeTag 走共享码表。 */
const SRC = join(process.cwd(), 'src')

const CODE_FIELDS = [
  'status',
  'origin',
  'targetProcess',
  'processCode',
  'taskType',
  'taskStatus',
  'demandType',
  'demandStatus',
  'subjectType',
  'workTypeCode',
  'employmentStatus',
  'qualificationStatus',
  'categoryCode',
  'cashType',
  'costCategory',
  'operationType',
  'sourceModule',
  'sourceType',
  'feedCode',
  'linkType',
  'correctionType',
  'factKind',
  'itemKind',
  'effectType',
  'schemeType',
  'routeType',
  'obligationType',
  'conditionCode',
  'moduleCode',
  'actionCode',
  'resultCode',
  'lineType',
  'componentCode',
  'roleCodes',
  'permissionCodes',
]

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry)
    return statSync(full).isDirectory() ? walk(full) : [full]
  })
}

function templateOf(content: string): string {
  const match = content.match(/<template>([\s\S]*)<\/template>/)
  return match?.[1] ?? ''
}

describe('前端展示编码门禁', () => {
  it('模板不直接渲染领域编码字段', () => {
    const offenders: string[] = []
    for (const file of walk(SRC).filter((path) => path.endsWith('.vue'))) {
      const template = templateOf(readFileSync(file, 'utf8'))
      for (const field of CODE_FIELDS) {
        const columnPattern = new RegExp(`<el-table-column[^>]*prop="${field}"`)
        if (columnPattern.test(template)) {
          offenders.push(`${file}: 表格列 prop="${field}"`)
        }
        const expressionPattern = new RegExp(`\\{\\{\\s*[^}]*\\.${field}\\s*\\}\\}`)
        if (expressionPattern.test(template)) {
          offenders.push(`${file}: 模板表达式 .${field}`)
        }
      }
    }
    expect(offenders, offenders.join('\n')).toEqual([])
  })
})
