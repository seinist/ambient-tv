#!/usr/bin/env python3
"""Minimal reproducible extension of pinned Monogram MTProto: channels.leaveChannel.

The source is GPL-3.0-only. Original upstream ref and exact modifications are
retained for corresponding-source distribution. Fails closed on changed upstream
API/ABI and is idempotent for local repeated builds.

Every upstream file this script changes gets a dated modification notice at its
top (GPL-3.0 section 5a). Files it removes are listed in docs/monogram-integration.md.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
changes = {}

# GPL-3.0 section 5a: modified files carry a prominent notice with a relevant date.
# The date is fixed (not the build date) so repeated builds stay reproducible.
MODIFICATION_DATE = "2026-11-27"
NOTICE_MARKER = "Modified by Ambient TV, "
COMMENT_PREFIX = {".rs": "//", ".kt": "//", ".kts": "//", ".toml": "#"}
MODIFICATIONS = {
    "native/mtproto/build.gradle.kts":
        "isolated opt-in ARM64 CPU flags to Android cargo-ndk tasks",
    "vendor/mono-rs/crates/mono-proto-messages/src/membership.rs":
        "added leave_chat (Telegram channels.leaveChannel)",
    "vendor/mono-rs/crates/mono-proto-client/src/membership.rs":
        "added leave_chat client call",
    "vendor/mono-rs/crates/mono-proto-client/src/dispatch.rs":
        "added leave_chat and search_global_channels dispatch",
    "native/mtproto-rs/src/ffi.rs":
        "added leave_chat, search_global_filtered and bounded in-memory media ranges; removed unused exports",
    "vendor/mono-rs/crates/mono-proto-media/src/download.rs":
        "added scoped bounded in-memory range output with existing session publication",
    "vendor/mono-rs/crates/mono-proto-media/src/profile.rs":
        "bounded preview and playing request windows independently of full file downloads",
    "vendor/mono-rs/crates/mono-proto-client/src/media_download.rs":
        "kept unknown-size and small full downloads on a single request window",
    "vendor/mono-rs/crates/mono-proto-crypto/src/message.rs":
        "removed fixed-size KDF heap allocations and reused authenticated plaintext allocation",
    "native/mtproto-rs/src/lib.rs":
        "removed sticker, VPX, waveform and QR modules",
    "native/mtproto-rs/Cargo.toml":
        "removed rxing (QR) and tlottie (sticker) dependencies",
    "vendor/mono-rs/crates/mono-proto-messages/src/messages/search_rpc.rs":
        "added search_global_channels (messages.searchGlobal with media filter, channels only)",
    "native/mtproto/src/main/java/org/monogram/mtproto/MtprotoNative.kt":
        "added leaveChat",
    "native/mtproto/src/main/java/org/monogram/mtproto/MtprotoNativeUniFfi.kt":
        "added leaveChat binding",
}


def modification_notice(relative: str) -> str:
    what = MODIFICATIONS.get(relative)
    prefix = COMMENT_PREFIX.get(Path(relative).suffix)
    if what is None or prefix is None:
        raise SystemExit(f"No modification notice defined for {relative}")
    return (f"{prefix} {NOTICE_MARKER}{MODIFICATION_DATE}: {what}.\n"
            f"{prefix} Changed by scripts/patch_monogram_leave.py; distributed under GPL-3.0-only.\n")

def amend(relative: str, before: str, after: str) -> None:
    path = root / relative
    original = changes.get(path)
    if original is None:
        original = path.read_text(encoding="utf-8")
    if after in original:
        changes[path] = original
        return
    if original.count(before) != 1:
        raise SystemExit(f"Unexpected pinned Monogram API in {relative}: expected unique anchor")
    changes[path] = original.replace(before, after, 1)

def add_before(relative: str, anchor: str, addition: str, marker: str) -> None:
    path = root / relative
    original = changes.get(path)
    if original is None:
        original = path.read_text(encoding="utf-8")
    if marker in original:
        changes[path] = original
        return
    if original.count(anchor) != 1:
        raise SystemExit(f"Unexpected pinned Monogram API in {relative}: expected unique insertion anchor")
    changes[path] = original.replace(anchor, addition + "\n" + anchor, 1)

messages = "vendor/mono-rs/crates/mono-proto-messages/src/membership.rs"
amend(messages,
      "Bool, ChannelsJoinChannelRequest, Chat as TlChat, ContactsUnblockRequest, InputChannel,",
      "Bool, ChannelsJoinChannelRequest, ChannelsLeaveChannelRequest, Chat as TlChat, ContactsUnblockRequest, InputChannel,")
add_before(messages,
           "pub fn unblock_user(state: &mut MembershipContext<'_>, chat_id: i64)",
           """/// Telegram channels.leaveChannel, preserving the updated peer/dialog metadata.
