package langchain4j.android_document_loader;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
public class AndroidDocumentLoader {
    public static Document loadFromAssets(Context context, String assetPath, DocumentParser parser) {
        try {
            File tempFile = createTempFileFromAssets(context, assetPath);
            return FileSystemDocumentLoader.loadDocument(tempFile.getAbsolutePath(), parser);
        } catch (Exception e) {
            throw new RuntimeException("Error loading document from assets: " + assetPath, e);
        }
    }

    private static File createTempFileFromAssets(Context context, String assetPath) throws Exception {
        File tempFile = File.createTempFile("langchain_", "_doc", context.getCacheDir());
        tempFile.deleteOnExit();

        try (InputStream is = context.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
        }
        return tempFile;
    }
}