package com.hirain.aiagent.rag.indexer.corpus;
import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files; import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
final class CorpusPathResolverTest {
 @TempDir Path root;
 @Test void shouldRejectParentTraversal() throws Exception { Path outside=Files.createTempFile("outside",".md"); CliCommandException e=assertThrows(CliCommandException.class,()->new CorpusPathResolver().resolve(root,"../"+outside.getFileName())); assertEquals("SOURCE_PATH_OUTSIDE_CORPUS",e.reasonCode()); }
 @Test void shouldResolveRegularFileInsideRoot() throws Exception { Path f=root.resolve("a.md");Files.writeString(f,"x");assertEquals(f.toRealPath(),new CorpusPathResolver().resolve(root,"a.md")); }
}
