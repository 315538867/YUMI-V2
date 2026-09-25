import { describe, expect, it } from 'vitest';
import type { RouteObject } from 'react-router-dom';

import { appRoutes } from './routes';
import { ROUTE_PATHS } from './paths';
import { PlaceholderPage } from '../pages/PlaceholderPage';

/** 展平路由表（含 children），得到所有已接线路径。 */
function wiredPaths(routes: RouteObject[], prefix = ''): string[] {
  return routes.flatMap(route => {
    const path = route.path ?? prefix;
    const nested = route.children ? wiredPaths(route.children, path) : [];
    return route.path ? [path, ...nested] : nested;
  });
}

/** 展平路由表，得到每条路径对应的页面组件（用于识别占位页）。 */
function wiredElements(routes: RouteObject[]): { path: string; element: unknown }[] {
  return routes.flatMap(route => {
    const own = route.path && route.element ? [{ path: route.path, element: route.element }] : [];
    return [...own, ...(route.children ? wiredElements(route.children) : [])];
  });
}

/**
 * 任务 9.9：`ROUTE_PATHS` 的每条正式路由都必须**真正接线**（可达且不是占位页），
 * 避免「清单里有、页面是占位」被当成验收证据；仅 `*` 兜底路由允许占位。
 */
describe('路由接线核对（任务 9.9）', () => {
  it('ROUTE_PATHS 的每条正式路由都已接线', () => {
    const wired = wiredPaths(appRoutes);
    for (const path of ROUTE_PATHS) {
      expect(wired, `未接线：${path}`).toContain(path);
    }
  });

  it('正式路由都指向真实页面，没有占位页（仅 * 兜底允许）', () => {
    const placeholders = wiredElements(appRoutes)
      .filter(entry => (entry.element as { type?: unknown })?.type === PlaceholderPage)
      .map(entry => entry.path);
    expect(placeholders).toEqual(['*']);
  });

  it('除 /login 外都在受保护布局内', () => {
    const layout = appRoutes.find(route => route.children?.some(child => child.path === '/orders'));
    expect(layout).toBeDefined();
    const protectedPaths = wiredPaths(layout!.children ?? []);
    for (const path of ROUTE_PATHS.filter(path => path !== '/login')) {
      expect(protectedPaths, `未受保护：${path}`).toContain(path);
    }
    expect(protectedPaths).not.toContain('/login');
  });
});
