package com.hirain.aiagent.rag.profile;
import com.hirain.aiagent.rag.model.VehicleProfile;
/** Profile 只能来自可信车辆事实提供者。 */
public interface VehicleProfileProvider { VehicleProfile currentProfile(); boolean isDemoProfile(); }
