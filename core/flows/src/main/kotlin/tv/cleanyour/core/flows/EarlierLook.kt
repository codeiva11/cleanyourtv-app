package tv.cleanyour.core.flows

import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.store.BlobStore
import tv.cleanyour.core.store.DocumentStore

// look2 (spec 1, review B1): one step of history per TV. Every send that changes the look keeps
// the choice the TV had before it in tvs/<id>/earlier.txt (HomePrefs form), and, when that look
// showed the person's photo, the photo in tvs/<id>/earlier.jpg (at most OwnPicture.MAX_BYTES). The
// first send keeps the TV's look before it: Setup's Default look. "Put back the earlier look" only
// fills the draft (LookEdit.PutBackEarlier); the one button sends it. Like picture.jpg, both files
// live in the app's private files under the TV's folder: never backed up, never in a TV report or a
// restore point, gone with "Forget this TV".
internal object EarlierLook {
    // The earlier choice and its photo (a file the preview and the push can read) with its dim.
    class Saved(val choice: HomeChoice, val photo: LookPicture.Of?)

    fun textPath(id: TvId) = "tvs/${id.value}/earlier.txt"
    fun photoPath(id: TvId) = "tvs/${id.value}/earlier.jpg"

    // The look a TV had before the first send from this phone: Setup's Default look.
    fun firstLook(before: HomeChoice?, dock: List<PackageName>): HomeChoice =
        before ?: HomeChoice(dock, DEFAULT_LOOK)

    suspend fun load(documents: DocumentStore, blobs: BlobStore, id: TvId): Saved? {
        val choice = documents.read(textPath(id)) { HomePrefs.decode(it.decodeToString()) } ?: return null
        val photo = if (choice.picture != LookPicture.MINE_ID) null else documents.read(photoPath(id)) { it }?.let { bytes ->
            attempt { blobs.get(sha256Of(bytes)) ?: blobs.putBytes(bytes) }.getOrNull()
        }
        return Saved(choice, photo?.let { OwnPicture.mine(it, choice.dim) })
    }

    // Keeps `before` as the earlier look when `next` changes something (a new photo always does).
    // `photo`: the bytes of the photo `before` showed (the kept picture.jpg), read before a new
    // photo replaces it. Returns whether it kept one.
    suspend fun remember(documents: DocumentStore, id: TvId, before: HomeChoice, next: HomeChoice, newPhoto: Boolean, photo: ByteArray?): Boolean {
        if (!newPhoto && lookOf(before) == lookOf(next)) return false
        val withPhoto = before.picture == LookPicture.MINE_ID && photo != null && photo.size <= OwnPicture.MAX_BYTES
        documents.write(textPath(id), HomePrefs.encode(before.copy(cards = null)).encodeToByteArray())
        if (withPhoto) documents.write(photoPath(id), photo) else attempt { documents.delete(photoPath(id)) }
        return true
    }

    // The look part of a choice (not its dock or cards), to tell whether two choices look different.
    fun lookOf(c: HomeChoice?): String? = c?.let { HomePrefs.encode(it.copy(dock = emptyList(), cards = null, dockChosen = true)) }

    // The earlier look differs from what was last sent (so "Put back the earlier look" shows).
    fun differs(earlier: Saved, sent: HomeChoice?, mine: LookPicture.Of?): Boolean {
        if (lookOf(earlier.choice) != lookOf(sent)) return true
        val photo = earlier.photo ?: return false
        return mine == null || mine.ref.sha256 != photo.ref.sha256
    }
}
