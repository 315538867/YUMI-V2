const { contextBridge, ipcRenderer } = require('electron');

/**
 * 桌面薄壳暴露面：打印、文件选择与系统信息。
 * 业务数据与规则一律走共享前端的 HTTPS API，不在主进程复制。
 */
contextBridge.exposeInMainWorld('yumiShell', {
  print: options => ipcRenderer.invoke('print', options),
  chooseFile: () => ipcRenderer.invoke('chooseFile'),
  systemInfo: () => ipcRenderer.invoke('systemInfo'),
});
