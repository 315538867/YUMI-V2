import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 结构守卫：业务模块只能通过模块 service 使用平台客户端。
 *
 * 规则来源：`docs/一期/05-跨模块接口与事务规则.md` §11。页面（`.vue`）不得直接导入
 * `apiClient` 或调用 `fetch`；`services` 目录是唯一出口。
 */
const SRC = join(process.cwd(), 'src')

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry)
    return statSync(full).isDirectory() ? walk(full) : [full]
  })
}

describe('前端接口消费边界', () => {
  it('页面不直接导入 apiClient 或使用 fetch', () => {
    const offenders = walk(SRC)
      .filter((file) => file.endsWith('.vue'))
      .filter((file) => {
        const content = readFileSync(file, 'utf8')
        return content.includes('apiClient') || /\bfetch\(/.test(content)
      })

    expect(offenders).toEqual([])
  })

  it('业务页面不直接导入平台 api 对象', () => {
    const offenders = walk(join(SRC, 'modules'))
      .filter((file) => file.endsWith('.vue'))
      .filter((file) =>
        readFileSync(file, 'utf8').includes("from '@/platform/services/api'"),
      )

    expect(offenders).toEqual([])
  })

  it('只有 platform/services 与模块 services 可以创建 API 客户端', () => {
    const offenders = walk(SRC)
      .filter((file) => file.endsWith('.ts'))
      .filter((file) => !file.includes(`${join('src', 'platform', 'services')}`))
      .filter((file) => !file.includes(`${join('services')}`))
      .filter((file) => !file.endsWith('.test.ts'))
      .filter((file) => readFileSync(file, 'utf8').includes('createApiClient('))

    expect(offenders).toEqual([])
  })
})