pub fn leave_chat(state: &mut MembershipContext<'_>, chat_id: i64) -> Result<(), MtprotoError> {
    if channel_id_from_chat_id(chat_id).is_none() {
        return Err(MtprotoError::Message("leave is only supported for channels".into()));
    }
    let cached = peers::require_usable_peer(&state.peers, chat_id)?;
    let response: Updates = crate::api_invoke::invoke_api(
        &mut state.snapshot,
        state.api_id,
        ChannelsLeaveChannelRequest {
            channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                channel_id: cached.id,
                access_hash: cached.access_hash,
            })),
        },
    )?;
    let (users, chats) = updates_users_chats(&response);
    cache_from_users_chats(
        &mut state.peers, &mut state.media, users.into_iter(), chats.iter().cloned(),
    );
    Ok(())
}
""",
           "pub fn leave_chat(state: &mut MembershipContext")
client = "vendor/mono-rs/crates/mono-proto-client/src/membership.rs"
add_before(client,
           "pub fn unblock_user(state: &mut ClientState, chat_id: i64)",
           """pub fn leave_chat(state: &mut ClientState, chat_id: i64) -> Result<(), MtprotoError> {
    mono_proto_messages::membership::leave_chat(&mut context(state), chat_id)
}
""",
           "pub fn leave_chat(state: &mut ClientState")
dispatch = "vendor/mono-rs/crates/mono-proto-client/src/dispatch.rs"
add_before(dispatch,
           "pub fn unblock_user(handle: u64, chat_id: i64)",
           """pub fn leave_chat(handle: u64, chat_id: i64) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| super::membership::leave_chat(state, chat_id))
    })
}
""",
           "pub fn leave_chat(handle: u64")
ffi = "native/mtproto-rs/src/ffi.rs"
add_before(ffi,
           "#[uniffi::export]\npub fn unblock_user(handle: u64, chat_id: i64)",
           """#[uniffi::export]
pub fn leave_chat(handle: u64, chat_id: i64) -> Result<(), MtprotoError> {
    mono_proto_client::dispatch::leave_chat(handle, chat_id)
}
""",
           "pub fn leave_chat(handle: u64")
api = "native/mtproto/src/main/java/org/monogram/mtproto/MtprotoNative.kt"
add_before(api,
           "    /** `contacts.unblock` for a user peer. */",
           """    /** Telegram channels.leaveChannel. Available in the Ambient TV opt-in patched build. */
    @Throws(MtprotoException::class)
    fun leaveChat(handle: Long, chatId: Long): Unit =
        throw MtprotoException.Message("leave channel requires native patch")
""",
           "fun leaveChat(handle: Long, chatId: Long)")
adapter = "native/mtproto/src/main/java/org/monogram/mtproto/MtprotoNativeUniFfi.kt"
amend(adapter,
      "import uniffi.monogram_mtproto.joinChat as nativeJoinChat",
      "import uniffi.monogram_mtproto.joinChat as nativeJoinChat\nimport uniffi.monogram_mtproto.leaveChat as nativeLeaveChat")
add_before(adapter,
           "    override fun unblockUser(\n",
           """    override fun leaveChat(handle: Long, chatId: Long) {
        nativeLeaveChat(handle.toULong(), chatId)
    }
""",
           "override fun leaveChat(handle: Long, chatId: Long)")

# Category timelines: one messages.searchGlobal with a media filter over all channels
# instead of one messages.search per channel (FLOOD_WAIT on a first load without cache).
# Upstream (since 0.5.0) has search_global_filtered with a folder but no broadcasts-only
# scope; Ambient adds search_global_channels on top of upstream's request builder.
search_rpc = "vendor/mono-rs/crates/mono-proto-messages/src/messages/search_rpc.rs"
add_before(search_rpc,
           "fn global_search_request(",
           """/// Ambient TV: messages.searchGlobal with a media filter, optionally only channels.
