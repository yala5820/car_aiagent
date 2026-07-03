# AIAgentTestApp 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建独立的 Android 测试应用 AIAgentTestApp，通过 AIDL 连接 AIAgent 中枢，提供聊天式界面测试所有 inputType（TEXT/IMAGE/VOICE/CONTROL）

**Architecture:** 
- 独立项目 `AIAgentTestApp/`，与 `AIAgent/` 同级
- 复制 AIAgent 的 AIDL 桩代码 + 客户端类到源码树（同 Launcher 做法）
- 主界面：RecyclerView 气泡聊天 + EditText 输入 + 发送按钮
- 设置页：左上角入口，留空预留

**Tech Stack:** Android Java, RecyclerView, AIDL

## Global Constraints

- 包名: `com.hirain.aiagent.test`
- minSdk: 24, targetSdk: 34, compileSdk: 36
- AGP: 8.9.1, Gradle: 9.3.1 (与 Launcher 一致)
- 通过 AIDL 与 AIAgentService 通信（IAIAgentAidlInterface）
- 图片输入通过 imagePath（Base64 → 临时文件）
- 响应通过 IAIAgentServiceListener.onAIResponse(AgentResponse) 回调

---

## 文件结构

```
AIAgentTestApp/
├── build.gradle.kts                        # 顶级构建文件
├── settings.gradle.kts                     # 项目设置
├── gradle.properties                       # Gradle 属性
├── local.properties                        # SDK 路径
├── gradle/
│   ├── libs.versions.toml                  # 版本目录
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew / gradlew.bat
└── app/
    ├── build.gradle                        # 模块构建文件
    └── src/
        └── main/
            ├── AndroidManifest.xml
            ├── java/com/hirain/aiagent/    (AIDL 桩 + 客户端类)
            │   ├── AgentRequest.java
            │   ├── AgentResponse.java
            │   ├── IAIAgentAidlInterface.java
            │   ├── IAIAgentAidlListener.java
            │   ├── AIAgent.java
            │   └── IAIAgentServiceListener.java
            ├── java/com/hirain/aiagent/test/
            │   ├── MainActivity.java       # 聊天主界面
            │   ├── SettingsActivity.java   # 设置占位
            │   ├── ChatMessage.java        # 消息数据模型
            │   ├── ChatAdapter.java        # RecyclerView 适配器
            │   └── MyApplication.java      # 入口初始化
            └── res/
                ├── layout/
                │   ├── activity_main.xml
                │   ├── activity_settings.xml
                │   └── item_chat_message.xml
                ├── drawable/
                │   ├── bg_chat_bubble_send.xml     # 发送气泡背景
                │   ├── bg_chat_bubble_receive.xml  # 接收气泡背景
                │   └── ic_send.xml                 # 发送按钮图标
                ├── values/
                │   ├── strings.xml
                │   ├── colors.xml
                │   └── themes.xml
                └── mipmap-*/ic_launcher.xml
```

---

## Task 1: 项目脚手架搭建

**Files:**
- Create: `AIAgentTestApp/build.gradle.kts`
- Create: `AIAgentTestApp/settings.gradle.kts`
- Create: `AIAgentTestApp/gradle.properties`
- Create: `AIAgentTestApp/local.properties`
- Create: `AIAgentTestApp/gradle/libs.versions.toml`
- Create: `AIAgentTestApp/gradle/wrapper/gradle-wrapper.properties`
- Create: `AIAgentTestApp/app/build.gradle`
- Create: `AIAgentTestApp/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: 可编译的 Android 项目骨架

- [ ] **Step 1: 创建顶级设置文件和构建文件**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AIAgentTestApp"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvm.args=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
```

`local.properties`:
```properties
sdk.dir=D:/code/android/forSdk/Sdk
```

- [ ] **Step 2: 创建 libs.versions.toml**

从 Launcher 精简，只保留必要依赖：
```toml
[versions]
agp = "8.9.1"
appcompat = "1.6.1"
material = "1.10.0"
activity = "1.8.0"
constraintlayout = "2.1.4"
recyclerview = "1.3.2"

[libraries]
appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
activity = { group = "androidx.activity", name = "activity", version.ref = "activity" }
constraintlayout = { group = "androidx.constraintlayout", name = "constraintlayout", version.ref = "constraintlayout" }
recyclerview = { group = "androidx.recyclerview", name = "recyclerview", version.ref = "recyclerview" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
```

- [ ] **Step 3: 创建 gradle-wrapper.properties**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 4: 创建 app/build.gradle**

```groovy
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hirain.aiagent.test"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hirain.aiagent.test"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            minifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
}
```

