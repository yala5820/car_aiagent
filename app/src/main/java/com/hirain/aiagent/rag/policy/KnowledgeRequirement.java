package com.hirain.aiagent.rag.policy;

/**
 * 当前请求对车辆官方知识检索的需求强度。
 * OPTIONAL 仅保留给协议向后兼容；V1 生产 Detector 必须只产生 NONE 或 REQUIRED，
 * 防止把知识 Tool 与车控、视觉 Tool 以“可选并集”方式混用。
 */
public enum KnowledgeRequirement { NONE, OPTIONAL, REQUIRED }
