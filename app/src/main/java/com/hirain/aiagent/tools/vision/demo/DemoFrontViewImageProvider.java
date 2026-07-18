package com.hirain.aiagent.tools.vision.demo;

/** 仅从配置白名单读取 Demo 图片，绝不接收模型提供的路径。 */
public final class DemoFrontViewImageProvider implements FrontViewImageProvider {
    private final VisionAssetReader reader; private final VisionDemoConfigLoader loader;
    public DemoFrontViewImageProvider(VisionAssetReader reader) { this.reader=reader; this.loader=new VisionDemoConfigLoader(reader); }
    @Override public FrontViewImage load(String overrideImageId) throws VisionConfigException {
        VisionDemoConfig config=loader.load();
        if (!config.isReady()) throw new VisionConfigException("CONFIG_NOT_READY", "no_default_image");
        String id = overrideImageId != null && !overrideImageId.isBlank() ? overrideImageId : config.defaultImageId;
        VisionDemoConfig.ImageEntry entry=config.find(id);
        if(entry==null) throw new VisionConfigException("IMAGE_NOT_CONFIGURED", "image_id_not_whitelisted");
        try {
            byte[] bytes=reader.read(entry.assetPath);
            if(bytes.length==0) throw new VisionConfigException("IMAGE_EMPTY", "empty_image");
            if(bytes.length>config.maxImageBytes) throw new VisionConfigException("IMAGE_TOO_LARGE", "max_image_bytes");
            if(!matchesMime(bytes, entry.mimeType)) throw new VisionConfigException("IMAGE_MIME_MISMATCH", "signature_mismatch");
            return new FrontViewImage(id,"DEMO_ASSET",entry.mimeType,bytes,System.currentTimeMillis());
        } catch(VisionConfigException e){throw e;} catch(java.io.FileNotFoundException e){throw new VisionConfigException("IMAGE_NOT_FOUND","asset_missing");}
        catch(Exception e){throw new VisionConfigException("IMAGE_NOT_FOUND","asset_read_failed");}
    }
    private static boolean matchesMime(byte[] b,String mime){
        if("image/jpeg".equals(mime)) return b.length>2&&(b[0]&255)==255&&(b[1]&255)==216;
        if("image/png".equals(mime)) return b.length>8&&(b[0]&255)==137&&b[1]==80&&b[2]==78&&b[3]==71;
        return "image/webp".equals(mime)&&b.length>12&&b[0]==82&&b[1]==73&&b[2]==70&&b[3]==70&&b[8]==87&&b[9]==69&&b[10]==66&&b[11]==80;
    }
}
