// 1. 在 app 模块中创建兼容性辅助类

package com.hirain.aiagent;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 提供 InputStream.readAllBytes() 的向后兼容实现。
 */
public final class InputStreamCompat {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    // 私有构造函数，防止实例化
    private InputStreamCompat() {}

    /**
     * 从输入流中读取所有剩余的字节。
     * @param is 要读取的输入流。
     * @return 包含流中所有字节的字节数组。
     * @throws IOException 如果发生 I/O 错误。
     */
    public static byte[] readAllBytes(InputStream is) throws IOException {
        Log.d("jk","InputStreamCompat readAllBytes");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int n;
        while ((n = is.read(buffer)) != -1) {
            bos.write(buffer, 0, n);
        }
        return bos.toByteArray();
    }
}
