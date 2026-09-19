plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aicabinet.edge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aicabinet.edge"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.6.0"
        buildConfigField("String", "DEVICE_ID", "\"CAB-001\"")
        buildConfigField("String", "MQTT_BROKER", "\"tcp://10.0.2.2:11883\"")
        // dev 默认设备账号（aicabinet-device 仅能收发 cabinet/{deviceId}/#，见 infra/docker/emqx/aicabinet-acl.conf）。
        // 生产现场经 SharedPreferences 覆盖 mqtt_username/mqtt_password 为生产强口令。
        buildConfigField("String", "MQTT_USERNAME", "\"aicabinet-device\"")
        buildConfigField("String", "MQTT_PASSWORD", "\"dev-mqtt-device-pass\"")
        buildConfigField("boolean", "MQTT_USE_TLS", "false")
        // 自签环境请改为 true，并下发 truststore；公有 CA 可保持 false
        buildConfigField("boolean", "MQTT_TLS_STRICT", "false")
        buildConfigField("String", "MQTT_TRUST_STORE_PATH", "\"\"")
        buildConfigField("String", "MQTT_TRUST_STORE_PASSWORD", "\"\"")
        buildConfigField("String", "MQTT_TRUST_STORE_TYPE", "\"PKCS12\"")
        buildConfigField("String", "MQTT_KEY_STORE_PATH", "\"\"")
        buildConfigField("String", "MQTT_KEY_STORE_PASSWORD", "\"\"")
        buildConfigField("String", "MQTT_KEY_STORE_TYPE", "\"PKCS12\"")
        buildConfigField("String", "TRADE_SERVICE_URL", "\"http://10.0.2.2:8080\"")
        buildConfigField("String", "INTERNAL_API_KEY", "\"dev-internal-key-change-me\"")
        buildConfigField("String", "SERIAL_PORT_PATH", "\"/dev/ttyS2\"")
        buildConfigField("boolean", "USE_MOCK_DRIVER", "true")
        buildConfigField("boolean", "MULTI_CAMERA_ENABLED", "false")
    }

    flavorDimensions += "target"
    productFlavors {
        create("mock") {
            dimension = "target"
            buildConfigField("boolean", "USE_MOCK_DRIVER", "true")
        }
        create("device") {
            dimension = "target"
            buildConfigField("boolean", "USE_MOCK_DRIVER", "false")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // JVM 单元测试（testMockDebugUnitTest / testDeviceDebugUnitTest）。
    // isReturnDefaultValues：android.util.Log 等未打桩的框架方法返回默认值，
    // 而不是抛 "Stub!" —— 纯逻辑用例不必为了避 Log 而强引 Robolectric。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    val camerax = "1.3.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("com.licheedev:android-serialport:2.1.2")

    // 单元测试（JVM，无需真机/模拟器）。AGP 的 testMockDebugUnitTest 任务用 junit4 跑。
    testImplementation("junit:junit:4.13.2")
}
