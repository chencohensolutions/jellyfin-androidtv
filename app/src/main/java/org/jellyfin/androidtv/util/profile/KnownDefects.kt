package org.jellyfin.androidtv.util.profile

import android.os.Build

/**
 * List of devie models with known HEVC DoVi/HDR10+ playback issues.
 */
private val modelsWithDoViHdr10PlusBug = listOf(
	"AFTKRT", // Amazon Fire TV 4K Max (2nd Gen)
	"AFTKA", // Amazon Fire TV 4K Max (1st Gen)
	"AFTKM", // Amazon Fire TV 4K (2nd Gen)
	"AFTMM", // Amazon Fire TV 4K (1st Gen)
)

// The codec capability API reports DV profile 7 (dual-layer/EL) decoding as supported, but the
// c2.mtk.dvav.ser.decoder component actually fails (MediaCodec error 0x80000000) on real profile 7
// streams - confirmed via logcat on this exact model.
private val modelsWithDoViElDecodeBug = listOf(
	"Google TV Streamer",
)

object KnownDefects {
	val hevcDoviHdr10PlusBug = Build.MODEL in modelsWithDoViHdr10PlusBug
	val hevcDoviElDecodeBug = Build.MODEL in modelsWithDoViElDecodeBug
}
