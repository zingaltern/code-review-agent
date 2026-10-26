package com.example.export;

import java.util.List;

/**
 * 导出载荷目录（异步导出任务的公共目录层）。
 *
 * <p>统一收录导出文件的标准列序、分块档位、内容类型、任务状态与通知键名目录，
 * 供执行器与通知模块引用，避免各任务自行拼装字符串导致文件口径漂移。
 * 本类只收录常量目录与纯数据载体，不包含编码与计算逻辑。
 */
public final class ExportPayloadCodec {

  /** 列定义常量 1：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_1 = "report_id";

  /** 列定义常量 2：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_2 = "report_name";

  /** 列定义常量 3：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_3 = "tenant_id";

  /** 列定义常量 4：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_4 = "stat_date";

  /** 列定义常量 5：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_5 = "order_count";

  /** 列定义常量 6：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_6 = "total_amount";

  /** 列定义常量 7：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_7 = "refund_amount";

  /** 列定义常量 8：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_8 = "net_amount";

  /** 列定义常量 9：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_9 = "currency";

  /** 列定义常量 10：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_10 = "channel";

  /** 列定义常量 11：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_11 = "operator";

  /** 列定义常量 12：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_12 = "created_at";

  /** 列定义常量 13：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_13 = "updated_at";

  /** 列定义常量 14：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_14 = "status";

  /** 列定义常量 15：导出文件的标准列序与表头文案。 */
  public static final String COLUMN_15 = "remark";

  /** 分块常量 1：异步导出文件的分块行数档位。 */
  public static final int CHUNK_ROWS_1 = 1000;

  /** 分块常量 2：异步导出文件的分块行数档位。 */
  public static final int CHUNK_ROWS_2 = 2000;

  /** 分块常量 3：异步导出文件的分块行数档位。 */
  public static final int CHUNK_ROWS_3 = 5000;

  /** 分块常量 4：异步导出文件的分块行数档位。 */
  public static final int CHUNK_ROWS_4 = 10000;

  /** 分块常量 5：异步导出文件的分块行数档位。 */
  public static final int CHUNK_ROWS_5 = 20000;

  /** 分块常量 6：异步导出文件的分块行数档位。 */
  public static final int CHUNK_ROWS_6 = 50000;

  /** 分块常量 7：异步导出文件的分块行数档位。 */
  public static final int CHUNK_ROWS_7 = 100000;

  /** 分块常量 8：异步导出文件的分块行数档位。 */
  public static final int CHUNK_ROWS_8 = 200000;

  /** MIME 常量 1：导出产物的内容类型标注。 */
  public static final String MIME_1 = "text/csv";

  /** MIME 常量 2：导出产物的内容类型标注。 */
  public static final String MIME_2 = "application/vnd.ms-excel";

  /** MIME 常量 3：导出产物的内容类型标注。 */
  public static final String MIME_3 = "application/json";

  /** MIME 常量 4：导出产物的内容类型标注。 */
  public static final String MIME_4 = "application/octet-stream";

  /** MIME 常量 5：导出产物的内容类型标注。 */
  public static final String MIME_5 = "text/plain";

  /** MIME 常量 6：导出产物的内容类型标注。 */
  public static final String MIME_6 = "application/pdf";

  /** MIME 常量 7：导出产物的内容类型标注。 */
  public static final String MIME_7 = "application/zip";

  /** 状态常量 1：导出任务清单的任务状态口径。 */
  public static final String STATUS_1 = "submitted";

  /** 状态常量 2：导出任务清单的任务状态口径。 */
  public static final String STATUS_2 = "running";

  /** 状态常量 3：导出任务清单的任务状态口径。 */
  public static final String STATUS_3 = "done";

  /** 状态常量 4：导出任务清单的任务状态口径。 */
  public static final String STATUS_4 = "failed";

  /** 状态常量 5：导出任务清单的任务状态口径。 */
  public static final String STATUS_5 = "cancelled";

  /** 通知键常量 1：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_1 = "task";

  /** 通知键常量 2：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_2 = "report";

  /** 通知键常量 3：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_3 = "status";

  /** 通知键常量 4：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_4 = "rows";

  /** 通知键常量 5：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_5 = "file";

  /** 通知键常量 6：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_6 = "chunks";

  /** 通知键常量 7：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_7 = "reason";

  /** 通知键常量 8：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_8 = "finished_at";

  /** 通知键常量 9：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_9 = "operator";

  /** 通知键常量 10：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_10 = "tenant";

  /** 通知键常量 11：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_11 = "channel";

  /** 通知键常量 12：完成/失败通知的键名目录。 */
  public static final String NOTICE_KEY_12 = "request_id";

