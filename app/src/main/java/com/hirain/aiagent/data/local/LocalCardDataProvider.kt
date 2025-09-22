package com.hirain.aiagent.data.local

import com.hirain.aiagent.data.CardInfo

object LocalCardDataProvider {
    val allFunctions = listOf("播放歌曲忘情水", "放平座椅", "开启座椅按摩", "打开空调")
    val functions2 = listOf("喂猫粮", "喂狗粮", "摸摸猫", "摸摸狗", "梳梳毛")
    val functions3 = listOf("打开游戏", "喝饮料", "放音乐", "连接游戏手柄", "打开音响")
    val function4 = listOf("打开音响", "打开投影仪", "播放电影")
    val functions5 = listOf("加热水", "咖啡研磨", "接咖啡")


    val allCards = listOf(
    CardInfo("午休场景卡", allFunctions, "1111111111111现在我需要根据这些信息推理出应该使用哪些工具来提供服务。工具列表在tool标签中定义。"),
    CardInfo("游戏场景卡", functions3, "2222222222222222现在我需要根据这些信息推理出应该使用哪些工具来提供服务。工具列表在tool标签中定义。"),
    CardInfo("观影场景卡", function4, "33333333333333333现在我需要根据这些信息推理出应该使用哪些工具来提供服务。工具列表在tool标签中定义。"),
    CardInfo("咖啡场景卡", functions5, "4444444444444现在我需要根据这些信息推理出应该使用哪些工具来提供服务。工具列表在tool标签中定义。"),
    CardInfo("宠物场景卡", functions2, "55555555555555555现在我需要根据这些信息推理出应该使用哪些工具来提供服务。工具列表在tool标签中定义。"),
    )
}