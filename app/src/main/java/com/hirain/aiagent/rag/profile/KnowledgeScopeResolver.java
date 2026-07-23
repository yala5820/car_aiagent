package com.hirain.aiagent.rag.profile;
import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.model.RagFailureReason;
import java.util.Locale;
/** 与离线 Scope V1 一致的确定性规范化：小写、非字母数字片段折叠为单个连字符。 */
public final class KnowledgeScopeResolver {
 public KnowledgeScopeResolution resolve(VehicleProfile profile) {
  if (profile == null) return new KnowledgeScopeResolution(null, RagFailureReason.PROFILE_INCOMPLETE);
  String[] values={profile.vehicleModel(),profile.modelYear(),profile.region(),profile.softwareVersion(),profile.configurationCode()};
  StringBuilder id=new StringBuilder(); for(String value:values){String part=normalize(value);if(part.isEmpty())return new KnowledgeScopeResolution(null,RagFailureReason.PROFILE_INCOMPLETE);if(id.length()>0)id.append('-');id.append(part);} return new KnowledgeScopeResolution(id.toString(),null);
 }
 private static String normalize(String value) { return value==null?"":value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$",""); }
}
