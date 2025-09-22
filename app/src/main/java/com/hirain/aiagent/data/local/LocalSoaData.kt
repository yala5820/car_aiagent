package com.hirain.aiagent.soa.data.local

import com.hirain.aiagent.soa.data.SoaDataInfo

object LocalSoaData {

    val allSoaData = listOf(
        SoaDataInfo(id = 10001, properId = 0x11600207, areaId = 0x0, name = "车速", value = 0x0, description = "km/h"),
        SoaDataInfo(id = 10002, properId = 0x16200B03, areaId = 0x40, name = "右后儿童锁状态", value = 0x2, description = "0x0:Status_Lock  0x1:Status_Unlock  0x2:Status_Init  0x3: Reserved"),
        SoaDataInfo(id = 10003, properId = 0x16200B03, areaId = 0x10, name = "左后儿童锁状态", value = 0x2, description = "0x0:Status_Lock  0x1:Status_Unlock  0x2:Status_Init  0x3: Reserved"),
        SoaDataInfo(id = 10004, properId = 0x13400BC0, areaId = 0x40, name = "右前车窗开度", value = 0x0, description = "0x0~0x64:0%~100%"),
        SoaDataInfo(id = 10005, properId = 0x13400BC0, areaId = 0x400, name = "右后车窗开度", value = 0x0, description = "0x0~0x64:0%~100%"),
        SoaDataInfo(id = 10006, properId = 0x13400BC0, areaId = 0x10000, name = "天窗开度", value = 0x0, description = "0x0~0x64:0%~100%"),
        SoaDataInfo(id = 10007, properId = 0x15400B87, areaId = 0x1, name = "主驾座椅靠背位置", value = 0x0, description = "0x0~0x64:0%~100% 0x65~0x7E:Reserved"),
        SoaDataInfo(id = 10008, properId = 0x15400B87, areaId = 0x4, name = "副驾座椅靠背位置", value = 0x0, description = "0x0~0x64:0%~100% 0x65~0x7E:Reserved"),
        SoaDataInfo(id = 10009, properId = 0x15400B8D, areaId = 0x0, name = "主驾座椅座垫位置", value = 0x0, description = "0x0~0x64:0%~100% 0x65~0x7E:Reserved"),
        SoaDataInfo(id = 10010, properId = 0x15400513, areaId = 0x1, name = "主驾座椅通风状态", value = 0x0, description = "0x0~0x64:0%~100%"),
        SoaDataInfo(id = 10011, properId = 0x15400513, areaId = 0x4, name = "副驾座椅通风状态", value = 0x0, description = "0x0~0x64:0%~100%"),
        SoaDataInfo(id = 10012, properId = 0x15400513, areaId = 0x40, name = "后排右座椅通风状态", value = 0x0, description = "0x0~0x64:0%~100%"),
        SoaDataInfo(id = 10013, properId = 0x15400513, areaId = 0x10, name = "后排左座椅通风状态", value = 0x0, description = "0x0~0x64:0%~100%"),
        SoaDataInfo(id = 10014, properId = 0x1540050B, areaId = 0x1, name = "主驾座椅加热温度", value = 0x0, description = "0x0:-40℃及以下  0x1~0x7C:-39℃～84℃(1℃/STEP)  0x7D:85℃及以上"),
        SoaDataInfo(id = 10015, properId = 0x1540050B, areaId = 0x4, name = "副驾座椅加热温度", value = 0x0, description = "0x0:-40℃及以下  0x1~0x7C:-39℃～84℃(1℃/STEP)  0x7D:85℃及以上"),
        SoaDataInfo(id = 10016, properId = 0x1540050B, areaId = 0x40, name = "后排右座椅加热温度", value = 0x0, description = "0x0:-40℃及以下  0x1~0x7C:-39℃～84℃(1℃/STEP)  0x7D:85℃及以上"),
        SoaDataInfo(id = 10017, properId = 0x1540050B, areaId = 0x10, name = "后排左座椅加热温度", value = 0x0, description = "0x0:-40℃及以下  0x1~0x7C:-39℃～84℃(1℃/STEP)  0x7D:85℃及以上"),
        SoaDataInfo(id = 10018, properId = 0x13200504, areaId = 0x0, name = "前除霜状态", value = 0x0, description = "0x0:OFF 0x1:ON"),
        SoaDataInfo(id = 10019, properId = 0x15200510, areaId = 0x0, name = "空调开关", value = 0x0, description = "0x0:OFF 0x1:ON"),
        SoaDataInfo(id = 10020, properId = 0x15200505, areaId = 0x0, name = "AC模式", value = 0x0, description = "0x0:AUTO control  0x1:OFF  0x2:ON  0x3:Not display(SYSTEM~OFF)"),
        SoaDataInfo(id = 10021, properId = 0x1520050A, areaId = 0x0, name = "空调自动模式状态", value = 0x0, description = "0x0:OFF 0x1:ON"),
        SoaDataInfo(id = 10022, properId = 0x15200508, areaId = 0x0, name = "内外循环状态", value = 0x0, description = "0x0:REC  0x1:FRE  0x2:AUTO  0x3:Not used"),
        SoaDataInfo(id = 10023, properId = 0x21400170, areaId = 0x0, name = "左后顶灯开关", value = 0x0, description = "0x0:OFF 0x1:ON"),
        SoaDataInfo(id = 10024, properId = 0x21400171, areaId = 0x0, name = "右后顶灯开关", value = 0x0, description = "0x0:OFF 0x1:ON"),
        SoaDataInfo(id = 10025, properId = 0x21400172, areaId = 0x0, name = "左前顶灯开关", value = 0x0, description = "0x0:OFF 0x1:ON"),
        SoaDataInfo(id = 10026, properId = 0x21400173, areaId = 0x0, name = "右前顶灯开关", value = 0x0, description = "0x0:OFF 0x1:ON"),
    )
}