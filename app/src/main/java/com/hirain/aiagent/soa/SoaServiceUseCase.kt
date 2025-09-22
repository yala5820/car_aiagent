package com.hirain.aiagent.soa

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.util.Log
import hirain.carina.ISoaBusService
import hirain.carina.ISoaService

class SoaServiceUseCase(
    private val getValue: (Map<Long, Int>) -> Unit
) {

    private val TAG: String = "jk"

    private var soaService: ISoaService? = null
    private var soaDataUseCase: SoaDataUseCase? = null

    init {
        getSoaService()
    }

    @SuppressLint("PrivateApi")
    private fun getSoaBusService(): ISoaBusService? {
        var soaBusService: ISoaBusService? = null
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod =
                serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "SoaBusService") as IBinder?
            binder?.linkToDeath(DeathRecipient { soaBusService = null }, 0)
            soaBusService = ISoaBusService.Stub.asInterface(binder)
            Log.d(TAG, "getSoaBusService: binder:$binder SoaBusService:$soaBusService")
        } catch (e: Exception) {
            Log.d(TAG, "getServiceWithRetry: ", e)
        }
        return soaBusService
    }

    @SuppressLint("PrivateApi")
    private fun getSoaService() {
        var soaBusService = getSoaBusService()
        if (soaBusService == null || !soaBusService.checkService("s2sservice")) {
            Log.d(TAG, "SoaService service is not available")
            return
        }
        if (null == soaService) {
            try {
                val serviceManagerClass = Class.forName("android.os.ServiceManager")
                val getServiceMethod =
                    serviceManagerClass.getMethod("getService", String::class.java)
                val binder = getServiceMethod.invoke(null, "s2sservice") as IBinder?
                binder?.linkToDeath(DeathRecipient { soaService = null }, 0)
                soaService = ISoaService.Stub.asInterface(binder)
                Log.d(TAG, "getServiceWithRetry: binder:$binder SoaService:$soaService")
            } catch (e: Exception) {
                Log.d(TAG, "getServiceWithRetry: ", e)
            }
            if (null == soaService) {
                Log.d(TAG, "SoaService service is not available: null")
            }
        }
    }

    fun getSoaData() {
        Log.i("jk", "Test get data start!!!------------------------")
        if (soaService == null) getSoaService()
        if (soaDataUseCase == null) soaDataUseCase = SoaDataUseCase(soaService, getValue)
        Log.i("jk", "Test get data!!!------------------------")
        soaDataUseCase?.getSoaValue()
    }

//    fun testSetData() {
//        Log.i("jk", "-------------------Test set data start!!!")
//        if (soaService == null) getSoaService()
//        if (soaDataUseCase == null) soaDataUseCase = SoaDataUseCase(soaService)
//        Log.i("jk", "-------------------Test set data!!!")
//        soaDataUseCase?.setSoaValue(listOf(10001))
//    }

}