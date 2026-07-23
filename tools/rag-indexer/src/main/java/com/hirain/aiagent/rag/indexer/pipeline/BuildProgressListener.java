package com.hirain.aiagent.rag.indexer.pipeline;

/** CLI 可注入进度观察者；默认不输出正文、绝对路径或凭证。 */
@FunctionalInterface public interface BuildProgressListener { void onPhase(BuildPhase phase); }
