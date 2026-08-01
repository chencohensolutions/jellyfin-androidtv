import java.util.Properties

plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "org.jellyfin.androidtv.dovi"
	compileSdk = libs.versions.android.compileSdk.get().toInt()
	ndkVersion = libs.versions.android.ndk.get()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

// The dovi_jni Rust crate (playback/dovi/rust) provides the actual Dolby Vision profile 7 -> 8
// RPU conversion, built for Android via cargo-ndk into src/main/jniLibs/<abi>/libdovi_jni.so.
// See rust/Cargo.toml for the crate dependencies (dolby_vision, jni).
val cargoNdkAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

fun commandExists(vararg command: String): Boolean = try {
	ProcessBuilder(*command).redirectErrorStream(true).start().waitFor() == 0
} catch (error: Exception) {
	false
}

val cargoNdkAvailable = commandExists("cargo", "ndk", "--version")

fun androidSdkDir(): String {
	System.getenv("ANDROID_HOME")?.let { return it }
	System.getenv("ANDROID_SDK_ROOT")?.let { return it }

	val localProperties = rootProject.file("local.properties")
	if (localProperties.exists()) {
		val properties = Properties()
		localProperties.inputStream().use { properties.load(it) }
		properties.getProperty("sdk.dir")?.let { return it }
	}

	error("Could not determine the Android SDK location (ANDROID_HOME/ANDROID_SDK_ROOT/local.properties sdk.dir)")
}

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
	group = "rust"
	description = "Cross-compiles the dovi_jni Rust crate for Android via cargo-ndk."

	onlyIf {
		if (!cargoNdkAvailable) {
			logger.warn(
				"[playback:dovi] Skipping native library build: 'cargo ndk' was not found on PATH. " +
					"Install Rust (see https://rustup.rs) and run `cargo install cargo-ndk` to build " +
					"libdovi_jni.so. The app will still build without it, but the experimental " +
					"\"Convert Dolby Vision profile 7 to profile 8 on device\" setting will be a no-op."
			)
		}
		cargoNdkAvailable
	}

	workingDir = file("rust")
	inputs.dir("rust/src")
	inputs.file("rust/Cargo.toml")
	outputs.dir("src/main/jniLibs")

	environment("ANDROID_NDK_HOME", file(androidSdkDir()).resolve("ndk/${libs.versions.android.ndk.get()}").absolutePath)

	val abiArgs = cargoNdkAbis.flatMap { listOf("-t", it) }
	commandLine(
		listOf("cargo", "ndk") + abiArgs + listOf(
			"-o", projectDir.resolve("src/main/jniLibs").absolutePath,
			"build", "--release"
		)
	)
}

tasks.named("preBuild") {
	dependsOn(cargoNdkBuild)
}

dependencies {
	// Kotlin
	implementation(libs.kotlinx.coroutines)

	// Logging
	implementation(libs.timber)

	// Testing
	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
}
