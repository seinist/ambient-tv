package com.ambient.tv.domain

import com.ambient.tv.domain.model.CastMember
import com.ambient.tv.domain.model.dedupeCastMembers
import org.junit.Assert.assertEquals
import org.junit.Test

class CastMemberDedupeTest {
    @Test fun keepsFirstAppearancePerActorId() {
        val cast = listOf(
            CastMember("1", "Ada", "Lead", null),
            CastMember("1", "Ada", "Cameo", null),
            CastMember("2", "Bea", "Support", null),
        )
        assertEquals(listOf("Ada", "Bea"), cast.dedupeCastMembers().map { it.name })
        assertEquals("Lead", cast.dedupeCastMembers().first().role)
    }

    @Test fun fallsBackToNameWhenIdMissing() {
        val cast = listOf(
            CastMember(null, "Ada", "Lead", null),
            CastMember(null, "ada", "Cameo", null),
            CastMember("3", "Cara", "Friend", null),
        )
        assertEquals(listOf("Ada", "Cara"), cast.dedupeCastMembers().map { it.name })
    }

    @Test fun prefersEntryWithPortraitAndRoleOverBareDuplicate() {
        val cast = listOf(
            CastMember("j1", "Austin Abrams", null, null),
            CastMember("j2", "Kali Reis", null, null),
            CastMember("p1", "Austin Abrams", "Bryan", "https://img/austin"),
            CastMember("p2", "Kali Reis", "Maxine", "https://img/kali"),
            CastMember("p3", "Zach Cherry", "Carl", null),
        )
        val deduped = cast.dedupeCastMembers()
        assertEquals(listOf("Austin Abrams", "Kali Reis", "Zach Cherry"), deduped.map { it.name })
        assertEquals(listOf("Bryan", "Maxine", "Carl"), deduped.map { it.role })
    }
}