#[allow(clippy::too_many_arguments)]
pub fn search_global_channels(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    query: &str,
    filter: &str,
    broadcasts_only: bool,
    offset_rate: i32,
    offset_peer_id: i64,
    offset_id: i32,
    limit: i32,
) -> Result<GlobalMessageSearchDto, MtprotoError> {
    let mut request = global_search_request(
        peers, query, filter, offset_rate, offset_peer_id, offset_id, limit, 0,
    )?;
    if broadcasts_only {
        request.flags |= MessagesSearchGlobalRequest::BROADCASTS_ONLY_FLAG;
        request.broadcasts_only = Some(Box::new(mono_proto::latest::api::True::True(
            mono_proto::latest::api::TrueConstructor {},
        )));
    }
    let response: mono_proto::latest::api::MessagesMessages =
        api_invoke::invoke_api(snapshot, api_id, request)?;
    let (messages, next_rate, _) = dialogs::dtos_from_messages_page(response, peers, media)?;
    let (next_rate, next_peer_id, next_offset_id) = next_global_page(&messages, next_rate);
    Ok(GlobalMessageSearchDto {
        messages,
        next_rate,
        next_peer_id,
        next_offset_id,
    })
}
""",
           "pub fn search_global_channels(")
add_before(dispatch,
           "pub fn get_pinned_messages(",
           """#[allow(clippy::too_many_arguments)]
pub fn search_global_channels(
    handle: u64,
    query: String,
    filter: String,
    broadcasts_only: bool,
    offset_rate: i32,
    offset_peer_id: i64,
    offset_id: i32,
    limit: i32,
) -> Result<crate::GlobalMessageSearchDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::message_ops::search_rpc::search_global_channels(
                &mut state.snapshot,
                state.api_id,
                &mut state.peers,
                &mut state.media,
                &query,
                &filter,
                broadcasts_only,
                offset_rate,
                offset_peer_id,
                offset_id,
                limit,
            )
        })
    })
}
""",
           "pub fn search_global_channels(")
add_before(ffi,
           "#[uniffi::export]\npub fn get_pinned_messages(",
           """#[uniffi::export]
