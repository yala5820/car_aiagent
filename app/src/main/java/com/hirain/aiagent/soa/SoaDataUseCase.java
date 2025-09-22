package com.hirain.aiagent.soa;

import android.os.RemoteException;
import android.util.Log;

import com.hirain.aiagent.soa.data.SoaDataInfo;
import com.hirain.aiagent.soa.data.local.LocalSoaData;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import hirain.carina.GetValueRequest;
import hirain.carina.GetValueRequests;
import hirain.carina.ISoaCallback;
import hirain.carina.ISoaService;
import hirain.carina.PropValue;
import hirain.carina.RawPropValues;
import hirain.carina.SetValueRequest;
import hirain.carina.SetValueRequests;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

public class SoaDataUseCase {

    private final ISoaService soaService;
    private final List<@NotNull SoaDataInfo> allSoaData = LocalSoaData.INSTANCE.getAllSoaData();
    private final ISoaCallback soaCallback;

    public SoaDataUseCase(ISoaService soaService, Function1<? super Map<Long, Integer>, Unit> getValue) {
        this.soaService = soaService;
        this.soaCallback = new SoaCallbackImpl(getValue);
    }

    public void getSoaValue() {
        Log.i("jk", "Start get value----");
        GetValueRequests getValueRequests = new GetValueRequests();
        GetValueRequest[] requests = new GetValueRequest[allSoaData.size()];

        for (int i = 0; i < allSoaData.size(); i++) {
            SoaDataInfo soaDataInfo = allSoaData.get(i);
            GetValueRequest request = new GetValueRequest();
            request.requestId = soaDataInfo.getId();
            request.prop = getPropValue(soaDataInfo);
            requests[i] = request;
        }

        getValueRequests.payloads = requests;
        Log.i("jk", "Get value request: " + Arrays.toString(getValueRequests.payloads));
        try {
            soaService.getValues(soaCallback, getValueRequests);
        } catch (RemoteException e) {
            Log.i("jk", "getSoaValue error " + e);
        }
    }

    public void setSoaValue(List<Long> requestIds) {
        Log.i("jk", "Start set value----");
        ISoaCallback soaCallback = null;
        SetValueRequests setValueRequests = new SetValueRequests();
        SetValueRequest[] requests = new SetValueRequest[requestIds.size()];

        for (int i = 0; i < requestIds.size(); i++) {
            SetValueRequest request = new SetValueRequest();
            request.requestId = 10001;
            SoaDataInfo soaDataInfo = new SoaDataInfo(10001,0, 0, "", 0, "");
            request.value = getPropValue(soaDataInfo);
            requests[i] = request;
        }

        setValueRequests.payloads = requests;
        Log.i("jk", "Set value request: " + setValueRequests.payloads.length);
        try {
            soaService.setValues(soaCallback, setValueRequests);
        } catch (RemoteException e) {
            Log.i("jk", "setSoaValue error " + e);
        }
    }

    private PropValue getPropValue(SoaDataInfo soaDataInfo) {
        RawPropValues rawPropValues = new RawPropValues();
        rawPropValues.stringValue = "I am setting data." + System.currentTimeMillis();
        rawPropValues.byteValues = new byte[]{1};
        rawPropValues.floatValues = new float[]{1};
        rawPropValues.int32Values = new int[]{1};
        rawPropValues.int64Values = new long[]{1};
        PropValue propValue = new PropValue();
        propValue.timestamp = System.currentTimeMillis();
        propValue.areaId = soaDataInfo.getAreaId();
        propValue.prop = soaDataInfo.getProperId();
        propValue.value = rawPropValues;
        return propValue;
    }

}