- [ ] **Step 5: 创建 AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".MyApplication"
        android:allowBackup="true"
        android:label="AI测试"
        android:supportsRtl="true"
        android:theme="@style/Theme.AIAgentTestApp">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:label="设置" />
    </application>

</manifest>
```

- [ ] **Step 6: 复制 Gradle wrapper**

从 Launcher 项目复制 gradlew、gradlew.bat 和 gradle-wrapper.jar：
```bash
cp /d/code/android/AndroidStudioProjects/Launcher/gradlew /d/code/android/AndroidStudioProjects/AIAgentTestApp/
cp /d/code/android/AndroidStudioProjects/Launcher/gradlew.bat /d/code/android/AndroidStudioProjects/AIAgentTestApp/
cp /d/code/android/AndroidStudioProjects/Launcher/gradle/wrapper/gradle-wrapper.jar /d/code/android/AndroidStudioProjects/AIAgentTestApp/gradle/wrapper/
```

---

## Task 2: 复制 AIDL 桩和客户端类

**Files:**
- Create: `AIAgentTestApp/app/src/main/java/com/hirain/aiagent/` — 6 files

**Interfaces:**
- Consumes: AIAgent 项目 build 输出的 AIDL 桩
- Produces: 与 AIAgentService 通信所需的客户端类

从 AIAgent 项目复制以下文件（同 Launcher 做法）：

```bash
SRC=/d/code/android/AndroidStudioProjects/AIAgent
DST=/d/code/android/AndroidStudioProjects/AIAgentTestApp

# AIDL 生成的桩
cp $SRC/app/build/generated/aidl_source_output_dir/debug/out/com/hirain/aiagent/IAIAgentAidlInterface.java $DST/app/src/main/java/com/hirain/aiagent/
cp $SRC/app/build/generated/aidl_source_output_dir/debug/out/com/hirain/aiagent/IAIAgentAidlListener.java $DST/app/src/main/java/com/hirain/aiagent/

# 客户端类
cp $SRC/app/src/main/java/com/hirain/aiagent/AgentRequest.java $DST/app/src/main/java/com/hirain/aiagent/
cp $SRC/app/src/main/java/com/hirain/aiagent/AgentResponse.java $DST/app/src/main/java/com/hirain/aiagent/
cp $SRC/app/src/main/java/com/hirain/aiagent/AIAgent.java $DST/app/src/main/java/com/hirain/aiagent/
cp $SRC/app/src/main/java/com/hirain/aiagent/IAIAgentServiceListener.java $DST/app/src/main/java/com/hirain/aiagent/
```

---

## Task 3: 创建资源文件（布局 + 主题）

**Files:**
- Create: `AIAgentTestApp/app/src/main/res/` — 多个资源文件

- [ ] **Step 1: values/colors.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="purple_500">#6750A4</color>
    <color name="purple_700">#4F378B</color>
    <color name="teal_200">#03DAC5</color>
    <color name="teal_700">#018786</color>
    <color name="white">#FFFFFF</color>
    <color name="black">#FF000000</color>
    <color name="chat_bg">#FFF8F8F8</color>
    <color name="bubble_send">#FFE8F0FE</color>
    <color name="bubble_receive">#FFFFFFFF</color>
    <color name="divider">#FFE0E0E0</color>
</resources>
```

- [ ] **Step 2: values/themes.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AIAgentTestApp" parent="Theme.Material3.Light.NoActionBar">
        <item name="colorPrimary">@color/purple_500</item>
        <item name="colorPrimaryVariant">@color/purple_700</item>
        <item name="colorOnPrimary">@color/white</item>
        <item name="colorSecondary">@color/teal_200</item>
        <item name="colorSecondaryVariant">@color/teal_700</item>
        <item name="android:statusBarColor">@color/white</item>
    </style>
</resources>
```

- [ ] **Step 3: values/strings.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">AI测试</string>
    <string name="input_hint">请输入消息…</string>
    <string name="btn_send">发送</string>
    <string name="title_settings">设置</string>
</resources>
```

- [ ] **Step 4: drawable/bg_chat_bubble_send.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/bubble_send" />
    <corners android:topLeft="16dp" android:topRight="16dp"
             android:bottomLeft="16dp" android:bottomRight="4dp" />
    <padding android:left="12dp" android:top="8dp"
             android:right="12dp" android:bottom="8dp" />
</shape>
```

- [ ] **Step 5: drawable/bg_chat_bubble_receive.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/bubble_receive" />
    <corners android:topLeft="16dp" android:topRight="16dp"
             android:bottomLeft="4dp" android:bottomRight="16dp" />
    <padding android:left="12dp" android:top="8dp"
             android:right="12dp" android:bottom="8dp" />
</shape>
```

