<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { fileService, type UploadedFile } from '@/platform/services/fileService'

/**
 * 业务附件上传与授权预览。
 *
 * 上传只产生临时文件；正式引用仍由业务命令在同一事务内建立。预览必须携带引用方读取权限点，
 * 页面不能仅凭文件标识绕过业务权限。
 */
const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    permissionCode?: string
    label?: string
    accept?: string
    disabled?: boolean
  }>(),
  {
    modelValue: null,
    permissionCode: '',
    label: '选择附件',
    accept: '',
    disabled: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
  uploaded: [file: UploadedFile]
  failure: [message: string]
}>()

const uploading = ref(false)
const fileName = ref('')
const previewing = ref(false)

function messageOf(error: unknown): string {
  return userMessageOf(error, '上传失败，请稍后重试')
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file === undefined || props.disabled) {
    return
  }
  uploading.value = true
  try {
    const uploaded = await fileService.upload(file)
    fileName.value = uploaded.originalName
    emit('update:modelValue', uploaded.fileBusinessId)
    emit('uploaded', uploaded)
    ElMessage.success('附件已上传')
  } catch (error) {
    const message = messageOf(error)
    emit('failure', message)
    ElMessage.error(message)
  } finally {
    uploading.value = false
  }
}

async function preview(): Promise<void> {
  if (props.modelValue === null || props.permissionCode === '') {
    return
  }
  previewing.value = true
  try {
    const blob = await fileService.readContent(props.modelValue, props.permissionCode)
    const url = URL.createObjectURL(blob)
    globalThis.open?.(url, '_blank', 'noopener')
    globalThis.setTimeout?.(() => URL.revokeObjectURL(url), 60_000)
  } catch (error) {
    const message = messageOf(error)
    emit('failure', message)
    ElMessage.error(message)
  } finally {
    previewing.value = false
  }
}

function clear(): void {
  fileName.value = ''
  emit('update:modelValue', null)
}
</script>

<template>
  <div class="business-file-upload">
    <label class="picker" :class="{ disabled }">
      <input
        type="file"
        :accept="accept || undefined"
        :disabled="disabled || uploading"
        @change="onFileChange"
      />
      <el-button :loading="uploading" :disabled="disabled" size="small">
        {{ uploading ? '上传中…' : label }}
      </el-button>
    </label>
    <span v-if="modelValue" class="file-name">{{ fileName || modelValue }}</span>
    <el-button
      v-if="modelValue && permissionCode"
      link
      type="primary"
      size="small"
      :loading="previewing"
      @click="preview"
    >
      预览
    </el-button>
    <el-button v-if="modelValue && !disabled" link size="small" @click="clear">移除</el-button>
  </div>
</template>

<style scoped>
.business-file-upload {
  display: flex;
  align-items: center;
  gap: var(--yumi-space-2);
  flex-wrap: wrap;
}

.picker input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.picker.disabled {
  cursor: not-allowed;
}

.file-name {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--yumi-text-regular);
  font-size: var(--yumi-font-size-sm);
}
</style>
