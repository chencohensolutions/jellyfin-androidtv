package org.jellyfin.androidtv.dovi

/**
 * Raw JNI bridge to the `dovi_jni` Rust native library (see `playback/dovi/rust`). Do not call
 * directly - use [DoviRpuConverter] instead, which adds library-load and crash-safety handling.
 */
internal object DoviNative {
	/**
	 * Converts a single HEVC access unit (Annex-B framed NAL units) from Dolby Vision profile 7
	 * to profile 8: rewrites the RPU NAL unit and strips profile-7-only enhancement-layer NAL
	 * units.
	 *
	 * @return the rewritten access unit, or `null` if there was nothing to convert (no RPU found)
	 *   or the native conversion failed - callers must then use the original buffer unmodified.
	 */
	external fun convertAccessUnit(input: ByteArray): ByteArray?
}
