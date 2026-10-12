package org.lvl3.hibi

import org.junit.Assert.*
import org.junit.Test
import org.lvl3.hibi.model.*

class DraftsTest {
    @Test fun openingAndClosingPublishedEntryDoesNotLeaveEditingDraft() {
        val original = Entry(markdown = "- Originale Markdown-Schreibweise\n")
        assertFalse(keepDraft(original, original))
        assertFalse(keepDraft(original.copy(updatedAt = original.updatedAt + 10), original))
        assertEquals("- Originale Markdown-Schreibweise\n", original.markdown)
    }
    @Test fun changedWordsDateAndPhotosRemainRecoverableWithoutChangingPublishedEntry() {
        val original = Entry(day = "2026-10-12", markdown = "Meine Worte")
        assertTrue(keepDraft(original.copy(markdown = "Neue Worte"), original))
        assertTrue(keepDraft(original.copy(day = "2026-10-13"), original))
        assertTrue(keepDraft(original.copy(photos = listOf(Photo("image", "jpg", "image/jpeg", 1, 1, "hash"))), original))
        assertEquals("Meine Worte", original.markdown)
    }
    @Test fun emptyNewDraftIsDiscardedButTextAndPhotoOnlyDraftsAreKept() {
        assertFalse(keepDraft(Entry(), null))
        assertFalse(keepDraft(Entry(markdown = " \n "), null))
        assertTrue(keepDraft(Entry(markdown = "Ein Gedanke"), null))
        assertTrue(keepDraft(Entry(photos = listOf(Photo("image", "jpg", "image/jpeg", 1, 1, "hash"))), null))
    }
}
