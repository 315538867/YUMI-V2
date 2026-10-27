import { onBeforeUnmount, onMounted } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'

/**
 * 表单未保存离开保护：路由离开弹确认，浏览器刷新/关闭触发原生提示。
 * 保存成功或主动放弃时先调用 allowLeave。
 */
export function useUnsavedGuard(isDirty: () => boolean) {
  let allowed = false

  function allowLeave(): void {
    allowed = true
  }

  onBeforeRouteLeave(async () => {
    if (allowed || !isDirty()) {
      return true
    }
    try {
      await ElMessageBox.confirm('当前修改尚未保存，离开将丢失。', '离开页面', {
        confirmButtonText: '离开',
        cancelButtonText: '继续编辑',
        type: 'warning',
      })
      return true
    } catch {
      return false
    }
  })

  function beforeUnload(event: BeforeUnloadEvent): void {
    if (!allowed && isDirty()) {
      event.preventDefault()
      event.returnValue = ''
    }
  }

  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

  return { allowLeave }
}
