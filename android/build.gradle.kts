// Android Gradle plugin 9.3 is the first line that supports API level 37
// (Android 17). It requires Gradle 9.5.0 or newer and has Kotlin support built
// in, so no separate Kotlin plugin is applied.
plugins {
    id("com.android.application") version "9.3.2" apply false
}
