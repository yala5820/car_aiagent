package com.hirain.aiagent.rag.document;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/** 验证 Android 消费端与离线构建端使用完全一致的 HeadingPath V1 协议。 */
public class HeadingPathCodecTest {
    @Test
    public void decodesVersionedSingleHeading() {
        assertEquals(List.of("更换空调滤清器"), HeadingPathCodec.decode("v1:7:更换空调滤清器"));
    }

    @Test
    public void decodesVersionedHeadingHierarchy() {
        assertEquals(List.of("零配件", "更换空调滤清器"),
                HeadingPathCodec.decode("v1:3:零配件7:更换空调滤清器"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnversionedLegacyValue() {
        HeadingPathCodec.decode("7:更换空调滤清器");
    }
}
