#!/usr/bin/env python3
"""Minimal reproducible extension of pinned Monogram MTProto: channels.leaveChannel.

The source is GPL-3.0-only. Original upstream ref and exact modifications are
retained for corresponding-source distribution. Fails closed on changed upstream
API/ABI and is idempotent for local repeated builds.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
changes = {}

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
           "    override fun unblockUser(handle: Long, chatId: Long)",
           """    override fun leaveChat(handle: Long, chatId: Long) {
        nativeLeaveChat(handle.toULong(), chatId)
    }
""",
           "override fun leaveChat(handle: Long, chatId: Long)")

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
      "UpdateEventDto, UpdatesStateDto, UploadItemDto, VpxAlphaFrame, VpxFrame, WallpaperCatalogDto,",
      "UpdateEventDto, UpdatesStateDto, UploadItemDto, WallpaperCatalogDto,")
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

# Commit files only after every source contract has been checked.
for path, result in changes.items():
    if path.read_text(encoding="utf-8") != result:
        path.write_text(result, encoding="utf-8")
for relative in unused_wrappers:
    (root / relative).unlink(missing_ok=True)
print("Applied pinned channel leave and size-focused MTProto-only source patch")
