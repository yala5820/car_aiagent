package com.hirain.aiagent.rag.indexer.parser;
import com.hirain.aiagent.rag.indexer.model.SourceFormat; import java.util.*;
/** Registry 只接受安全校验后的枚举值，格式不支持即失败而不是猜测 Parser。 */
public final class DocumentParserRegistry { private final Map<SourceFormat,DocumentParser> parsers=new EnumMap<>(SourceFormat.class); public DocumentParserRegistry(Collection<? extends DocumentParser> values){for(DocumentParser p:values){if(parsers.put(p.sourceFormat(),p)!=null)throw new IllegalArgumentException("重复 Parser");}} public DocumentParser require(SourceFormat format){DocumentParser p=parsers.get(format);if(p==null)throw new IllegalArgumentException("未注册 Parser："+format);return p;} }
