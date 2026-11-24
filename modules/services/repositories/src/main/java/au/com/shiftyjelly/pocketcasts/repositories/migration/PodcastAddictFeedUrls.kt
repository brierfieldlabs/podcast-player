package au.com.shiftyjelly.pocketcasts.repositories.migration

import au.com.shiftyjelly.pocketcasts.servers.ServiceManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Use the same UUID-to-RSS lookup as the existing OPML exporter, not podcastUrl (website). */
object PodcastAddictFeedUrls {
    suspend fun resolve(serviceManager: ServiceManager, uuids: List<String>): Map<String, String>? {
        if (uuids.isEmpty()) return emptyMap()
        val exported = serviceManager.exportFeedUrls(uuids).getOrNull() ?: return null
        return verified(uuids, exported)
    }

    // An incomplete response cannot establish which subscriptions are truly missing.
    internal fun verified(uuids: List<String>, exported: Map<String, String>): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        for (uuid in uuids) {
            val url = exported[uuid]?.trim() ?: return null
            if (url.toHttpUrlOrNull() == null) return null
            result[uuid] = url
        }
        return result
    }
}
