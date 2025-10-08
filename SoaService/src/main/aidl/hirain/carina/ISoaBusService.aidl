package hirain.carina;

import hirain.carina.ISoaBusCallback;

interface ISoaBusService {
    boolean addService(in @utf8InCpp String name);
    boolean removeService(in @utf8InCpp String name);
    boolean checkService(in @utf8InCpp String name);
    @utf8InCpp List<String> getServiceList();
    boolean addListener(in @utf8InCpp List<String> names, in ISoaBusCallback callback);
    boolean removeListener(in @utf8InCpp List<String> names, in ISoaBusCallback callback);
}
