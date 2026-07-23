package com.hirain.aiagent.rag.indexer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.hirain.aiagent.rag.indexer.util.DeterministicJson;
public final class ConfigFingerprint { private ConfigFingerprint() { } public static String of(JsonNode json) { return DeterministicJson.sha256(json); } }
