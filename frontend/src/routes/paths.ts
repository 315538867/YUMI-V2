/** design.md 第 7 节前端页面矩阵的正式路由清单（占位页与实现页共用同一表）。 */
export const ROUTE_PATHS = [
  '/login',
  '/catalog/products',
  '/catalog/customers',
  '/catalog/employees',
  '/orders',
  '/orders/new',
  '/orders/:id',
  '/orders/:id/changes/:changeId',
  '/inventory',
  '/production',
  '/production/plans/:id/verify',
  '/reports',
  '/settings',
] as const;

/** 除 /login 外全部路径都要求已登录（含未知路径，未认证时统一回落登录页）。 */
export function isProtectedPath(pathname: string): boolean {
  return pathname !== '/login';
}
