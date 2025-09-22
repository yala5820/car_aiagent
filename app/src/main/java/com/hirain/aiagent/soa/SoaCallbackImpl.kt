package com.hirain.aiagent.soa

import android.util.Log
import hirain.carina.GetValueResult
import hirain.carina.GetValueResults
import hirain.carina.ISoaCallback
import hirain.carina.PropErrors
import hirain.carina.PropValues
import hirain.carina.SetValueResult
import hirain.carina.SetValueResults

class SoaCallbackImpl(
    private val getValue: (Map<Long, Int>) -> Unit
): ISoaCallback.Stub() {

    override fun onGetValues(responses: GetValueResults?) {
        Log.i("jk", "onGetValues---------------------")
        val results = mapOf<Long, Int>()
        for (response: GetValueResult in responses?.payloads!!) {
            val value = response.prop.value.int32Values[0]
            Log.i("jk", "id: ${response.requestId} get value: $value")
            results.plus(Pair<Long, Int>(response.requestId, value))
        }
        getValue.invoke(results)
    }

    override fun onSetValues(responses: SetValueResults?) {
        Log.i("jk", "onSetValues---------------------")
        for (response: SetValueResult in responses?.payloads!!) {
            val id = response.requestId
            Log.i("jk", "id: $id set value successfully!")
        }
    }

    override fun onPropertyEvent(propValues: PropValues?) {
        TODO("Not yet implemented")
    }

    override fun onPropertySetError(errors: PropErrors?) {
        TODO("Not yet implemented")
    }
}