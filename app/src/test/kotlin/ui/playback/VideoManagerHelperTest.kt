package org.jellyfin.androidtv.ui.playback

import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe

class VideoManagerHelperTest : FunSpec({
	val transferSdr = 3
	val transferSt2084 = 6
	val transferHlg = 7

	fun formatWithTransfer(transfer: Int) = Format.Builder()
		.setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build())
		.build()

	test("PQ, HLG, and Dolby Vision formats are HDR") {
		formatWithTransfer(transferSt2084).isHdrVideo() shouldBe true
		formatWithTransfer(transferHlg).isHdrVideo() shouldBe true
		Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build().isHdrVideo() shouldBe true
	}

	test("SDR and missing color metadata are not HDR") {
		formatWithTransfer(transferSdr).isHdrVideo() shouldBe false
		Format.Builder().build().isHdrVideo() shouldBe false
	}

	test("HDR GUI alpha clamps percentages to 10 through 100") {
		calculateHdrGuiAlpha(true, 10) shouldBeExactly 0.1f
		calculateHdrGuiAlpha(true, 55) shouldBeExactly 0.55f
		calculateHdrGuiAlpha(true, 100) shouldBeExactly 1f
		calculateHdrGuiAlpha(true, 0) shouldBeExactly 0.1f
		calculateHdrGuiAlpha(true, 150) shouldBeExactly 1f
	}

	test("SDR GUI alpha is always fully opaque") {
		calculateHdrGuiAlpha(false, 10) shouldBeExactly 1f
		calculateHdrGuiAlpha(false, 100) shouldBeExactly 1f
}
})
