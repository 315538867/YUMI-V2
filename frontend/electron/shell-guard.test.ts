import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * 1.9 薄壳守卫测试（静态）：证明 Electron 主进程
 * 1) 不包含数据库凭据或 MySQL 直连；
 * 2) 不复制订单/履约等业务规则；
 * 3) 只依赖 electron 与 Node 内建模块；
 * 4) 安全窗口配置保持 contextIsolation/nodeIntegration 约束。
 */

const electronDir = dirname(fileURLToPath(import.meta.url));
const mainSource = readFileSync(join(electronDir, 'main.cjs'), 'utf8');
const preloadSource = readFileSync(join(electronDir, 'preload.cjs'), 'utf8');
const sources = [
  { file: 'main.cjs', source: mainSource },
  { file: 'preload.cjs', source: preloadSource },
];

const FORBIDDEN_DB_PATTERNS = [
  /mysql/i,
  /jdbc:/i,
  /3306/,
  /YUMI_DB/,
  /createConnection/i,
  /createPool/i,
  /password\s*[:=]/i,
];

const FORBIDDEN_BUSINESS_TERMS = [
  '订单',
  '履约',
  '发货',
  '应收',
  '退款',
  '结清',
  '售后',
  '库存',
  '生产计划',
  'QUANTITY_',
  'STATE_',
  'Idempotency',
  'SNAPSHOT_',
];

const ALLOWED_MODULES = new Set(['electron', 'node:path']);

function importTargets(source: string): string[] {
  const targets: string[] = [];
  const requirePattern = /require\(\s*['"]([^'"]+)['"]\s*\)/g;
  for (const match of source.matchAll(requirePattern)) {
    targets.push(match[1]);
  }
  return targets;
}

describe('Electron 薄壳守卫', () => {
  it('主进程与 preload 不含数据库凭据或 MySQL 直连', () => {
    for (const { file, source } of sources) {
      for (const pattern of FORBIDDEN_DB_PATTERNS) {
        expect(pattern.test(source), `${file} 命中禁止的数据库/凭据模式 ${pattern}`).toBe(false);
      }
    }
  });

  it('主进程与 preload 不复制订单等业务规则', () => {
    for (const { file, source } of sources) {
      for (const term of FORBIDDEN_BUSINESS_TERMS) {
        expect(source.includes(term), `${file} 出现业务词 ${term}`).toBe(false);
      }
    }
  });

  it('只依赖 electron 与 Node 内建模块', () => {
    for (const { file, source } of sources) {
      const targets = importTargets(source);
      expect(targets.length, `${file} 未发现 require 目标（解析器失效）`).toBeGreaterThan(0);
      for (const target of targets) {
        const allowed = ALLOWED_MODULES.has(target) || target.startsWith('node:');
        expect(allowed, `${file} 引入了不允许的模块 ${target}`).toBe(true);
      }
    }
  });

  it('窗口保持 contextIsolation 且关闭 nodeIntegration、启用 sandbox', () => {
    expect(mainSource).toContain('contextIsolation: true');
    expect(mainSource).toContain('nodeIntegration: false');
    expect(mainSource).toContain('sandbox: true');
    expect(mainSource).not.toContain('nodeIntegration: true');
  });

  it('preload 只通过 contextBridge 暴露打印、文件选择与系统信息', () => {
    expect(preloadSource).toContain('contextBridge.exposeInMainWorld');
    expect(preloadSource).toMatch(/print\s*[:(]/);
    expect(preloadSource).toMatch(/chooseFile\s*[:(]/);
    expect(preloadSource).toMatch(/systemInfo\s*[:(]/);
    expect(preloadSource).not.toContain("require('fs')");
    expect(preloadSource).not.toContain('require("fs")');
  });
});