- [ ] **Step 6: drawable/ic_send.xml**

一个简单的发送图标（矢量）：
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@color/purple_500"
        android:pathData="M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z" />
</vector>
```

- [ ] **Step 7: layout/activity_main.xml**

主聊天界面布局，ConstraintLayout 实现，适配车机和手机：
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/chat_bg">

    <!-- 顶部工具栏 -->
    <androidx.appcompat.widget.Toolbar
        android:id="@+id/toolbar"
        android:layout_width="0dp"
        android:layout_height="?attr/actionBarSize"
        android:background="@color/white"
        app:title="AI 测试"
        app:titleTextColor="@color/black"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- 设置按钮 -->
    <ImageButton
        android:id="@+id/btn_settings"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:src="@drawable/ic_send"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="设置"
        android:rotation="90"
        app:layout_constraintTop_toTopOf="@id/toolbar"
        app:layout_constraintStart_toStartOf="@id/toolbar"
        app:layout_constraintBottom_toBottomOf="@id/toolbar" />

    <!-- 聊天消息列表 -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rv_chat"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:clipToPadding="false"
        android:padding="12dp"
        android:overScrollMode="never"
        app:layout_constraintTop_toBottomOf="@id/toolbar"
        app:layout_constraintBottom_toTopOf="@id/input_layout"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- 底部输入区域 -->
    <LinearLayout
        android:id="@+id/input_layout"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:background="@color/white"
        android:paddingHorizontal="8dp"
        android:paddingVertical="6dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <EditText
            android:id="@+id/et_input"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="@string/input_hint"
            android:inputType="text"
            android:maxLines="4"
            android:background="@null"
            android:textSize="16sp"
            android:paddingHorizontal="12dp"
            android:paddingVertical="10dp" />

        <ImageButton
            android:id="@+id/btn_send"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:src="@drawable/ic_send"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/btn_send"
            android:layout_gravity="center_vertical" />

    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 8: layout/activity_settings.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="设置（预留）"
        android:textSize="18sp"
        android:textColor="#999"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 9: layout/item_chat_message.xml**

消息气泡布局：
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingVertical="4dp">

    <!-- 时间标签 -->
    <TextView
        android:id="@+id/tv_time"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="12sp"
        android:textColor="#999"
        android:layout_gravity="center_horizontal"
        android:visibility="gone" />

    <!-- 气泡 -->
    <TextView
        android:id="@+id/tv_message"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:maxWidth="280dp"
        android:textSize="16sp"
        android:textColor="@color/black"
        android:lineSpacingExtra="4dp"
        android:padding="12dp" />

</LinearLayout>
```

---

## Task 4: 创建 Java 源文件（模型 + 适配器 + Application）

**Files:**
- Create: `AIAgentTestApp/app/src/main/java/com/hirain/aiagent/test/ChatMessage.java`
- Create: `AIAgentTestApp/app/src/main/java/com/hirain/aiagent/test/ChatAdapter.java`
- Create: `AIAgentTestApp/app/src/main/java/com/hirain/aiagent/test/MyApplication.java`

- [ ] **Step 1: ChatMessage.java**

```java
package com.hirain.aiagent.test;

public class ChatMessage {
    public static final int TYPE_SENT = 0;      // 发送的消息（用户）
    public static final int TYPE_RECEIVED = 1;  // 接收的消息（AI）

    private final int type;
    private final String content;
    private final long timestamp;

    public ChatMessage(int type, String content, long timestamp) {
        this.type = type;
        this.content = content;
        this.timestamp = timestamp;
    }

    public int getType() { return type; }
    public String getContent() { return content; }
    public long getTimestamp() { return timestamp; }
}
```

- [ ] **Step 2: ChatAdapter.java**

```java
package com.hirain.aiagent.test;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    private final List<ChatMessage> messages = new ArrayList<>();

    public void addMessage(ChatMessage msg) {
        messages.add(msg);
        notifyItemInserted(messages.size() - 1);
    }

    public void addMessages(List<ChatMessage> msgs) {
        int start = messages.size();
        messages.addAll(msgs);
        notifyItemRangeInserted(start, msgs.size());
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getType();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new ViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        holder.tvMessage.setText(msg.getContent());

        // 根据发送/接收设置不同对齐和气泡背景
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) holder.tvMessage.getLayoutParams();
        if (msg.getType() == ChatMessage.TYPE_SENT) {
            holder.tvMessage.setBackgroundResource(R.drawable.bg_chat_bubble_send);
            params.gravity = android.view.Gravity.END;
        } else {
            holder.tvMessage.setBackgroundResource(R.drawable.bg_chat_bubble_receive);
            params.gravity = android.view.Gravity.START;
        }
        holder.tvMessage.setLayoutParams(params);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tv_message);
        }
    }
}
```

