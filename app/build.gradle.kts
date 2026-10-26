plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

val grpc = "1.68.1"
val protobuf = "3.25.5"
val appVersion = rootProject.file("VERSION").readText().trim()

android {
    namespace = "app.fafnirx.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.fafnirx.android"
        minSdk = 26
        targetSdk = 35
        versionName = appVersion
        versionCode = appVersion.split(".").let { it[0].toInt() * 10000 + it[1].toInt() * 100 + it[2].toInt() }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint { checkReleaseBuilds = false }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/DEPENDENCIES", "DebugProbesKt.bin", "kotlin/**", "**.kotlin_*")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobuf" }
    plugins { create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpc" } }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
            task.plugins { create("grpc") { option("lite") } }
        }
    }
}

dependencies {
    implementation("io.grpc:grpc-okhttp:$grpc")
    implementation("io.grpc:grpc-stub:$grpc")
    implementation("io.grpc:grpc-protobuf-lite:$grpc")
    implementation("com.google.protobuf:protobuf-javalite:$protobuf")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.grpc:grpc-inprocess:$grpc")
    testImplementation("io.grpc:grpc-netty-shaded:$grpc")
}
