import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipFile

fun releaseValue(name: String): String? = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)

val releaseStoreFile = releaseValue("TEAMBOX_RELEASE_STORE_FILE")
val releaseStorePassword = releaseValue("TEAMBOX_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseValue("TEAMBOX_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseValue("TEAMBOX_RELEASE_KEY_PASSWORD")
val releaseTargetRuleKeyId = releaseValue("TEAMBOX_TARGET_RULE_KEY_ID")
val releaseTargetRulePublicKeyHex = releaseValue("TEAMBOX_TARGET_RULE_PUBLIC_KEY_X509_HEX")
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasAnyReleaseSigningValue = releaseSigningValues.any { it != null }
val hasCompleteReleaseSigning = releaseSigningValues.all { it != null }

check(!hasAnyReleaseSigningValue || hasCompleteReleaseSigning) {
    "Release 签名参数不完整；请同时设置 TEAMBOX_RELEASE_STORE_FILE、" +
        "TEAMBOX_RELEASE_STORE_PASSWORD、TEAMBOX_RELEASE_KEY_ALIAS、" +
        "TEAMBOX_RELEASE_KEY_PASSWORD。"
}

val targetRuleTrustValues = listOf(releaseTargetRuleKeyId, releaseTargetRulePublicKeyHex)
val hasAnyTargetRuleTrustValue = targetRuleTrustValues.any { it != null }
val hasCompleteTargetRuleTrust = targetRuleTrustValues.all { it != null }
check(!hasAnyTargetRuleTrustValue || hasCompleteTargetRuleTrust) {
    "目标规则信任参数不完整；请同时设置 TEAMBOX_TARGET_RULE_KEY_ID 和 " +
        "TEAMBOX_TARGET_RULE_PUBLIC_KEY_X509_HEX。"
}
if (hasCompleteTargetRuleTrust) {
    val keyId = requireNotNull(releaseTargetRuleKeyId)
    val publicKeyHex = requireNotNull(releaseTargetRulePublicKeyHex)
    check(Regex("[A-Za-z0-9._-]{1,80}").matches(keyId)) { "目标规则 keyId 格式无效。" }
    check(publicKeyHex.length % 2 == 0 && Regex("[0-9A-Fa-f]+").matches(publicKeyHex)) {
        "目标规则 X.509 公钥必须是偶数长度的十六进制字符串。"
    }
    val encodedKey = ByteArray(publicKeyHex.length / 2) { index ->
        publicKeyHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    val publicKey = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encodedKey)) as ECPublicKey
    }.getOrElse { error("目标规则 X.509 公钥无法解析为 EC 公钥：${it.message}") }
    check(publicKey.params.curve.field.fieldSize == 256) { "目标规则公钥必须使用 P-256 曲线。" }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }

        androidMain.dependencies {
            implementation(project(":androidRuntime"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

    }
}

android {
    namespace = "com.danmukey.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wintopic.teambox"
        minSdk = 26
        targetSdk = 35
        versionCode = 100000
        versionName = "1.0.0"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    val configuredReleaseSigning = if (hasCompleteReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(requireNotNull(releaseStoreFile))
            storePassword = requireNotNull(releaseStorePassword)
            keyAlias = requireNotNull(releaseKeyAlias)
            keyPassword = requireNotNull(releaseKeyPassword)
        }
    } else {
        null
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "TARGET_RULE_KEY_ID", "\"${releaseTargetRuleKeyId.orEmpty()}\"")
            buildConfigField(
                "String",
                "TARGET_RULE_PUBLIC_KEY_X509_HEX",
                "\"${releaseTargetRulePublicKeyHex.orEmpty()}\"",
            )
            configuredReleaseSigning?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.danmukey.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Teambox"
            packageVersion = "1.0.0"
        }
    }
}

