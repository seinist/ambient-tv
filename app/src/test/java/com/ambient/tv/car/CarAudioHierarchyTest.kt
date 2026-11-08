package com.ambient.tv.car

import org.junit.Assert.*
import org.junit.Test

class CarAudioHierarchyTest {
    @Test fun liveTvIsAnIndependentTabWhenRadioBrowserIsDisabled() {
        val hierarchy = carAudioHierarchy(false, false, true, false, false)
        assertEquals(listOf(CarAudioIds.LIVE_ROOT, CarAudioIds.MUSIC_LIBRARIES), hierarchy.roots)
        assertEquals(listOf(CarAudioIds.LIVE_RADIO_TVH), hierarchy.liveFolders)
        assertTrue(hierarchy.radioFolders.isEmpty())
    }

    @Test fun iptvAloneAlsoCreatesTheLiveTvTab() {
        val hierarchy = carAudioHierarchy(false, true, false, false, false)
        assertEquals(listOf(CarAudioIds.LIVE_ROOT, CarAudioIds.MUSIC_LIBRARIES), hierarchy.roots)
        assertEquals(listOf(CarAudioIds.LIVE_RADIO_IPTV), hierarchy.liveFolders)
    }

    @Test fun radioBrowserAndLiveTvKeepSeparateContentTrees() {
        val hierarchy = carAudioHierarchy(true, true, true, false, false)
        assertEquals(listOf(CarAudioIds.RADIO_ROOT, CarAudioIds.LIVE_ROOT, CarAudioIds.MUSIC_LIBRARIES), hierarchy.roots)
        assertEquals(listOf(CarAudioIds.LIVE_RADIO_IPTV, CarAudioIds.LIVE_RADIO_TVH), hierarchy.liveFolders)
        assertEquals(listOf(
            CarAudioIds.RADIO_FAVORITES, CarAudioIds.RADIO_RECENT, CarAudioIds.RADIO_POPULAR,
            CarAudioIds.RADIO_ALL, CarAudioIds.RADIO_GENRES, CarAudioIds.RADIO_COUNTRIES, CarAudioIds.RADIO_LANGUAGES,
        ), hierarchy.radioFolders)
        assertTrue(hierarchy.radioFolders.intersect(hierarchy.liveFolders.toSet()).isEmpty())
    }

    @Test fun allSourcesFitFourTabsWithoutLosingMusicOrStorage() {
        val hierarchy = carAudioHierarchy(true, true, true, true, true)
        assertEquals(listOf(
            CarAudioIds.RADIO_ROOT, CarAudioIds.LIVE_ROOT, CarAudioIds.SPOTIFY_ROOT, CarAudioIds.LIBRARY_ROOT,
        ), hierarchy.roots)
        assertEquals(listOf(CarAudioIds.MUSIC_LIBRARIES, CarAudioIds.STORAGE_ROOT), hierarchy.libraryFolders)
    }

    @Test fun storageKeepsItsExistingRootWhenThereIsRoom() {
        val hierarchy = carAudioHierarchy(true, false, true, false, true)
        assertEquals(listOf(
            CarAudioIds.RADIO_ROOT, CarAudioIds.LIVE_ROOT, CarAudioIds.MUSIC_LIBRARIES, CarAudioIds.STORAGE_ROOT,
        ), hierarchy.roots)
        assertFalse(hierarchy.roots.contains(CarAudioIds.LIBRARY_ROOT))
    }

    @Test fun everyConfigurationHasUniqueBrowsableRootsAndKeepsEveryEnabledSourceReachable() {
        for (mask in 0 until 32) {
            val radio = (mask and 1) != 0
            val iptv = (mask and 2) != 0
            val tvheadend = (mask and 4) != 0
            val spotify = (mask and 8) != 0
            val storage = (mask and 16) != 0
            val hierarchy = carAudioHierarchy(radio, iptv, tvheadend, spotify, storage)
            val description = "source mask $mask"

            assertTrue(description, hierarchy.roots.size <= 4)
            assertEquals(description, hierarchy.roots.size, hierarchy.roots.distinct().size)
            assertTrue(description, hierarchy.roots.all(CarAudioIds::isFolder))
            assertEquals(description, radio, CarAudioIds.RADIO_ROOT in hierarchy.roots)
            assertEquals(description, iptv || tvheadend, CarAudioIds.LIVE_ROOT in hierarchy.roots)
            assertEquals(description, spotify, CarAudioIds.SPOTIFY_ROOT in hierarchy.roots)

            val reachable = hierarchy.roots.flatMap { root ->
                listOf(root) + when (root) {
                    CarAudioIds.RADIO_ROOT -> hierarchy.radioFolders
                    CarAudioIds.LIVE_ROOT -> hierarchy.liveFolders
                    CarAudioIds.LIBRARY_ROOT -> hierarchy.libraryFolders
                    else -> emptyList()
                }
            }
            assertTrue(description, reachable.all(CarAudioIds::isFolder))
            assertEquals(description, 1, reachable.count { it == CarAudioIds.MUSIC_LIBRARIES })
            assertEquals(description, if (storage) 1 else 0, reachable.count { it == CarAudioIds.STORAGE_ROOT })
            assertEquals(description, if (iptv) 1 else 0, reachable.count { it == CarAudioIds.LIVE_RADIO_IPTV })
            assertEquals(description, if (tvheadend) 1 else 0, reachable.count { it == CarAudioIds.LIVE_RADIO_TVH })
            assertTrue(description, reachable.none { CarAudioIds.liveAudioSpec(it) != null })
        }
    }

    @Test fun combinedLibraryRemainsAddressableAfterSourcesAreDisabled() {
        val hierarchy = carAudioHierarchy(false, false, false, false, false)
        assertEquals(listOf(CarAudioIds.MUSIC_LIBRARIES), hierarchy.roots)
        assertEquals(listOf(CarAudioIds.MUSIC_LIBRARIES), hierarchy.libraryFolders)
        assertTrue(CarAudioIds.isFolder(CarAudioIds.LIBRARY_ROOT))
        assertTrue(CarAudioIds.isFolder(CarAudioIds.LIVE_ROOT))
        assertNull(CarAudioIds.liveAudioSpec(CarAudioIds.LIVE_ROOT))
    }
}
