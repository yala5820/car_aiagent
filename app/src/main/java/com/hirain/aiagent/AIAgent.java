package com.hirain.aiagent;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.AgentResponse;

public class AIAgent {
    private static final String TAG = "AIAgent";
    private static final int MSG_REBIND = 0;
    private static final long RETRY_DELAY_MS = 2000;

    private static AIAgent sInstance;

    private Context mContext;
    private IAIAgentAidlInterface m_service;
    private final AtomicBoolean mIsConnection = new AtomicBoolean(false);
    private final AtomicBoolean mIsInit = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<IAIAgentServiceListener> mIAIAgentServiceListeners =
            new CopyOnWriteArrayList<>();
    private final IAIAgentAidlListener m_aidlCallback = new AIAgentAidlCallback();
    private final ServiceConnection m_connection = new AIAgentServiceConnection();
    private final IBinder.DeathRecipient mDeathRecipient = this::onBinderDied;

    private Handler mHandler;
    private Handler mRetryHandler;
    private final AtomicReference<HandlerThread> mRetryHandlerThread = new AtomicReference<>();
    private boolean mNeedRetry;

    public static AIAgent getInstance() {
        if (sInstance == null) {
            sInstance = new AIAgent();
        }
        return sInstance;
    }

    private AIAgent() {}

    public void init(Context context) {
        init(context, null);
    }

