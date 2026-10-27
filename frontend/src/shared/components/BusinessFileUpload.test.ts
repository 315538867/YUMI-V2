import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import BusinessFileUpload from '@/shared/components/BusinessFileUpload.vue'
import { installElementPlus } from '@/platform/i18n/elementLocale'
import { ApiClientError, ApiErrorTypes } from '@/platform/services/apiClient'

const upload = vi.fn()
const readContent = vi.fn()

vi.mock('@/platform/services/fileService', () => ({
  fileService: {
    upload: (...args: unknown[]) => upload(...args),
    readContent: (...args: unknown[]) => readContent(...args),
  },
}))

function mountComponent(props: Record<string, unknown> = {}) {
  return mount(BusinessFileUpload, {
    props: { permissionCode: 'finance.read', ...props },
    global: {
      plugins: [
        {
          install: (app: Parameters<typeof installElementPlus>[0]) => installElementPlus(app),
        },
      ],
    },
  })
}

describe('业务附件上传', () => {
  beforeEach(() => {
    upload.mockReset()
    readContent.mockReset()
    vi.stubGlobal('open', vi.fn())
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:preview'),
      revokeObjectURL: vi.fn(),
    })
  })

  it('上传失败时发出失败事件，不写入文件标识', async () => {
    upload.mockRejectedValue(
      new ApiClientError({
        type: ApiErrorTypes.VALIDATION,
        code: 'INVALID_REQUEST_BODY',
        message: '附件格式不合法',
      }),
    )
    const wrapper = mountComponent()
    const input = wrapper.get('input[type="file"]')
    const file = new File(['x'], 'bad.txt', { type: 'text/plain' })
    Object.defineProperty(input.element, 'files', { value: [file] })

    await input.trigger('change')
    await vi.waitFor(() => expect(wrapper.emitted('failure')).toBeTruthy())

    expect(wrapper.emitted('failure')?.[0]).toEqual(['附件格式不合法'])
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('上传成功后回传文件标识', async () => {
    upload.mockResolvedValue({
      fileBusinessId: 'FILE-1',
      originalName: '合同.pdf',
      mediaType: 'application/pdf',
      sizeBytes: 2,
    })
    const wrapper = mountComponent()
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['ok'], '合同.pdf', { type: 'application/pdf' })],
    })

    await input.trigger('change')
    await vi.waitFor(() => expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['FILE-1']))
  })

  it('预览携带引用方权限点读取内容', async () => {
    readContent.mockResolvedValue(new Blob(['content'], { type: 'application/pdf' }))
    const wrapper = mountComponent({ modelValue: 'FILE-1' })

    const previewButton = wrapper.findAll('button').find((button) => button.text().includes('预览'))
    expect(previewButton).toBeDefined()
    await previewButton?.trigger('click')
    await vi.waitFor(() => expect(readContent).toHaveBeenCalledWith('FILE-1', 'finance.read'))
    expect(globalThis.open).toHaveBeenCalledWith('blob:preview', '_blank', 'noopener')
  })
})
