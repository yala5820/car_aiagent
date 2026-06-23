package com.hirain.aiagent.infra.soa

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.util.Log
import hirain.carina.ISoaBusService
import hirain.carina.ISoaService

class SoaService {

    private val TAG: String = "jk"

    private var soaService: ISoaService? = null

    companion object {
        private val instance = SoaService()
        fun getInstance(): SoaService = instance

    }
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
    public fun getDoorStatus():String {
        Log.d(TAG, "SoaService getDoorStatus")

        return ""

    }
    public fun getFragStatus():String {
        Log.d(TAG, "SoaService getFragStatus")

        return ""

    }
    public fun getAcStatus():String {
        Log.d(TAG, "SoaService getAcStatus")

        return ""

    }
    public fun getChassisStatus():String {
        Log.d(TAG, "SoaService getChassisStatus")

        return ""

    }
    public fun getWindowStatus():String {
        Log.d(TAG, "SoaService getWindowStatus")

        return ""

    }
    public fun getSeatStatus():String {
        Log.d(TAG, "SoaService getSeatStatus")

        return ""

    }

    public fun set_seat_fl_heat( heat:Boolean) {
        Log.d(TAG, "SoaService set_seat_fl_heat")


    }
    public fun set_seat_fr_heat(heat:Boolean) {
        Log.d(TAG, "SoaService set_seat_fr_heat")


    }
    public fun set_seat_rl_heat(heat:Boolean) {
        Log.d(TAG, "SoaService set_seat_rl_heat")


    }
    public fun set_seat_rr_heat(heat:Boolean) {
        Log.d(TAG, "SoaService set_seat_rr_heat")


    }
    public fun set_seat_fl_air(air:Int) {
        Log.d(TAG, "SoaService set_seat_fl_air")


    }
    public fun set_seat_fr_air(air:Int) {
        Log.d(TAG, "SoaService set_seat_fr_air")


    }
    public fun set_seat_rl_air(air:Int) {
        Log.d(TAG, "SoaService set_seat_rl_air")


    }
    public fun set_seat_rr_air(air:Int) {
        Log.d(TAG, "SoaService set_seat_rr_air")


    }
    public fun set_seat_massage_mode(mode:String) {
        Log.d(TAG, "SoaService set_seat_massage_mode")


    }
    public fun set_seat_massage_intensity(intensity:String) {
        Log.d(TAG, "SoaService set_seat_massage_intensity")


    }
    public fun set_steering_heat(heat:Boolean) {
        Log.d(TAG, "SoaService set_steering_heat")


    }
    public fun set_chassis_mode(mode:String) {
        Log.d(TAG, "SoaService set_chassis_mode")


    }

    public fun set_door_lock(lock:Boolean) {
        Log.d(TAG, "SoaService set_door_lock")


    }
    public fun set_frag_type(type:String) {
        Log.d(TAG, "SoaService set_frag_type")


    }
    public fun set_frag_intensity(intensity:String) {
        Log.d(TAG, "SoaService set_frag_intensity")


    }
    public fun set_ac_status(status:Boolean) {
        Log.d(TAG, "SoaService setAcStatus")


    }
    public fun set_ac_drive_temp(temp:Int) {
        Log.d(TAG, "SoaService set_ac_drive_temp")


    }
    public fun set_ac_assist_temp(temp:Int) {
        Log.d(TAG, "SoaService set_ac_assist_temp")


    }
    public fun set_ac_fan_intensity(intensity:Int) {
        Log.d(TAG, "SoaService set_ac_fan_intensity")


    }
    public fun set_ac_eco_mode(mode:Boolean) {
        Log.d(TAG, "SoaService set_ac_eco_mode")


    }
    public fun set_ac_anion_status(status:Boolean) {
        Log.d(TAG, "SoaService set_ac_anion_status")


    }
    public fun set_ac_clean_mode(mode:String) {
        Log.d(TAG, "SoaService set_ac_clean_mode")


    }
    public fun set_ac_cyc_mode(mode:String) {
        Log.d(TAG, "SoaService set_ac_cyc_mode")


    }
    public fun set_ac_drive_sweep_auto(auto:Boolean) {
        Log.d(TAG, "SoaService set_ac_drive_sweep_auto")


    }
    public fun set_ac_assist_sweep_auto(auto:Boolean) {
        Log.d(TAG, "SoaService set_ac_assist_sweep_auto")


    }
    public fun set_ac_drive_left_air_outlet(auto:Boolean) {
        Log.d(TAG, "SoaService set_ac_drive_left_air_outlet")


    }
    public fun set_ac_drive_right_air_outlet(auto:Boolean) {
        Log.d(TAG, "SoaService set_ac_drive_right_air_outlet")


    }
    public fun set_ac_assist_air_outlet_mode(mode:String) {
        Log.d(TAG, "SoaService set_ac_assist_air_outlet_mode")


    }
    public fun set_ac_assist_left_air_outlet(auto:Boolean) {
        Log.d(TAG, "SoaService set_ac_assist_left_air_outlet")


    }
    public fun set_ac_assist_right_air_outlet(auto:Boolean) {
        Log.d(TAG, "SoaService set_ac_assist_right_air_outlet")


    }

    public fun setFlWindowStatus(status:Int) {
        Log.d(TAG, "SoaService setFlWindowStatus")


    }
    public fun setFrWindowStatus(status:Int) {
        Log.d(TAG, "SoaService setFrWindowStatus")


    }
    public fun setRlWindowStatus(status:Int) {
        Log.d(TAG, "SoaService setRlWindowStatus")


    }
    public fun setRrWindowStatus(status:Int) {
        Log.d(TAG, "SoaService setRrWindowStatus")


    }
    public fun setTopWindowStatus(status:Int) {
        Log.d(TAG, "SoaService setTopWindowStatus")


    }
    public fun setSunShadowStatus(status:Int) {
        Log.d(TAG, "SoaService setSunShadowStatus")


    }
    public fun set_window_f_defrosting(defrosting:Boolean) {
        Log.d(TAG, "SoaService set_window_f_defrosting")


    }
    public fun set_window_r_heat(heat:Boolean) {
        Log.d(TAG, "SoaService set_window_r_heat")


    }
    public fun set_mirror_l_heat(heat:Boolean) {
        Log.d(TAG, "SoaService set_mirror_l_heat")


    }
    public fun set_mirror_r_heat(heat:Boolean) {
        Log.d(TAG, "SoaService set_mirror_r_heat")


    }
    public fun set_no_window_opening_passengers(open:Boolean) {
        Log.d(TAG, "SoaService set_no_window_opening_passengers")


    }
}