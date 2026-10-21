import type { App } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

/**
 * 安装 Element Plus 并启用中文语言包。
 *
 * Element Plus 2.14.7 的 `ConfigProvider`/installer 类型把 `locale` 推导成 prop 定义对象本身
 * （上游类型缺陷，运行期接受语言包对象）。该缺陷只在此处集中规避一次，业务页面不重复断言。
 */
const elementPlusInstaller = ElementPlus as unknown as {
  install: (app: App, options?: { locale: typeof zhCn }) => void
}

export function installElementPlus(app: App): void {
  elementPlusInstaller.install(app, { locale: zhCn })
}
