const { app, BrowserWindow, dialog, ipcMain, webContents } = require('electron');
const path = require('node:path');

const WEB_URL = process.env.YUMI_WEB_URL || 'http://localhost:5173';

let mainWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    title: 'YUMI V2',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  mainWindow.loadURL(WEB_URL);
  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

ipcMain.handle('print', (event, options) =>
  webContents.fromId(event.sender.id).print(options || {}, succeeded => ({ succeeded })),
);

ipcMain.handle('chooseFile', async () => {
  const result = await dialog.showOpenDialog(mainWindow, { properties: ['openFile'] });
  return { canceled: result.canceled, filePaths: result.filePaths };
});

ipcMain.handle('systemInfo', () => ({
  platform: process.platform,
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
  },
}));

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
  console.log('[yumi-electron] ready url=' + WEB_URL);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

