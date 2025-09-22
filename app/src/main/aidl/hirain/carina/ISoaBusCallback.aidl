package hirain.carina;

import hirain.carina.SoaServiceInfo;

interface ISoaBusCallback {
    oneway void onServiceChange(in SoaServiceInfo info);
}
