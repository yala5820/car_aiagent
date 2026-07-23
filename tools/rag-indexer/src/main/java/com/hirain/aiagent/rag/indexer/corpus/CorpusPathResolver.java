package com.hirain.aiagent.rag.indexer.corpus;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.cli.CliExitCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 在打开文件前以真实路径验证 Root 边界，阻止 `..` 与符号链接逃逸。 */
public final class CorpusPathResolver {
 public Path resolve(Path corpusRoot, String relativePath) {
  try {
   if(relativePath==null||relativePath.indexOf('\0')>=0) fail();
   Path declared=Path.of(relativePath); if(declared.isAbsolute()) fail();
   Path realRoot=corpusRoot.toRealPath(); Path realFile=realRoot.resolve(declared).normalize().toRealPath();
   if(!realFile.startsWith(realRoot)||!Files.isRegularFile(realFile)) fail();
   return realFile;
  } catch(IOException|IllegalArgumentException e){ fail(); throw new AssertionError(e); }
 }
 private static void fail(){throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR,"SOURCE_PATH_OUTSIDE_CORPUS");}
}
