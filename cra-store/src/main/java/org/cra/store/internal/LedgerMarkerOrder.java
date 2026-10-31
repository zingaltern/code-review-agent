package org.cra.store.internal;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerRunMarker;

/**
 * 账本标记排序：完成标记按承载评论的创建时间（SPI 约定"按发布时间序"，02§5）。
 *
 * <p>端口返回的标记顺序取决于各端点分页与拼接顺序（先 inline 后 issue），不能直接当作时间序；收敛轮次取末元素作上一轮基点，顺序必须稳定。
 */
final class LedgerMarkerOrder {

  private LedgerMarkerOrder() {}

  static List<LedgerRunMarker> completionMarkers(CommentLedger ledger) {
    Map<Long, Instant> commentTimes = new HashMap<>();
    for (LedgerComment comment : ledger.comments()) {
      commentTimes.put(comment.id(), comment.createdAt());
    }
    return ledger.runMarkers().stream()
        .filter(LedgerRunMarker::isCompletion)
        .sorted(
            Comparator.comparing(
                (LedgerRunMarker marker) ->
                    commentTimes.getOrDefault(marker.commentId(), Instant.EPOCH)))
        .toList();
  }
}
