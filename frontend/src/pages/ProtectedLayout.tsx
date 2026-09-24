import { Button, Layout, Menu, Tag, Typography } from 'antd';
import {
  AppstoreOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  SettingOutlined,
  ShoppingCartOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import {
  Outlet,
  redirect,
  useLoaderData,
  useLocation,
  useNavigate,
  type LoaderFunctionArgs,
} from 'react-router-dom';
import { currentSession, logout, type Session } from '../api/session';
import { YumiApiError } from '../api/errors';
import { isProtectedPath } from '../routes/paths';

export async function sessionLoader({ request }: LoaderFunctionArgs): Promise<Session | null> {
  const { pathname } = new URL(request.url);
  if (!isProtectedPath(pathname)) {
    return null;
  }
  try {
    return await currentSession();
  } catch (error) {
    if (error instanceof YumiApiError && error.status === 401) {
      throw redirect('/login');
    }
    throw error;
  }
}

const MENU_ITEMS = [
  { key: '/orders', icon: <ShoppingCartOutlined />, label: '订单工作台' },
  {
    key: 'catalog',
    icon: <DatabaseOutlined />,
    label: '基础资料',
    children: [
      { key: '/catalog/products', label: '商品' },
      { key: '/catalog/customers', label: '客户' },
      { key: '/catalog/employees', label: '员工' },
    ],
  },
  { key: '/inventory', icon: <AppstoreOutlined />, label: '库存' },
  { key: '/production', icon: <ToolOutlined />, label: '生产' },
  { key: '/reports', icon: <FileTextOutlined />, label: '报表' },
  { key: '/settings', icon: <SettingOutlined />, label: '设置' },
];

export function ProtectedLayout() {
  const session = useLoaderData() as Session;
  const location = useLocation();
  const navigate = useNavigate();

  async function onLogout() {
    try {
      await logout();
    } finally {
      navigate('/login', { replace: true });
    }
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider width={200} theme="light">
        <div className="sider-brand">
          <Typography.Text strong>YUMI V2</Typography.Text>
        </div>
        <Menu
          mode="inline"
          theme="light"
          defaultOpenKeys={['catalog']}
          selectedKeys={[location.pathname]}
          items={MENU_ITEMS}
          onClick={({ key }) => {
            if (key.startsWith('/')) {
              navigate(key);
            }
          }}
        />
      </Layout.Sider>
      <Layout>
        <Layout.Header className="app-header">
          <Typography.Text type="secondary">订单履约工作区</Typography.Text>
          <span className="header-user">
            <Tag>{session.username}</Tag>
            <Button size="small" onClick={onLogout}>
              退出登录
            </Button>
          </span>
        </Layout.Header>
        <Layout.Content className="app-content">
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
