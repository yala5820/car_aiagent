package com.hirain.aiagent.rag.indexer.lexical;

import java.util.List;

/** 跨端词法分析契约；输入相同文本和版本配置必须得到相同顺序 Token。 */
public interface LexicalAnalyzer {
    List<String> analyze(String text);
}
