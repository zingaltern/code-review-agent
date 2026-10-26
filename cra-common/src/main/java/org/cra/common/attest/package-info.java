/**
 * 服务端报告签名契约（04§2.4、M4b）：HMAC-SHA256 信封的签发与验签，cra-server 与 cra-eval 共用。
 *
 * <p>纯 JDK 实现（无第三方依赖），保证 cra-common 的底座纯度（10§2）。
 */
package org.cra.common.attest;
