import { readFileSync, readdirSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(process.cwd(), 'src')
const SPACING =
  'margin|margin-top|margin-right|margin-bottom|margin-left|padding|padding-top|padding-right|padding-bottom|padding-left|gap|row-gap|column-gap'

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name)
    return entry.isDirectory() ? walk(full) : full.endsWith('.vue') ? [full] : []
  })
}

function styleBlock(source: string): string {
  const match = /<style[^>]*>([\s\S]*?)<\/style>/.exec(source)
  return match?.[1]?.replace(/\/\*[\s\S]*?\*\//g, '') ?? ''
}

describe('样式令牌契约', () => {
  const files = walk(SRC)

  it('页面样式只使用颜色令牌，不写色值', () => {
    const offenders: string[] = []
    for (const file of files) {
      const css = styleBlock(readFileSync(file, 'utf8'))
      for (const pattern of [/#[0-9a-fA-F]{3,8}\b/g, /\brgba?\(/g, /\bhsla?\(/g]) {
        for (const match of css.matchAll(pattern)) {
          offenders.push(`${relative(SRC, file)}: ${match[0]}`)
        }
      }
    }
    expect(offenders, offenders.join('\n')).toEqual([])
  })

  it('字号、字重与圆角只能引用令牌', () => {
    const offenders: string[] = []
    for (const file of files) {
      const css = styleBlock(readFileSync(file, 'utf8'))
      for (const pattern of [
        /font-size:\s*[0-9.]+px/g,
        /font-weight:\s*(?:[0-9]+|bold|normal)\b/g,
        /border-radius:\s*[0-9.]+px/g,
      ]) {
        for (const match of css.matchAll(pattern)) {
          offenders.push(`${relative(SRC, file)}: ${match[0]}`)
        }
      }
    }
    expect(offenders, offenders.join('\n')).toEqual([])
  })

  it('外边距、内边距与间距只能引用令牌', () => {
    const offenders: string[] = []
    const pattern = new RegExp(`\\b(?:${SPACING}):\\s*[^;]*?(?<![\\w.-])[0-9.]+px`, 'g')
    for (const file of files) {
      const css = styleBlock(readFileSync(file, 'utf8'))
      for (const match of css.matchAll(pattern)) {
        offenders.push(`${relative(SRC, file)}: ${match[0]}`)
      }
    }
    expect(offenders, offenders.join('\n')).toEqual([])
  })

  it('页面样式不直接消费 Element Plus 令牌，统一经主题令牌映射', () => {
    const offenders: string[] = []
    for (const file of files) {
      const css = styleBlock(readFileSync(file, 'utf8'))
      for (const match of css.matchAll(/var\(--el-[a-z-]+\)/g)) {
        offenders.push(`${relative(SRC, file)}: ${match[0]}`)
      }
    }
    expect(offenders, offenders.join('\n')).toEqual([])
  })

  it('模块页面不重复定义页头、工具条和提示类', () => {
    const offenders: string[] = []
    const selector = /(?:^|\})\s*(h1|\.head|\.toolbar|\.alert|\.hint|\.meta|\.actions)\s*(?:,|\{)/g
    for (const file of files.filter((item) => item.includes('/modules/'))) {
      const css = styleBlock(readFileSync(file, 'utf8'))
      for (const match of css.matchAll(selector)) {
        offenders.push(`${relative(SRC, file)}: ${match[1]}`)
      }
    }
    expect(offenders, offenders.join('\n')).toEqual([])
  })
})
