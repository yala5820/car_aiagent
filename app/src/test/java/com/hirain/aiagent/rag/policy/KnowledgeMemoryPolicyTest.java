package com.hirain.aiagent.rag.policy;import org.junit.Test;import static org.junit.Assert.*;
public class KnowledgeMemoryPolicyTest {@Test public void projectionIsBoundedAndDoesNotExposeRawEnvelope(){String raw="x".repeat(500);String projected=new KnowledgeMemoryPolicy().compact(raw);assertTrue(projected.length()<400);assertTrue(projected.contains("vehicle_knowledge"));}}
