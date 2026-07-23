package com.hirain.aiagent.rag.profile;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.rag.model.VehicleProfile;
import java.util.Objects;
/** V1 将虚拟状态机作为唯一可信 Profile 源；后续 SOA 接入仅替换此 Provider。 */
public final class VehicleStateMachineVehicleProfileProvider implements VehicleProfileProvider {
 private final VehicleStateMachine stateMachine;
 public VehicleStateMachineVehicleProfileProvider(VehicleStateMachine stateMachine) { this.stateMachine = Objects.requireNonNull(stateMachine); }
 @Override public VehicleProfile currentProfile() { return stateMachine.vehicleProfileSnapshot(System.currentTimeMillis()); }
 @Override public boolean isDemoProfile() { return stateMachine.isDemoVehicleProfile(); }
}
