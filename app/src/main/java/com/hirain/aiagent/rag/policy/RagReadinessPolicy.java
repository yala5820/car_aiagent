package com.hirain.aiagent.rag.policy;
/** Release 禁止 TEST_ONLY 阈值/Bundle；Debug 或显式测试可使用但必须由上层写入 Trace。 */
public final class RagReadinessPolicy { public enum Approval { TEST_ONLY, APPROVED } public boolean ready(boolean debugBuild,Approval threshold,Approval bundle){return debugBuild||(threshold==Approval.APPROVED&&bundle==Approval.APPROVED);} }
