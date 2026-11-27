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
    "vendor/mono-rs/crates/mono-proto-socket/src/tcp.rs":
        "detach an individual completed request socket without closing the client connection",
    "vendor/mono-rs/crates/mono-proto-runtime/src/request_control.rs":
        "unregister only the completed socket from request cancellation",
    "vendor/mono-rs/crates/mono-proto-runtime/src/rpc/supervisor.rs":
        "park a completed connection only after detaching request cancellation",
    "vendor/mono-rs/crates/mono-proto-transport/src/framing.rs":
        "consume large padded intermediate frames by ownership and preserve trailing input",
    "settings.gradle.kts":
        "restrict Gradle to the MTProto module and use the provisioned JDK",
    "build.gradle.kts":
        "remove unused application, Compose, serialization, KSP and Google Services plugin resolution",
    "vendor/mono-rs/crates/mono-proto-engine/src/rpc.rs":
        "accept owned inbound packets and move raw RPC responses",
    "vendor/mono-rs/crates/mono-proto-runtime/src/rpc/invoke.rs":
        "move authenticated packet buffers through the RPC path",
    "vendor/mono-rs/crates/mono-proto-runtime/src/rpc/inbound.rs":
        "reuse owned non-container result bodies and avoid redundant snapshot clones",
    "vendor/mono-rs/crates/mono-proto-runtime/src/connection/tcp.rs":
        "avoid redundant successful socket timeout configuration",
    "vendor/mono-rs/crates/mono-proto-client/src/lanes.rs":
        "prefer warm same-DC media lanes and promote queued foreground requests",
    "vendor/mono-rs/crates/mono-proto-runtime/src/transfer_policy.rs":
        "honor live promotion during download admission",
    "vendor/mono-rs/crates/mono-proto-runtime/src/api_invoke.rs":
        "decode non-refilling media batches only once",
    "vendor/mono-rs/crates/mono-proto-client/src/media_lane.rs":
        "avoid duplicate decoding and lane splitting for captured ranges",
    "vendor/mono-rs/crates/mono-proto-runtime/src/rpc/framing.rs":
        "reuse a bounded receive scratch buffer and reserve validated large frame capacity",
    "vendor/mono-rs/crates/mono-proto-crypto/src/symmetric.rs":
        "added safe in-place AES-IGE primitives",
    "native/mtproto/build.gradle.kts":
        "isolated opt-in ARM64 CPU flags to Android cargo-ndk tasks",
    "vendor/mono-rs/crates/mono-proto-messages/src/membership.rs":
        "added leave_chat (Telegram channels.leaveChannel)",
    "vendor/mono-rs/crates/mono-proto-client/src/membership.rs":
        "added leave_chat client call",
    "vendor/mono-rs/crates/mono-proto-client/src/dispatch.rs":
        "added leave_chat and search_global_channels dispatch",
    "native/mtproto-rs/src/ffi.rs":
        "added leave_chat, search_global_filtered and bounded in-memory media ranges and complete previews; removed unused exports",
    "vendor/mono-rs/crates/mono-proto-media/src/download.rs":
        "added scoped bounded in-memory range and exact-size preview output with existing session publication",
    "vendor/mono-rs/crates/mono-proto-media/src/profile.rs":
        "bounded preview and playing request windows independently of full file downloads",
    "vendor/mono-rs/crates/mono-proto-media/src/thumbs.rs":
        "select smallest adequate network preview from indexed Telegram sizes for Ambient cards",
    "vendor/mono-rs/crates/mono-proto-media/src/index.rs":
        "retain bounded network preview dimensions during Telegram media indexing",
    "vendor/mono-rs/crates/mono-proto-media/src/location.rs":
        "resolve scoped card size without changing full photo or video downloads",
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

