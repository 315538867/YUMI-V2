import { Button, Card, Form, Input, Typography, App } from 'antd';
import { redirect, useNavigate } from 'react-router-dom';
import { login, currentSession } from '../api/session';
import { YumiApiError, errorAction } from '../api/errors';

/** 已登录用户访问 /login 直接进入订单工作台。 */
export async function loginLoader() {
  try {
    await currentSession();
    throw redirect('/orders');
  } catch (error) {
    if (error instanceof Response) {
      throw error;
    }
    return null;
  }
}

interface LoginFormValues {
  username: string;
  password: string;
}

export function LoginPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();

  async function onFinish(values: LoginFormValues) {
    try {
      await login(values.username, values.password);
      navigate('/orders', { replace: true });
    } catch (error) {
      if (error instanceof YumiApiError) {
        if (error.code === 'AUTH_INVALID') {
          message.error(errorAction(error.code));
          return;
        }
        message.error(`${errorAction(error.code)}（requestId: ${error.requestId}）`);
        return;
      }
      throw error;
    }
  }

  return (
    <div className="login-page">
      <Card className="login-card">
        <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 4 }}>
          YUMI V2
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center' }}>
          管理员登录
        </Typography.Paragraph>
        <Form<LoginFormValues> layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item
            label="用户名"
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input autoComplete="username" autoFocus placeholder="管理员用户名" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password autoComplete="current-password" placeholder="密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