val verifyReleaseManifestPolicy by tasks.registering {
    group = "verification"
    description = "Verifies Android Release privacy, debug, and development-trust boundaries."
    dependsOn("processReleaseManifest")

    doLast {
        val manifestFile = layout.buildDirectory
            .file("intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml")
            .get()
            .asFile
        check(manifestFile.isFile) { "找不到 Release 合并 Manifest：${manifestFile.absolutePath}" }
        val manifest = manifestFile.readText()
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val manifestDocument = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile)
        val permissionNodes = manifestDocument.getElementsByTagName("uses-permission")
        val declaredPermissions = buildSet {
            for (index in 0 until permissionNodes.length) {
                val permission = permissionNodes.item(index) as org.w3c.dom.Element
                add(permission.getAttributeNS(androidNamespace, "name"))
            }
        }
        val serviceNodes = manifestDocument.getElementsByTagName("service")
        val declaredServices = List(serviceNodes.length) { index ->
            serviceNodes.item(index) as org.w3c.dom.Element
        }

        check("android.permission.INTERNET" in declaredPermissions) {
            "Release Manifest 必须申请 INTERNET 权限以检查 GitHub Release 更新。"
        }
        check("android.permission.ACCESS_NETWORK_STATE" !in declaredPermissions) {
            "Release Manifest 不得申请 ACCESS_NETWORK_STATE 权限。"
        }
        check("DebugCommandReceiver" !in manifest && "com.danmukey.debug.action" !in manifest) {
            "Release Manifest 不得包含 Debug 测试入口。"
        }
        check("TemplateCaptureActivity" !in manifest) {
            "单功能 Release 不得包含模板选区 Activity。"
        }
        check(
            declaredServices.none { service ->
                service.getAttributeNS(androidNamespace, "name").endsWith(".DanmuKeyboardService")
            },
        ) {
            "单功能 Release 不得继续暴露输入法服务。"
        }
        check("android.permission.FOREGROUND_SERVICE" in declaredPermissions) {
            "Release Manifest 必须申请 FOREGROUND_SERVICE 权限。"
        }
        check("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" in declaredPermissions) {
            "Release Manifest 必须申请 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限。"
        }
        val projectionService = declaredServices.singleOrNull { service ->
            service.getAttributeNS(androidNamespace, "name").endsWith(".ProjectionCaptureService")
        } ?: error("Release Manifest 必须且只能声明一个 ProjectionCaptureService。")
        val projectionServiceType = projectionService
            .getAttributeNS(androidNamespace, "foregroundServiceType")
        check(projectionServiceType == "mediaProjection") {
            "ProjectionCaptureService 必须仅声明 foregroundServiceType=mediaProjection。"
        }
        check(projectionService.getAttributeNS(androidNamespace, "exported") == "false") {
            "ProjectionCaptureService 必须保持 android:exported=false。"
        }
        check("android:debuggable=\"true\"" !in manifest) {
            "Release Manifest 不得启用 debuggable。"
        }
        check("android:usesCleartextTraffic=\"false\"" in manifest) {
            "Release Manifest 必须明确禁用明文网络流量。"
        }
        check("android:allowBackup=\"false\"" in manifest) {
            "Release Manifest 必须禁用应用数据备份。"
        }

        val releaseApk = layout.buildDirectory
            .dir("outputs/apk/release")
            .get()
            .asFile
            .listFiles()
            .orEmpty()
            .singleOrNull { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?: error("找不到唯一的 Release APK，无法检查目标规则信任边界。")
        val forbiddenArtifactMarkers = listOf(
            "danmukey-development-2026-08",
            "3059301306072a8648ce3d020106082a8648ce3d030107",
            "DebugCommandReceiver",
            "com.danmukey.debug.action.",
        )
        fun findForbiddenArtifactMarker(artifact: File, dexEntryPattern: Regex): String? =
            ZipFile(artifact).use { archive ->
                archive.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.matches(dexEntryPattern) }
                    .map { entry ->
                        archive.getInputStream(entry).use { stream ->
                            String(stream.readBytes(), Charsets.ISO_8859_1)
                        }
                    }
                    .flatMap { dexText ->
                        forbiddenArtifactMarkers.asSequence().filter { marker -> dexText.contains(marker) }
                    }
                    .firstOrNull()
            }

        val releaseBundle = layout.buildDirectory
            .dir("outputs/bundle/release")
            .get()
            .asFile
            .listFiles()
            .orEmpty()
            .singleOrNull { it.isFile && it.extension.equals("aab", ignoreCase = true) }
        val releaseArtifacts = buildList {
            add(releaseApk to Regex("classes[0-9]*\\.dex"))
            releaseBundle?.let { add(it to Regex("base/dex/classes[0-9]*\\.dex")) }
        }
        releaseArtifacts.forEach { (artifact, dexEntryPattern) ->
            val leakedMarker = findForbiddenArtifactMarker(artifact, dexEntryPattern)
            check(leakedMarker == null) {
                "${artifact.name} 不得包含 Release 禁止标记：$leakedMarker"
            }
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    finalizedBy(verifyReleaseManifestPolicy)
}
