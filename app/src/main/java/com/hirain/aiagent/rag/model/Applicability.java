package com.hirain.aiagent.rag.model;

/** 资料相对于可信车辆 Profile 的适用性；UNKNOWN 不可作为可回答证据。 */
public enum Applicability { EXACT, COMPATIBLE, UNKNOWN }
