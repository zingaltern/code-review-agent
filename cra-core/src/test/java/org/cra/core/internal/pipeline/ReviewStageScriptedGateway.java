package org.cra.core.internal.pipeline;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;

/** 评审扇出测试的脚本化假网关：记录 caller，按序回放（耗尽后重复末条）。 */
final class ReviewStageScriptedGateway implements LlmGateway {

  // 并发派发下被多线程调用：线程安全集合 + 并发下顺序不保证（断言用计数）
  private final Queue<String> callers = new ConcurrentLinkedQueue<>();
  private final List<LlmResponse> script;
  private final AtomicInteger index = new AtomicInteger();

  ReviewStageScriptedGateway(LlmResponse... responses) {
    this.script = List.of(responses);
  }

  Queue<String> callers() {
    return callers;
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    callers.add(request.caller());
    int i = Math.min(index.getAndIncrement(), script.size() - 1);
    return script.get(i);
  }
}
