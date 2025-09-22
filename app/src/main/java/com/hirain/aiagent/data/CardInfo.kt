package com.hirain.aiagent.data

data class CardInfo(
    var name: String,
    var functions: List<String>,
    var reasoningProcess: String = "",
    var isLoading: Boolean = true
)