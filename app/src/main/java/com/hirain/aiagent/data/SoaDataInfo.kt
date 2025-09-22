package com.hirain.aiagent.soa.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SoaDataInfo(
    @Transient
    val id: Long = 10001,
    @Transient
    val properId: Int = 0x0,
    @Transient
    val areaId: Int = 0x0,
    val name: String,
    var value: Int,
    val description: String,
)