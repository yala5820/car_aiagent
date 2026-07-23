package com.hirain.aiagent.rag.indexer.corpus;
import com.hirain.aiagent.rag.indexer.cli.CliCommandException; import org.junit.jupiter.api.Test; import java.nio.charset.StandardCharsets; import static org.junit.jupiter.api.Assertions.*;
final class SourceCharsetResolverTest { @Test void shouldDefaultToUtf8AndRejectUnknown(){SourceCharsetResolver r=new SourceCharsetResolver();assertEquals(StandardCharsets.UTF_8,r.resolve(null));CliCommandException e=assertThrows(CliCommandException.class,()->r.resolve("not-a-charset"));assertEquals("SOURCE_CHARSET_UNSUPPORTED",e.reasonCode());} }
