/**
 * 风险分级与签字建议模型（07§5 M4a；01§3.4 担责功能）。
 *
 * <p>本包只含纯数据模型与 commit 绑定判定：分级规则消费方在 cra-core（SignoffAdvisor），渲染消费方在 core 的 Summary 段与 CLI
 * 本地报告。核心约束：agent 永远不是 approver（{@link org.cra.common.signoff.SignoffAdvice#AGENT_IS_APPROVER}）。
 */
package org.cra.common.signoff;