  /** 清单列常量 1：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_1 = "task_id";

  /** 清单列常量 2：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_2 = "report_id";

  /** 清单列常量 3：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_3 = "tenant_id";

  /** 清单列常量 4：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_4 = "status";

  /** 清单列常量 5：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_5 = "chunk_count";

  /** 清单列常量 6：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_6 = "row_count";

  /** 清单列常量 7：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_7 = "payload_name";

  /** 清单列常量 8：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_8 = "mime";

  /** 清单列常量 9：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_9 = "created_at";

  /** 清单列常量 10：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_10 = "finished_at";

  /** 清单列常量 11：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_11 = "operator";

  /** 清单列常量 12：任务清单文件的标准列名。 */
  public static final String LIST_COLUMN_12 = "remark";

  /** 渠道常量 1：通知投递的渠道目录。 */
  public static final String CHANNEL_1 = "inbox";

  /** 渠道常量 2：通知投递的渠道目录。 */
  public static final String CHANNEL_2 = "email";

  /** 渠道常量 3：通知投递的渠道目录。 */
  public static final String CHANNEL_3 = "webhook";

  /** 渠道常量 4：通知投递的渠道目录。 */
  public static final String CHANNEL_4 = "sms";

  /** 渠道常量 5：通知投递的渠道目录。 */
  public static final String CHANNEL_5 = "slack";

  /** 渠道常量 6：通知投递的渠道目录。 */
  public static final String CHANNEL_6 = "dingtalk";

  /** 渠道常量 7：通知投递的渠道目录。 */
  public static final String CHANNEL_7 = "feishu";

  /** 渠道常量 8：通知投递的渠道目录。 */
  public static final String CHANNEL_8 = "teams";

  /** 渠道常量 9：通知投递的渠道目录。 */
  public static final String CHANNEL_9 = "push";

  /** 渠道常量 10：通知投递的渠道目录。 */
  public static final String CHANNEL_10 = "none";

  /** 字段说明常量 1：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_1 = "报表唯一标识";

  /** 字段说明常量 2：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_2 = "报表名称";

  /** 字段说明常量 3：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_3 = "租户标识";

  /** 字段说明常量 4：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_4 = "统计日期（存储口径）";

  /** 字段说明常量 5：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_5 = "订单笔数";

  /** 字段说明常量 6：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_6 = "合计金额";

  /** 字段说明常量 7：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_7 = "退款金额";

  /** 字段说明常量 8：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_8 = "净额";

  /** 字段说明常量 9：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_9 = "币种（ISO 代码）";

  /** 字段说明常量 10：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_10 = "渠道";

  /** 字段说明常量 11：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_11 = "操作人";

  /** 字段说明常量 12：导出字段的联调说明目录。 */
  public static final String FIELD_DOC_12 = "备注";

  private ExportPayloadCodec() {}

  /** 列引用（名称 + 说明，纯数据载体）。 */
  public static final class ColumnRef {

    private final String name;

    private final String doc;

    private ColumnRef(String name, String doc) {
      this.name = name;
      this.doc = doc;
    }

    /** 装配列引用。 */
    public static ColumnRef of(String name, String doc) {
      return new ColumnRef(name, doc);
    }

    public String name() {
      return name;
    }

    public String doc() {
      return doc;
    }
  }

  /** 编码表头行（固定标准列序）。 */
  public static String encodeHeaderRow() {
    return String.join(
        ",",
        List.of(
            COLUMN_1, COLUMN_2, COLUMN_3, COLUMN_4, COLUMN_5,
            COLUMN_6, COLUMN_7, COLUMN_8, COLUMN_9, COLUMN_10,
            COLUMN_11, COLUMN_12, COLUMN_13, COLUMN_14, COLUMN_15));
  }

  /** 编码任务清单表头（固定清单列序）。 */
  public static String encodeListHeaderRow() {
    return String.join(
        ",",
        List.of(
            LIST_COLUMN_1, LIST_COLUMN_2, LIST_COLUMN_3, LIST_COLUMN_4, LIST_COLUMN_5,
            LIST_COLUMN_6, LIST_COLUMN_7, LIST_COLUMN_8, LIST_COLUMN_9, LIST_COLUMN_10,
            LIST_COLUMN_11, LIST_COLUMN_12));
  }

  /** 渲染标准列目录行。 */
  public static String renderColumnCatalog() {
    return encodeHeaderRow();
  }

  /** 归一化分块档位：非正数回退默认档。 */
  public static int normalizeChunkRows(int chunkRows) {
    return chunkRows <= 0 ? CHUNK_ROWS_1 : chunkRows;
  }

  /** 归一化状态口径：空值回退 submitted。 */
  public static String normalizeStatus(String status) {
    return status == null || status.isBlank() ? STATUS_1 : status.trim();
  }
}
