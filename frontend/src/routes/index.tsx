import { Navigate, createBrowserRouter, type RouteObject } from 'react-router-dom';
import { LoginPage, loginLoader } from '../pages/LoginPage';
import { ProtectedLayout, sessionLoader } from '../pages/ProtectedLayout';
import { PlaceholderPage } from '../pages/PlaceholderPage';
import { ProductsPage } from '../pages/catalog/ProductsPage';
import { CustomersPage } from '../pages/catalog/CustomersPage';
import { EmployeesPage } from '../pages/catalog/EmployeesPage';
import { SettingsPage } from '../pages/settings/SettingsPage';
import { ROUTE_PATHS } from './paths';

function placeholder(path: string, title: string, task: string): RouteObject {
  return { path, element: <PlaceholderPage title={title} task={task} /> };
}

export const appRoutes: RouteObject[] = [
  { path: '/', element: <Navigate to="/orders" replace /> },
  { path: '/login', element: <LoginPage />, loader: loginLoader },
  {
    element: <ProtectedLayout />,
    loader: sessionLoader,
    children: [
      placeholder('/orders', '订单工作台', '3.11'),
      placeholder('/orders/new', '新建订单', '3.11'),
      placeholder('/orders/:id', '订单详情', '3.12'),
      placeholder('/orders/:id/changes/:changeId', '订单变更确认', '3.13'),
      { path: '/catalog/products', element: <ProductsPage /> },
      { path: '/catalog/customers', element: <CustomersPage /> },
      { path: '/catalog/employees', element: <EmployeesPage /> },
      placeholder('/inventory', '库存', '4.11'),
      placeholder('/production', '生产工作台', '5.14'),
      placeholder('/production/plans/:id/verify', '计划核验', '5.15'),
      placeholder('/reports', '台账与导出', '9.1'),
      { path: '/settings', element: <SettingsPage /> },
      { path: '*', element: <PlaceholderPage title="页面不存在" task="路由表" /> },
    ],
  },
];

export const appRouter = createBrowserRouter(appRoutes);
export { ROUTE_PATHS };
