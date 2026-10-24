// Корневой build-файл BridgePipe.
// Версии плагинов подобраны под JDK 17 / Gradle 8.9 (устанавливается в CI).

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
