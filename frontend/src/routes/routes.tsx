import { Navigate, type RouteObject } from 'react-router-dom';
import { LoginPage, loginLoader } from '../pages/LoginPage';
import { ProtectedLayout, sessionLoader } from '../pages/ProtectedLayout';
import { PlaceholderPage } from '../pages/PlaceholderPage';
import { ProductsPage } from '../pages/catalog/ProductsPage';
import { CustomersPage } from '../pages/catalog/CustomersPage';
import { EmployeesPage } from '../pages/catalog/EmployeesPage';
import { SettingsPage } from '../pages/settings/SettingsPage';
import { InventoryPage } from '../pages/inventory/InventoryPage';
import { ProductionPage } from '../pages/production/ProductionPage';
import { ProductionVerifyPage } from '../pages/production/ProductionVerifyPage';
import { ReportsPage } from '../pages/reports/ReportsPage';
import { OrdersPage } from '../pages/orders/OrdersPage';
import { OrderCreatePage } from '../pages/orders/OrderCreatePage';
import { OrderDetailPage } from '../pages/orders/OrderDetailPage';
import { OrderChangePage } from '../pages/orders/OrderChangePage';
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
      { path: '/orders', element: <OrdersPage /> },
      { path: '/orders/new', element: <OrderCreatePage /> },
      { path: '/orders/:id', element: <OrderDetailPage /> },
      { path: '/orders/:id/changes/:changeId', element: <OrderChangePage /> },
      { path: '/catalog/products', element: <ProductsPage /> },
      { path: '/catalog/customers', element: <CustomersPage /> },
      { path: '/catalog/employees', element: <EmployeesPage /> },
      { path: '/inventory', element: <InventoryPage /> },
      { path: '/production', element: <ProductionPage /> },
      { path: '/production/plans/:id/verify', element: <ProductionVerifyPage /> },
      { path: '/reports', element: <ReportsPage /> },
      { path: '/settings', element: <SettingsPage /> },
      { path: '*', element: <PlaceholderPage title="页面不存在" task="路由表" /> },
    ],
  },
];

