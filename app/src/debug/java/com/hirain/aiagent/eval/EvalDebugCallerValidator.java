package com.hirain.aiagent.eval;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** Debug Service 的调用方门禁：仅接纳指定的可调试 TestApp。 */
public final class EvalDebugCallerValidator {
    public static final String ALLOWED_PACKAGE = "com.hirain.aiagent.test";
    private final Context context;
    public EvalDebugCallerValidator(Context context) { this.context = context.getApplicationContext(); }
    public boolean isAllowed(int uid) {
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) return false;
        for (String packageName : packages) {
            if (!ALLOWED_PACKAGE.equals(packageName)) continue;
            try {
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                return (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            } catch (PackageManager.NameNotFoundException ignored) { return false; }
        }
        return false;
    }
}
