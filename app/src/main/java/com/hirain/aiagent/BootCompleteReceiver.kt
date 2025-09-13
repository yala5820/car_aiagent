/******************************************************************************
/                              Copyright
/------------------------------------------------------------------------------
/    Copyright © 2023 Z-ONE Technology Co., Ltd.  All rights reserved.
/
/    This software is furnished under a license and may be used and copied
/    only in accordance with the terms of such license and with the inclusion
/    of the above copyright notice. This software or any other copies thereof
/    may not be provided or otherwise made available to any other person.
/    No title to and ownership of the software is hereby transferred.
/
/    The information in this software is subject to change without notice
/    and should not be constructed as a commitment by
/    Z-ONE Technology Co., Ltd.
/
/    Z-ONE Technology Co., Ltd assumes no responsibility for the use
/    or reliability of its Software on equipment which is not supported by
/    Z-ONE Technology Co., Ltd.
/------------------------------------------------------------------------------
 *******************************************************************************/

package com.hirain.aiagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
class BootCompleteReceiver: BroadcastReceiver() {
    override fun onReceive(p0: Context?, p1: Intent?) {
        Log.d("aiagent", "BootCompleteReceiverxxxxxxx")
        p0?.startForegroundService(Intent(p0, AIAgentService::class.java))
    }

}