package com.hirain.aiagent.rag.indexer.corpus;
import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import org.junit.jupiter.api.Test; import java.nio.file.Path; import static org.junit.jupiter.api.Assertions.*;
final class SourceFormatDetectorTest { @Test void shouldRejectFormatDisguise(){CliCommandException e=assertThrows(CliCommandException.class,()->new SourceFormatDetector().verifyDeclaredExtension(Path.of("manual.pdf"),"MARKDOWN"));assertEquals("SOURCE_FORMAT_MISMATCH",e.reasonCode());} }
