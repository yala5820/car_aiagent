package com.hirain.aiagent.core.preprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PreProcessor;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 注入当前时间作为临时上下文。
 */
public class TimeContextPreProcessor implements PreProcessor {

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH时mm分", Locale.getDefault());

    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        return List.of(UserMessage.from("当前时间：" + sdf.format(new Date())));
    }
}