- [ ] **Step 3: MyApplication.java**

```java
package com.hirain.aiagent.test;

import android.app.Application;
import com.hirain.aiagent.AIAgent;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AIAgent.getInstance().init(this);
    }
}
```

---

## Task 5: 创建 MainActivity（聊天主界面）

**Files:**
- Create: `AIAgentTestApp/app/src/main/java/com/hirain/aiagent/test/MainActivity.java`

核心逻辑：绑定 AIAgent，发送消息，接收回调，更新聊天列表。

```java
package com.hirain.aiagent.test;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hirain.aiagent.AIAgent;
import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.AgentResponse;
import com.hirain.aiagent.IAIAgentServiceListener;

import java.util.UUID;

public class MainActivity extends AppCompatActivity implements IAIAgentServiceListener {

    private static final String TAG = "AIAgentTest";
    private static final String SESSION_ID = "test_user";
    private static final String SOURCE_APP = "aiagent_test";

    private RecyclerView rvChat;
    private EditText etInput;
    private ImageButton btnSend;
    private ImageButton btnSettings;
    private ChatAdapter chatAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupRecyclerView();
        setupListeners();

        // 注册 AIAgent 回调
        AIAgent.getInstance().registerAIAgentLisener(this);

        // 添加欢迎消息
        chatAdapter.addMessage(new ChatMessage(
                ChatMessage.TYPE_RECEIVED, "你好！我是 AI 测试助手，请开始对话。", System.currentTimeMillis()));
    }

    private void initViews() {
        rvChat = findViewById(R.id.rv_chat);
        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);
        btnSettings = findViewById(R.id.btn_settings);
    }

    private void setupRecyclerView() {
        chatAdapter = new ChatAdapter();
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(chatAdapter);
    }

    private void setupListeners() {
        btnSend.setOnClickListener(v -> sendTextMessage());

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        etInput.setOnEditorActionListener((v, actionId, event) -> {
            sendTextMessage();
            return true;
        });
    }

    private void sendTextMessage() {
        String text = etInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        etInput.setText("");

        // 显示用户消息
        chatAdapter.addMessage(new ChatMessage(
                ChatMessage.TYPE_SENT, text, System.currentTimeMillis()));
        scrollToBottom();

        // 构造 AgentRequest
        AgentRequest req = new AgentRequest();
        req.setRequestId(UUID.randomUUID().toString());
        req.setSessionId(SESSION_ID);
        req.setSourceApp(SOURCE_APP);
        req.setText(text);
        req.setInputType("TEXT");
        req.setTimestamp(System.currentTimeMillis());

        AIAgent.getInstance().processAgentRequest(req);
    }

    private void scrollToBottom() {
        rvChat.post(() -> rvChat.smoothScrollToPosition(chatAdapter.getItemCount() - 1));
    }

    // ======== IAIAgentServiceListener ========

    @Override
    public void onAIAgentServiceConnected() {
        Log.d(TAG, "AIAgentService 连接成功");
    }

    @Override
    public void onAIAgentServiceDisconnected() {
        Log.d(TAG, "AIAgentService 断开");
    }

    @Override
    public void onAIResponse(AgentResponse response) {
        if (response == null || response.getText() == null) return;
        runOnUiThread(() -> {
            chatAdapter.addMessage(new ChatMessage(
                    ChatMessage.TYPE_RECEIVED, response.getText(), System.currentTimeMillis()));
            scrollToBottom();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AIAgent.getInstance().unRegisterAIAgentLisener(this);
    }
}
```

---

## Task 6: 创建 SettingsActivity（占位）

**Files:**
- Create: `AIAgentTestApp/app/src/main/java/com/hirain/aiagent/test/SettingsActivity.java`

```java
package com.hirain.aiagent.test;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
    }
}
```

---

## Verification

1. **编译验证**: 
```bash
cd /d/code/android/AndroidStudioProjects/AIAgentTestApp
./gradlew :app:compileDebugJavaWithJavac
```
Expected: BUILD SUCCESSFUL

2. **启动验证**（需要设备 + AIAgentService 已运行）:
   - 安装并启动 AIA 테스트
   - 确认看到聊天界面 + 欢迎消息
   - 输入文字点发送 → AgentRequest(TEXT) → AIAgentService → AgentResponse → 显示在聊天列表
   - 点左上角设置按钮 → 跳转到设置页（空白预留）→ 返回
