import { Card, Tag, Typography } from 'antd';

/** design.md 第 7 节矩阵中尚未实现页面的正式占位（路由已正式可达）。 */
export function PlaceholderPage({ title, task }: { title: string; task: string }) {
  return (
    <Card>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        {title}
        <Tag style={{ marginLeft: 12 }}>待 {task} 实现</Tag>
      </Typography.Title>
      <Typography.Text type="secondary">页面内容将由对应阶段任务填充，当前仅保证正式路由可达。</Typography.Text>
    </Card>
  );
}
