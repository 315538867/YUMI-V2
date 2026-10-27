import { describe, expect, it } from 'vitest'
import { useEditorForm } from '@/platform/composables/useEditorForm'

describe('编辑页表单状态', () => {
  it('必填规则写入字段错误，字段错误不重复进入表单汇总', () => {
    const form = useEditorForm(['name'], '请刷新后重试')

    expect(form.validate([{ field: 'name', invalid: true, message: '请填写名称' }])).toBe(false)
    expect(form.validationErrors.name).toBe('请填写名称')
    expect(form.formAlert.value).toBe('')
  })

  it('校验通过后清空上一次错误', () => {
    const form = useEditorForm(['name'], '冲突')
    form.validate([{ field: 'name', invalid: true, message: '请填写名称' }])
    expect(form.validate([{ field: 'name', invalid: false, message: '' }])).toBe(true)
    expect(form.validationErrors.name).toBeUndefined()
    expect(form.formAlert.value).toBe('')
  })

  it('表单级错误进入汇总', () => {
    const form = useEditorForm(['name'], '冲突')
    form.validate([{ field: '_form', invalid: true, message: '请至少录入一条明细' }])
    expect(form.formAlert.value).toBe('请至少录入一条明细')
  })
})
