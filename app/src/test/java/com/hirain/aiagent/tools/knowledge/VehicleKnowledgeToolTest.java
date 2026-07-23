package com.hirain.aiagent.tools.knowledge;
import org.junit.Test;import static org.junit.Assert.*;
/** Tool 不能在缺少 Runtime ThreadLocal 时创建临时状态或触发检索。 */
public class VehicleKnowledgeToolTest { @Test public void missingContextFailsClosed(){String json=new VehicleKnowledgeTool(null).searchVehicleKnowledge("空调使用条件");assertTrue(json.contains("KNOWLEDGE_REQUEST_CONTEXT_MISSING"));} }