    public void init(Context context, IAIAgentServiceListener listener) {
        if (context == null) {
            Log.e(TAG, "init: context == null");
            return;
        }
        mContext = context.getApplicationContext();
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper(), this::handleMainMessage);
        }
        if (listener != null) {
            mIAIAgentServiceListeners.add(listener);
        }
        mIsInit.set(true);
        // 先启动 AIAgentService（如果还没运行）
        ensureServiceRunning();
        if (!bindService()) {
            mNeedRetry = true;
            reBindService();
        }
    }

    public void release() {
        mIsInit.set(false);
        mNeedRetry = false;
        if (mRetryHandler != null) {
            mRetryHandler.removeMessages(MSG_REBIND);
        }
        if (mHandler != null) {
            mHandler.removeMessages(MSG_REBIND);
        }
        if (m_service != null && m_service.asBinder().isBinderAlive()) {
            try {
                m_service.unregisterListener(m_aidlCallback);
                m_service.asBinder().unlinkToDeath(mDeathRecipient, 0);
            } catch (Exception e) {
                Log.e(TAG, "release: " + e.getMessage());
            }
        }
        if (mContext != null && m_connection != null) {
            try {
                mContext.unbindService(m_connection);
            } catch (Exception e) {
                Log.e(TAG, "unbindService: " + e.getMessage());
            }
        }
        m_service = null;
        mIsConnection.set(false);
        releaseThreadHandle();
    }

    public int processAgentRequest(AgentRequest request) {
        if (m_service == null) {
            Log.w(TAG, "processAgentRequest: service not connected");
            return -1;
        }
        try {
            m_service.processAgentRequest(request);
            return 0;
        } catch (RemoteException e) {
            Log.e(TAG, "processAgentRequest failed", e);
            return -1;
        }
    }

    public boolean isConnected() {
        return mIsConnection.get();
    }

    public void registerAIAgentLisener(IAIAgentServiceListener listener) {
        if (listener != null && !mIAIAgentServiceListeners.contains(listener)) {
            mIAIAgentServiceListeners.add(listener);
        }
    }

    public void unRegisterAIAgentLisener(IAIAgentServiceListener listener) {
        if (listener != null) {
            mIAIAgentServiceListeners.remove(listener);
        }
    }

    // 确保 AIAgentService 已在运行（不受 MIUI 前台服务限制影响）
    private void ensureServiceRunning() {
        if (mContext == null) return;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.hirain.aiagent", "com.hirain.aiagent.AIAgentService"));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mContext.startForegroundService(intent);
            } else {
                mContext.startService(intent);
            }
            Log.d(TAG, "ensureServiceRunning: startForegroundService called");
        } catch (Exception e) {
            Log.e(TAG, "ensureServiceRunning failed", e);
        }
    }

    private boolean bindService() {
        if (mContext == null) return false;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.hirain.aiagent", "com.hirain.aiagent.AIAgentService"));
        // 不使用 BIND_AUTO_CREATE —— AIAgentService 由 SystemUI 或 BootReceiver 启动
        // MIUI 会阻止非系统应用通过 BIND_AUTO_CREATE 创建前台服务
        // BIND_NOT_FOREGROUND 告诉系统此绑定不提升服务优先级
        boolean bound = mContext.bindService(intent, m_connection, Context.BIND_NOT_FOREGROUND);
        Log.d(TAG, "bindService bound = " + bound);
        return bound;
    }

    private void reBindService() {
        Log.d(TAG, "== rebindService ==");
        bindService();
        if (!mIsConnection.get() && mNeedRetry) {
            createThreadHandle();
            if (mRetryHandler != null) {
                mRetryHandler.sendEmptyMessageDelayed(MSG_REBIND, RETRY_DELAY_MS);
            }
        }
    }

    private void createThreadHandle() {
        if (mRetryHandlerThread.get() != null) return;
        HandlerThread thread = new HandlerThread("retry_thread");
        thread.start();
        mRetryHandlerThread.set(thread);
        mRetryHandler = new Handler(thread.getLooper(), msg -> {
            Log.d(TAG, "retry handler: rebind");
            reBindService();
            return true;
        });
    }

    private void releaseThreadHandle() {
        if (mRetryHandler != null) {
            mRetryHandler.removeMessages(MSG_REBIND);
            mRetryHandler = null;
        }
        HandlerThread thread = mRetryHandlerThread.getAndSet(null);
        if (thread != null) {
            thread.quitSafely();
        }
    }

    private void onBinderDied() {
        Log.d(TAG, "enter binderDied!");
        mIsConnection.set(false);
        mNeedRetry = true;
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_REBIND);
        }
    }

    private boolean handleMainMessage(Message msg) {
        if (msg.what == MSG_REBIND) {
            reBindService();
            return true;
        }
        return false;
    }

    private void notifyServiceConnected() {
        mIsConnection.set(true);
        mNeedRetry = false;
        for (IAIAgentServiceListener listener : mIAIAgentServiceListeners) {
            if (listener != null) {
                listener.onAIAgentServiceConnected();
            }
        }
    }

    private void notifyServiceDisconnected() {
        mIsConnection.set(false);
        for (IAIAgentServiceListener listener : mIAIAgentServiceListeners) {
            if (listener != null) {
                listener.onAIAgentServiceDisconnected();
            }
        }
    }

    private void notifyAIResponse(AgentResponse response) {
        Log.d(TAG, "enter onAIResponse. requestId=" + response.getRequestId()
                + " success=" + response.isSuccess());
        for (IAIAgentServiceListener listener : mIAIAgentServiceListeners) {
            if (listener != null) {
                listener.onAIResponse(response);
            }
        }
    }

    // ---- Inner classes ----

    private class AIAgentAidlCallback extends IAIAgentAidlListener.Stub {
        @Override
        public void onAIResponse(AgentResponse response) throws RemoteException {
            Log.d(TAG, "onAIResponse requestId=" + response.getRequestId()
                    + " success=" + response.isSuccess());
            notifyAIResponse(response);
        }
    }

    private class AIAgentServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "enter onAIAgentServiceConnected.");
            m_service = IAIAgentAidlInterface.Stub.asInterface(service);
            try {
                service.linkToDeath(mDeathRecipient, 0);
                if (m_service != null) {
                    m_service.registerListener(m_aidlCallback);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "linkToDeath or registerListener failed", e);
            }
            notifyServiceConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "enter onAIAgentServiceDisconnected.");
            m_service = null;
            notifyServiceDisconnected();
        }
    }
}
