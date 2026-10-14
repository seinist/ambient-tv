package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDialogPolicyTest {
    @Test fun phoneLandscapeUsesCompactWidthAndMoreVerticalRoom() {
        assertEquals(0.68f, appDialogDefaultWidthFraction(handheld = true, wideHandheld = true))
        assertEquals(0.90f, appDialogHeightFraction(0.72f, handheld = true, wideHandheld = true))
        assertEquals(0.94f, appDialogHeightFraction(0.98f, handheld = true, wideHandheld = true))
    }

    @Test fun portraitPhoneAndTelevisionPoliciesStayUnchanged() {
        assertEquals(0.94f, appDialogDefaultWidthFraction(handheld = true, wideHandheld = false))
        assertEquals(0.46f, appDialogDefaultWidthFraction(handheld = false, wideHandheld = false))
        assertEquals(0.72f, appDialogHeightFraction(0.72f, handheld = false, wideHandheld = false))
        assertEquals(560, appDialogMaxWidthDp(handheld = true, wideHandheld = true))
        assertEquals(720, appDialogMaxWidthDp(handheld = true, wideHandheld = false))
        assertEquals(640, appDialogMaxWidthDp(handheld = false, wideHandheld = false))
    }

    @Test fun tabletDialogsAreNotCappedLikeAPhoneCard() {
        assertEquals(0.82f, appDialogDefaultWidthFraction(handheld = true, wideHandheld = true, tablet = true))
        assertEquals(0.94f, appDialogDefaultWidthFraction(handheld = true, wideHandheld = false, tablet = true))
        assertEquals(1200, appDialogMaxWidthDp(handheld = true, wideHandheld = true, tablet = true))
        assertEquals(960, appDialogMaxWidthDp(handheld = true, wideHandheld = false, tablet = true))
        assertTrue(appDialogMaxWidthDp(handheld = true, wideHandheld = true, tablet = true) > 560)
    }
}
