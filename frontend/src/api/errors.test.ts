import { describe, expect, it } from 'vitest';
import { ERROR_CODES, YumiApiError, errorAction, isErrorCode } from './errors';

describe('错误码动作映射', () => {
  it('并发冲突提示刷新后重试', () => {
    expect(errorAction('CONFLICT_VERSION')).toBe('请刷新后重试');
  });

  it('未认证提示重新登录', () => {
    expect(errorAction('AUTH_REQUIRED')).toBe('请重新登录');
  });

  it('校验失败提示修正字段', () => {
    expect(errorAction('VALIDATION_INVALID')).toContain('字段');
  });

  it('未知错误码回退默认提示', () => {
    expect(errorAction('FUTURE_UNKNOWN_CODE')).toBe('操作未完成，请稍后重试或联系管理员');
  });

  it('错误码目录覆盖任务要求的六大族（FINANCE 语义层为 PAYMENT/REFUND/CLOSE）', () => {
    const families = [
      'VALIDATION_',
      'STATE_',
      'CONFLICT_',
      'QUANTITY_',
      'SOURCE_',
      'PAYMENT_',
      'REFUND_',
      'CLOSE_',
    ];
    for (const family of families) {
      expect(
        ERROR_CODES.some(code => code.startsWith(family)),
        `缺少错误族 ${family}`,
      ).toBe(true);
    }
  });

  it('错误码目录与平台族 AUTH/MIGRATION 对齐', () => {
    expect(isErrorCode('AUTH_REQUIRED')).toBe(true);
    expect(isErrorCode('MIGRATION_INVALID')).toBe(true);
    expect(isErrorCode('NOT_A_CODE')).toBe(false);
  });
});

describe('YumiApiError', () => {
  it('保留服务端 code、字段定位与 requestId', () => {
    const error = new YumiApiError(
      {
        code: 'QUANTITY_BELOW_SHIPPED',
        message: '新数量不得低于累计有效发货',
        fieldErrors: [{ field: 'quantity', message: '新数量不得低于累计有效发货' }],
        requestId: 'req-42',
      },
      409,
    );
    expect(error.code).toBe('QUANTITY_BELOW_SHIPPED');
    expect(error.status).toBe(409);
    expect(error.fieldErrors[0].field).toBe('quantity');
    expect(error.requestId).toBe('req-42');
  });
});
