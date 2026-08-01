//! JNI bridge exposing on-device Dolby Vision profile 7 -> profile 8 conversion to the Android
//! app. Wraps the `dolby_vision` crate (lightweight, standalone - NOT the full `dovi_tool` CLI)
//! for RPU parsing/rewriting, plus our own minimal Annex-B NAL splitter (see [`nal`]) to locate
//! the RPU NAL unit and strip profile-7-only enhancement-layer NAL units.
//!
//! Crash safety: `convertAccessUnit` must never let a panic unwind into JNI/the JVM (undefined
//! behavior). All conversion logic runs inside [`std::panic::catch_unwind`]; any panic or error
//! results in a `null` return, which the Kotlin wrapper treats as "leave the buffer untouched".

mod nal;

use std::panic;

use dolby_vision::rpu::dovi_rpu::DoviRpu;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use jni::JNIEnv;

use nal::NAL_UNIT_TYPE_UNSPEC62;

/// `dolby_vision` crate's "mode 2": convert to profile 8.1 compatible with no-op luma/chroma
/// mapping curves. Handles source profiles 5, 7 and 8 (no-op if already profile 8).
const CONVERSION_MODE_TO_MEL_OR_81: u8 = 2;

/// JNI entry point for `org.jellyfin.androidtv.dovi.DoviNative.convertAccessUnit`.
///
/// `input` is one HEVC access unit in Annex-B format (start-code delimited NAL units), as found
/// in an ExoPlayer `DecoderInputBuffer` right before it would be queued to `MediaCodec`.
///
/// Returns a new Annex-B buffer with the RPU rewritten to profile 8 and enhancement-layer NAL
/// units removed, or `null` if the access unit contained no convertible RPU or conversion failed
/// (caller must fall back to queuing the original, unmodified buffer).
#[no_mangle]
pub extern "system" fn Java_org_jellyfin_androidtv_dovi_DoviNative_convertAccessUnit<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JByteArray<'local>,
) -> jbyteArray {
    let outcome = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(&input).ok()?;
        convert_access_unit(&bytes)
    }));

    let converted = match outcome {
        Ok(Some(bytes)) => bytes,
        _ => return std::ptr::null_mut(),
    };

    match env.byte_array_from_slice(&converted) {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Rewrites the RPU NAL unit (if any) to profile 8 and drops enhancement-layer NAL units.
/// Returns `None` if nothing needed changing (e.g. no RPU found), so the caller can skip the
/// buffer replacement entirely.
fn convert_access_unit(data: &[u8]) -> Option<Vec<u8>> {
    let nals = nal::split_annex_b(data);
    let mut out = Vec::with_capacity(data.len());
    let mut changed = false;

    for unit in nals {
        // Profile 7 enhancement-layer NAL units always use a non-zero nuh_layer_id; the base
        // layer (profile 8 compatible after RPU rewrite) always uses layer id 0.
        if unit.nuh_layer_id != 0 {
            changed = true;
            continue;
        }

        let is_rpu = nal::nal_unit_type(unit.payload) == Some(NAL_UNIT_TYPE_UNSPEC62);
        if is_rpu {
            if let Some(rewritten) = rewrite_rpu(unit.payload) {
                out.extend_from_slice(unit.start_code);
                out.extend_from_slice(&rewritten);
                changed = true;
                continue;
            }
            // Parsing/conversion failed - keep the original RPU bytes rather than dropping them,
            // so the rest of the access unit stays structurally intact.
        }

        out.extend_from_slice(unit.start_code);
        out.extend_from_slice(unit.payload);
    }

    changed.then_some(out)
}

/// Parses, converts (profile 7 -> profile 8 compatible) and re-serializes a single RPU NAL unit
/// using the `dolby_vision` crate. This is the entire RPU rewrite - the crate owns all RPU
/// parsing/bitstream logic, we only provide the NAL unit bytes.
fn rewrite_rpu(nalu: &[u8]) -> Option<Vec<u8>> {
    let mut rpu = DoviRpu::parse_unspec62_nalu(nalu).ok()?;
    rpu.convert_with_mode(CONVERSION_MODE_TO_MEL_OR_81).ok()?;
    rpu.write_hevc_unspec62_nalu().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_rpu_returns_none() {
        // Plain HEVC slice NAL, no RPU, no EL - nothing to convert.
        let data = [0x00, 0x00, 0x01, 0x26, 0x01, 0xAA, 0xBB];
        assert!(convert_access_unit(&data).is_none());
    }

    #[test]
    fn strips_enhancement_layer_nal_units() {
        // Base layer slice (layer id 0) + a fake EL NAL unit (layer id != 0) that should be
        // stripped even without a real RPU present.
        let base = [0x00, 0x00, 0x01, 0x26, 0x01, 0xAA];
        let el = [0x00, 0x00, 0x01, 0x27, 0x09, 0xEE]; // nuh_layer_id = 33
        let mut data = Vec::new();
        data.extend_from_slice(&base);
        data.extend_from_slice(&el);

        let result = convert_access_unit(&data).expect("EL removal should report a change");
        assert_eq!(result, base);
    }
}
