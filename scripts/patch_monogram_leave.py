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
           "    /** \`contacts.unblock\` for a user peer. */",
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

# Commit files only after every source contract has been checked.
for path, result in changes.items():
    if path.read_text(encoding="utf-8") != result:
        path.write_text(result, encoding="utf-8")
print("Applied pinned channels.leaveChannel extension to MTProto-only sources")
