package com.hirain.aiagent.eval;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** 仅 Debug variant 合并的显式 Binder Service，与 AIAgentService 使用同一进程。 */
public final class EvalDebugService extends Service {
    private EvalDebugBinder binder;
    @Override public void onCreate() { super.onCreate(); binder = new EvalDebugBinder(this); }
    @Override public IBinder onBind(Intent intent) { return binder; }
}
