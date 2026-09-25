import { afterEach, describe, expect, it, vi } from 'vitest';

import { printCurrentPage } from './print';

describe('打印入口（任务 9.3）', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('存在 Electron 薄壳时调用主进程打印，不调用浏览器打印', async () => {
    const shellPrint = vi.fn().mockResolvedValue({ succeeded: true });
    const browserPrint = vi.fn();
    vi.stubGlobal('window', { yumiShell: { print: shellPrint }, print: browserPrint });

    await printCurrentPage();

    expect(shellPrint).toHaveBeenCalledWith({});
    expect(browserPrint).not.toHaveBeenCalled();
  });

  it('浏览器中回退 window.print', async () => {
    const browserPrint = vi.fn();
    vi.stubGlobal('window', { print: browserPrint });

    await printCurrentPage();

    expect(browserPrint).toHaveBeenCalledTimes(1);
  });
});
