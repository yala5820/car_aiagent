# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class com.example.InputStreamCompat { *; }

# ── LangChain4j 工具反射 ──
# 保留 @Tool 注解所在方法（ToolDispatcher 通过反射调用）
-keep @dev.langchain4j.agent.tool.Tool class * {
    @dev.langchain4j.agent.tool.Tool <methods>;
}

# 保留所有 Manager 中含有 @Tool 注解的方法
-keepclassmembers class com.hirain.aiagent.tools.** {
    @dev.langchain4j.agent.tool.Tool *;
}

# 保留 @P 注解（ToolSpecifications 构建参数描述时需反射读取）
-keepclassmembers class * {
    @dev.langchain4j.agent.tool.P <fields>;
}