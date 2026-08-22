//plugins {
//    alias(libs.plugins.android.application) apply false
//    alias(libs.plugins.kotlin.android) apply false
//    alias(libs.plugins.hilt.android) apply false
//}
//
//buildscript {
//    repositories {
//        google()
//        mavenCentral()
//    }
//}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt.android) apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
}