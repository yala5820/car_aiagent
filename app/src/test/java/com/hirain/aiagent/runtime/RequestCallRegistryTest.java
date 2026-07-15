package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import okhttp3.Call;
import okhttp3.Request;

public class RequestCallRegistryTest {

    @Test
    public void cancel_cancelsRegisteredCall() {
        RequestCallRegistry registry = new RequestCallRegistry();
        Call call = newCall();
        registry.register("req-1", call);

        assertTrue(registry.cancel("req-1"));

        assertTrue(call.isCanceled());
        assertTrue(registry.isCancellationRequested("req-1"));
    }

    @Test
    public void cancellationBeforeRegister_cancelsLateCall() {
        RequestCallRegistry registry = new RequestCallRegistry();
        registry.cancel("req-1");
        Call lateCall = newCall();

        registry.register("req-1", lateCall);

        assertTrue(lateCall.isCanceled());
    }

    @Test
    public void oldRegistrationCannotRemoveNewerCall() {
        RequestCallRegistry registry = new RequestCallRegistry();
        Call first = newCall();
        RequestCallRegistry.Registration firstRegistration = registry.register("req-1", first);
        Call second = newCall();
        RequestCallRegistry.Registration secondRegistration = registry.register("req-1", second);

        firstRegistration.close();

        assertTrue(registry.hasActiveCall("req-1"));
        registry.cancel("req-1");
        assertTrue(second.isCanceled());
        secondRegistration.close();
        assertFalse(registry.hasActiveCall("req-1"));
    }

    @Test
    public void clear_removesEarlyCancellationMarkerForFutureReuse() {
        RequestCallRegistry registry = new RequestCallRegistry();
        registry.cancel("req-1");

        registry.clear("req-1");
        Call future = newCall();
        registry.register("req-1", future);

        assertFalse(future.isCanceled());
    }

    private static Call newCall() {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        Request request = new Request.Builder().url("http://127.0.0.1/").build();
        return client.newCall(request);
    }
}
