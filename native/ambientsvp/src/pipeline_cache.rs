//! A bounded cache envelope; Android accepts disk data only from the
//! application's own private cache directory. The checksum detects accidental
//! corruption, not an attacker with the application's UID.
const MAGIC: &[u8; 8] = b"ASVPC001";
pub const MAX_BYTES: usize = 8 * 1024 * 1024;
const MAX_KEY: usize = 4096;
const MAX_FILE: usize = MAX_BYTES + MAX_KEY + 24;

fn checksum(data: &[u8]) -> u64 {
    // Stable FNV-1a checksum across Rust versions; not authentication.
    data.iter().fold(0xcbf29ce484222325u64, |h, b|
        (h ^ u64::from(*b)).wrapping_mul(0x100000001b3))
}

fn encode(key: &str, data: &[u8]) -> Option<Vec<u8>> {
    if key.is_empty() || key.len() > MAX_KEY || data.is_empty() || data.len() > MAX_BYTES {
        return None;
    }
    let mut out = Vec::with_capacity(24 + key.len() + data.len());
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&(key.len() as u32).to_le_bytes());
    out.extend_from_slice(&(data.len() as u32).to_le_bytes());
    out.extend_from_slice(key.as_bytes());
    out.extend_from_slice(data);
    let sum = checksum(&out);
    out.extend_from_slice(&sum.to_le_bytes());
    Some(out)
}

fn decode<'a>(key: &str, bytes: &'a [u8]) -> Option<&'a [u8]> {
    if bytes.len() < 24 || bytes.len() > MAX_FILE || bytes.get(..8)? != MAGIC { return None; }
    let k = u32::from_le_bytes(bytes.get(8..12)?.try_into().ok()?) as usize;
    let n = u32::from_le_bytes(bytes.get(12..16)?.try_into().ok()?) as usize;
    if k == 0 || k > MAX_KEY || n == 0 || n > MAX_BYTES || bytes.len() != 24 + k + n {
        return None;
    }
    let end = 16 + k + n;
    if bytes.get(16..16+k)? != key.as_bytes()
        || checksum(bytes.get(..end)?) != u64::from_le_bytes(bytes.get(end..)?.try_into().ok()?) {
        return None;
    }
    bytes.get(16+k..end)
}

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use std::{fs::{self, File, OpenOptions}, io::{Read, Write},
        os::unix::{fs::{MetadataExt, OpenOptionsExt}, ffi::OsStrExt},
        path::{Path, PathBuf}, sync::{Mutex, atomic::{AtomicU64, Ordering}}};
    static DIRECTORY: Mutex<Option<PathBuf>> = Mutex::new(None);
    static SERIAL: AtomicU64 = AtomicU64::new(0);

    fn private(metadata: &fs::Metadata) -> bool {
        // SAFETY: geteuid has no pointer arguments and cannot unwind.
        metadata.uid() == unsafe { libc::geteuid() } && metadata.mode() & 0o077 == 0
    }

    fn private_directory(path: &Path) -> bool {
        fs::symlink_metadata(path).is_ok_and(|m| m.is_dir() && private(&m))
    }

    pub fn configure(path: &[u8]) -> bool {
        if path.is_empty() || path.len() > MAX_KEY || path.contains(&0) { return false; }
        let path = PathBuf::from(std::ffi::OsStr::from_bytes(path));
        if !path.is_absolute() || !private_directory(&path) { return false; }
        let Ok(path) = path.canonicalize() else { return false; };
        if !private_directory(&path) { return false; }
        let Ok(mut directory) = DIRECTORY.lock() else { return false; };
        // Do not allow runtime redirection after the first configuration.
        if let Some(previous) = directory.as_ref() { return previous == &path; }
        *directory = Some(path);
        true
    }

    fn directory() -> Option<PathBuf> {
        let path = DIRECTORY.lock().ok()?.clone()?;
        private_directory(&path).then_some(path)
    }

    pub fn load(key: &str) -> Option<Vec<u8>> {
        let path = directory()?.join("pipeline.bin");
        // Never follow a symlink or block on special files.
        let file = OpenOptions::new().read(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC)
            .open(path).ok()?;
        let meta = file.metadata().ok()?;
        if !meta.is_file() || !private(&meta) || meta.nlink() != 1
            || meta.len() > MAX_FILE as u64 { return None; }
        let mut bytes = Vec::with_capacity(meta.len() as usize);
        file.take(MAX_FILE as u64 + 1).read_to_end(&mut bytes).ok()?;
        Some(decode(key, &bytes)?.to_vec())
    }

    pub fn store(key: &str, data: &[u8]) -> Option<()> {
        let dir = directory()?;
        let bytes = encode(key, data)?;
        // One cache entry per app, bounded. A crashed writer leaves at most
        // a temporary file; remove that process's temporary on ordinary errors.
        let name = format!(".pipeline-{}-{}.tmp", std::process::id(), SERIAL.fetch_add(1, Ordering::Relaxed));
        let path = dir.join(name);
        let mut file: File = OpenOptions::new().write(true).create_new(true)
            .mode(0o600).custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&path).ok()?;
        let result = (|| {
            file.write_all(&bytes).ok()?;
            drop(file);
            // Cache data is disposable; atomic replace and checksum suffice.
            fs::rename(&path, dir.join("pipeline.bin")).ok()?;
            Some(())
        })();
        if result.is_none() { let _ = fs::remove_file(path); }
        result
    }
}

#[cfg(target_os = "android")]
pub use android::{configure, load, store};

#[cfg(not(target_os = "android"))]
pub fn load(_: &str) -> Option<Vec<u8>> { None }
#[cfg(not(target_os = "android"))]
pub fn store(_: &str, _: &[u8]) -> Option<()> { None }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn envelope_roundtrip_rejects_truncation_corruption_and_wrong_driver() {
        let key = "adapter:driver:wgpu";
        let data = (0..4096).map(|i| (i % 251) as u8).collect::<Vec<_>>();
        let encoded = encode(key, &data).unwrap();
        assert_eq!(decode(key, &encoded), Some(data.as_slice()));
        assert!(decode("different-driver", &encoded).is_none());
        for n in [0, 7, 16, encoded.len()-1] { assert!(decode(key, &encoded[..n]).is_none()); }
        for i in [0, 8, 12, 16, 25, encoded.len()-9, encoded.len()-1] {
            let mut corrupted = encoded.clone();
            corrupted[i] ^= 0x80;
            assert!(decode(key, &corrupted).is_none());
        }
        let mut appended = encoded;
        appended.push(0);
        assert!(decode(key, &appended).is_none());
        assert!(encode(key, &[]).is_none());
        assert!(encode(key, &vec![0; MAX_BYTES+1]).is_none());
    }
}
