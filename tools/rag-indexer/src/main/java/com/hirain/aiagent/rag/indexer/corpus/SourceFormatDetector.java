package com.hirain.aiagent.rag.indexer.corpus;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.cli.CliExitCode;
import java.nio.file.Path;
import java.util.Locale;

/** V1 不依赖扩展名兜底：扩展名只用于拒绝声明与文件名明显冲突的输入。 */
public final class SourceFormatDetector {
 public void verifyDeclaredExtension(Path file,String declaredFormat){
  String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
  boolean ok=("PDF".equals(declaredFormat)&&name.endsWith(".pdf"))||("STATIC_HTML".equals(declaredFormat)&&(name.endsWith(".html")||name.endsWith(".htm")))||("MARKDOWN".equals(declaredFormat)&&(name.endsWith(".md")||name.endsWith(".markdown")));
  if(!ok)throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR,"SOURCE_FORMAT_MISMATCH");
 }
}
