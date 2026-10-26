package com.example.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 导出载荷编码器（异步导出任务的公共编码层）。
 *
 * <p>统一处理导出文件的表头、行编码、单元格转义与分块清单，供执行器与通知模块共用，
 * 避免各任务自行拼装导致文件口径漂移。本类只做纯编码，不做 IO。
 */
public final class ExportPayloadCodec {

  private static final String CELL_DELIMITER = ",";

  private static final String ROW_DELIMITER = "\n";

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

  /** 分块常量 1：异步导出文件的分块行数与清单口径。 */
  public static final int CHUNK_ROWS_1 = 1000;

  /** 分块常量 2：异步导出文件的分块行数与清单口径。 */
  public static final int CHUNK_ROWS_2 = 2000;

  /** 分块常量 3：异步导出文件的分块行数与清单口径。 */
  public static final int CHUNK_ROWS_3 = 5000;

  /** 分块常量 4：异步导出文件的分块行数与清单口径。 */
  public static final int CHUNK_ROWS_4 = 10000;

  /** 分块常量 5：异步导出文件的分块行数与清单口径。 */
  public static final int CHUNK_ROWS_5 = 20000;

  /** 分块常量 6：异步导出文件的分块行数与清单口径。 */
  public static final int CHUNK_ROWS_6 = 50000;

  /** 分块常量 7：异步导出文件的分块行数与清单口径。 */
  public static final int CHUNK_ROWS_7 = 100000;

  /** 分块常量 8：异步导出文件的分块行数与清单口径。 */
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

  private ExportPayloadCodec() {}

