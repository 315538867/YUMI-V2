import { createBrowserRouter } from 'react-router-dom';

import { appRoutes } from './routes';
import { ROUTE_PATHS } from './paths';

/** 应用路由器（仅在此处创建，避免测试环境需要 DOM）。路由表见 ./routes.tsx。 */
export const appRouter = createBrowserRouter(appRoutes);
export { ROUTE_PATHS };
