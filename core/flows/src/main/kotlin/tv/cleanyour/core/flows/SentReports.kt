package tv.cleanyour.core.flows

import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.store.DocumentStore

// 0.4.7 (must 4, PM 4): "Help add this TV" remembers that this TV's report was sent, when, and the
// issue it went to once the person pasted the link, so the next visit says so instead of asking
// for the same report again. Sending again stays allowed. tvs/<id>/report-sent.txt, two lines:
// "at=<ms>" and "issue=<url>" (the second only once known). Forget this TV removes it with the
// TV's folder.
data class SentReport(val atMs: Long, val issueUrl: String? = null)

class SentReports(private val store: DocumentStore) {
    private fun path(id: TvId) = "tvs/${id.value}/report-sent.txt"

    suspend fun save(id: TvId, r: SentReport) {
        val lines = listOfNotNull("at=${r.atMs}", r.issueUrl?.takeIf { it.isNotBlank() }?.let { "issue=${it.replace('\n', ' ').replace('\r', ' ').trim()}" })
        store.write(path(id), lines.joinToString("") { "$it\n" }.encodeToByteArray())
    }

    suspend fun load(id: TvId): SentReport? = store.read(path(id)) { bytes ->
        val lines = bytes.decodeToString().lines()
        fun value(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")?.trim()?.ifEmpty { null }
        value("at")?.toLongOrNull()?.let { SentReport(it, value("issue")) }
    }

    companion object {
        // A GitHub issue link of this app's repository, as the person pastes it: the issue's own
        // page, with or without a fragment or a trailing slash; nothing else is kept.
        private val ISSUE = Regex("^https://github\\.com/udhaykumarbala/cleanyourtv-app/issues/(\\d+)/?(#.*)?$")

        fun issueUrl(text: String): String? = ISSUE.matchEntire(text.trim())?.let { "https://github.com/udhaykumarbala/cleanyourtv-app/issues/${it.groupValues[1]}" }

        // "#1234" for the banner, from the saved link.
        fun issueNumber(url: String): String? = ISSUE.matchEntire(url)?.let { "#${it.groupValues[1]}" }
    }
}