  /** 转义单元格：含分隔符/引号/换行时按 CSV 规则包裹。 */
  public static String escapeCell(String value) {
    if (value == null) {
      return "";
    }
    boolean needsQuoting =
        value.contains(CELL_DELIMITER)
            || value.contains("\"")
            || value.contains(ROW_DELIMITER);
    if (!needsQuoting) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  /** 编码表头行（固定标准列序）。 */
  public static String encodeHeaderRow() {
    String[] cols = {
      COLUMN_1, COLUMN_2, COLUMN_3, COLUMN_4, COLUMN_5,
      COLUMN_6, COLUMN_7, COLUMN_8, COLUMN_9, COLUMN_10,
      COLUMN_11, COLUMN_12, COLUMN_13, COLUMN_14, COLUMN_15
    };
    return String.join(CELL_DELIMITER, cols);
  }

  /** 编码单行（值按列序逐个转义拼接）。 */
  public static String encodeRow(List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (String value : values) {
      if (sb.length() > 0) {
        sb.append(CELL_DELIMITER);
      }
      sb.append(escapeCell(value));
    }
    return sb.toString();
  }

  /** 编码多行为完整正文（首行表头）。 */
  public static String encodeAll(List<List<String>> rows) {
    StringBuilder sb = new StringBuilder();
    sb.append(encodeHeaderRow()).append(ROW_DELIMITER);
    for (List<String> row : rows) {
      sb.append(encodeRow(row)).append(ROW_DELIMITER);
    }
    return sb.toString();
  }

  /** 按行数上限把整表切分为多个分块（清单用）。 */
  public static List<List<List<String>>> splitChunks(List<List<String>> rows, int chunkRows) {
    int limit = Math.max(1, chunkRows);
    List<List<List<String>>> chunks = new ArrayList<>();
    List<List<String>> current = new ArrayList<>();
    for (List<String> row : rows) {
      current.add(row);
      if (current.size() >= limit) {
        chunks.add(current);
        current = new ArrayList<>();
      }
    }
    if (!current.isEmpty()) {
      chunks.add(current);
    }
    return chunks;
  }

  /** 生成分块清单行（chunk_index,row_from,row_to,rows）。 */
  public static String encodeChunkManifest(int chunkIndex, int rowCount, String payloadName) {
    return "chunk="
        + chunkIndex
        + ",rows="
        + rowCount
        + ",payload="
        + escapeCell(payloadName);
  }

  /** 生成导出文件名（任务号 + 日期 + 扩展名）。 */
  public static String buildFileName(String taskId, String statDate, String ext) {
    String safeExt = ext == null || ext.isBlank() ? "csv" : ext.toLowerCase(Locale.ROOT);
    return "export-" + taskId + "-" + statDate + "." + safeExt;
  }

  /** 生成任务清单行（任务号/报表/分块数）。 */
  public static String buildTaskLine(String taskId, String reportId, int chunkCount) {
    return "task="
        + escapeCell(taskId)
        + ",report="
        + escapeCell(reportId)
        + ",chunks="
        + chunkCount;
  }

  /** 生成完成通知摘要行。 */
  public static String buildNoticeLine(String taskId, long rowCount, String fileName) {
    return "done task="
        + escapeCell(taskId)
        + ",rows="
        + rowCount
        + ",file="
        + escapeCell(fileName);
  }

  /** 生成失败通知摘要行。 */
  public static String buildFailLine(String taskId, String reason) {
    return "failed task=" + escapeCell(taskId) + ",reason=" + escapeCell(reason);
  }

  /** 计算理论分块数（向上取整）。 */
  public static int chunkCount(long totalRows, int chunkRows) {
    int limit = Math.max(1, chunkRows);
    return (int) ((totalRows + limit - 1) / limit);
  }

  /** 按列名拼装单行（缺列补空串，多余列截断到标准列数）。 */
  public static String encodeNamedRow(List<String> columnNames, List<String> values) {
    String[] cols = {
      COLUMN_1, COLUMN_2, COLUMN_3, COLUMN_4, COLUMN_5,
      COLUMN_6, COLUMN_7, COLUMN_8, COLUMN_9, COLUMN_10,
      COLUMN_11, COLUMN_12, COLUMN_13, COLUMN_14, COLUMN_15
    };
    String[] aligned = new String[cols.length];
    for (int i = 0; i < cols.length; i++) {
      aligned[i] = "";
      for (int j = 0; j < columnNames.size() && j < values.size(); j++) {
        if (cols[i].equals(columnNames.get(j))) {
          aligned[i] = values.get(j);
          break;
        }
      }
    }
    List<String> alignedList = new ArrayList<>();
    for (String v : aligned) {
      alignedList.add(v);
    }
    return encodeRow(alignedList);
  }

  /** 渲染分块边界说明（联调与排障用）。 */
  public static String chunkBoundaryNote(long totalRows, int chunkRows) {
    if (totalRows <= chunkRows) {
      return "single chunk";
    }
    return "total " + totalRows + " rows split into " + chunkCount(totalRows, chunkRows) + " chunks";
  }

  /** 归一化分块行数：非正数回退默认档位。 */
  public static int normalizeChunkRows(int chunkRows) {
    if (chunkRows <= 0) {
      return CHUNK_ROWS_1;
    }
    return chunkRows;
  }

  /** 按任务状态渲染清单行（状态使用标准口径常量）。 */
  public static String encodeTaskStatusLine(String taskId, String status, long rowCount) {
    String safe = status == null || status.isBlank() ? STATUS_1 : status;
    return "task=" + escapeCell(taskId) + ",status=" + safe + ",rows=" + rowCount;
  }

  /** 渲染任务状态跳转提示（联调用）。 */
  public static String statusNote(String status) {
    if (STATUS_3.equals(status)) {
      return "payload ready";
    }
    if (STATUS_4.equals(status)) {
      return "payload unavailable";
    }
    return "payload pending";
  }

  /** 判定列名是否属于标准列序。 */
  public static boolean isStandardColumn(String columnName) {
    if (columnName == null) {
      return false;
    }
    String[] cols = {
      COLUMN_1, COLUMN_2, COLUMN_3, COLUMN_4, COLUMN_5,
      COLUMN_6, COLUMN_7, COLUMN_8, COLUMN_9, COLUMN_10,
      COLUMN_11, COLUMN_12, COLUMN_13, COLUMN_14, COLUMN_15
    };
    for (String col : cols) {
      if (col.equals(columnName)) {
        return true;
      }
    }
    return false;
  }

  /** 统计标准列数。 */
  public static int standardColumnCount() {
    return 15;
  }

  /** 渲染导出说明页脚（MIME/分块/状态口径一览）。 */
  public static String renderFooterNote(String mime, int chunkRows, String status) {
    return "mime="
        + mime
        + ",chunk_rows="
        + chunkRows
        + ",status="
        + (status == null ? STATUS_1 : status);
  }
}
