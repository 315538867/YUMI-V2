declare global {
  interface Window {
    yumiShell?: {
      print?: (options?: Record<string, unknown>) => Promise<{ succeeded: boolean }>;
    };
  }
}

/**
 * 打印当前页面（任务 9.3）：Electron 薄壳存在时走主进程打印，浏览器回退 `window.print()`。
 * 只负责触发打印，**不复制任何业务计算**——打印内容始终是页面上已由服务端快照渲染出的只读事实。
 */
export async function printCurrentPage(): Promise<void> {
  const shell = typeof window === 'undefined' ? undefined : window.yumiShell;
  if (shell?.print) {
    await shell.print({});
    return;
  }
  window.print();
}
