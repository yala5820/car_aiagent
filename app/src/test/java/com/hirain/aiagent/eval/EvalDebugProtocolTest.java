package com.hirain.aiagent.eval;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 内部 Debug 协议只能接受固定 operation 与兼容 major，解析错误必须稳定返回。 */
public class EvalDebugProtocolTest {
    private final EvalDebugProtocolCodec codec = new EvalDebugProtocolCodec();

    @Test public void decodesAcquireAndPatch() {
        String json = "{\"protocolVersion\":{\"major\":1,\"minor\":0,\"schemaHash\":\"x\"},"
                + "\"operation\":\"APPLY_STATE\",\"correlationId\":\"case-1\",\"leaseToken\":\"secret\","
                + "\"statePatch\":{\"systems\":{\"speed\":{\"vehicleSpd\":40}}}}";
        EvalDebugProtocolCodec.DecodeResult result = codec.decode(json);
        assertTrue(result.isSuccess());
        assertEquals("APPLY_STATE", result.request.operation);
        assertEquals(40D, result.request.statePatch.getSystems().get("speed").get("vehicleSpd"));
    }

    @Test public void rejectsUnknownOperationAndMajor() {
        assertFalse(codec.decode("{\"protocolVersion\":{\"major\":1},\"operation\":\"DELETE_ALL\"}").isSuccess());
        EvalDebugProtocolCodec.DecodeResult incompatible = codec.decode("{\"protocolVersion\":{\"major\":2},\"operation\":\"GET_VERSION\"}");
        assertEquals("PROTOCOL_VERSION_UNSUPPORTED", incompatible.errorCode);
        assertFalse(codec.decode("{\"protocolVersion\":{\"major\":1},\"operation\":\"GET_VERSION\",\"unexpected\":true}").isSuccess());
    }
}
