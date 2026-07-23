package com.hirain.aiagent.rag.indexer.config;
public record EmbeddingBuildConfig(String provider, String model, int dimension, int templateVersion) { }
