package com.ambient.tv.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import com.ambient.tv.R
import com.ambient.tv.ui.MainActivity

/** Android ranks/displays these targets; never treat an incoming shortcut ID as authority. */
internal object CompanionShareTargets {
    private const val PREFIX = "companion-tv-"
    private const val CATEGORY = "com.ambient.tv.category.SHARE_TO_TV"
    fun id(peer: CompanionPeer) = PREFIX + peer.fingerprint
    fun peer(shortcutId: String, paired: List<CompanionPeer>): CompanionPeer? = paired.firstOrNull { id(it) == shortcutId }

    /**
     * Keeps the share sheet entry off on TV devices. The manifest already disables the alias via
     * a -television resource; this repeats it at runtime in case a launcher resolved it differently.
     * On phones the manifest value applies (state DEFAULT).
     */
    fun syncShareEntry(context: Context) {
        runCatching {
            val component = ComponentName(context, SHARE_ALIAS)
            val wanted = if (com.ambient.tv.ui.mobile.isTelevision(context)) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(component) != wanted)
                pm.setComponentEnabledSetting(component, wanted, android.content.pm.PackageManager.DONT_KILL_APP)
        }
    }
    private const val SHARE_ALIAS = "com.ambient.tv.ui.ShareToTvActivity"

    fun publish(context: Context, peers: List<CompanionPeer>) {
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java)
            val flags = ShortcutManager.FLAG_MATCH_DYNAMIC or ShortcutManager.FLAG_MATCH_PINNED or
                if (Build.VERSION.SDK_INT >= 30) ShortcutManager.FLAG_MATCH_CACHED else 0
            val known = if (Build.VERSION.SDK_INT >= 30) manager.getShortcuts(flags)
                else manager.dynamicShortcuts + manager.pinnedShortcuts
            val validIds = peers.map(::id).toSet()
            val removed = known.filter { it.id.startsWith(PREFIX) && it.id !in validIds }.map { it.id }.distinct()
            if (removed.isNotEmpty()) {
                manager.disableShortcuts(removed, context.getString(R.string.companion_target_removed))
                manager.removeDynamicShortcuts(removed)
                if (Build.VERSION.SDK_INT >= 30) manager.removeLongLivedShortcuts(removed)
            }
            val slots = (manager.maxShortcutCountPerActivity - manager.dynamicShortcuts.count { !it.id.startsWith(PREFIX) }).coerceAtLeast(0)
            val recent = peers.asReversed().take(slots)
            val selected = recent.map(::id).toSet()
            manager.removeDynamicShortcuts(manager.dynamicShortcuts.filter { it.id.startsWith(PREFIX) && it.id !in selected }.map { it.id })
            val shortcuts = recent.mapIndexed { index, peer ->
                val label = context.getString(R.string.companion_send_to, peer.name)
                ShortcutInfo.Builder(context, id(peer))
                    .setShortLabel(label).setLongLabel(label).setRank(index)
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_companion_share))
                    .setActivity(ComponentName(context, MainActivity::class.java))
                    // Shield's ShortcutService rejects the generated bitmap form of a long-lived
                    // vector icon. Dynamic targets are sufficient for Direct Share and remain
                    // removable immediately on revocation.
                    .setCategories(setOf(CATEGORY))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse("ambienttv://devices"))
                        .putExtra(Intent.EXTRA_SHORTCUT_ID, id(peer)))
                    .build()
            }
            if (shortcuts.isNotEmpty()) {
                manager.enableShortcuts(shortcuts.map { it.id })
                manager.addDynamicShortcuts(shortcuts)
            }
        }
    }
}
