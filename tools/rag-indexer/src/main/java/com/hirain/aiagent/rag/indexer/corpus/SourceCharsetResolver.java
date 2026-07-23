package com.hirain.aiagent.rag.indexer.corpus;
import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.cli.CliExitCode;
import java.nio.charset.Charset; import java.nio.charset.StandardCharsets;
/** V1 Charset 决策：显式值优先；未声明时只使用 UTF-8，绝不回退到机器默认编码。 */
public final class SourceCharsetResolver {
 public Charset resolve(String explicit){ try { return explicit==null||explicit.isBlank()?StandardCharsets.UTF_8:Charset.forName(explicit); } catch(Exception e){throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR,"SOURCE_CHARSET_UNSUPPORTED");} }
}
