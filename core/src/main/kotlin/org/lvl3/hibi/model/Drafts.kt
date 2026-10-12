package org.lvl3.hibi.model

/** Only user-visible content counts as a change; opening an entry must never edit it. */
fun sameContent(first: Entry, second: Entry): Boolean =
    first.id == second.id && first.day == second.day &&
        first.markdown == second.markdown && first.photos == second.photos

fun keepDraft(draft: Entry, published: Entry?): Boolean =
    if (published != null) !sameContent(draft, published)
    else draft.markdown.isNotBlank() || draft.photos.isNotEmpty()