#[allow(clippy::too_many_arguments)]
pub fn search_global_filtered(
    handle: u64,
    query: String,
    filter: String,
    broadcasts_only: bool,
    offset_rate: i32,
    offset_peer_id: i64,
    offset_id: i32,
    limit: i32,
) -> Result<GlobalMessageSearchDto, MtprotoError> {
    mono_proto_client::dispatch::search_global_channels(
        handle, query, filter, broadcasts_only, offset_rate, offset_peer_id, offset_id, limit,
    )
}
""",
           "pub fn search_global_filtered(")

# Ambient TV needs Telegram account/media transport, not Monogram sticker animation,
# VPX video-sticker decoding, waveform generation, or its separate Rust QR codec.
# Ambient's QR renderer is already used for account setup. Remove these transitive
# Rust dependencies *before* compiling while retaining the pinned UniFFI media API.
# This is intentionally a source patch: Cargo's LTO cannot remove exported FFI.
def drop_span(relative: str, start: str, end: str, marker: str) -> None:
    path = root / relative
    source = changes.get(path, path.read_text(encoding="utf-8"))
    if marker in source:
        return
    if source.count(start) != 1 or source.count(end) != 1:
        raise SystemExit(f"Unexpected pinned optional API in {relative}: missing/ambiguous boundary")
    first, last = source.index(start), source.index(end)
    if first >= last:
        raise SystemExit(f"Unexpected optional API order in {relative}")
    changes[path] = source[:first] + marker + "\n\n" + source[last:]


def drop_tail(relative: str, start: str, expected_tail: str, marker: str) -> None:
    path = root / relative
    source = changes.get(path, path.read_text(encoding="utf-8"))
    if marker in source:
        return
    if source.count(start) != 1 or not source.rstrip().endswith(expected_tail):
        raise SystemExit(f"Unexpected pinned optional API tail in {relative}")
    changes[path] = source[:source.index(start)] + marker + "\n"


crate = "native/mtproto-rs/Cargo.toml"
amend(crate,
      'rxing = { version = "0.9.3", default-features = false, features = ["qrcode", "encoders", "decoders", "encoding_rs"] }',
      '# Ambient TV generates and scans QR in its existing Android UI, not Rust.')
amend(crate,
      'flate2 = "1"\ntlottie = { path = "../../vendor/tlottie", default-features = false, features = ["cpu", "std"] }',
      '# TGS/Lottie stickers are not rendered by the Ambient TV MTProto adapter.')

lib = "native/mtproto-rs/src/lib.rs"
amend(lib,
      'mod lottie;\nmod qr;\n#[allow(unsafe_code)]\nmod vpx;\nmod waveform;',
      '// Ambient TV omits sticker, waveform and redundant QR-only Rust modules.')
amend(lib,
      'pub use qr::QrCodeMatrix;',
      '// No native QR matrix needed: Ambient TV has its own QR renderer.')

ffi = "native/mtproto-rs/src/ffi.rs"
amend(ffi,
      "use crate::lottie;\nuse crate::qr;\nuse crate::vpx;\nuse crate::waveform;",
      "// Ambient TV: omitted native sticker, waveform and QR helpers.")
amend(ffi,
      "InstantViewDto, JoinChatDto, LottieSize, MessageDto, MessagesPageDto, MtprotoError,",
      "InstantViewDto, JoinChatDto, MessageDto, MessagesPageDto, MtprotoError,")
amend(ffi,
      "UpdatesStateDto, UploadItemDto, VpxAlphaFrame, VpxFrame, WallpaperCatalogDto,",
      "UpdatesStateDto, UploadItemDto, WallpaperCatalogDto,")
drop_span(
    ffi,
    "#[uniffi::export]\npub fn create_lottie(data: Vec<u8>)",
    "#[uniffi::export]\npub fn get_message_read_participants(",
    "// Ambient TV: sticker renderer and VPX decoder exports omitted.",
)
drop_tail(
    ffi,
    "#[uniffi::export]\npub fn decode_vpx_alpha_packet(",
    "qr::decode(luma, width, height)\n}",
    "// Ambient TV: sticker alpha, waveform and native QR exports omitted.",
)

# The upstream Kotlin facades reference UniFFI exports removed above.
# They are not used by Ambient TV; exclude them from the compiled AAR.
unused_wrappers = {
    "native/mtproto/src/main/java/org/monogram/mtproto/LottieNative.kt": "object LottieNative",
    "native/mtproto/src/main/java/org/monogram/mtproto/VpxNative.kt": "object VpxNative",
}
for relative, expected in unused_wrappers.items():
    wrapper = root / relative
    if wrapper.exists() and expected not in wrapper.read_text(encoding="utf-8"):
        raise SystemExit(f"Unexpected pinned Kotlin facade in {relative}")


# Native performance patch: the original range RPC, reference refresh, DC migration,
# CDN fallback and session-lease checks remain authoritative. Only the bounded
# origin range's output sink changes; file downloads keep their original path.
range_output = "vendor/mono-rs/crates/mono-proto-media/src/download.rs"
add_before(range_output, "pub struct StagedDownload {", """// Scoped to a single synchronous range RPC and its exact private destination.
struct AmbientRangeCapture {
    destination: std::path::PathBuf,
    staged: Option<(std::path::PathBuf, Vec<u8>)>,
    published: bool,
}
std::thread_local! {
    static AMBIENT_RANGE: std::cell::RefCell<Option<AmbientRangeCapture>> =
        const { std::cell::RefCell::new(None) };
}
pub fn ambient_capture_range<T>(
    destination: &std::path::Path,
    work: impl FnOnce() -> Result<T, MtprotoError>,
) -> Result<(T, Option<Vec<u8>>), MtprotoError> {
    AMBIENT_RANGE.with(|slot| {
        let mut slot = slot.borrow_mut();
        if slot.is_some() {
            return Err(MtprotoError::Message("nested media range capture".into()));
        }
        *slot = Some(AmbientRangeCapture {
            destination: destination.to_owned(), staged: None, published: false,
        });
        Ok(())
    })?;
    struct Reset;
    impl Drop for Reset {
        fn drop(&mut self) { AMBIENT_RANGE.with(|slot| { slot.borrow_mut().take(); }); }
    }
    let _reset = Reset;
    let result = work()?;
    let payload = AMBIENT_RANGE.with(|slot| {
        slot.borrow_mut().take().and_then(|capture| {
            if capture.published { capture.staged.map(|(_, bytes)| bytes) } else { None }
        })
    });
    Ok((result, payload))
}
fn ambient_begin_range(destination: &std::path::Path) -> bool {
    AMBIENT_RANGE.with(|slot| {
        let mut slot = slot.borrow_mut();
        if let Some(capture) = slot.as_mut().filter(|c| c.destination.as_path() == destination) {
            capture.staged = None;
            capture.published = false;
            true
        } else { false }
    })
}
fn ambient_stage_range(destination: &std::path::Path, stage: &std::path::Path, bytes: Vec<u8>) {
    AMBIENT_RANGE.with(|slot| {
        if let Some(capture) = slot.borrow_mut().as_mut().filter(|c| c.destination.as_path() == destination) {
            capture.staged = Some((stage.to_owned(), bytes));
        }
    });
}
// Called only within the existing publish_media session/connection/request guards.
fn ambient_publish_range(destination: &std::path::Path, stage: &std::path::Path) -> bool {
    AMBIENT_RANGE.with(|slot| {
        let mut slot = slot.borrow_mut();
        if let Some(capture) = slot.as_mut().filter(|c| c.destination.as_path() == destination)
            && capture.staged.as_ref().is_some_and(|(path, _)| path.as_path() == stage) {
            capture.published = true;
            return true;
        }
        false
    })
}
const AMBIENT_RANGE_LIMIT: u64 = 512 * 1024;
enum AmbientRangeOutput {
    File(std::fs::File),
    Memory(std::io::Cursor<Vec<u8>>),
}
impl AmbientRangeOutput {
    fn set_len(&mut self, len: u64) -> std::io::Result<()> {
        match self {
            Self::File(file) => file.set_len(len),
            Self::Memory(cursor) if len <= AMBIENT_RANGE_LIMIT => {
                cursor.get_mut().resize(len as usize, 0);
                Ok(())
            }
            Self::Memory(_) => Err(std::io::Error::other("oversized captured media range")),
        }
    }
}
impl std::io::Seek for AmbientRangeOutput {
    fn seek(&mut self, pos: std::io::SeekFrom) -> std::io::Result<u64> {
        match self {
            Self::File(file) => std::io::Seek::seek(file, pos),
            Self::Memory(cursor) => std::io::Seek::seek(cursor, pos),
        }
    }
}
impl std::io::Write for AmbientRangeOutput {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        match self {
            Self::File(file) => std::io::Write::write(file, bytes),
            Self::Memory(cursor) => {
                if cursor.position().checked_add(bytes.len() as u64)
                    .is_none_or(|end| end > AMBIENT_RANGE_LIMIT) {
                    return Err(std::io::Error::other("oversized captured media range"));
                }
                std::io::Write::write(cursor, bytes)
            }
        }
    }
    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Self::File(file) => std::io::Write::flush(file),
            Self::Memory(_) => Ok(()),
        }
    }
}
#[cfg(test)]
mod ambient_range_tests {
    use super::*;
    #[test]
    fn only_published_exact_destination_and_stage_are_returned() {
        let dest = std::path::Path::new("ambient-range");
        let stage = std::path::Path::new("ambient-stage");
        let (_, payload) = ambient_capture_range(dest, || {
            assert!(ambient_begin_range(dest));
            ambient_stage_range(dest, stage, vec![1, 2, 3]);
            assert!(!ambient_publish_range(dest, std::path::Path::new("other-stage")));
            assert!(ambient_publish_range(dest, stage));
            Ok(())
        }).unwrap();
        assert_eq!(payload, Some(vec![1, 2, 3]));
        let (_, payload) = ambient_capture_range(dest, || {
            assert!(ambient_begin_range(dest));
            ambient_stage_range(dest, stage, vec![1]);
            assert!(ambient_begin_range(dest)); // retry invalidates old staging
            Ok(())
        }).unwrap();
        assert!(payload.is_none());
        assert!(!ambient_begin_range(dest));
    }
    #[test]
    fn real_range_sink_collects_reduced_wire_parts_without_payload_file() {
        use mono_proto::latest::api::{StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor};
        let root = std::env::temp_dir().join(format!("ambient-range-{}-{:?}", std::process::id(), std::thread::current().id()));
        std::fs::create_dir_all(&root).unwrap();
        let dest = root.join("chunk");
        let media = MediaRef {
            file_size: Some(2 * i64::from(FAST_CHUNK)),
            kind: "video".into(), cache_key: "ambient-range".into(),
            location: MediaLocation::Document {
                id: 987654321, access_hash: 4, file_reference: vec![1],
                thumb_size: String::new(), dc_id: 2, mime_type: "video/mp4".into(),
            },
            thumb_cache_key: None, thumb_location: None,
            display_cache_key: None, display_location: None,
            sticker_set_id: None, sticker_set_access_hash: None,
            source_url: None, refresh_source: None, webpage_id: None,
        };
        let _window = bind_download_profile(DownloadProfile::Playing);
        set_forced_chunk(FAST_CHUNK);
        let mut offsets = Vec::new();
        let (_, payload) = ambient_capture_range(&dest, || {
            let staged = StagedDownload::new(&dest)?;
            download_media_range_batched_streaming_capped(
                2, &media, staged.path(), &dest, Some(0), 1, None,
                |_, requests, _| {
                    assert_eq!(requests.len(), 1);
                    let request = &requests[0];
                    assert!(request.cdn_supported.is_none());
                    if request.limit == FAST_CHUNK {
                        return Err(MtprotoError::Message("LIMIT_INVALID".into()));
                    }
                    assert_eq!(request.limit, DEFAULT_CHUNK);
                    offsets.push(request.offset);
                    Ok(vec![Ok(UploadFile::UploadFile(UploadFileConstructor {
                        type_: Box::new(StorageFileType::StorageFileUnknown(StorageFileUnknownConstructor {})),
                        mtime: 0, bytes: vec![9; DEFAULT_CHUNK as usize],
                    }))])
                },
            )?;
            assert_eq!(std::fs::metadata(staged.path()).unwrap().len(), 0);
            staged.publish(&dest)
        }).unwrap();
        assert_eq!(offsets, (0..4).map(|i| i64::from(i * DEFAULT_CHUNK)).collect::<Vec<_>>());
        assert_eq!(payload.unwrap(), vec![9; FAST_CHUNK as usize]);
        assert!(!dest.exists());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn captured_output_is_bounded() {
        use std::io::{Seek, SeekFrom, Write};
        let mut out = AmbientRangeOutput::Memory(std::io::Cursor::new(Vec::new()));
        out.seek(SeekFrom::Start(AMBIENT_RANGE_LIMIT - 1)).unwrap();
        assert!(out.write_all(&[1, 2]).is_err());
        assert!(out.set_len(AMBIENT_RANGE_LIMIT + 1).is_err());
    }
}
""", "struct AmbientRangeCapture")
amend(range_output,
      "        remove_staging_sidecars(&self.path);\n        fs::rename(&self.path, destination)",
      """        if ambient_publish_range(destination, &self.path) {
            // The empty private staging file is cleaned by Drop; the authenticated
            // payload has never been written to it.
            return Ok(destination.display().to_string());
        }
        remove_staging_sidecars(&self.path);
        fs::rename(&self.path, destination)""")
amend(range_output,
      "        let mut out = if !stream && file_len > 0 {",
      """        let ambient_range = stream && ambient_begin_range(cancellation_path);
        let mut out = if ambient_range {
            AmbientRangeOutput::Memory(std::io::Cursor::new(Vec::with_capacity(AMBIENT_RANGE_LIMIT as usize)))
        } else if !stream && file_len > 0 {""")
amend(range_output,
      """            file
        } else {
            fs::File::create(dest_path).map_err(|e| MtprotoError::Message(e.to_string()))?
        };
        let window""",
      """            AmbientRangeOutput::File(file)
        } else {
            AmbientRangeOutput::File(fs::File::create(dest_path).map_err(|e| MtprotoError::Message(e.to_string()))?)
        };
        let window""")
amend(range_output,
      """        out.flush()
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        Ok(())
    })();""",
      """        out.flush()
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        if let AmbientRangeOutput::Memory(cursor) = out {
            ambient_stage_range(cancellation_path, dest_path, cursor.into_inner());
        }
        Ok(())
    })();""")
# Kotlin asks for a 512 KiB aligned range; LIMIT_INVALID may reduce the wire part
# to 128 KiB. Keep collecting until the requested range is full or EOF is proven.
amend(range_output,
      """                    .min((FAST_CHUNK / chunk).max(1) as usize)""",
      """                    .min(((origin.saturating_add(i64::from(FAST_CHUNK)) - next)
                        .max(1) / i64::from(chunk)).max(1) as usize)""")
amend(range_output,
      """                if stream {
                    break 'download;
                }
                if last_part {""",
      """                if stream {
                    if !ambient_range || last_part || written_end >= origin.saturating_add(i64::from(FAST_CHUNK)) {
                        break 'download;
                    }
                    if written_end <= next {
                        return Err(MtprotoError::Message("incomplete media range".into()));
                    }
                    next = written_end;
                    continue 'download;
                }
                if last_part {""")
amend(range_output,
      "                    let seek = offset.unwrap_or(next);",
      "                    let seek = if ambient_range { next } else { offset.unwrap_or(next) };")
amend(range_output,
      "                let (flags, cdn_supported) = cdn_fields_for_seek(origin);",
      """                // CDN's whole-file receiver is inappropriate for a bounded range.
                let (flags, cdn_supported) = if ambient_range {
                    (0, None)
                } else { cdn_fields_for_seek(origin) };""")
# Keep small and unknown-size transfers at one wire request (old latency fix).
profile_source = "vendor/mono-rs/crates/mono-proto-media/src/profile.rs"
add_before(profile_source, "pub fn configured_download_window(profile: DownloadProfile)", """// Opt in from Ambient TV; keep upstream default profile behavior unchanged.
static AMBIENT_MEDIA_MODE: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);
pub fn set_ambient_media_mode(enabled: bool) {
    AMBIENT_MEDIA_MODE.store(enabled, std::sync::atomic::Ordering::Relaxed);
}
pub fn ambient_media_mode() -> bool {
    AMBIENT_MEDIA_MODE.load(std::sync::atomic::Ordering::Relaxed)
}
""", "static AMBIENT_MEDIA_MODE:")
amend(profile_source,
      "        in_flight: maximum.in_flight.min(policy.pipeline_parts()),",
      """        in_flight: if ambient_media_mode() && profile != DownloadProfile::User {
            1
        } else { maximum.in_flight.min(policy.pipeline_parts()) },""")
client_download = "vendor/mono-rs/crates/mono-proto-client/src/media_download.rs"
amend(client_download,
      "    let dest = Path::new(&dest_path);\n    let first = download_media_range_on_lane",
      """    // Only known large, complete user downloads benefit from a wide pipeline.
    // Preview and unknown-size requests must not send speculative requests past EOF.
    let _small_file = (mono_proto_media::profile::ambient_media_mode()
        && profile == media::DownloadProfile::User
        && media.file_size.is_none_or(|size| size < 2 * 1024 * 1024))
        .then(|| media::bind_download_profile(media::DownloadProfile::Ordinary));
    let dest = Path::new(&dest_path);
    let first = download_media_range_on_lane""")
amend(client_download,
      """                drop(admission);
                cdn_or_origin(""",
      """                drop(admission);
                if offset.is_some() && mono_proto_media::profile::ambient_media_mode() {
                    return media::with_cdn_supported(false, || {
                        let _admission = client.policy.admit_download(target_dc, large)?;
                        download_media_range_on_lane_dc(
                            handle, client, home, api_id, media, dest, offset, target_dc,
                        )
                    });
                }
                cdn_or_origin(""")
add_before(ffi, "#[uniffi::export]\npub fn set_download_concurrency(", """#[uniffi::export]
pub fn set_ambient_media_mode(enabled: bool) {
    proto_media::profile::set_ambient_media_mode(enabled);
}
""", "pub fn set_ambient_media_mode(")
# Add the byte-returning bridge. Inline/CDN paths retain the existing file sink
# and are read with an explicit bound; all failures propagate normally.
add_before(ffi, "#[uniffi::export]\npub fn download_message_thumb(", """#[uniffi::export]
pub fn download_message_media_chunk_bytes(
    handle: u64, chat_id: i64, message_id: i32, dest_path: String, offset: i64,
) -> Result<Vec<u8>, MtprotoError> {
    use std::io::Read;
    const LIMIT: usize = 512 * 1024;
    if offset < 0 || offset % LIMIT as i64 != 0 {
        return Err(MtprotoError::Message("invalid media range offset".into()));
    }
    let destination = std::path::Path::new(&dest_path);
    let (path, captured) = proto_media::download::ambient_capture_range(destination, || {
        mono_proto_client::media_download::download_message_media_range(
            handle, chat_id, message_id, dest_path.clone(),
            proto_media::location::MediaDownloadKind::Full, Some(offset),
        )
    })?;
    let bytes = if let Some(bytes) = captured { bytes } else {
        if std::path::Path::new(&path) != destination {
            return Err(MtprotoError::Message("unexpected range destination".into()));
        }
        let mut bytes = Vec::with_capacity(LIMIT);
        std::fs::File::open(destination)
            .map_err(|e| MtprotoError::Message(e.to_string()))?
            .take((LIMIT + 1) as u64)
            .read_to_end(&mut bytes)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        bytes
    };
    if bytes.is_empty() || bytes.len() > LIMIT {
        return Err(MtprotoError::Message("invalid captured media range size".into()));
    }
    Ok(bytes)
}
""", "pub fn download_message_media_chunk_bytes(")
# Hot-path JNI uses the same validated RPC as UniFFI. The player and cache
# already require JVM byte arrays; copying directly into the final byte array
# avoids the intermediate UniFFI RustBuffer/JNA lifting without an extra
# DirectByteBuffer -> byte[] copy. Authentication and cancellation stay shared.
add_before(ffi, "#[uniffi::export]\npub fn download_message_thumb(", """#[cfg(target_os = "android")]
#[allow(unsafe_code)]
#[jni::jni_mangle("com.ambient.tv.data.telegram.AmbientMonogramJni")]
pub extern "system" fn downloadMediaRange<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _this: jni::objects::JObject<'caller>,
    handle: jni::sys::jlong,
    chat_id: jni::sys::jlong,
    message_id: jni::sys::jint,
    destination: jni::objects::JString<'caller>,
    offset: jni::sys::jlong,
) -> jni::objects::JByteArray<'caller> {
    unowned_env.with_env(|env| -> jni::errors::Result<_> {
        let path = destination.try_to_string(env)?;
        match download_message_media_chunk_bytes(handle as u64, chat_id, message_id, path, offset) {
            Ok(bytes) => env.byte_array_from_slice(&bytes),
            Err(error) => {
                let message = jni::strings::JNIString::from(error.to_string().as_str());
                env.throw_new(jni::jni_str!("java/lang/RuntimeException"), message)?;
                Ok(jni::objects::JByteArray::null())
            }
        }
    }).resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
""", "pub extern \"system\" fn downloadMediaRange")
# Crypto: KDF inputs have a fixed 52-byte size and need no heap allocation.
crypto_message = "vendor/mono-rs/crates/mono-proto-crypto/src/message.rs"
amend(crypto_message,
      """    let mut a_input = Vec::with_capacity(52);
    a_input.extend_from_slice(msg_key);
    a_input.extend_from_slice(&auth_key[x..x + 36]);
    let a = sha256(&a_input);
    let mut b_input = Vec::with_capacity(52);
    b_input.extend_from_slice(&auth_key[40 + x..76 + x]);
    b_input.extend_from_slice(msg_key);""",
      """    let mut a_input = [0_u8; 52];
    a_input[..16].copy_from_slice(msg_key);
    a_input[16..].copy_from_slice(&auth_key[x..x + 36]);
    let a = sha256(&a_input);
    let mut b_input = [0_u8; 52];
    b_input[..36].copy_from_slice(&auth_key[40 + x..76 + x]);
    b_input[36..].copy_from_slice(msg_key);""")
amend(crypto_message,
      "    let plaintext = aes_ige_decrypt(&packet[24..], &aes_key, &aes_iv)?;",
      "    let mut plaintext = aes_ige_decrypt(&packet[24..], &aes_key, &aes_iv)?;")
amend(crypto_message,
      """    Ok(DecryptedMessage {
        server_salt,""",
      """    // Authentication and all envelope checks precede reuse of the allocation.
    let padding = plaintext[body_end..].to_vec();
    plaintext.copy_within(32..body_end, 0);
    plaintext.truncate(body_length);
    Ok(DecryptedMessage {
        server_salt,""")
amend(crypto_message,
      """        body: plaintext[32..body_end].to_vec(),
        padding: plaintext[body_end..].to_vec(),""",
      """        body: plaintext,
        padding,""")

# An optional CPU-specific Android build must not pass ARM flags to the
# host Rust compiler used to generate UniFFI Kotlin bindings.
upstream_build = "native/mtproto/build.gradle.kts"
amend(upstream_build,
      '        inputs.property("abis", abis)',
      '''        inputs.property("abis", abis)
        val androidRustFlags = providers.environmentVariable("MONOGRAM_ANDROID_RUSTFLAGS").orElse("").get()
        inputs.property("ambientAndroidRustFlags", androidRustFlags)''')
amend(upstream_build,
      '                        pb.environment()["CARGO_BUILD_JOBS"] = jobs.toString()',
      '''                        pb.environment()["CARGO_BUILD_JOBS"] = jobs.toString()
                        if (androidRustFlags.isNotBlank()) {
                            check(abi == "arm64-v8a") { "Ambient CPU tuning requires ARM64" }
                            pb.environment()["RUSTFLAGS"] = androidRustFlags
                            pb.environment().remove("CARGO_ENCODED_RUSTFLAGS")
                        }''')

# Commit files only after every source contract has been checked.
for path, result in changes.items():
    current = path.read_text(encoding="utf-8")
    # Every recorded file is patched (a fresh change or one made by an earlier run).
    if NOTICE_MARKER not in result:
        result = modification_notice(path.relative_to(root).as_posix()) + result
    if current != result:
        path.write_text(result, encoding="utf-8")
for relative in unused_wrappers:
    (root / relative).unlink(missing_ok=True)
print("Applied pinned Monogram media-only and native performance patches")
