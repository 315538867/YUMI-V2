package com.yumi.finance.payroll.api;

/**
 * 工资来源摄取：劳动与责任各自游标，整页成功后才推进；重复读取不重复入库。
 */
public interface PayrollSourceIngestionService {

    PayrollIngestionRef ingest(IngestPayrollSourcesCommand command);
}