def amend_count(relative: str, before: str, after: str, count: int) -> None:
    path = root / relative
    original = changes.get(path)
    if original is None:
        original = path.read_text(encoding="utf-8")
    if original.count(after) == count:
        changes[path] = original
        return
    if original.count(before) != count:
        raise SystemExit(f"Unexpected pinned Monogram API in {relative}: expected {count} anchors")
    changes[path] = original.replace(before, after)
    
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
    limit: u64,
}
std::thread_local! {
    static AMBIENT_RANGE: std::cell::RefCell<Option<AmbientRangeCapture>> =
        const { std::cell::RefCell::new(None) };
}
pub fn ambient_capture_range<T>(
    destination: &std::path::Path,
    work: impl FnOnce() -> Result<T, MtprotoError>,
) -> Result<(T, Option<Vec<u8>>), MtprotoError> {
    ambient_capture_range_bounded(destination, AMBIENT_RANGE_LIMIT, work)
}
pub fn ambient_capture_range_bounded<T>(
    destination: &std::path::Path,
    limit: u64,
    work: impl FnOnce() -> Result<T, MtprotoError>,
) -> Result<(T, Option<Vec<u8>>), MtprotoError> {
    if limit != AMBIENT_RANGE_LIMIT && limit != 2 * AMBIENT_RANGE_LIMIT {
        return Err(MtprotoError::Message("invalid media range capacity".into()));
    }
    AMBIENT_RANGE.with(|slot| {
        let mut slot = slot.borrow_mut();
        if slot.is_some() {
            return Err(MtprotoError::Message("nested media range capture".into()));
        }
        *slot = Some(AmbientRangeCapture {
            destination: destination.to_owned(), staged: None, published: false, limit,
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
// The complete-preview mode is deliberately distinct from playback ranges.
std::thread_local! {
    static AMBIENT_PREVIEW_SIZE: std::cell::Cell<Option<u64>> = const { std::cell::Cell::new(None) };
    static AMBIENT_PREVIEW_EOF: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}
pub fn ambient_prepare_preview(size: Option<i64>, inline: bool) -> Result<(), MtprotoError> {
    AMBIENT_PREVIEW_SIZE.with(|slot| {
        if slot.get().is_none() { return Ok(()); }
        // Display MediaRef intentionally has file_size=None upstream. Do not invent
        // a size from the full video/document. Unknown sizes require explicit EOF.
        if inline || size.is_some_and(|size| size > (2 * AMBIENT_RANGE_LIMIT) as i64) {
            return Err(MtprotoError::Message("ambient preview requires file sink".into()));
        }
        slot.set(Some(size.filter(|size| *size > 0).unwrap_or(0) as u64));
        Ok(())
    })
}
pub fn ambient_complete_preview_active() -> bool {
    AMBIENT_PREVIEW_SIZE.with(|slot| slot.get().is_some())
}
fn ambient_note_preview_eof() {
    AMBIENT_PREVIEW_EOF.with(|slot| slot.set(true));
}
pub fn ambient_capture_complete_preview<T>(
    destination: &std::path::Path,
    work: impl FnOnce() -> Result<T, MtprotoError>,
) -> Result<Vec<u8>, MtprotoError> {
    AMBIENT_PREVIEW_SIZE.with(|slot| {
        if slot.get().is_some() {
            return Err(MtprotoError::Message("nested preview capture".into()));
        }
        slot.set(Some(0));
        AMBIENT_PREVIEW_EOF.with(|eof| eof.set(false));
        Ok(())
    })?;
    struct Reset;
    impl Drop for Reset {
        fn drop(&mut self) {
            AMBIENT_PREVIEW_SIZE.with(|slot| slot.set(None));
            AMBIENT_PREVIEW_EOF.with(|slot| slot.set(false));
        }
    }
    let _reset = Reset;
    let (_, payload) = ambient_capture_range_bounded(destination, 2 * AMBIENT_RANGE_LIMIT, work)?;
    let expected = AMBIENT_PREVIEW_SIZE.with(|slot| slot.get().unwrap_or(0));
    let bytes = payload.ok_or_else(|| MtprotoError::Message("ambient preview requires file sink".into()))?;
    let eof = AMBIENT_PREVIEW_EOF.with(|slot| slot.get());
    if bytes.is_empty() || (expected != 0 && bytes.len() as u64 != expected) ||
        (expected == 0 && !eof) {
        return Err(MtprotoError::Message("incomplete captured preview".into()));
    }
    Ok(bytes)
}
static AMBIENT_CDN_RANGES: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);
pub fn set_ambient_cdn_ranges(enabled: bool) {
    AMBIENT_CDN_RANGES.store(enabled, std::sync::atomic::Ordering::Relaxed);
}
fn ambient_cdn_ranges() -> bool {
    AMBIENT_CDN_RANGES.load(std::sync::atomic::Ordering::Relaxed)
}
pub fn ambient_range_capacity(destination: &std::path::Path) -> u64 {
    AMBIENT_RANGE.with(|slot| slot.borrow().as_ref()
        .filter(|capture| capture.destination.as_path() == destination)
        .map_or(AMBIENT_RANGE_LIMIT, |capture| capture.limit))
}
pub fn ambient_capture_active(destination: &std::path::Path) -> bool {
    AMBIENT_RANGE.with(|slot| slot.borrow().as_ref().is_some_and(|capture| capture.destination.as_path() == destination))
}
fn ambient_range_cancelled(destination: &std::path::Path) -> bool {
    if ambient_capture_active(destination) && mono_proto_runtime::request_control::current_id() != 0 {
        mono_proto_runtime::request_control::check().is_err()
    } else {
        download_cancelled(destination)
    }
}
fn ambient_begin_range(destination: &std::path::Path) -> bool {
    AMBIENT_RANGE.with(|slot| {
        let mut slot = slot.borrow_mut();
        if let Some(capture) = slot.as_mut().filter(|c| c.destination.as_path() == destination) {
            capture.staged = None;
            capture.published = false;
            AMBIENT_PREVIEW_EOF.with(|slot| slot.set(false));
            true
        } else { false }
    })
}
pub fn ambient_stage_range(destination: &std::path::Path, stage: &std::path::Path, bytes: Vec<u8>) {
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
pub const AMBIENT_RANGE_LIMIT: u64 = 512 * 1024;
enum AmbientRangeOutput {
    File(std::fs::File),
    Memory(std::io::Cursor<Vec<u8>>, u64),
}
impl AmbientRangeOutput {
    // The usual single-wire 512 KiB response can be moved into the memory
    // sink, rather than allocated and copied. Reduced parts append normally.
    fn write_owned(&mut self, bytes: Vec<u8>) -> std::io::Result<()> {
        if let Self::Memory(cursor, limit) = self
            && cursor.position() == 0 && cursor.get_ref().is_empty()
            && bytes.len() as u64 <= *limit {
            let len = bytes.len() as u64;
            *cursor = std::io::Cursor::new(bytes);
            cursor.set_position(len);
            return Ok(());
        }
        std::io::Write::write_all(self, &bytes)
    }
    fn set_len(&mut self, len: u64) -> std::io::Result<()> {
        match self {
            Self::File(file) => file.set_len(len),
            Self::Memory(cursor, limit) if len <= *limit => {
                cursor.get_mut().resize(len as usize, 0);
                Ok(())
            }
            Self::Memory(..) => Err(std::io::Error::other("oversized captured media range")),
        }
    }
}
impl std::io::Seek for AmbientRangeOutput {
    fn seek(&mut self, pos: std::io::SeekFrom) -> std::io::Result<u64> {
        match self {
            Self::File(file) => std::io::Seek::seek(file, pos),
            Self::Memory(cursor, _) => std::io::Seek::seek(cursor, pos),
        }
    }
}
impl std::io::Write for AmbientRangeOutput {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        match self {
            Self::File(file) => std::io::Write::write(file, bytes),
            Self::Memory(cursor, limit) => {
                if cursor.position().checked_add(bytes.len() as u64)
                    .is_none_or(|end| end > *limit) {
                    return Err(std::io::Error::other("oversized captured media range"));
                }
                std::io::Write::write(cursor, bytes)
            }
        }
    }
    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Self::File(file) => std::io::Write::flush(file),
            Self::Memory(..) => Ok(()),
        }
    }
}
#[cfg(test)]
mod ambient_range_tests {
    use super::*;
    #[test]
    fn single_wire_response_reuses_owned_payload() {
        let bytes = vec![3; AMBIENT_RANGE_LIMIT as usize];
        let ptr = bytes.as_ptr();
        let mut out = AmbientRangeOutput::Memory(std::io::Cursor::new(Vec::new()), AMBIENT_RANGE_LIMIT);
        out.write_owned(bytes).unwrap();
        let AmbientRangeOutput::Memory(cursor, _) = out else { panic!("memory sink"); };
        assert_eq!(cursor.get_ref().as_ptr(), ptr);
        assert_eq!(cursor.position(), AMBIENT_RANGE_LIMIT);
    }
    #[test]
    fn two_part_capture_keeps_single_part_default_and_enforces_bound() {
        let dest = std::path::Path::new("ambient-window");
        assert_eq!(ambient_range_capacity(dest), AMBIENT_RANGE_LIMIT);
        let (_, payload) = ambient_capture_range_bounded(dest, 2 * AMBIENT_RANGE_LIMIT, || {
            assert_eq!(ambient_range_capacity(dest), 2 * AMBIENT_RANGE_LIMIT);
            assert!(ambient_begin_range(dest));
            let mut out = AmbientRangeOutput::Memory(std::io::Cursor::new(Vec::new()), ambient_range_capacity(dest));
            out.write_owned(vec![1; AMBIENT_RANGE_LIMIT as usize]).unwrap();
            out.write_owned(vec![2; AMBIENT_RANGE_LIMIT as usize]).unwrap();
            assert!(out.write_owned(vec![3]).is_err());
            let AmbientRangeOutput::Memory(cursor, _) = out else { panic!("memory sink"); };
            ambient_stage_range(dest, dest, cursor.into_inner());
            assert!(ambient_publish_range(dest, dest));
            Ok(())
        }).unwrap();
        let bytes = payload.unwrap();
        assert_eq!(bytes.len(), 2 * AMBIENT_RANGE_LIMIT as usize);
        assert!(bytes[..AMBIENT_RANGE_LIMIT as usize].iter().all(|b| *b == 1));
        assert!(bytes[AMBIENT_RANGE_LIMIT as usize..].iter().all(|b| *b == 2));
        assert_eq!(ambient_range_capacity(dest), AMBIENT_RANGE_LIMIT);
    }
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
            assert!(!staged.path().exists());
            staged.publish(&dest)
        }).unwrap();
        assert_eq!(offsets, (0..4).map(|i| i64::from(i * DEFAULT_CHUNK)).collect::<Vec<_>>());
        assert_eq!(payload.unwrap(), vec![9; FAST_CHUNK as usize]);
        assert!(!dest.exists());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn complete_preview_rejects_unknown_size_and_truncated_payload() {
        let dest = std::path::Path::new("ambient-preview-test");
        assert!(ambient_capture_complete_preview(dest, || {
            ambient_prepare_preview(None, false)?;
            ambient_stage_range(dest, dest, vec![1, 2, 3]);
            assert!(ambient_publish_range(dest, dest));
            Ok(())
        }).is_err());
        let unknown = ambient_capture_complete_preview(dest, || {
            ambient_prepare_preview(None, false)?;
            ambient_note_preview_eof();
            ambient_stage_range(dest, dest, vec![1, 2, 3]);
            assert!(ambient_publish_range(dest, dest));
            Ok(())
        }).unwrap();
        assert_eq!(unknown, vec![1, 2, 3]);
        assert!(ambient_capture_complete_preview(dest, || {
            ambient_prepare_preview(Some(4), false)?;
            ambient_stage_range(dest, dest, vec![1, 2, 3]);
            assert!(ambient_publish_range(dest, dest));
            Ok(())
        }).is_err());
        let bytes = ambient_capture_complete_preview(dest, || {
            ambient_prepare_preview(Some(4), false)?;
            ambient_stage_range(dest, dest, vec![1, 2, 3, 4]);
            assert!(ambient_publish_range(dest, dest));
            Ok(())
        }).unwrap();
        assert_eq!(bytes, vec![1, 2, 3, 4]);
    }
    #[test]
    fn captured_output_is_bounded() {
        use std::io::{Seek, SeekFrom, Write};
        let mut out = AmbientRangeOutput::Memory(std::io::Cursor::new(Vec::new()), AMBIENT_RANGE_LIMIT);
        out.seek(SeekFrom::Start(AMBIENT_RANGE_LIMIT - 1)).unwrap();
        assert!(out.write_all(&[1, 2]).is_err());
        assert!(out.set_len(AMBIENT_RANGE_LIMIT + 1).is_err());
    }
}
""", "struct AmbientRangeCapture")
amend(range_output,
      "        remove_staging_sidecars(&self.path);\n        fs::rename(&self.path, destination)",
      """        if ambient_publish_range(destination, &self.path) {
            // No staging file was created: preserve avoids cleanup syscalls.
            self.preserve = true;
            return Ok(destination.display().to_string());
        }
        remove_staging_sidecars(&self.path);
        fs::rename(&self.path, destination)""")
# Reserve a unique logical stage path without opening a file for RAM ranges.
# Inline and exceptional paths can still create the stage and publish it normally.
amend(range_output,
      """            let path = PathBuf::from(name);
            match fs::OpenOptions::new()""",
      """            let path = PathBuf::from(name);
            if !resume && ambient_capture_active(destination) {
                return Ok(Self { path, preserve: false, _lock: lock });
            }
            match fs::OpenOptions::new()""")
amend(range_output,
      """        let lock = DestinationLock::acquire(destination);
        if let Some(parent) = destination""",
      """        let lock = DestinationLock::acquire(destination);
        if !ambient_capture_active(destination)
            && let Some(parent) = destination""")
amend(range_output,
      """    let location = input_location(&media.location)?;
    if let Some(parent) = dest_path.parent() {""",
      """    let location = input_location(&media.location)?;
    if !ambient_capture_active(cancellation_path)
        && let Some(parent) = dest_path.parent() {""")
amend(range_output,
      """        let mut file_len = fs::metadata(dest_path)
            .map(|meta| meta.len() as i64)
            .unwrap_or(0);""",
      """        let mut file_len = if stream { 0 } else {
            fs::metadata(dest_path).map(|meta| meta.len() as i64).unwrap_or(0)
        };""")
amend(range_output,
      "        let mut out = if !stream && file_len > 0 {",
      """        let ambient_range = stream && ambient_begin_range(cancellation_path);
        let capture_limit = ambient_range_capacity(cancellation_path);
        let mut out = if ambient_range {
            AmbientRangeOutput::Memory(std::io::Cursor::new(Vec::new()), capture_limit)
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
        if let AmbientRangeOutput::Memory(cursor, _) = out {
            ambient_stage_range(cancellation_path, dest_path, cursor.into_inner());
        }
        Ok(())
    })();""")
# Kotlin asks for a 512 KiB aligned range; LIMIT_INVALID may reduce the wire part
# to 128 KiB. Keep collecting until the requested range is full or EOF is proven.
amend(range_output,
      """                    .min((FAST_CHUNK / chunk).max(1) as usize)""",
      """                    .min(((origin.saturating_add(if ambient_range { capture_limit as i64 } else { i64::from(FAST_CHUNK) }) - next)
                        .max(1) / i64::from(chunk)).max(1) as usize)
                    .min(if ambient_complete_preview_active() { 1 } else { usize::MAX })""")
amend(range_output,
      """                if stream {
                    break 'download;
                }
                if last_part {""",
      """                if stream {
                    if ambient_range && ambient_complete_preview_active() {
                        // One wire request at a time: an actual short/empty getFile
                        // response proves EOF. Reject holes and a full unknown-size cap.
                        if downloaded_bytes != written_end - origin {
                            return Err(MtprotoError::Message("incomplete captured preview".into()));
                        }
                        if last_part { ambient_note_preview_eof(); }
                        if written_end >= origin.saturating_add(capture_limit as i64)
                            && !last_part && media.file_size.is_none_or(|size| size <= 0) {
                            return Err(MtprotoError::Message("ambient preview requires file sink".into()));
                        }
                    }
                    if !ambient_range || last_part || written_end >= origin.saturating_add(capture_limit as i64)
                        || media.file_size.is_some_and(|size| size > 0 && written_end >= size) {
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
                    if ambient_cdn_ranges() && !ambient_complete_preview_active() {
                        crate::media_cdn::getfile_cdn_fields()
                    } else { (0, None) }
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
# Prefer a usable 320px-class preview to a 40–100px network thumbnail. The
# stripped JPEG stays available for progressive painting. Never guess unindexed letters.
thumb_source = "vendor/mono-rs/crates/mono-proto-media/src/thumbs.rs"
add_before(thumb_source, "pub fn pick_getfile_preview(", """fn ambient_pick_preview(
    sizes: &[(String, i32, Option<Vec<u8>>)],
    exclude: Option<&str>,
) -> Option<(String, Option<Vec<u8>>)> {
    let candidates = || sizes.iter().filter(|(ty, area, bytes)|
        is_getfile_thumb_size(ty) && !thumb_has_inline_bytes(bytes) &&
        *area > 0 && Some(ty.as_str()) != exclude);
    candidates().filter(|(_, area, _)| *area >= 320 * 180)
        .min_by_key(|(_, area, _)| *area)
        .or_else(|| candidates().max_by_key(|(_, area, _)| *area))
        .map(|(ty, _, bytes)| (ty.clone(), bytes.clone()))
}
#[cfg(test)]
mod ambient_preview_tests {
    use super::*;
    #[test]
    fn smallest_adequate_real_network_preview() {
        let sizes = vec![
            ("s".into(), 90 * 60, None),
            ("m".into(), 320 * 180, None),
            ("x".into(), 800 * 450, None),
            ("i".into(), 640 * 480, Some(vec![1])),
        ];
        assert_eq!(ambient_pick_preview(&sizes, None).unwrap().0, "m");
        assert_eq!(ambient_pick_preview(&sizes, Some("m")).unwrap().0, "x");
        assert_eq!(ambient_pick_preview(&sizes[..1], None).unwrap().0, "s");
    }
}
""", "fn ambient_pick_preview(")
amend(thumb_source,
      """pub fn pick_getfile_preview(
    sizes: &[(String, i32, Option<Vec<u8>>)],
) -> Option<(String, Option<Vec<u8>>)> {""",
      """pub fn pick_getfile_preview(
    sizes: &[(String, i32, Option<Vec<u8>>)],
) -> Option<(String, Option<Vec<u8>>)> {
    if crate::profile::ambient_media_mode() {
        return ambient_pick_preview(sizes, None);
    }""")
amend(thumb_source,
      """    full_ty: &str,
) -> Option<(String, Option<Vec<u8>>)> {
    let usable: Vec<&(String, i32, Option<Vec<u8>>)> = sizes""",
      """    full_ty: &str,
) -> Option<(String, Option<Vec<u8>>)> {
    if crate::profile::ambient_media_mode() {
        // Full photo is deliberately excluded. An already selected network
        // thumb may also be the best display size; the inline peek never fetches it.
        return ambient_pick_preview(sizes, Some(full_ty));
    }
    let usable: Vec<&(String, i32, Option<Vec<u8>>)> = sizes""")
# Metadata only: bounded shared dimensions, no file references or image payloads.
add_before(thumb_source, "pub fn pick_getfile_preview(", """type AmbientPreviewId = (bool, i64, i64);
type AmbientPreviewSizes = Vec<(String, i32)>;
#[derive(Default)]
struct AmbientPreviewIndex {
    items: std::collections::HashMap<AmbientPreviewId, AmbientPreviewSizes>,
    order: std::collections::VecDeque<AmbientPreviewId>,
}
fn ambient_preview_index() -> &'static std::sync::Mutex<AmbientPreviewIndex> {
    static INDEX: std::sync::OnceLock<std::sync::Mutex<AmbientPreviewIndex>> = std::sync::OnceLock::new();
    INDEX.get_or_init(Default::default)
}
pub fn ambient_clear_preview_index() {
    if let Ok(mut index) = ambient_preview_index().lock() {
        *index = AmbientPreviewIndex::default();
    }
}
pub fn ambient_register_preview_sizes<'a>(
    document: bool, id: i64, access_hash: i64, sizes: impl Iterator<Item = &'a PhotoSize>,
) {
    if !crate::profile::ambient_media_mode() { return; }
    let key = (document, id, access_hash);
    if ambient_preview_index().lock().ok().is_some_and(|index| index.items.contains_key(&key)) { return; }
    let candidates: AmbientPreviewSizes = sizes.filter_map(|size| {
        let (ty, w, h) = match size {
            PhotoSize::PhotoSize(s) => (&s.type_, s.w, s.h),
            PhotoSize::PhotoSizeProgressive(s) => (&s.type_, s.w, s.h),
            _ => return None,
        };
        (is_getfile_thumb_size(ty) && w > 0 && h > 0).then(|| (ty.clone(), w.saturating_mul(h)))
    }).take(16).collect();
    if candidates.is_empty() { return; }
    if let Ok(mut index) = ambient_preview_index().lock() {
        if index.items.contains_key(&key) { return; }
        while index.items.len() >= 2048 {
            if let Some(old) = index.order.pop_front() { index.items.remove(&old); } else { break; }
        }
        index.order.push_back(key);
        index.items.insert(key, candidates);
    }
}
std::thread_local! {
    static AMBIENT_PREVIEW_TARGET: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}
pub fn ambient_with_preview_target<T>(target: u32, work: impl FnOnce() -> T) -> T {
    let target = match target { 160 | 320 | 640 => target, _ => 320 };
    struct Reset(u32);
    impl Drop for Reset { fn drop(&mut self) { AMBIENT_PREVIEW_TARGET.with(|slot| slot.set(self.0)); } }
    let _reset = Reset(AMBIENT_PREVIEW_TARGET.with(|slot| slot.replace(target)));
    work()
}
fn ambient_pick_indexed_size(sizes: &[(String, i32)], target: u32, exclude: Option<&str>) -> Option<String> {
    let area = (target * target * 9 / 16) as i32;
    let candidates = || sizes.iter().filter(|(ty, _)| Some(ty.as_str()) != exclude);
    candidates().filter(|(_, size)| *size >= area).min_by_key(|(_, size)| *size)
        .or_else(|| candidates().max_by_key(|(_, size)| *size)).map(|(ty, _)| ty.clone())
}
pub fn ambient_indexed_display(media: &crate::media_types::MediaRef) -> Option<crate::media_types::MediaLocation> {
    use crate::media_types::MediaLocation;
    let target = AMBIENT_PREVIEW_TARGET.with(|slot| slot.get());
    if target == 0 { return None; }
    let base = [&media.location, media.display_location.as_ref().unwrap_or(&media.location),
        media.thumb_location.as_ref().unwrap_or(&media.location)].into_iter().find(|loc|
        matches!(loc, MediaLocation::Photo { .. } | MediaLocation::Document { .. }))?;
    let key = match base {
        MediaLocation::Photo { id, access_hash, .. } => (false, *id, *access_hash),
        MediaLocation::Document { id, access_hash, .. } => (true, *id, *access_hash),
        _ => return None,
    };
    let exclude = match &media.location {
        MediaLocation::Photo { thumb_size, .. } => Some(thumb_size.as_str()),
        _ => None,
    };
    let index = ambient_preview_index().lock().ok()?;
    let letter = ambient_pick_indexed_size(index.items.get(&key)?, target, exclude)?;
    crate::location::with_thumb_size(base, &letter)
}
#[cfg(test)]
mod ambient_dynamic_preview_tests {
    use super::*;
    #[test]
    fn measured_size_classes_select_only_known_candidates() {
        let sizes = vec![("s".into(), 160 * 90), ("m".into(), 320 * 180), ("x".into(), 640 * 360)];
        assert_eq!(ambient_pick_indexed_size(&sizes, 160, None).as_deref(), Some("s"));
        assert_eq!(ambient_pick_indexed_size(&sizes, 320, None).as_deref(), Some("m"));
        assert_eq!(ambient_pick_indexed_size(&sizes, 640, None).as_deref(), Some("x"));
        assert_eq!(ambient_pick_indexed_size(&sizes, 640, Some("x")).as_deref(), Some("m"));
    }
}
""", "struct AmbientPreviewIndex")
index_source = "vendor/mono-rs/crates/mono-proto-media/src/index.rs"
amend(index_source,
      """    photo: &mono_proto::latest::api::PhotoConstructor,
    thumb_size: String,
    inline: Option<Vec<u8>>,
) -> MediaLocation {
    if let Some(bytes) = inline {""",
      """    photo: &mono_proto::latest::api::PhotoConstructor,
    thumb_size: String,
    inline: Option<Vec<u8>>,
) -> MediaLocation {
    crate::thumbs::ambient_register_preview_sizes(false, photo.id, photo.access_hash,
        vector_boxed_items(&photo.sizes));
    if let Some(bytes) = inline {""")
amend(index_source,
      """    doc: &mono_proto::latest::api::DocumentConstructor,
    thumb_size: String,
    inline: Option<Vec<u8>>,
) -> MediaLocation {
    if let Some(bytes) = inline {""",
      """    doc: &mono_proto::latest::api::DocumentConstructor,
    thumb_size: String,
    inline: Option<Vec<u8>>,
) -> MediaLocation {
    if let Some(sizes) = doc.thumbs.as_ref() {
        crate::thumbs::ambient_register_preview_sizes(true, doc.id, doc.access_hash, vector_boxed_items(sizes));
    }
    if let Some(bytes) = inline {""")
location_source = "vendor/mono-rs/crates/mono-proto-media/src/location.rs"
amend(location_source,
      """        MediaDownloadKind::Display => {
            if let Some(loc) = &media.display_location""",
      """        MediaDownloadKind::Display => {
            if let Some(loc) = crate::thumbs::ambient_indexed_display(media) {
                let mut copy = media.clone();
                copy.cache_key = format!("{}:display:{}", media.cache_key, location_thumb_size(&loc).unwrap_or(""));
                copy.location = loc;
                copy.file_size = None;
                return Ok(copy);
            }
            if let Some(loc) = &media.display_location""")
client_download = "vendor/mono-rs/crates/mono-proto-client/src/media_download.rs"
add_before(client_download, "pub fn indexed_media_for_download(", """/// Immutable network artwork identity; never reuse an inline or peer-avatar alias.
pub fn ambient_display_identity(handle: u64, chat_id: i64, message_id: i32) -> Option<String> {
    if message_id <= 0 { return None; }
    let client = get_client(handle).ok()?;
    let data = client.data.lock();
    if data.session_dead || data.user_id.is_none() { return None; }
    let indexed = data.media.get(&(chat_id, message_id))?;
    let display = media::media_for_download(indexed, media::MediaDownloadKind::Display).ok()?;
    match &display.location {
        media::MediaLocation::Photo { id, access_hash, thumb_size, .. } =>
            Some(format!("photo:{id}:{access_hash}:{thumb_size}")),
        media::MediaLocation::Document { id, access_hash, thumb_size, .. } =>
            Some(format!("doc:{id}:{access_hash}:{thumb_size}")),
        _ => None,
    }
}
""", "pub fn ambient_display_identity(")
add_before(ffi, "#[uniffi::export]\npub fn download_message_thumb(", """#[uniffi::export]
pub fn ambient_clear_preview_index() {
    proto_media::thumbs::ambient_clear_preview_index();
}
#[uniffi::export]
pub fn ambient_display_identity(handle: u64, chat_id: i64, message_id: i32, target: u32) -> Option<String> {
    proto_media::thumbs::ambient_with_preview_target(target, ||
        mono_proto_client::media_download::ambient_display_identity(handle, chat_id, message_id))
}
""", "pub fn ambient_display_identity(")
amend(client_download,
      "    let dest = Path::new(&dest_path);\n    let first = download_media_range_on_lane",
      """    // Only known large, complete user downloads benefit from a wide pipeline.
    // Preview and unknown-size requests must not send speculative requests past EOF.
    let _small_file = (mono_proto_media::profile::ambient_media_mode()
        && profile == media::DownloadProfile::User
        && media.file_size.is_none_or(|size| size < 2 * 1024 * 1024))
        .then(|| media::bind_download_profile(media::DownloadProfile::Ordinary));
    let dest = Path::new(&dest_path);
    mono_proto_media::download::ambient_prepare_preview(
        media.file_size, media::location_is_inline(&media.location))?;
    let first = download_media_range_on_lane""")
amend(client_download,
      """                drop(admission);
                cdn_or_origin(""",
      """                drop(admission);
                if let Some(start) = offset
                    && mono_proto_media::download::ambient_capture_active(dest) {
                    if mono_proto_media::download::ambient_complete_preview_active() {
                        return Err(MtprotoError::Message("ambient preview requires file sink".into()));
                    }
                    // A reduced wire retry may redirect after the requested start.
                    // Never publish that suffix as if it were the complete range.
                    return cdn_or_origin(
                        || {
                            if job.offset != start {
                                return Err(MtprotoError::Message("CDN range start changed".into()));
                            }
                            let _cdn_admission = client.policy.admit_download(job.dc_id, large)?;
                            follow_cdn_redirect_bounded(handle, api_id, dest, &job, true)
                        },
                        || {
                            mono_proto_runtime::request_control::check()
                                .map_err(|e| MtprotoError::Message(e.to_string()))?;
                            let _admission = client.policy.admit_download(target_dc, large)?;
                            download_media_range_on_lane_dc(
                                handle, client, home, api_id, media, dest, offset, target_dc,
                            )
                        },
                    );
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
    expected_key: String,
) -> Result<Vec<u8>, MtprotoError> {
    download_message_media_window_bytes(handle, chat_id, message_id, dest_path, offset, expected_key, 1)
}

#[uniffi::export]
pub fn download_message_media_window_bytes(
    handle: u64, chat_id: i64, message_id: i32, dest_path: String, offset: i64,
    expected_key: String, parts: u32,
) -> Result<Vec<u8>, MtprotoError> {
    use std::io::Read;
    const PART: usize = 512 * 1024;
    if !(1..=2).contains(&parts) {
        return Err(MtprotoError::Message("invalid media window width".into()));
    }
    let limit = PART * parts as usize;
    if offset < 0 || offset % PART as i64 != 0 {
        return Err(MtprotoError::Message("invalid media range offset".into()));
    }
    let destination = std::path::Path::new(&dest_path);
    let (path, captured) = proto_media::download::ambient_capture_range_bounded(destination, limit as u64, || {
        mono_proto_client::media_download::download_message_media_range_checked(
            handle, chat_id, message_id, dest_path.clone(),
            proto_media::location::MediaDownloadKind::Full, Some(offset),
            if expected_key.is_empty() { None } else { Some(expected_key.as_str()) },
        )
    })?;
    let bytes = if let Some(bytes) = captured { bytes } else {
        if std::path::Path::new(&path) != destination {
            return Err(MtprotoError::Message("unexpected range destination".into()));
        }
        let mut bytes = Vec::with_capacity(limit);
        std::fs::File::open(destination)
            .map_err(|e| MtprotoError::Message(e.to_string()))?
            .take((limit + 1) as u64)
            .read_to_end(&mut bytes)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        bytes
    };
    if bytes.is_empty() || bytes.len() > limit {
        return Err(MtprotoError::Message("invalid captured media range size".into()));
    }
    Ok(bytes)
}
""", "pub fn download_message_media_chunk_bytes(")
# Complete previews reuse the bounded range sink only with an exact native file size.
add_before(ffi, "#[uniffi::export]\npub fn download_message_thumb(", """#[uniffi::export]
pub fn download_message_preview_bytes(
    handle: u64, chat_id: i64, message_id: i32, dest_path: String, target: u32,
) -> Result<Vec<u8>, MtprotoError> {
    let destination = std::path::Path::new(&dest_path);
    proto_media::thumbs::ambient_with_preview_target(target, || {
        proto_media::download::ambient_capture_complete_preview(destination, || {
            mono_proto_client::media_download::download_message_media_range_checked(
                handle, chat_id, message_id, dest_path.clone(),
                proto_media::location::MediaDownloadKind::Display, Some(0), None,
            )
        })
    })
}
#[uniffi::export]
pub fn download_message_preview_file(
    handle: u64, chat_id: i64, message_id: i32, dest_path: String, target: u32,
) -> Result<String, MtprotoError> {
    proto_media::thumbs::ambient_with_preview_target(target, || {
        mono_proto_client::media_download::download_message_media_range_checked(
            handle, chat_id, message_id, dest_path,
            proto_media::location::MediaDownloadKind::Display, None, None,
        )
    })
}

#[cfg(target_os = "android")]
#[allow(unsafe_code)]
#[jni::jni_mangle("com.ambient.tv.data.telegram.AmbientMonogramJni")]
pub extern "system" fn downloadPreview<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _this: jni::objects::JObject<'caller>,
    handle: jni::sys::jlong,
    chat_id: jni::sys::jlong,
    message_id: jni::sys::jint,
    destination: jni::objects::JString<'caller>,
    target: jni::sys::jint,
) -> jni::objects::JByteArray<'caller> {
    unowned_env.with_env(|env| -> jni::errors::Result<_> {
        let path = destination.try_to_string(env)?;
        match download_message_preview_bytes(handle as u64, chat_id, message_id, path, target as u32) {
            Ok(bytes) => env.byte_array_from_slice(&bytes),
            Err(error) => {
                let code = match &error {
                    MtprotoError::UnknownClient => "unknown-client",
                    MtprotoError::PasswordRequired => "password-required",
                    MtprotoError::RegistrationRequired => "registration-required",
                    MtprotoError::Message(message) if message.eq_ignore_ascii_case("cancelled") => "cancelled",
                    MtprotoError::Message(_) => "rpc",
                };
                let description = format!("{code}:{error}");
                let message = jni::strings::JNIString::from(description.as_str());
                env.throw_new(jni::jni_str!("com/ambient/tv/data/telegram/AmbientMonogramJniFailure"), message)?;
                Ok(jni::objects::JByteArray::null())
            }
        }
    }).resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
""", "pub fn download_message_preview_bytes(")
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
    expected_key: jni::objects::JString<'caller>,
    parts: jni::sys::jint,
) -> jni::objects::JByteArray<'caller> {
    unowned_env.with_env(|env| -> jni::errors::Result<_> {
        let path = destination.try_to_string(env)?;
        let expected = expected_key.try_to_string(env)?;
        match download_message_media_window_bytes(handle as u64, chat_id, message_id, path, offset, expected, parts as u32) {
            Ok(bytes) => env.byte_array_from_slice(&bytes),
            Err(error) => {
                let code = match &error {
                    MtprotoError::UnknownClient => "unknown-client",
                    MtprotoError::PasswordRequired => "password-required",
                    MtprotoError::RegistrationRequired => "registration-required",
                    MtprotoError::Message(message) if message.eq_ignore_ascii_case("cancelled") => "cancelled",
                    MtprotoError::Message(_) => "rpc",
                };
                let description = format!("{code}:{error}");
                let message = jni::strings::JNIString::from(description.as_str());
                env.throw_new(jni::jni_str!("com/ambient/tv/data/telegram/AmbientMonogramJniFailure"), message)?;
                Ok(jni::objects::JByteArray::null())
            }
        }
    }).resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
""", "pub extern \"system\" fn downloadMediaRange")
# Write directly into two final JVM part arrays; retain the old symbol as a compatibility fallback.
add_before(ffi, "#[uniffi::export]\\npub fn download_message_thumb(", """#[cfg(target_os = "android")]
#[allow(unsafe_code)]
#[jni::jni_mangle("com.ambient.tv.data.telegram.AmbientMonogramJni")]
pub extern "system" fn downloadMediaParts<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _this: jni::objects::JObject<'caller>,
    handle: jni::sys::jlong,
    chat_id: jni::sys::jlong,
    message_id: jni::sys::jint,
    destination: jni::objects::JString<'caller>,
    offset: jni::sys::jlong,
    expected_key: jni::objects::JString<'caller>,
    first: jni::objects::JByteArray<'caller>,
    second: jni::objects::JByteArray<'caller>,
) -> jni::sys::jint {
    unowned_env.with_env(|env| -> jni::errors::Result<_> {
        const PART: usize = 512 * 1024;
        let first_len = env.get_array_length(&first)? as usize;
        let second_len = env.get_array_length(&second)? as usize;
        let path = destination.try_to_string(env)?;
        let expected = expected_key.try_to_string(env)?;
        let result = if first_len != PART || !(1..=PART).contains(&second_len) {
            Err(MtprotoError::Message("invalid media part array lengths".into()))
        } else {
            download_message_media_window_bytes(handle as u64, chat_id, message_id, path, offset, expected, 2)
                .and_then(|bytes| {
                    if bytes.len() == first_len + second_len { Ok(bytes) }
                    else { Err(MtprotoError::Message("incomplete media window".into())) }
                })
        };
        match result {
            Ok(bytes) => {
                // SAFETY: u8 and JNI jbyte (i8) have identical size/alignment;
                // every bit pattern is valid and the borrowed slice stays live.
                let signed = unsafe { std::slice::from_raw_parts(bytes.as_ptr().cast::<jni::sys::jbyte>(), bytes.len()) };
                env.set_byte_array_region(&first, 0, &signed[..first_len])?;
                env.set_byte_array_region(&second, 0, &signed[first_len..])?;
                Ok(bytes.len() as jni::sys::jint)
            }
            Err(error) => {
                let code = match &error {
                    MtprotoError::UnknownClient => "unknown-client",
                    MtprotoError::PasswordRequired => "password-required",
                    MtprotoError::RegistrationRequired => "registration-required",
                    MtprotoError::Message(message) if message.eq_ignore_ascii_case("cancelled") => "cancelled",
                    MtprotoError::Message(_) => "rpc",
                };
                let description = format!("{code}:{error}");
                env.throw_new(jni::jni_str!("com/ambient/tv/data/telegram/AmbientMonogramJniFailure"), jni::strings::JNIString::from(description.as_str()))?;
                Ok(0)
            }
        }
    }).resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
""", "pub extern \"system\" fn downloadMediaParts")
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
      """    if auth_key.len() != 256 {
        return Err(Error::InvalidLength {
            kind: "auth key",
            actual: auth_key.len(),
            expected: 256,
        });
    }
    if packet.len() < 24 + 48""",
      """    decrypt_message_owned(auth_key, packet.to_vec(), direction, expected_session_id, max_body_length)
}

/// Decrypt an owned transport packet without allocating a second ciphertext-sized buffer.
pub fn decrypt_message_owned(
    auth_key: &[u8],
    mut packet: Vec<u8>,
    direction: Direction,
    expected_session_id: i64,
    max_body_length: usize,
) -> Result<DecryptedMessage, Error> {
    if auth_key.len() != 256 {
        return Err(Error::InvalidLength {
            kind: "auth key",
            actual: auth_key.len(),
            expected: 256,
        });
    }
    if packet.len() < 24 + 48""")
amend(crypto_message,
      "    let plaintext = aes_ige_decrypt(&packet[24..], &aes_key, &aes_iv)?;",
      """    crate::aes_ige_decrypt_in_place(&mut packet[24..], &aes_key, &aes_iv)?;
    let plaintext = &packet[24..];""")
amend(crypto_message,
      """    Ok(DecryptedMessage {
        server_salt,""",
      """    // Authentication and all envelope checks precede reuse of the allocation.
    let padding = plaintext[body_end..].to_vec();
    packet.copy_within(24 + 32..24 + body_end, 0);
    packet.truncate(body_length);
    Ok(DecryptedMessage {
        server_salt,""")
amend(crypto_message,
      """        body: plaintext[32..body_end].to_vec(),
        padding: plaintext[body_end..].to_vec(),""",
      """        body: packet,
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
      '''                        pb.environment()["CARGO_BUILD_JOBS"] =
                            System.getenv("CARGO_BUILD_JOBS")?.takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true }
                                ?: jobs.toString()
                        // Android-only flags: also align 32-bit ELF LOAD segments.
                        val alignment = "-C link-arg=-Wl,-z,max-page-size=16384"
                        val encoded = pb.environment()["CARGO_ENCODED_RUSTFLAGS"]
                        if (encoded != null && androidRustFlags.isBlank()) {
                            pb.environment()["CARGO_ENCODED_RUSTFLAGS"] =
                                listOf(encoded, "-C", "link-arg=-Wl,-z,max-page-size=16384")
                                    .filter { it.isNotEmpty() }.joinToString(31.toChar().toString())
                        } else {
                            // CPU-specific ABI validation is performed by the builder.
                            // PGO flags may target any explicitly selected single ABI.
                            pb.environment()["RUSTFLAGS"] =
                                listOf(androidRustFlags.ifBlank { pb.environment()["RUSTFLAGS"].orEmpty() }, alignment)
                                    .filter { it.isNotBlank() }.joinToString(" ")
                            pb.environment().remove("CARGO_ENCODED_RUSTFLAGS")
                        }''')


# The bounded range sink does not refill a wire batch. Decode the final
# responses exactly once and keep the streaming callback for full downloads.
api_invoke = "vendor/mono-rs/crates/mono-proto-runtime/src/api_invoke.rs"
amend(api_invoke,
      """    invoke_api_batch_without_updates_streaming(snapshot, api_id, requests, &mut |_, _| None)
}""",
      """    invoke_api_batch_impl(snapshot, api_id, requests, None, None, false)
}""")
amend(api_invoke,
      """    if requests.is_empty() {
        return Ok(Vec::new());
    }
    let replay_safe = replay_safe_method(Req::CONSTRUCTOR_ID);
    let span = crate::perf::span("rpc:pipeline");""",
      """    invoke_api_batch_impl(snapshot, api_id, requests, Some(on_chunk), None, false)
}

pub fn invoke_api_batch_without_updates_streaming_borrowed<Req, Res>(
    snapshot: &mut Snapshot, api_id: i32, requests: Vec<Req>,
    on_chunk: &mut dyn FnMut(usize, &Result<Res, MtprotoError>) -> Option<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>
where Req: TlEncode + Boxed + Clone, Res: BoxedDecode {
    invoke_api_batch_impl(snapshot, api_id, requests, None, Some(on_chunk), false)
}

// The dual-lane coordinator already owns every decoded response sent over its
// channel. Do not decode the raw return vector a second time just to discard it.
pub fn invoke_api_batch_without_updates_streaming_consumed<Req, Res>(
    snapshot: &mut Snapshot, api_id: i32, requests: Vec<Req>,
    on_chunk: &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>
where Req: TlEncode + Boxed + Clone, Res: BoxedDecode {
    invoke_api_batch_impl(snapshot, api_id, requests, Some(on_chunk), None, true)
}

fn invoke_api_batch_impl<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    requests: Vec<Req>,
    mut on_chunk: Option<&mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>>,
    mut borrowed: Option<&mut dyn FnMut(usize, &Result<Res, MtprotoError>) -> Option<Req>>,
    discard_results: bool,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    if requests.is_empty() {
        return Ok(Vec::new());
    }
    let replay_safe = replay_safe_method(Req::CONSTRUCTOR_ID);
    let span = crate::perf::span("rpc:pipeline");""")
amend(api_invoke,
      """        &mut |index, raw_res| {
            let res = match raw_res {""",
      """        &mut |index, raw_res| {
            if on_chunk.is_none() && borrowed.is_none() { return None; }
            let res = match raw_res {""")
media_lane = "vendor/mono-rs/crates/mono-proto-client/src/media_lane.rs"
amend(media_lane,
      """                    invoke_media_batch_even_odd(
                        client,
                        &mut media_snap,""",
      """                    if offset.is_some() && mono_proto_media::download::ambient_capture_active(dest) {
                        // Ranges never refill; the final response loop validates and
                        // writes each part exactly once, including LIMIT_INVALID retries.
                        return with_client_transport(client, &mut slot, || {
                            mono_proto_runtime::api_invoke::invoke_api_batch_without_updates(
                                &mut media_snap, api_id, requests,
                            )
                        });
                    }
                    invoke_media_batch_even_odd(
                        client,
                        &mut media_snap,""")
amend(client_download,
      """                        mono_proto_runtime::api_invoke::invoke_api_batch_without_updates_streaming::<
                            _,
                            mono_proto::latest::api::UploadFile,
                        >(&mut state.snapshot, api_id, requests, on_chunk)""",
      """                        if offset.is_some() && mono_proto_media::download::ambient_capture_active(dest) {
                            mono_proto_runtime::api_invoke::invoke_api_batch_without_updates::<
                                _, mono_proto::latest::api::UploadFile,
                            >(&mut state.snapshot, api_id, requests)
                        } else {
                            mono_proto_runtime::api_invoke::invoke_api_batch_without_updates_streaming_borrowed::<
                                _, mono_proto::latest::api::UploadFile,
                            >(&mut state.snapshot, api_id, requests, on_chunk)
                        }""")
amend(range_output,
      """                if download_cancelled(cancellation_path) {
                    return Err(MtprotoError::Message("cancelled".into()));
                }
                if should_pause_optional(request_profile()) {""",
      """                if ambient_range_cancelled(cancellation_path) {
                    return Err(MtprotoError::Message("cancelled".into()));
                }
                if should_pause_optional(request_profile()) {""")
amend(range_output,
      """                for (index, response) in responses.into_iter().enumerate() {
                    if download_cancelled(cancellation_path) {""",
      """                for (index, response) in responses.into_iter().enumerate() {
                    if ambient_range_cancelled(cancellation_path) {""")
# Reuse one 64 KiB receive scratch allocation per native thread. Taking it
# from TLS permits nested calls without a RefCell borrow spanning network IO.
framing = "vendor/mono-rs/crates/mono-proto-runtime/src/rpc/framing.rs"
add_before(framing, "pub fn recv_framed(", """thread_local! {
    static AMBIENT_RECV_SCRATCH: std::cell::RefCell<Option<Vec<u8>>> =
        const { std::cell::RefCell::new(None) };
}
// Only reserve after a substantial frame prefix is present and the framing
// decoder has validated its declared length. Never preallocate arbitrary headers.
fn ambient_reserve_frame(input: &mut Vec<u8>, needed: usize, available: usize) -> Result<(), MtprotoError> {
    const MIN_PREFIX: usize = 64 * 1024;
    const MAX_RESERVE: usize = 2 * 1024 * 1024;
    if available == input.len() && available >= MIN_PREFIX && needed <= MAX_RESERVE
        && needed > input.capacity() && needed.saturating_sub(available) >= MIN_PREFIX {
        input.try_reserve_exact(needed - available)
            .map_err(|_| MtprotoError::Message("media frame allocation failed".into()))?;
    }
    Ok(())
}
#[cfg(test)]
mod ambient_recv_reserve_tests {
    use super::*;
    #[test]
    fn only_validated_substantial_bounded_prefix_reserves() {
        let mut input = vec![0; 4];
        ambient_reserve_frame(&mut input, 512 * 1024, 4).unwrap();
        assert!(input.capacity() < 512 * 1024);
        input.resize(64 * 1024, 0);
        let before = input.capacity();
        ambient_reserve_frame(&mut input, 3 * 1024 * 1024, 64 * 1024).unwrap();
        assert_eq!(input.capacity(), before);
        ambient_reserve_frame(&mut input, 512 * 1024, 64 * 1024).unwrap();
        assert!(input.capacity() >= 512 * 1024);
        assert_eq!(input.len(), 64 * 1024);
    }
}
struct AmbientRecvScratch(Vec<u8>);
impl AmbientRecvScratch {
    fn take() -> Self {
        Self(AMBIENT_RECV_SCRATCH.with(|slot| slot.borrow_mut().take())
            .unwrap_or_else(|| vec![0_u8; 64 * 1024]))
    }
}
impl Drop for AmbientRecvScratch {
    fn drop(&mut self) {
        let bytes = std::mem::take(&mut self.0);
        let _ = AMBIENT_RECV_SCRATCH.try_with(|slot| {
            if slot.borrow().is_none() {
                *slot.borrow_mut() = Some(bytes);
            }
        });
    }
}
""", "struct AmbientRecvScratch")
amend(framing, "    let mut buf = vec![0_u8; 64 * 1024];",
      "    let mut scratch = AmbientRecvScratch::take();\n    let buf = &mut scratch.0;")
amend(framing, "                let n = match conn.receive(&mut buf) {",
      "                ambient_reserve_frame(input, needed, available)?;\n                let n = match conn.receive(buf.as_mut_slice()) {")
# Safe in-place IGE: retain original APIs, use ownership for the envelope.
crypto_symmetric = "vendor/mono-rs/crates/mono-proto-crypto/src/symmetric.rs"
add_before(crypto_symmetric, "/// Apply AES-256-CTR in place", """/// Encrypt validated complete AES-IGE blocks in place.
pub fn aes_ige_encrypt_in_place(payload: &mut [u8], key: &[u8], iv: &[u8]) -> Result<(), Error> {
    validate_ige(payload, iv)?;
    let cipher = aes(key)?;
    let mut previous_ciphertext = <[u8; 16]>::try_from(&iv[..16]).expect("validated IV");
    let mut previous_plaintext = <[u8; 16]>::try_from(&iv[16..]).expect("validated IV");
    for plaintext in payload.as_chunks_mut::<16>().0 {
        let original = *plaintext;
        let block = (u128::from_ne_bytes(original) ^ u128::from_ne_bytes(previous_ciphertext)).to_ne_bytes();
        let mut aes_block = Block::<Aes256>::from(block);
        cipher.encrypt_block(&mut aes_block);
        let block: [u8; 16] = aes_block.into();
        let block = (u128::from_ne_bytes(block) ^ u128::from_ne_bytes(previous_plaintext)).to_ne_bytes();
        *plaintext = block;
        previous_ciphertext = block;
        previous_plaintext = original;
    }
    Ok(())
}
/// Decrypt validated complete AES-IGE blocks in place.
pub fn aes_ige_decrypt_in_place(payload: &mut [u8], key: &[u8], iv: &[u8]) -> Result<(), Error> {
    validate_ige(payload, iv)?;
    let cipher = aes(key)?;
    let mut previous_ciphertext = <[u8; 16]>::try_from(&iv[..16]).expect("validated IV");
    let mut previous_plaintext = <[u8; 16]>::try_from(&iv[16..]).expect("validated IV");
    for ciphertext in payload.as_chunks_mut::<16>().0 {
        let original = *ciphertext;
        let block = (u128::from_ne_bytes(original) ^ u128::from_ne_bytes(previous_plaintext)).to_ne_bytes();
        let mut aes_block = Block::<Aes256>::from(block);
        cipher.decrypt_block(&mut aes_block);
        let block: [u8; 16] = aes_block.into();
        let block = (u128::from_ne_bytes(block) ^ u128::from_ne_bytes(previous_ciphertext)).to_ne_bytes();
        *ciphertext = block;
        previous_plaintext = block;
        previous_ciphertext = original;
    }
    Ok(())
}

#[cfg(test)]
mod ambient_ige_tests {
    #[test]
    fn in_place_matches_existing_ige() {
        let key = [19_u8; 32];
        let iv = [42_u8; 32];
        let plain: Vec<u8> = (0..512 * 1024).map(|i| (i % 251) as u8).collect();
        let expected = super::aes_ige_encrypt(&plain, &key, &iv).unwrap();
        let mut actual = plain.clone();
        super::aes_ige_encrypt_in_place(&mut actual, &key, &iv).unwrap();
        assert_eq!(actual, expected);
        super::aes_ige_decrypt_in_place(&mut actual, &key, &iv).unwrap();
        assert_eq!(actual, plain);
        assert!(super::aes_ige_decrypt_in_place(&mut [0_u8; 15], &key, &iv).is_err());
    }
}
""", "pub fn aes_ige_encrypt_in_place(")
amend(crypto_message,
      "    let mut plaintext = Vec::with_capacity(32 + message.body.len() + message.padding.len());",
      """    let mut plaintext = Vec::with_capacity(24 + 32 + message.body.len() + message.padding.len());
    plaintext.resize(24, 0);""")
amend(crypto_message,
      """    let msg_key = message_key(auth_key, &plaintext, direction)?;
    let (aes_key, aes_iv) = message_aes_key_iv(auth_key, &msg_key, direction)?;
    let encrypted = aes_ige_encrypt(&plaintext, &aes_key, &aes_iv)?;
    let mut packet = Vec::with_capacity(24 + encrypted.len());
    packet.extend_from_slice(&auth_key_id(auth_key).to_le_bytes());
    packet.extend_from_slice(&msg_key);
    packet.extend_from_slice(&encrypted);
    Ok(packet)""",
      """    let msg_key = message_key(auth_key, &plaintext[24..], direction)?;
    let (aes_key, aes_iv) = message_aes_key_iv(auth_key, &msg_key, direction)?;
    crate::aes_ige_encrypt_in_place(&mut plaintext[24..], &aes_key, &aes_iv)?;
    plaintext[..8].copy_from_slice(&auth_key_id(auth_key).to_le_bytes());
    plaintext[8..24].copy_from_slice(&msg_key);
    Ok(plaintext)""")


# Warm same-DC media lanes first, with existing gate fairness and lease checks.
lanes = "vendor/mono-rs/crates/mono-proto-client/src/lanes.rs"
amend(lanes,
      """pub fn lock_media_lane(client: &Client) -> Result<LaneLease<'_>, MtprotoError> {
    let class = scheduler::current_class();""",
      """pub fn lock_media_lane(client: &Client) -> Result<LaneLease<'_>, MtprotoError> {
    lock_media_lane_for_dc(client, None)
}

pub fn lock_media_lane_for_dc(client: &Client, dc: Option<i32>) -> Result<LaneLease<'_>, MtprotoError> {
    let class = scheduler::current_class();""")
amend(lanes,
      """                if !waiter.is_next() {
                    waiter.wait();
                    continue;
                }
                let open = client.media_open.load(Ordering::Acquire).min(cap);
                for lane in client.media.iter().take(open) {
                    if let Some(lease) = lane.try_lease(class) {""",
      """                let promoted = mono_proto_runtime::request_control::promoted_class()
                    .filter(|p| p.priority() < class.priority() && scheduler::family(*p) == scheduler::LaneFamily::Media)
                    .unwrap_or(class);
                if promoted != class { waiter.promote(promoted); }
                if !waiter.is_next() {
                    waiter.wait();
                    continue;
                }
                let open = client.media_open.load(Ordering::Acquire).min(cap);
                // Probe without holding the lane lock across acquisition.
                if let Some(dc) = dc {
                    for lane in client.media.iter().take(open) {
                        let warm = lane.io.try_lock().is_some_and(|io| io.snapshot.dc_id == dc && io.transport.is_some());
                        if warm && let Some(lease) = lane.try_lease(promoted) {
                            return Ok(lease);
                        }
                    }
                }
                for lane in client.media.iter().take(open) {
                    if let Some(lease) = lane.try_lease(promoted) {""")
amend(media_lane, "    let mut io = lock_media_lane(client)?;",
      "    let mut io = crate::lanes::lock_media_lane_for_dc(client, Some(target_dc))?;")
transfer_policy = "vendor/mono-rs/crates/mono-proto-runtime/src/transfer_policy.rs"
amend(transfer_policy,
      """            let limits = *self.queue_limits.lock();
            let limit = if large { limits.1.min(2) } else { limits.0 };""",
      """            if let Some(promoted) = crate::request_control::promoted_class()
                && let Some(waiter) = active.waiters.iter_mut().find(|waiter| waiter.ticket == ticket) {
                waiter.priority = waiter.priority.min(promoted.priority());
            }
            let limits = *self.queue_limits.lock();
            let limit = if large { limits.1.min(2) } else { limits.0 };""")


engine_rpc = "vendor/mono-rs/crates/mono-proto-engine/src/rpc.rs"
amend(engine_rpc,
      """        let message = mono_proto_crypto::decrypt_message(
            auth_key,
            packet,""",
      """        let message = mono_proto_crypto::decrypt_message_owned(
            auth_key,
            packet,""")
amend(engine_rpc,
      """    ) -> Result<InboundMessage, Error> {
        let auth_key = self""",
      """    ) -> Result<InboundMessage, Error> {
        self.open_inbound_owned(packet.to_vec(), clock, check_time, max_body_length)
    }

    pub fn open_inbound_owned(
        &mut self,
        packet: Vec<u8>,
        clock: &impl Clock,
        check_time: bool,
        max_body_length: usize,
    ) -> Result<InboundMessage, Error> {
        let auth_key = self""")
add_before(engine_rpc,
      "    /// Decode and consume a typed response.",
      """    /// The raw RPC adapter needs the original byte vector, not a decoded copy.
    pub fn take_raw_response(&mut self, handle: &RequestHandle<Vec<u8>>) -> Option<Vec<u8>> {
        self.responses.remove(&handle.message_id)
    }
""", "pub fn take_raw_response(")
inbound = "vendor/mono-rs/crates/mono-proto-runtime/src/rpc/inbound.rs"
amend(inbound,
      "    let mut validated = snapshot.clone();",
      """    // Ordinary results never mutate child replay state. Only wrappers need
    // transactional validation of all children before committing the snapshot.
    if let Some(ctor) = peek_u32(body, 0)
        && ctor != MSG_CONTAINER && ctor != MSG_COPY && ctor != GZIP_PACKED {
        return parse_service_at_depth(body, 0);
    }
    let mut validated = snapshot.clone();""")
add_before(inbound, "pub fn parse_authenticated_inner(", """pub fn parse_authenticated_owned(
    mut body: Vec<u8>,
    outer_id: i64,
    snapshot: &mut Snapshot,
    clock: &impl Clock,
) -> Result<Vec<InboundEvent>, MtprotoError> {
    if peek_u32(&body, 0) != Some(RPC_RESULT) {
        return parse_authenticated(&body, outer_id, snapshot, clock);
    }
    let mut decoder = Decoder::new(&body, Limits::default())
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    decoder.read_u32().map_err(|e| MtprotoError::Message(e.to_string()))?;
    let req_msg_id = <i64 as TlDecode>::decode(&mut decoder)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let offset = decoder.offset();
    let result = if peek_u32(&body, offset) == Some(GZIP_PACKED) {
        ungzip_if_needed(&body[offset..])?
    } else {
        let len = body.len() - offset;
        body.copy_within(offset.., 0);
        body.truncate(len);
        body
    };
    Ok(vec![InboundEvent::RpcResult { req_msg_id, body: result }])
}
""", "pub fn parse_authenticated_owned(")
rpc_invoke = "vendor/mono-rs/crates/mono-proto-runtime/src/rpc/invoke.rs"
amend(rpc_invoke, "    parse_authenticated, should_process_inbound,",
      "    parse_authenticated_owned, should_process_inbound,")
amend(rpc_invoke,
      """                if let Some(response) = engine
                    .take_response::<RawMethod>(handle)
                    .map_err(|e| MtprotoError::Message(e.to_string()))?
                {""",
      """                if let Some(response) = engine.take_raw_response(handle) {""")
amend(rpc_invoke,
      """            if let Some(response) = engine
                .take_response::<RawMethod>(&handle)
                .map_err(|e| MtprotoError::Message(e.to_string()))?
            {""",
      """            if let Some(response) = engine.take_raw_response(&handle) {""")
amend_count(rpc_invoke,
      """        let packet = trim_padded_mtproto_packet(&packet);
        let inbound = engine
            .open_inbound(packet, clock, true, 1024 * 1024)""",
      """        let mut packet = packet;
        let length = trim_padded_mtproto_packet(&packet).len();
        packet.truncate(length);
        let inbound = engine
            .open_inbound_owned(packet, clock, true, 1024 * 1024)""", 2)
amend_count(rpc_invoke,
      """        for event in parse_authenticated(
            &body,""",
      """        for event in parse_authenticated_owned(
            body,""", 2)
amend(rpc_invoke,
      """    let inbound = engine
        .open_inbound(
            trim_padded_mtproto_packet(&packet),""",
      """    let mut packet = packet;
    let length = trim_padded_mtproto_packet(&packet).len();
    packet.truncate(length);
    let inbound = engine
        .open_inbound_owned(
            packet,""")
amend(rpc_invoke,
      """    for event in parse_authenticated(
        &inbound.message.body,""",
      """    for event in parse_authenticated_owned(
        inbound.message.body,""")
# Avoid redundant timeout configuration only after a successful socket update.
# Track the actual configured timeout independently of the logical read deadline.
tcp = "vendor/mono-rs/crates/mono-proto-runtime/src/connection/tcp.rs"
amend(tcp,
      """pub struct ObfuscatedTcp {
    inner: NativeTransportConnection,
    stream: Arc<TcpStream>,
    read_timeout: Duration,
}""",
      """pub struct ObfuscatedTcp {
    inner: NativeTransportConnection,
    stream: Arc<TcpStream>,
    read_timeout: Duration,
    configured_timeout_ms: Option<u64>,
}""")
amend(tcp,
      """        self.read_timeout = Duration::from_millis(ms.max(50));
        let read = self.read_timeout;""",
      """        self.read_timeout = Duration::from_millis(ms.max(50));
        if self.configured_timeout_ms == Some(ms) { return; }
        let read = self.read_timeout;""")
amend(tcp,
      """        let _ = self.stream.set_read_timeout(Some(read));
    }
}""",
      """        if self.stream.set_read_timeout(Some(read)).is_ok() {
            self.configured_timeout_ms = Some(ms);
        } else {
            self.configured_timeout_ms = None;
        }
    }
}""")
amend(tcp,
      """        read_timeout: Duration::from_secs(connect_secs.max(1)),
    };""",
      """        read_timeout: Duration::from_secs(connect_secs.max(1)),
        configured_timeout_ms: None,
    };""")


amend(range_output,
      """                            out.write_all(&bytes)
                                .map_err(|e| MtprotoError::Message(e.to_string()))?;
                            written_end = written_end.max(part_offset + bytes.len() as i64);
                            if counted_offsets.insert(part_offset) {
                                downloaded_bytes += bytes.len() as i64;
                                notify_progress(&target_str, downloaded_bytes, 0);
                            }
                            if bytes.len() < chunk as usize {""",
      """                            let len = bytes.len();
                            out.write_owned(bytes)
                                .map_err(|e| MtprotoError::Message(e.to_string()))?;
                            written_end = written_end.max(part_offset + len as i64);
                            if counted_offsets.insert(part_offset) {
                                downloaded_bytes += len as i64;
                                if ambient_range {
                                    crate::transfer_policy::current().floods.lock()
                                        .download_succeeded(dc_id, std::time::Instant::now());
                                }
                                notify_progress(&target_str, downloaded_bytes, 0);
                            }
                            if len < chunk as usize {""")

upstream_settings = "settings.gradle.kts"
amend(upstream_settings,
      '    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"',
      '    // Ambient TV provisions the required JDK before invoking Gradle.')
# The selected native library has no Gradle project dependencies. Avoid configuring
# the whole Android app or resolving its unrelated plugin markers, including Compose.
amend(upstream_settings,
      """include(":app")

include(":core:common")
include(":core:models")
include(":core:ui")
include(":core:database")
include(":core:markup")

include(":feature:auth")
include(":feature:chats")
include(":feature:dialog")
include(":feature:folders")
include(":feature:profile")
include(":feature:settings")

include(":network:http")
include(":network:bridge")

include(":native:mtproto")
include(":native:markup")""",
      '// Ambient TV: only the native library participates in this build.\ninclude(":native:mtproto")')
amend("build.gradle.kts",
      """plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
}

// Compose compiler stability/skipping reports for the recomposition work, off unless asked for:
//   ./gradlew.bat :core:ui:compileDebugKotlin :feature:chats:compileDebugKotlin -PcomposeReports
// Writes <module>/build/compose-reports/*-composables.txt and *-classes.txt.
subprojects {
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        if (providers.gradleProperty("composeReports").isPresent) {
            extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
                reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
                metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
            }
        }
    }
}""",
      """plugins {
    alias(libs.plugins.android.library) apply false
}""")


# Opt-in bounded CDN receiver. No whole-file fallback for a range. Every byte
# is decrypted at its absolute offset and covered by the server's SHA-256 hashes.
amend(media_lane,
      """    if job.offset != 0 {
        return Err(MtprotoError::Message("CDN resume requires origin download".into()));
    }
    let client = get_client(handle)?;""",
      """    follow_cdn_redirect_bounded(handle, api_id, dest, job, false)
}

fn follow_cdn_redirect_bounded(
    handle: u64, api_id: i32, dest: &Path, job: &media::CdnRedirect, range: bool,
) -> Result<String, MtprotoError> {
    if (!range && job.offset != 0) || job.offset < 0 ||
        (range && job.offset % mono_proto_media::download::AMBIENT_RANGE_LIMIT as i64 != 0) {
        return Err(MtprotoError::Message("CDN resume requires origin download".into()));
    }
    let client = get_client(handle)?;""")
amend(media_lane,
      """            receive_cdn_parts(job, dest, &staged, |offset| {""",
      """            receive_cdn_parts_dispatch(job, dest, &staged, range, |offset| {""")
add_before(media_lane, "fn receive_cdn_parts(", """fn receive_cdn_parts_dispatch(
    job: &media::CdnRedirect,
    dest: &Path,
    staged: &media::StagedDownload,
    range: bool,
    fetch: impl FnMut(i64) -> Result<Result<Vec<u8>, Vec<u8>>, MtprotoError>,
    reupload: impl FnMut(Vec<u8>) -> Result<(), MtprotoError>,
) -> Result<(), MtprotoError> {
    if range { receive_cdn_parts_bounded(job, dest, staged, true, fetch, reupload) }
    else { receive_cdn_parts(job, dest, staged, fetch, reupload) }
}
fn receive_cdn_parts_bounded(
    job: &media::CdnRedirect,
    dest: &Path,
    staged: &media::StagedDownload,
    range: bool,
    mut fetch: impl FnMut(i64) -> Result<Result<Vec<u8>, Vec<u8>>, MtprotoError>,
    mut reupload: impl FnMut(Vec<u8>) -> Result<(), MtprotoError>,
) -> Result<(), MtprotoError> {
    use std::io::{Seek, SeekFrom, Write};
    let mut memory = Vec::new();
    let mut out = if range { None } else { Some(std::fs::File::create(staged.path())
        .map_err(|e| MtprotoError::Message(e.to_string()))?) };
    let limit = mono_proto_media::download::ambient_range_capacity(dest) as usize;
    let mut offset = job.offset;
    loop {
        if range && memory.len() == limit { break; }
        if media::download_cancelled(dest) || mono_proto_runtime::request_control::check().is_err() {
            return Err(MtprotoError::Message("cancelled".into()));
        }
        let mut reuploads = 0;
        let mut part = loop {
            if media::download_cancelled(dest) || mono_proto_runtime::request_control::check().is_err() {
                return Err(MtprotoError::Message("cancelled".into()));
            }
            match fetch(offset)? {
                Ok(bytes) => break bytes,
                Err(token) => {
                    if reuploads >= 3 {
                        return Err(MtprotoError::Message("CDN reupload limit exceeded".into()));
                    }
                    reuploads += 1;
                    reupload(token)?;
                }
            }
        };
        if media::download_cancelled(dest) || mono_proto_runtime::request_control::check().is_err() {
            return Err(MtprotoError::Message("cancelled".into()));
        }
        media::decrypt_cdn_part(&job.encryption_key, &job.encryption_iv, offset, &mut part)?;
        if part.len() > media::CHUNK as usize || !media::verify_cdn_part(&job.file_hashes, offset, &part) {
            return Err(MtprotoError::Message("CDN hash verification failed".into()));
        }
        if let Some(out) = out.as_mut() {
            out.seek(SeekFrom::Start(offset as u64)).map_err(|e| MtprotoError::Message(e.to_string()))?;
            out.write_all(&part).map_err(|e| MtprotoError::Message(e.to_string()))?;
        } else {
            if memory.len().saturating_add(part.len()) > limit {
                return Err(MtprotoError::Message("CDN range exceeds limit".into()));
            }
            memory.extend_from_slice(&part);
        }
        if part.len() < media::CHUNK as usize { break; }
        offset = offset.checked_add(part.len() as i64)
            .ok_or_else(|| MtprotoError::Message("CDN offset overflow".into()))?;
        media::notify_progress(&dest.display().to_string(), offset, 0);
    }
    if let Some(mut out) = out {
        out.flush().map_err(|e| MtprotoError::Message(e.to_string()))
    } else {
        mono_proto_media::download::ambient_stage_range(dest, staged.path(), memory);
        Ok(())
    }
}

""", "fn receive_cdn_parts_dispatch(")
add_before(ffi, "#[uniffi::export]\npub fn set_download_concurrency(", """#[uniffi::export]
pub fn set_ambient_cdn_ranges(enabled: bool) {
    proto_media::download::set_ambient_cdn_ranges(enabled);
}
""", "pub fn set_ambient_cdn_ranges(")


transport_framing = "vendor/mono-rs/crates/mono-proto-transport/src/framing.rs"
add_before(transport_framing,
      "    /// Encode with explicit padding for deterministic and official fixtures.",
      """    /// Consume an owned receive buffer with the same checks as Framing::decode.
    /// A large complete packet moves its allocation; a small frame preceding
    /// a large suffix takes the existing copying path to avoid moving the suffix.
    pub fn decode_consuming(&mut self, input: &mut Vec<u8>) -> Result<Packet, Error> {
        if input.len() < 4 {
            return Err(Error::Incomplete { needed: 4, available: input.len() });
        }
        let length = u32::from_le_bytes(input[..4].try_into().expect("four bytes")) as usize;
        let maximum = self.max_packet + 15;
        checked_length(length, maximum)?;
        let total = 4_usize.checked_add(length).ok_or(Error::InvalidLength { length, maximum })?;
        if input.len() < total {
            return Err(Error::Incomplete { needed: total, available: input.len() });
        }
        let suffix_len = input.len() - total;
        if length >= 64 * 1024 && suffix_len <= length / 4 {
            let suffix = input.split_off(total);
            let mut payload = std::mem::replace(input, suffix);
            payload.copy_within(4..total, 0);
            payload.truncate(length);
            Ok(Packet { payload, consumed: total })
        } else {
            let decoded = packet(input, 4, length, maximum)?;
            input.drain(..total);
            Ok(decoded)
        }
    }

""", "pub fn decode_consuming(")
amend_count(framing,
      """        match framing.decode(input) {
            Ok(packet) => {
                let consumed = packet.consumed;
                let payload = packet.payload;
                input.drain(..consumed);""",
      """        match framing.decode_consuming(input) {
            Ok(packet) => {
                let payload = packet.payload;""", 2)
add_before(transport_framing, "impl Intermediate {", """#[cfg(test)]
mod ambient_owned_framing_tests {
    use super::*;
    #[test]
    fn owned_decode_retains_suffix_and_reuses_large_allocation() {
        let mut framing = PaddedIntermediate::default();
        let data = vec![7; 512 * 1024];
        let mut input = framing.encode_with_padding(&data, &[1, 2]).unwrap();
        let suffix = framing.encode_with_padding(&[3, 4, 5, 6], &[]).unwrap();
        input.extend_from_slice(&suffix);
        let before = input.as_ptr();
        let packet = framing.decode_consuming(&mut input).unwrap();
        assert_eq!(packet.payload.as_ptr(), before);
        assert_eq!(&packet.payload[..data.len()], data.as_slice());
        assert_eq!(&packet.payload[data.len()..], &[1, 2]);
        assert_eq!(input, suffix);
        assert_eq!(framing.decode_consuming(&mut input).unwrap().payload, vec![3,4,5,6]);
        assert!(input.is_empty());
    }
    #[test]
    fn incomplete_and_invalid_length_do_not_consume() {
        let mut framing = PaddedIntermediate::new(1024);
        for mut input in [vec![1,2], vec![8,0,0,0,1], vec![0,0,0,0], vec![0xff;4]] {
            let original = input.clone();
            assert!(framing.decode_consuming(&mut input).is_err());
            assert_eq!(input, original);
        }
    }
}
""", "mod ambient_owned_framing_tests")


# A successfully completed RPC has reached a verified frame boundary. Remove
# that socket (not the whole shared request binding) before returning its lane
# to the pool. A late cancellation must not shut down a healthy parked socket.
socket_tcp = "vendor/mono-rs/crates/mono-proto-socket/src/tcp.rs"
add_before(socket_tcp, "    pub fn detach_sockets(&self) {", """    pub fn detach_socket(&self, stream: &Arc<TcpStream>) -> Result<(), TransportError> {
        let mut state = self.state.lock();
        if state.closed || state.closing { return Err(closed_error()); }
        let weak = Arc::downgrade(stream);
        state.streams.retain(|entry| !entry.ptr_eq(&weak));
        Ok(())
    }
""", "pub fn detach_socket(&self,")
request_control = "vendor/mono-rs/crates/mono-proto-runtime/src/request_control.rs"
add_before(request_control, "pub fn detach_sockets() {", """pub fn detach_socket(stream: &Arc<TcpStream>) -> Result<(), Error> {
    CURRENT.with(|cell| match &cell.borrow().1 {
        Some(control) => control.detach_socket(stream),
        None => Ok(()),
    }).map_err(|_| Error::Connection("request cancelled".into()))
}
""", "pub fn detach_socket(stream:")
amend(tcp, """impl ObfuscatedTcp {
    pub fn set_io_timeout(&mut self, secs: u64) {""",
      """impl ObfuscatedTcp {
    pub fn detach_completed_request(&self) -> Result<(), TransportError> {
        crate::request_control::detach_socket(&self.stream)
    }
    pub fn set_io_timeout(&mut self, secs: u64) {""")
supervisor = "vendor/mono-rs/crates/mono-proto-runtime/src/rpc/supervisor.rs"
amend(supervisor,
      """        if let Some(transport) = self.transport.take() {
            put_live(transport);
        }""",
      """        if let Some(transport) = self.transport.take() {
            if transport.conn.detach_completed_request().is_ok() {
                put_live(transport);
            } else {
                // Cancellation won the race; never park its closed socket.
                self.state = ConnectionState::Disconnected;
            }
        }""")


amend(api_invoke,
      """    let span = crate::perf::span("rpc:pipeline");
    let raw = rpc::invoke_batch_raw_with_retry_streaming(""",
      """    let span = crate::perf::span("rpc:pipeline");
    let decoded = std::cell::RefCell::new(Vec::<Option<Result<Res, MtprotoError>>>::new());
    let raw = rpc::invoke_batch_raw_with_retry_streaming(""")
amend(api_invoke,
      """        |reused| {
            let mut bodies = Vec::with_capacity(requests.len());""",
      """        |reused| {
            // An RPC retry may reuse response indices. Only the successful
            // attempt's decoded vector may be returned to the caller.
            decoded.borrow_mut().clear();
            let mut bodies = Vec::with_capacity(requests.len());""")
amend(api_invoke,
      """            let refill = on_chunk(index, res)?;
            rpc::encode_boxed_bytes(&wrap_without_updates(refill)).ok()
        },
    );
    drop(span);
    Ok(raw?
        .into_iter()
        .map(|entry| entry.and_then(|body| decode_response::<Res>(&body)))
        .collect())""",
      """            let refill = if let Some(callback) = borrowed.as_deref_mut() {
                let refill = callback(index, &res);
                let mut values = decoded.borrow_mut();
                if values.len() <= index { values.resize_with(index + 1, || None); }
                values[index] = Some(res);
                refill
            } else {
                on_chunk.as_deref_mut()?(index, res)
            }?;
            rpc::encode_boxed_bytes(&wrap_without_updates(refill)).ok()
        },
    );
    drop(span);
    let raw = raw?;
    if discard_results { return Ok(Vec::new()); }
    let mut decoded = decoded.into_inner();
    Ok(raw.into_iter().enumerate().map(|(index, entry)| {
        decoded.get_mut(index).and_then(Option::take)
            .unwrap_or_else(|| entry.and_then(|body| decode_response::<Res>(&body)))
    }).collect())""")
# Preserve the old owned callback API for pinned upstream tests and external
# callers; the production download sink only needs to borrow each response.
amend(range_output,
      """    refill_limit: Option<usize>,
    mut fetch_batch: impl FnMut(
        bool,
        Vec<UploadGetFileRequest>,
        &mut dyn FnMut(usize, Result<UploadFile, MtprotoError>) -> Option<UploadGetFileRequest>,
    ) -> Result<Vec<Result<UploadFile, MtprotoError>>, MtprotoError>,
) -> Result<String, MtprotoError> {
    let _chunk_guard = preserve_download_window();
    let chunk = resolve_chunk(offset);
    if offset.is_some_and(|value| value < 0 || value % i64::from(chunk) != 0) {""",
      """    refill_limit: Option<usize>,
    mut fetch_batch: impl FnMut(
        bool,
        Vec<UploadGetFileRequest>,
        &mut dyn FnMut(usize, Result<UploadFile, MtprotoError>) -> Option<UploadGetFileRequest>,
    ) -> Result<Vec<Result<UploadFile, MtprotoError>>, MtprotoError>,
) -> Result<String, MtprotoError> {
    download_media_range_batched_streaming_borrowed(
        dc_id, media, dest_path, cancellation_path, offset, parts_in_flight, refill_limit,
        |first, requests, callback| fetch_batch(first, requests, &mut |index, value| callback(index, &value)),
    )
}

pub fn download_media_range_batched_streaming_borrowed(
    dc_id: i32,
    media: &MediaRef,
    dest_path: &Path,
    cancellation_path: &Path,
    offset: Option<i64>,
    parts_in_flight: usize,
    refill_limit: Option<usize>,
    mut fetch_batch: impl FnMut(
        bool, Vec<UploadGetFileRequest>,
        &mut dyn FnMut(usize, &Result<UploadFile, MtprotoError>) -> Option<UploadGetFileRequest>,
    ) -> Result<Vec<Result<UploadFile, MtprotoError>>, MtprotoError>,
) -> Result<String, MtprotoError> {
    let _chunk_guard = preserve_download_window();
    let chunk = resolve_chunk(offset);
    if offset.is_some_and(|value| value < 0 || value % i64::from(chunk) != 0) {""")
amend(range_output,
      """                                        res: Result<UploadFile, MtprotoError>|
                     -> Option<UploadGetFileRequest> {""",
      """                                        res: &Result<UploadFile, MtprotoError>|
                     -> Option<UploadGetFileRequest> {""")
# The media crate re-exports its download API; use the explicit module for the
# new borrowed entry point, so no change to the crate's public re-export list.
amend(media_lane,
      """    let result = invoke_parallel_media_batches(
        [""",
      """    let result = invoke_parallel_media_batches_borrowed(
        [""")
amend(media_lane,
      """    on_chunk: &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError> {
    use std::sync::mpsc::{Sender, sync_channel};""",
      """    on_chunk: &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError> {
    invoke_parallel_media_batches_borrowed(batches, invoke_a, invoke_b, &mut |index, result| {
        on_chunk(index, result.clone())
    })
}

pub fn invoke_parallel_media_batches_borrowed<Req: Send, Res: Send>(
    batches: [Vec<(usize, Req)>; 2],
    invoke_a: impl FnOnce(Vec<Req>, &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>)
        -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError> + Send,
    invoke_b: impl FnOnce(Vec<Req>, &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>)
        -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError> + Send,
    on_chunk: &mut dyn FnMut(usize, &Result<Res, MtprotoError>) -> Option<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError> {
    use std::sync::mpsc::{Sender, sync_channel};""")
amend(media_lane,
      "            let refill = on_chunk(index, result.clone())",
      "            let refill = on_chunk(index, &result)")
amend(media_lane,
      """        mono_proto_runtime::api_invoke::invoke_api_batch_without_updates_streaming::<
            _,
            mono_proto::latest::api::UploadFile,""",
      """        mono_proto_runtime::api_invoke::invoke_api_batch_without_updates_streaming_consumed::<
            _,
            mono_proto::latest::api::UploadFile,""")
amend(media_lane,
      """    on_chunk: &mut dyn FnMut(
        usize,
        Result<mono_proto::latest::api::UploadFile, MtprotoError>,
    ) -> Option<mono_proto::latest::api::UploadGetFileRequest>,
) -> Result<Vec<Result<mono_proto::latest::api::UploadFile, MtprotoError>>, MtprotoError> {
    let Some(first) = requests.first()""",
      """    on_chunk: &mut dyn FnMut(
        usize,
        &Result<mono_proto::latest::api::UploadFile, MtprotoError>,
    ) -> Option<mono_proto::latest::api::UploadGetFileRequest>,
) -> Result<Vec<Result<mono_proto::latest::api::UploadFile, MtprotoError>>, MtprotoError> {
    let Some(first) = requests.first()""")
amend(media_lane,
      """            mono_proto_runtime::api_invoke::invoke_api_batch_without_updates_streaming(
                snap_a, api_id, requests, on_chunk,""",
      """            mono_proto_runtime::api_invoke::invoke_api_batch_without_updates_streaming_borrowed(
                snap_a, api_id, requests, on_chunk,""")
amend(media_lane,
      "        media::download_media_range_batched_streaming_capped(",
      "        mono_proto_media::download::download_media_range_batched_streaming_borrowed(")
amend(media_lane,
      """let mut stream_chunk = |index, result: Result<mono_proto::latest::api::UploadFile, MtprotoError>|""",
      """let mut stream_chunk = |index, result: &Result<mono_proto::latest::api::UploadFile, MtprotoError>|""")
amend(client_download,
      "        let downloaded = media::download_media_range_batched_streaming_capped(",
      "        let downloaded = mono_proto_media::download::download_media_range_batched_streaming_borrowed(")


add_before(ffi, "#[uniffi::export]\npub fn download_message_thumb(", """// Present only in instrumented Android builds. Keep the profile inside
// the application-private files directory; the app supplies a stable path.
#[cfg(all(target_os = "android", ambient_pgo_generate))]
#[allow(unsafe_code)]
#[jni::jni_mangle("com.ambient.tv.data.telegram.AmbientMonogramJni")]
pub extern "system" fn flushPgoProfile<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _this: jni::objects::JObject<'caller>,
    destination: jni::objects::JString<'caller>,
) -> jni::sys::jint {
    unsafe extern "C" {
        fn __llvm_profile_set_filename(name: *const std::ffi::c_char);
        fn __llvm_profile_write_file() -> std::ffi::c_int;
    }
    static PATH: std::sync::OnceLock<std::ffi::CString> = std::sync::OnceLock::new();
    static WRITE: std::sync::Mutex<()> = std::sync::Mutex::new(());
    unowned_env.with_env(|env| -> jni::errors::Result<_> {
        let path = destination.try_to_string(env)?;
        let Ok(path) = std::ffi::CString::new(path) else { return Ok(-1); };
        let Ok(_lock) = WRITE.lock() else { return Ok(-1); };
        let path = PATH.get_or_init(|| path);
        // PATH owns the filename for the lifetime of the LLVM runtime.
        unsafe {
            __llvm_profile_set_filename(path.as_ptr());
            Ok(__llvm_profile_write_file())
        }
    }).resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
""", "pub extern \"system\" fn flushPgoProfile")


add_before(ffi, "#[uniffi::export]\npub fn set_download_concurrency(", """#[uniffi::export]
pub fn ambient_cpu_features() -> String {
    #[cfg(target_arch = "aarch64")]
    { return format!("aarch64 neon={} aes={} sha2={}",
        std::arch::is_aarch64_feature_detected!("neon"),
        std::arch::is_aarch64_feature_detected!("aes"),
        std::arch::is_aarch64_feature_detected!("sha2")); }
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    { return format!("{} aes={} sha={} ssse3={} avx2={}", std::env::consts::ARCH,
        std::arch::is_x86_feature_detected!("aes"),
        std::arch::is_x86_feature_detected!("sha"),
        std::arch::is_x86_feature_detected!("ssse3"),
        std::arch::is_x86_feature_detected!("avx2")); }
    #[allow(unreachable_code)]
    std::env::consts::ARCH.to_owned()
}
""", "pub fn ambient_cpu_features(")
add_before(media_lane, "fn receive_cdn_parts(", """#[cfg(test)]
mod ambient_bounded_cdn_tests {
    use super::*;
    use mono_proto::latest::api::FileHashConstructor;
    use sha2::{Digest, Sha256};
    #[test]
    fn bounded_cdn_nonzero_offset_never_returns_unverified_bytes() {
        let start = 512 * 1024_i64;
        let part_size = media::CHUNK as usize;
        let total = 512 * 1024;
        let plain = vec![37_u8; total];
        let job = media::CdnRedirect {
            dc_id: 203, file_token: vec![1], encryption_key: vec![7; 32],
            encryption_iv: vec![9; 16], offset: start,
            file_hashes: plain.chunks(part_size).enumerate().map(|(i, part)| {
                FileHash::FileHash(FileHashConstructor {
                    offset: start + (i * part_size) as i64,
                    limit: part.len() as i32, hash: Sha256::digest(part).to_vec(),
                })
            }).collect(),
        };
        let root = std::env::temp_dir().join(format!("ambient-cdn-bounded-{}-{:?}", std::process::id(), std::thread::current().id()));
        std::fs::create_dir_all(&root).unwrap();
        let dest = root.join("chunk");
        for corrupt in [false, true] {
            let mut offsets = Vec::new();
            let result = mono_proto_media::download::ambient_capture_range(&dest, || {
                let staged = media::StagedDownload::new(&dest)?;
                receive_cdn_parts_bounded(&job, &dest, &staged, true, |offset| {
                    let at = usize::try_from(offset - start).unwrap();
                    assert!(at < total);
                    offsets.push(offset);
                    let mut encrypted = plain[at..(at + part_size).min(total)].to_vec();
                    media::decrypt_cdn_part(&job.encryption_key, &job.encryption_iv, offset, &mut encrypted)?;
                    if corrupt { encrypted[0] ^= 1; }
                    Ok(Ok(encrypted))
                }, |_| panic!("unexpected reupload"))?;
                assert!(!staged.path().exists());
                staged.publish(&dest)
            });
            if corrupt {
                assert!(result.is_err());
            } else {
                assert_eq!(result.unwrap().1.unwrap(), plain);
                assert_eq!(offsets.len(), total.div_ceil(part_size));
            }
            assert!(!dest.exists());
        }
        std::fs::remove_dir_all(root).unwrap();
    }
}

""", "mod ambient_bounded_cdn_tests")
add_before(socket_tcp, "fn closed_error() -> TransportError {", """#[cfg(test)]
mod ambient_detach_tests {
    use super::*;
    use std::io::{Read, Write};
    #[test]
    fn completed_socket_survives_late_request_cancel() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let first = Arc::new(TcpStream::connect(addr).unwrap());
        let (mut peer_a, _) = listener.accept().unwrap();
        let second = Arc::new(TcpStream::connect(addr).unwrap());
        let (mut peer_b, _) = listener.accept().unwrap();
        peer_a.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        peer_b.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let control = ConnectionControl::default();
        control.register(&first).unwrap();
        control.register(&second).unwrap();
        control.detach_socket(&first).unwrap();
        control.close();
        (&*first).write_all(b"a").unwrap();
        let mut value = [0_u8; 1];
        peer_a.read_exact(&mut value).unwrap();
        assert_eq!(value, *b"a");
        assert_eq!(peer_b.read(&mut value).unwrap(), 0);
        assert!(control.detach_socket(&second).is_err());
    }
}

""", "mod ambient_detach_tests")


# Prevent the raw and typed batch result vectors from retaining an entire
# multi-gigabyte file. Keep the sliding window full for bounded refills, then
# continue on the same live lane/session with the next missing offset.
amend(media_lane,
      """            media::download_in_flight(),
            None,
            |_init_first, requests, on_chunk| {""",
      """            media::download_in_flight(),
            if mono_proto_media::profile::ambient_media_mode() {
                Some(media::download_in_flight().min(4))
            } else { None },
            |_init_first, requests, on_chunk| {""")

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
