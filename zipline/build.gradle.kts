import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  id("com.android.library")
  id("com.vanniktech.maven.publish")
  id("org.jetbrains.dokka")
}

abstract class VersionWriterTask : DefaultTask() {
  @InputFile
  val versionFile = project.file("native/quickjs/VERSION")

  @OutputDirectory
  val outputDir = project.layout.buildDirectory.file("generated/version/")

  @TaskAction
  fun stuff() {
    val version = versionFile.readText().trim()

    val outputFile = outputDir.get().asFile.resolve("app/cash/zipline/version.kt")
    outputFile.parentFile.mkdirs()
    outputFile.writeText("""
      |package app.cash.zipline
      |
      |internal const val quickJsVersion = "$version"
      |""".trimMargin())
  }
}
val versionWriterTaskProvider = tasks.register("writeVersion", VersionWriterTask::class)

val copyTestingJs = tasks.register<Copy>("copyTestingJs") {
  destinationDir = buildDir.resolve("generated/testingJs")
  if (true) {
    // Production, which is minified JavaScript.
    from(projectDir.resolve("testing/build/distributions/testing.js"))
    dependsOn(":zipline:testing:jsBrowserProductionWebpack")
  } else {
    // Development, which is not minified and has useful stack traces.
    from(projectDir.resolve("testing/build/developmentExecutable/testing.js"))
    dependsOn(":zipline:testing:jsBrowserDevelopmentWebpack")
  }
}

kotlin {
  android()
  jvm()
  js {
    nodejs()
  }

  sourceSets {
    val commonMain by getting {
      kotlin.srcDir(versionWriterTaskProvider)
      dependencies {
        api(Dependencies.kotlinxCoroutines)
        api(Dependencies.kotlinxSerializationJson)
        api(Dependencies.okioMultiplatform)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }

    val engineMain by creating
    val engineTest by creating

    val jniMain by creating {
      dependsOn(engineMain)
      dependencies {
        api(Dependencies.androidxAnnotation)
        api(Dependencies.kotlinReflect)
      }
    }
    val androidMain by getting {
      dependsOn(jniMain)
    }
    val jvmMain by getting {
      dependsOn(jniMain)
    }
    val jvmTest by getting {
      dependsOn(engineTest)
      kotlin.srcDir("src/jniTest/kotlin/")
      resources.srcDir(copyTestingJs)
      dependencies {
        implementation(Dependencies.truth)
        implementation(Dependencies.kotlinxCoroutinesTest)
        implementation(project(":zipline:testing"))
      }
    }
  }
}

android {
  compileSdkVersion(Ext.compileSdkVersion)

  defaultConfig {
    minSdkVersion(18)
    multiDexEnabled = true

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += Ext.ndkAbiFilters
    }

    externalNativeBuild {
      cmake {
        arguments("-DANDROID_TOOLCHAIN=clang", "-DANDROID_STL=c++_static")
        cFlags("-fstrict-aliasing", "-DCONFIG_VERSION=\\\"${quickJsVersion()}\\\"")
        cppFlags("-fstrict-aliasing", "-DCONFIG_VERSION=\\\"${quickJsVersion()}\\\"")
      }
    }

    packagingOptions {
      // We get multiple copies of some license files via JNA, which is a transitive dependency of
      // kotlinx-coroutines-test. Don't fail the build on these duplicates.
      exclude("META-INF/AL2.0")
      exclude("META-INF/LGPL2.1")
    }
  }

  sourceSets {
    val main by getting {
      manifest.srcFile("src/androidMain/AndroidManifest.xml")
    }
    val androidTest by getting {
      java.srcDirs("src/engineTest/kotlin/", "src/jniTest/kotlin/")
      resources.srcDir("src/androidInstrumentationTest/resources/")
      resources.srcDir(copyTestingJs)
    }
  }

  // The above `resources.srcDir(copyTestingJs)` code is supposed to automatically add a task
  // dependency, but it doesn't. So we add it ourselves using this nonsense.
  afterEvaluate {
    libraryVariants.onEach { libraryVariant ->
      libraryVariant.testVariant?.processJavaResourcesProvider?.configure {
        dependsOn(copyTestingJs)
      }
    }
  }

  buildTypes {
    val release by getting {
      externalNativeBuild {
        cmake {
          arguments("-DCMAKE_BUILD_TYPE=MinSizeRel")
          cFlags("-g0", "-Os", "-fomit-frame-pointer", "-DNDEBUG", "-fvisibility=hidden")
          cppFlags("-g0", "-Os", "-fomit-frame-pointer", "-DNDEBUG", "-fvisibility=hidden")
        }
      }
    }
    val debug by getting {
      externalNativeBuild {
        cmake {
          cFlags("-g", "-DDEBUG", "-DDUMP_LEAKS")
          cppFlags("-g", "-DDEBUG", "-DDUMP_LEAKS")
        }
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/androidMain/CMakeLists.txt")
    }
  }
}

dependencies {
  androidTestImplementation(Dependencies.junit)
  androidTestImplementation(Dependencies.androidxTestRunner)
  androidTestImplementation(Dependencies.truth)
  androidTestImplementation(Dependencies.kotlinxCoroutinesTest)
  androidTestImplementation(project(":zipline:testing"))

  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, project(":zipline-kotlin-plugin"))
}

fun quickJsVersion(): String {
  return File(projectDir, "native/quickjs/VERSION").readText().trim()
}