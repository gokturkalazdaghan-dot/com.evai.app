// android/app/build.gradle.kts (modül seviyesi)
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// local.properties, gradle.properties'in aksine .gitignore'da hariç
// tutulur ve her geliştiricinin kendi makinesinde tutması gereken gizli
// değerleri (RevenueCat API key gibi) barındırır. Burada elle okunup
// project extra property'lerine yazılıyor ki defaultConfig içindeki
// project.findProperty(...) çağrıları bu değerlere erişebilsin.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}
localProperties.forEach { (key, value) ->
    val name = key.toString()
    // ONCELIK: -P / gradle.properties / ortam degiskeni > local.properties
    //
    // Onceden local.properties KOSULSUZ eziyordu; komut satirindan
    // "-PEVA_GATEWAY_BASE_URL_RELEASE=..." vermek hicbir sey degistirmiyor,
    // derleme sessizce gelistiricinin yerel degerini kullaniyordu.
    // CI'da bu, yanlis sunucuya baglanan bir yayin paketi demektir.
    if (!project.hasProperty(name)) {
        project.extra.set(name, value.toString())
    }
}

android {
    namespace = "com.eva.app"
    compileSdk = 35

    // Android Automotive OS API'leri (CarPropertyManager) opsiyonel bir
    // platform kutuphanesidir; derleme yolunda olmasi APK'ya BIR SEY
    // EKLEMEZ. Telefonda sinif yuklenmez -- AutomotiveTelemetryProvider
    // FEATURE_AUTOMOTIVE kontrolunden geciremeden dokunulmaz.
    useLibrary("android.car")

    defaultConfig {
        // MAGAZA KIMLIGI: Play Console bu uygulamayi com.evai.app olarak
        // kaydetti. namespace (com.eva.app) Kotlin paket yapisidir ve
        // AYNI KALIR -- ikisi bagimsizdir, namespace'i degistirmek her
        // dosyadaki paket bildirimini yeniden yazmak olurdu.
        applicationId = "com.evai.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Yasal belge adresleri. Play, abonelik satan uygulamalarda
        // bunlarin uygulama ICINDEN acilabilmesini sart kosuyor.
        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            "\"${project.findProperty("PRIVACY_POLICY_URL") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "TERMS_OF_SERVICE_URL",
            "\"${project.findProperty("TERMS_OF_SERVICE_URL") ?: ""}\"",
        )

        // RevenueCat anahtari YAYIN derlemesinde 'goog_' ile BASLAMAK
        // ZORUNDA. SDK baska bir oneki tanimiyor, "API Key is not
        // recognized" dondurup tum abonelik akisini sessizce olduruyor --
        // kullanici hicbir sekilde abone olamaz. Magazada degil, burada
        // yakalanmali.
        val revenueCatKey = run {
            val configured = project.findProperty("REVENUECAT_PUBLIC_API_KEY") as String?
            val isReleaseBuild = gradle.startParameter.taskNames
                .any { it.contains("Release", ignoreCase = true) }

            if (isReleaseBuild && (configured == null || !configured.startsWith("goog_"))) {
                throw GradleException(
                    "REVENUECAT_PUBLIC_API_KEY gecersiz ya da eksik. Yayin derlemesi " +
                        "icin RevenueCat panelindeki Google Play uygulamasina ait PUBLIC " +
                        "anahtar gerekli (goog_ ile baslar). " +
                        "Bulundugu yer: Project settings > API keys.",
                )
            }

            configured ?: "goog_PLACEHOLDER_SET_IN_local_properties"
        }

        // RevenueCat Play Store API key — Play Console'daki uygulamanızla
        // eşleşen RevenueCat projesinden alınır (RevenueCat Dashboard →
        // Project Settings → API Keys → Google Play). Gerçek anahtarı asla
        // repoya commit etmeyin; bu değer local.properties / CI secret'ından
        // BuildConfig'e enjekte ediliyor.
        buildConfigField(
            "String",
            "REVENUECAT_PUBLIC_API_KEY",
            "\"$revenueCatKey\""
        )
        buildConfigField(
            "String",
            "EVA_GATEWAY_BASE_URL_DEBUG",
            "\"${project.findProperty("EVA_GATEWAY_BASE_URL_DEBUG") ?: "http://10.0.2.2:3000"}\""
        )
        buildConfigField(
            "String",
            "EVA_GATEWAY_BASE_URL_RELEASE",
            // Varsayilan YOK. Onceden sahibi olmadigimiz bir alan adi
            // (api.evaapp.com) varsayilandi: yapilandirma unutulursa
            // uygulama sessizce BASKASININ sunucusuna baglanmaya
            // calisirdi. Artik eksikse derleme durur.
            "\"${
                project.findProperty("EVA_GATEWAY_BASE_URL_RELEASE")
                    ?: if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
                        throw GradleException(
                            "EVA_GATEWAY_BASE_URL_RELEASE tanimli degil. " +
                                "Yayin derlemesi icin gercek sunucu adresini " +
                                "local.properties dosyasina ekleyin.",
                        )
                    } else {
                        "UNSET"
                    }
            }\""
        )
        buildConfigField(
            "long",
            "GOOGLE_CLOUD_PROJECT_NUMBER",
            "${project.findProperty("GOOGLE_CLOUD_PROJECT_NUMBER") ?: "0L"}"
        )
        // Google Maps API anahtari. Manifest'e meta-data olarak, koda da
        // BuildConfig uzerinden gecer. Bos birakilirsa harita DEVRE DISI
        // kalir (bos/gri bir harita gostermek yerine liste gosterilir).
        val mapsApiKey = (project.findProperty("MAPS_API_KEY") ?: "") as String
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")

        // Certificate pinning — bkz. AppConfig.gatewayCertificatePins
        // dosya-başı yorumu. Production'da local.properties (yerel) ya da
        // CI secret (release workflow) üzerinden doldurulmalı.
        buildConfigField(
            "String",
            "GATEWAY_CERT_PIN_1",
            "\"${project.findProperty("GATEWAY_CERT_PIN_1") ?: ""}\""
        )
        buildConfigField(
            "String",
            "GATEWAY_CERT_PIN_2",
            "\"${project.findProperty("GATEWAY_CERT_PIN_2") ?: ""}\""
        )
    }

    // Release imzalama. Anahtar deposu ve parolalar SADECE local.properties
    // (gitignore'da) ya da CI secret'larindan okunur -- repoya hicbir zaman
    // girmez. Degerler yoksa signingConfig OLUSTURULMAZ ve release build
    // imzasiz uretilir; boylece "anahtar yok" durumu sessizce yanlis bir
    // imzayla degil, acik bir eksiklikle sonuclanir.
    //
    // Yerel olarak upload anahtari uretmek icin:
    //   keytool -genkey -v -keystore eva-upload.jks -keyalg RSA \n    //           -keysize 2048 -validity 10000 -alias eva-upload
    // Ardindan local.properties'e sunlari ekleyin:
    //   EVA_KEYSTORE_FILE=C:/yol/eva-upload.jks
    //   EVA_KEYSTORE_PASSWORD=...
    //   EVA_KEY_ALIAS=eva-upload
    //   EVA_KEY_PASSWORD=...
    val keystorePath = project.findProperty("EVA_KEYSTORE_FILE") as String?
    val hasReleaseKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

    signingConfigs {
        if (hasReleaseKeystore) {
            // maybeCreate: AGP bazi surumlerde "release" yapilandirmasini
            // kendisi tanimliyor ve create() "zaten var" hatasi veriyor.
            // maybeCreate varsa mevcut olani doner, yoksa olusturur.
            maybeCreate("release").apply {
                storeFile = file(keystorePath!!)
                storePassword = project.findProperty("EVA_KEYSTORE_PASSWORD") as String?
                keyAlias = project.findProperty("EVA_KEY_ALIAS") as String?
                keyPassword = project.findProperty("EVA_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            // YEREL KOD HATA AYIKLAMA SEMBOLLERI
            //
            // Play "hata ayiklama sembolleri yuklemediniz" uyarisi
            // veriyor. Paketteki TEK yerel kutuphane AndroidX'e ait
            // (libandroidx.graphics.path.so) ve Google onu zaten
            // SEMBOLLERI CIKARILMIS halde yayinliyor -- eklenecek
            // sembol yok, uyari bu haliyle kapatilamaz.
            //
            // Pratikte sorun degil: kendi kodumuz Kotlin ve cokme
            // izlerinin cozumlemesi ProGuard haritasiyla yapiliyor,
            // o harita pakete dahil.
            //
            // Ayar burada duruyor cunku ileride KENDI yerel kodumuzu
            // eklersek sembolleri kendiliginden toplanacak.
            ndk {
                debugSymbolLevel = "FULL"
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // NOT: composeOptions/kotlinCompilerExtensionVersion Kotlin 2.0 ile
    // GECERSIZDIR -- derleyici surumu artik Kotlin plugin'i tarafindan
    // belirlenir (yukaridaki org.jetbrains.kotlin.plugin.compose).

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- Kotlin / Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // --- Jetpack Compose ---
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // --- Hilt Dependency Injection ---
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // --- RevenueCat (Abonelik Yönetimi) ---
    // NOT: "purchases-ktx" diye AYRI bir artifact YOKTUR (Maven Central'da
    // com/revenuecat/purchases/ altinda bulunmaz). Kafa karisikligi paket
    // adindan geliyor: coroutine uzantilari `com.revenuecat.purchases.ktx`
    // PAKETINDE yer alir ama v8'den beri ana `purchases` ARTIFACT'inin
    // icindedir. RevenueCatManager'daki awaitCustomerInfo / awaitOfferings /
    // awaitPurchase / awaitRestore cagrilari bu bagimlilikla karsilanir.
    implementation("com.revenuecat.purchases:purchases:8.9.0")

    // --- Harita ---
    // Google Maps bir API anahtari gerektirir (local.properties -> MAPS_API_KEY).
    // Anahtar YOKSA harita hic gosterilmez, uygulama liste gorunumuyle
    // calismaya devam eder -- bkz. AppConfig.isMapEnabled.
    implementation("com.google.maps.android:maps-compose:6.1.2")
    implementation("com.google.android.gms:play-services-maps:19.0.0")

    // --- Konum (FusedLocationProviderClient) ---
    // Uygulama bir sarj istasyonu bulucusu; konum sabit kodlu olamaz.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // --- Google Play Integrity (Cihaz Bütünlük Doğrulama) ---
    implementation("com.google.android.play:integrity:1.4.0")

    // --- Çalışma zamanı izinleri (mikrofon vb.) — VoiceAssistantScreen ---
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // --- Güvenli Yerel Depolama ---
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // --- Ağ Katmanı ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // --- Test ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
