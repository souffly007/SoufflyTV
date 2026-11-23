import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Version de SoufflyTV, la même pour Android et pour les installeurs PC (à changer ici seulement)
val appVersion = "0.9.2"
val appVersionCode = 29

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui)
            // Son AC3 / E-AC3 / DTS / TrueHD des films et séries sur les appareils sans décodeur Dolby
            implementation(libs.media3.ffmpeg.decoder)
            // DNS sécurisé : la vidéo passe par OkHttp quand il est activé
            implementation(libs.media3.datasource.okhttp)
            implementation(libs.okhttp)
            implementation(libs.okhttp.dnsoverhttps)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
            implementation(libs.okhttp.dnsoverhttps)
            implementation(libs.vlcj)
        }
    }
}

/*
 * Signature des versions « release » : les secrets restent hors du dépôt, dans keystore.properties
 * (à la racine du projet, ignoré par Git) :
 *
 *   storeFile=/var/home/MOI/AndroidStudioProjects/keystores/souffly.jks
 *   storePassword=…
 *   keyAlias=souffly
 *   keyPassword=…
 *
 * Sans ce fichier, les APK release sont produits non signés.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "fr.bonobo.soufflytv"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "fr.bonobo.soufflytv"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
    }
    buildFeatures {
        buildConfig = true
    }

    // Deux APK depuis le même code : télévision (télécommande) et smartphone/tablette (tactile).
    // Même identifiant : installer l'un par-dessus l'autre garde sources, favoris et réglages.
    flavorDimensions += "appareil"
    productFlavors {
        create("mobile") {
            dimension = "appareil"
            buildConfigField("boolean", "IS_TV", "false")
            manifestPlaceholders["leanbackRequired"] = "false"
            manifestPlaceholders["touchscreenRequired"] = "true"
        }
        create("tv") {
            dimension = "appareil"
            versionNameSuffix = "-tv"
            buildConfigField("boolean", "IS_TV", "true")
            manifestPlaceholders["leanbackRequired"] = "true"
            manifestPlaceholders["touchscreenRequired"] = "false"
        }
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
    lint {
        // un avertissement de lint ne doit pas bloquer la sortie d'une version
        abortOnError = false
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // F-Droid : pas de bloc de métadonnées de dépendances signé par Google
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Noms de fichiers lisibles : SoufflyTV-tv-0.5.2.apk, SoufflyTV-mobile-0.5.2.apk
    @Suppress("DEPRECATION")
    applicationVariants.all {
        val variant = this
        outputs.all {
            val suffix = if (variant.buildType.name == "release") "" else "-${variant.buildType.name}"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "SoufflyTV-${variant.flavorName}-${defaultConfig.versionName}$suffix.apk"
        }
    }
}

/** Les deux APK signés d'un coup : ./gradlew :composeApp:releaseApks */
tasks.register("releaseApks") {
    group = "souffly"
    description = "Construit les APK release TV et smartphone (signés si keystore.properties existe)."
    dependsOn("assembleTvRelease", "assembleMobileRelease")
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk").get().asFile
        println("\nAPK prêts :")
        dir.walkTopDown().filter { it.isFile && it.extension == "apk" && "release" in it.path }
            .forEach { println("  " + it.path) }
        if (!hasReleaseKey) println("  (non signés : keystore.properties absent)")
    }
}

compose.desktop {
    application {
        mainClass = "fr.bonobo.soufflytv.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Exe)
            // Java complet embarqué (TLS, JNA…) : indispensable pour le Flatpak et les paquets
            includeAllModules = true
            packageName = "SoufflyTV"
            // jpackage refuse un numéro majeur à 0 sur certaines plateformes
            // Windows ne remplace une installation que si le numéro augmente
            packageVersion = appVersion
            description = "Lecteur IPTV libre (M3U, Xtream Codes, portail Stalker)"
            vendor = "Bonobo"
            licenseFile.set(rootProject.file("LICENSE"))
            // Fichiers livrés avec l'app : resources/windows/vlc = libVLC embarquée (windows/prepare-vlc.ps1)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            linux {
                packageName = "soufflytv"
                menuGroup = "AudioVideo"
                iconFile.set(project.file("icons/soufflytv.png"))
            }
            windows {
                menu = true
                menuGroup = "SoufflyTV"
                shortcut = true
                dirChooser = true
                // installation pour l'utilisateur : pas besoin des droits administrateur
                perUserInstall = true
                iconFile.set(project.file("icons/soufflytv.ico"))
                upgradeUuid = "6f1c3a52-8d2e-4b7a-9f61-0c5e2d8b4a17"
            }
        }
    }
}
