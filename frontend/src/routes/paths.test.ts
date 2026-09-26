import { describe, expect, it } from 'vitest';
import { ROUTE_PATHS, isProtectedPath } from './paths';

describe('正式路由清单（design.md 第 7 节页面矩阵）', () => {
  it('包含全部正式路由', () => {
    expect([...ROUTE_PATHS].sort()).toEqual(
      [
        '/catalog/customers',
        '/catalog/employees',
        '/catalog/products',
        '/inventory',
        '/login',
        '/orders',
        '/orders/:id',
        '/orders/:id/changes/:changeId',
        '/orders/new',
        '/production',
        '/production/tasks/:id',
        '/production/tasks/:id/verify',
        '/production/tasks/new',
        '/reports',
        '/settings',
      ].sort(),
    );
  });

  it('仅 /login 免认证，其余路径受保护', () => {
    expect(isProtectedPath('/login')).toBe(false);
    expect(isProtectedPath('/orders')).toBe(true);
    expect(isProtectedPath('/orders/YM00001')).toBe(true);
    expect(isProtectedPath('/')).toBe(true);
  });
});
