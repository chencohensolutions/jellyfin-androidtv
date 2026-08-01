//! Minimal Annex-B (start-code delimited) HEVC NAL unit framing.
//!
//! This is generic HEVC bitstream parsing, not `dovi_tool`-specific logic: we only need it to
//! locate NAL unit boundaries and read the 2-byte NAL unit header so that RPU (type 62) and
//! enhancement-layer (`nuh_layer_id != 0`) NAL units can be found within an access unit.

/// A single NAL unit as found in an Annex-B buffer.
pub struct Nal<'a> {
    /// The start code bytes that preceded this NAL unit (3 bytes `00 00 01` or 4 bytes
    /// `00 00 00 01`).
    pub start_code: &'a [u8],
    /// The NAL unit bytes themselves (2-byte header + RBSP), NOT including the start code.
    pub payload: &'a [u8],
    /// HEVC `nuh_layer_id` (6 bits), extracted from the NAL unit header. Dolby Vision profile 7
    /// enhancement-layer NAL units use a non-zero layer id.
    pub nuh_layer_id: u8,
}

/// HEVC `nal_unit_type` for the Dolby Vision RPU NAL unit (unregistered NAL unit type 62).
pub const NAL_UNIT_TYPE_UNSPEC62: u8 = 62;

/// Splits an Annex-B byte stream into its constituent NAL units, honoring both 3-byte and 4-byte
/// start codes. NAL units with fewer than 2 payload bytes (i.e. no valid header) are skipped.
pub fn split_annex_b(data: &[u8]) -> Vec<Nal<'_>> {
    let starts = find_start_codes(data);
    let mut nals = Vec::with_capacity(starts.len());

    for (i, &(sc_start, payload_start)) in starts.iter().enumerate() {
        let payload_end = starts
            .get(i + 1)
            .map(|&(next_sc, _)| next_sc)
            .unwrap_or(data.len());

        if payload_start + 2 > payload_end {
            continue;
        }

        let payload = &data[payload_start..payload_end];
        let start_code = &data[sc_start..payload_start];

        nals.push(Nal {
            start_code,
            payload,
            nuh_layer_id: nuh_layer_id(payload),
        });
    }

    nals
}

/// Reads `nal_unit_type` (6 bits) from a NAL unit's 2-byte header.
pub fn nal_unit_type(payload: &[u8]) -> Option<u8> {
    payload.first().map(|&b| (b >> 1) & 0x3f)
}

/// Reads `nuh_layer_id` (6 bits, split across both header bytes) from a NAL unit's 2-byte header.
fn nuh_layer_id(payload: &[u8]) -> u8 {
    ((payload[0] & 0x01) << 5) | (payload[1] >> 3)
}

/// Finds every start code in `data`, returning `(start_code_offset, payload_offset)` pairs in
/// order of appearance.
fn find_start_codes(data: &[u8]) -> Vec<(usize, usize)> {
    let mut result = Vec::new();
    let mut i = 0;

    while i + 2 < data.len() {
        if data[i] == 0 && data[i + 1] == 0 {
            if data[i + 2] == 1 {
                result.push((i, i + 3));
                i += 3;
                continue;
            } else if i + 3 < data.len() && data[i + 2] == 0 && data[i + 3] == 1 {
                result.push((i, i + 4));
                i += 4;
                continue;
            }
        }
        i += 1;
    }

    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_three_byte_start_codes() {
        // 2 NAL units, 3-byte start codes, layer id 0.
        let data = [0x00, 0x00, 0x01, 0x26, 0x01, 0xAA, 0x00, 0x00, 0x01, 0x02, 0x03, 0xBB];
        let nals = split_annex_b(&data);

        assert_eq!(nals.len(), 2);
        assert_eq!(nals[0].start_code, &[0x00, 0x00, 0x01]);
        assert_eq!(nals[0].payload, &[0x26, 0x01, 0xAA]);
        assert_eq!(nal_unit_type(nals[0].payload), Some(19));
        assert_eq!(nals[0].nuh_layer_id, 0);
        assert_eq!(nals[1].payload, &[0x02, 0x03, 0xBB]);
    }

    #[test]
    fn splits_four_byte_start_codes() {
        // byte0 = 0b0_111110_0 -> nal_unit_type = 62 (0x3E), nuh_layer_id bit0 = 0.
        let data = [0x00, 0x00, 0x00, 0x01, 0x7C, 0x01, 0xCC, 0xDD];
        let nals = split_annex_b(&data);

        assert_eq!(nals.len(), 1);
        assert_eq!(nals[0].start_code, &[0x00, 0x00, 0x00, 0x01]);
        assert_eq!(nal_unit_type(nals[0].payload), Some(NAL_UNIT_TYPE_UNSPEC62));
    }

    #[test]
    fn extracts_nonzero_layer_id() {
        // nal_unit_type=20 (0x28>>1=20), nuh_layer_id bits: byte0 bit0=1, byte1 top5=00001 -> layer id = (1<<5)|1 = 33
        let data = [0x00, 0x00, 0x01, 0x29, 0x09, 0xEE];
        let nals = split_annex_b(&data);

        assert_eq!(nals[0].nuh_layer_id, 33);
    }
}
