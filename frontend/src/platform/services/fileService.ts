import { api } from '@/platform/services/api'

export interface UploadedFile {
  fileBusinessId: string
  originalName: string
  mediaType: string
  sizeBytes: number
}

function newIdempotencyKey(): string {
  return (
    globalThis.crypto?.randomUUID?.() ?? `file-${Date.now()}-${Math.random().toString(16).slice(2)}`
  )
}

async function toBase64(file: File): Promise<string> {
  const buffer = await file.arrayBuffer()
  let binary = ''
  const bytes = new Uint8Array(buffer)
  for (const byte of bytes) {
    binary += String.fromCharCode(byte)
  }
  return globalThis.btoa(binary)
}

/** 临时文件上传与授权读取；正式引用由业务命令在同一事务内建立。 */
export const fileService = {
  async upload(file: File): Promise<UploadedFile> {
    return api.command<
      UploadedFile,
      { originalName: string; mediaType: string; contentBase64: string }
    >('/api/platform/files', {
      idempotencyKey: newIdempotencyKey(),
      body: {
        originalName: file.name,
        mediaType: file.type || 'application/octet-stream',
        contentBase64: await toBase64(file),
      },
    })
  },

  async readContent(fileBusinessId: string, permissionCode: string): Promise<Blob> {
    const encoded = encodeURIComponent(permissionCode)
    return api.queryBlob(
      `/api/platform/files/${encodeURIComponent(fileBusinessId)}/content?permissionCode=${encoded}`,
    )
  },
}
