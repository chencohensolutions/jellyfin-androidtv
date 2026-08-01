package org.jellyfin.androidtv.dovi

import timber.log.Timber

/**
 * Converts Dolby Vision profile 7 (dual-layer, FEL/MEL) HEVC access units to profile 8
 * (single-layer) on-device, by rewriting the RPU and stripping enhancement-layer NAL units.
 * Backed by the `dovi_jni` Rust native library (see `playback/dovi/rust`).
 *
 * Loading the native library and every native call are wrapped so a missing library or a
 * conversion failure can never crash playback - callers should treat a `null` result as "queue
 * the original buffer instead, unmodified".
 */
object DoviRpuConverter {
	private val nativeLibraryAvailable: Boolean by lazy {
		try {
			System.loadLibrary("dovi_jni")
			true
		} catch (error: UnsatisfiedLinkError) {
			Timber.w(error, "libdovi_jni not available, Dolby Vision profile 7 to 8 conversion is disabled")
			false
		}
	}

	/**
	 * Converts one HEVC access unit (Annex-B framed) from Dolby Vision profile 7 to profile 8.
	 *
	 * @return the rewritten access unit, or `null` if nothing needed converting, the native
	 *   library isn't available, or the native call failed - in which case [input] should be
	 *   queued unmodified.
	 */
	fun convertAccessUnit(input: ByteArray): ByteArray? {
		if (!nativeLibraryAvailable) return null

		return try {
			DoviNative.convertAccessUnit(input)
		} catch (error: Throwable) {
			Timber.e(error, "Dolby Vision profile 7 to 8 conversion failed, using original buffer")
			null
		}
	}
}
