//! Minimal 16-bit PCM mono WAV reader/writer for host validation tooling
//! (A-2 replay harness and its fixture test).
//!
//! Pure `std`, no dependencies. Reads the chunks a real-world recorder
//! emits (extra `fmt` bytes, `fact`/`LIST` chunks in any order); writes the
//! canonical 44-byte-header layout. Whole-file in memory — fine for a host
//! tool (an 8 h 16 kHz night is ~115 MB).

/// Decoded WAV: sample rate in Hz and mono i16 samples.
pub struct WavMono16 {
    pub sample_rate: u32,
    pub samples: Vec<i16>,
}

/// Why a byte buffer is not a usable WAV for us.
#[derive(Debug, PartialEq, Eq)]
pub enum WavError {
    TooShort,
    NotRiff,
    NotWave,
    NoFmt,
    NoData,
    UnsupportedFormat(String),
}

impl std::fmt::Display for WavError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            WavError::TooShort => write!(f, "file too short to be a WAV"),
            WavError::NotRiff => write!(f, "missing RIFF magic"),
            WavError::NotWave => write!(f, "missing WAVE magic"),
            WavError::NoFmt => write!(f, "no fmt chunk"),
            WavError::NoData => write!(f, "no data chunk"),
            WavError::UnsupportedFormat(s) => write!(f, "unsupported WAV format: {s}"),
        }
    }
}

fn u16le(b: &[u8]) -> u16 {
    u16::from_le_bytes([b[0], b[1]])
}

fn u32le(b: &[u8]) -> u32 {
    u32::from_le_bytes([b[0], b[1], b[2], b[3]])
}

/// Decode 16-bit PCM mono samples. Errors unless the stream is PCM (`1`),
/// mono, and 16-bit; other sample rates decode fine (the replay bin, not
/// this module, enforces 16 kHz).
pub fn decode_mono16(bytes: &[u8]) -> Result<WavMono16, WavError> {
    if bytes.len() < 12 {
        return Err(WavError::TooShort);
    }
    if &bytes[0..4] != b"RIFF" {
        return Err(WavError::NotRiff);
    }
    if &bytes[8..12] != b"WAVE" {
        return Err(WavError::NotWave);
    }
    let mut fmt: Option<(u32, u16)> = None; // (sample_rate, bits)
    let mut data: Option<&[u8]> = None;
    let mut pos = 12;
    while pos + 8 <= bytes.len() {
        let id = &bytes[pos..pos + 4];
        let len = u32le(&bytes[pos + 4..pos + 8]) as usize;
        let body = pos + 8;
        // Chunks are word-aligned; tolerate truncation of the last chunk.
        let end = (body + len).min(bytes.len());
        if id == b"fmt " {
            if end - body < 16 {
                return Err(WavError::NoFmt);
            }
            let audio_format = u16le(&bytes[body..body + 2]);
            let channels = u16le(&bytes[body + 2..body + 4]);
            let sample_rate = u32le(&bytes[body + 4..body + 8]);
            let bits = u16le(&bytes[body + 14..body + 16]);
            if audio_format != 1 || channels != 1 || bits != 16 {
                return Err(WavError::UnsupportedFormat(format!(
                    "need PCM mono 16-bit, got format={audio_format} channels={channels} bits={bits}"
                )));
            }
            fmt = Some((sample_rate, bits));
        } else if id == b"data" {
            data = Some(&bytes[body..end]);
        }
        pos = end + (end % 2);
    }
    let (sample_rate, _) = fmt.ok_or(WavError::NoFmt)?;
    let raw = data.ok_or(WavError::NoData)?;
    // Drop a trailing odd byte; each sample is 2 bytes LE.
    let n = raw.len() / 2;
    let mut samples = Vec::with_capacity(n);
    for i in 0..n {
        samples.push(i16::from_le_bytes([raw[2 * i], raw[2 * i + 1]]));
    }
    Ok(WavMono16 { sample_rate, samples })
}

/// Encode mono i16 samples as a canonical 44-byte-header WAV.
pub fn encode_mono16(sample_rate: u32, samples: &[i16]) -> Vec<u8> {
    let data_bytes = samples.len() * 2;
    let mut out = Vec::with_capacity(44 + data_bytes);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data_bytes as u32).to_le_bytes());
    out.extend_from_slice(b"WAVE");
    out.extend_from_slice(b"fmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes()); // PCM
    out.extend_from_slice(&1u16.to_le_bytes()); // mono
    out.extend_from_slice(&sample_rate.to_le_bytes());
    out.extend_from_slice(&(sample_rate * 2).to_le_bytes()); // byte rate
    out.extend_from_slice(&2u16.to_le_bytes()); // block align
    out.extend_from_slice(&16u16.to_le_bytes()); // bits
    out.extend_from_slice(b"data");
    out.extend_from_slice(&(data_bytes as u32).to_le_bytes());
    for s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let samples: Vec<i16> = (0..1000).map(|i| ((i * 37) % 2000 - 1000) as i16).collect();
        let bytes = encode_mono16(16_000, &samples);
        assert_eq!(bytes.len(), 44 + 2000);
        let wav = decode_mono16(&bytes).unwrap();
        assert_eq!(wav.sample_rate, 16_000);
        assert_eq!(wav.samples, samples);
    }

    #[test]
    fn rejects_stereo() {
        let mut bytes = encode_mono16(16_000, &[0i16; 10]);
        // Patch channels field to 2.
        bytes[22] = 2;
        assert!(matches!(
            decode_mono16(&bytes),
            Err(WavError::UnsupportedFormat(_))
        ));
    }

    #[test]
    fn skips_unknown_chunks() {
        let bytes = encode_mono16(16_000, &[1i16, -1]);
        // Insert a JUNK chunk between header and fmt.
        let mut with_junk = bytes[..12].to_vec();
        with_junk.extend_from_slice(b"JUNK");
        with_junk.extend_from_slice(&4u32.to_le_bytes());
        with_junk.extend_from_slice(&[0u8; 4]);
        with_junk.extend_from_slice(&bytes[12..]);
        // Fix RIFF size.
        let riff_size = (with_junk.len() - 8) as u32;
        with_junk[4..8].copy_from_slice(&riff_size.to_le_bytes());
        let wav = decode_mono16(&with_junk).unwrap();
        assert_eq!(wav.samples, vec![1i16, -1]);
    }
}
