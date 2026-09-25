import { useEffect, useState } from 'react';
import { Alert, App, Button, Card, DatePicker, InputNumber, Select, Space, Table, Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import {
  getConsistency,
  queryReport,
  REPORT_TYPES,
  reportExportUrl,
  type ConsistencyReport,
  type ReportPage,
} from '../../api/reports';
import { printCurrentPage } from '../../lib/print';
import { describeApiError } from '../../api/errors';

const { RangePicker } = DatePicker;

/**
 * 台账与导出（任务 9.1/9.2/9.3/9.4 的页面入口）：
 * 六类台账查询（筛选与分页由服务端执行）、CSV 导出（固定业务字段、不含物流字段）、打印/PDF（浏览器打印）
 * 与一致性检查结果展示（不一致时直接看到失败证据）。
 */
export function ReportsPage() {
  const { message } = App.useApp();
  const [type, setType] = useState<string>('ORDER_FULFILLMENT');
  const [range, setRange] = useState<[string, string] | null>(null);
  const [orderId, setOrderId] = useState<number | undefined>();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [report, setReport] = useState<ReportPage | null>(null);
  const [consistency, setConsistency] = useState<ConsistencyReport | null>(null);
  const [loading, setLoading] = useState(false);

  async function reload() {
    setLoading(true);
    try {
      const [pageData, consistencyData] = await Promise.all([
        queryReport(type, {
          dateFrom: range?.[0],
          dateTo: range?.[1],
          orderId,
          page,
          size,
        }),
        getConsistency(),
      ]);
      setReport(pageData);
      setConsistency(consistencyData);
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, [type, range, orderId, page, size]);

  const exportUrl = reportExportUrl(type, { dateFrom: range?.[0], dateTo: range?.[1], orderId });

  return (
    <Card
      title={
        <Space>
          <Typography.Text strong>台账与导出</Typography.Text>
          <Tag color="blue">服务端事实</Tag>
        </Space>
      }
      extra={
        <Space>
          <Button href={exportUrl} download>
            导出 CSV
          </Button>
          <Button onClick={() => void printCurrentPage()}>打印 / PDF</Button>
          <Button onClick={() => void reload()} loading={loading}>
            刷新
          </Button>
        </Space>
      }
    >
      <Space size={8} style={{ marginBottom: 12 }} wrap>
        <Select
          style={{ width: 180 }}
          value={type}
          options={REPORT_TYPES.map(entry => ({ value: entry.value, label: entry.label }))}
          onChange={value => {
            setType(value);
            setPage(1);
          }}
        />
        <RangePicker
          style={{ width: 240 }}
          onChange={value =>
            setRange(value?.[0] && value?.[1] ? [value[0].format('YYYY-MM-DD'), value[1].format('YYYY-MM-DD')] : null)
          }
        />
        <InputNumber
          style={{ width: 160 }}
          min={1}
          precision={0}
          placeholder="订单 ID"
          value={orderId}
          onChange={value => setOrderId(value ?? undefined)}
        />
        <InputNumber
          style={{ width: 120 }}
          min={1}
          max={200}
          precision={0}
          placeholder="每页条数"
          value={size}
          onChange={value => {
            setSize(value ?? 20);
            setPage(1);
          }}
        />
      </Space>

      <Table
        size="small"
        rowKey={(_, index) => String(index)}
        loading={loading}
        dataSource={report?.rows ?? []}
        locale={{ emptyText: '暂无数据（可调整筛选条件）' }}
        scroll={{ x: 1200 }}
        pagination={{
          current: report?.page ?? 1,
          pageSize: report?.size ?? size,
          total: report?.total ?? 0,
          showSizeChanger: false,
          onChange: next => setPage(next),
        }}
        columns={(report?.columns ?? []).map(column => ({
          title: column.title,
          dataIndex: column.key,
          key: column.key,
          ellipsis: true,
        }))}
      />

      <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
        导出使用固定业务字段清单：发货台账**不含物流公司、单号、运费与发货备注**（物流字段只在发货详情/打印显示）；
        金额以字符串输出，历史资料使用确认/发货快照。
      </Typography.Paragraph>

      <Typography.Text strong>事实重建与投影一致性检查</Typography.Text>
      {consistency && (
        <Alert
          style={{ marginTop: 8 }}
          type={consistency.allConsistent ? 'success' : 'error'}
          showIcon
          title={consistency.allConsistent ? '投影与事实一致' : '存在不一致（失败证据，未做静默覆盖）'}
          description={
            <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
              {consistency.checks.map(check => (
                <li key={check.name}>
                  <b>{check.name}</b>：{check.consistent ? '一致' : check.detail}
                </li>
              ))}
            </ul>
          }
        />
      )}
      <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
        检查覆盖履约余额、库存批次与售后可补发；不一致时只产出失败证据，由运维按事实重建处理（不做静默覆盖）。
        当前时间：{dayjs().format('YYYY-MM-DD HH:mm')}
      </Typography.Paragraph>
    </Card>
  );
}
