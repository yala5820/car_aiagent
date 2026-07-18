package com.hirain.aiagent.tools.vision.demo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class VisionDemoConfigLoaderTest {
    @Test public void emptyImageListIsValidButNotReady() throws Exception {
        VisionDemoConfig config = VisionDemoConfigLoader.parse("{\"schemaVersion\":1,\"defaultImageId\":\"\",\"maxImageBytes\":1,\"images\":[]}");
        assertFalse(config.isReady());
    }
    @Test(expected = VisionConfigException.class)
    public void rejectsPathEscape() throws Exception {
        VisionDemoConfigLoader.parse("{\"schemaVersion\":1,\"defaultImageId\":\"x\",\"maxImageBytes\":1,\"images\":[{\"imageId\":\"x\",\"assetPath\":\"vision/demo/images/../x.jpg\",\"mimeType\":\"image/jpeg\"}]}");
    }
    @Test public void acceptsWhitelistedJpeg() throws Exception {
        VisionDemoConfig config = VisionDemoConfigLoader.parse("{\"schemaVersion\":1,\"defaultImageId\":\"x\",\"maxImageBytes\":1,\"images\":[{\"imageId\":\"x\",\"assetPath\":\"vision/demo/images/x.jpg\",\"mimeType\":\"image/jpeg\"}]}");
        assertTrue(config.isReady());
    }
}
