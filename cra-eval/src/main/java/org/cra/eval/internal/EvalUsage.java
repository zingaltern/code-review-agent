package org.cra.eval.internal;

/** {@code cra-eval} 用法文本（错误信息与帮助共用）。 */
final class EvalUsage {

  private EvalUsage() {}

  static String text() {
    return """
        用法:
          cra-eval run --dataset <dir> [--set all|bad|clean|l2|adversarial|adv-injection|adv-hallucination|adv-refusal]
              [--k N] [--depth quick|standard] [--cli-jar PATH] [--work-dir DIR] [--json PATH]
              [--tier auto|l1|l2|l3] [--commit SHA] [--fail-on-red] [--sha256-out PATH]
              [--crosschecker-enabled] [--timeout-sec N] [--case ID[,ID...]]
          cra-eval annotate --fixture <caseDir> [--action prefill|confirm|relabel] [--to-set bad|clean]
              [--annotator NAME] [--reason TEXT]
          cra-eval verify --report <json> [--sha256 <hex>|--sha256-file <path>] [--tier l1|l2|l3]
              [--expect-commit SHA]
              [--signature <envelope> [--signing-key-env NAME|--signing-key-file PATH]
              [--signing-key-id ID]]
          cra-eval gate --report <json> [--tier l1|l2|l3] [--json-out PATH] [--sha256-out PATH]
              [--crosschecker-enabled]
          cra-eval report --repo <owner/name> --since 7d [--json PATH] [--ledger-fixture DIR]
              [--trace-dir DIR[,DIR...]] [--previous REPORT.json[,REPORT.json...]] [--dataset DIR]
              [--token TOKEN] [--api-base URL]
          cra-eval badcase add <run_ref> <finding_id> --label missed|false-positive|relabeled
              --dataset <dir> [--to-set bad|clean] [--case-id ID] [--annotator NAME] [--reason TEXT]
              [--diff-file PATH] [--snapshot-dir DIR] [--artifact-root DIR] [--token TOKEN]
              [--api-base URL] [--file PATH] [--line N]""";
  }
}
