package au.com.shiftyjelly.pocketcasts.repositories.migration

import android.util.Xml
import java.io.OutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Writes only the uniquely missing feeds from an approved preview for the existing OPML importer. */
internal object PodcastAddictMissingSubscriptions {
    fun writeOpml(feedUrls: List<String>, output: OutputStream) {
        require(feedUrls.all { it.toHttpUrlOrNull() != null })
        val xml = Xml.newSerializer()
        xml.setOutput(output, "UTF-8")
        xml.startDocument("UTF-8", true)
        xml.startTag(null, "opml").attribute(null, "version", "2.0")
        xml.startTag(null, "head")
        xml.startTag(null, "title").text("Podcast Addict missing subscriptions").endTag(null, "title")
        xml.endTag(null, "head")
        xml.startTag(null, "body")
        for (url in feedUrls.distinct()) {
            xml.startTag(null, "outline")
                .attribute(null, "type", "rss")
                .attribute(null, "text", "Podcast")
                .attribute(null, "xmlUrl", url)
                .endTag(null, "outline")
        }
        xml.endTag(null, "body")
        xml.endTag(null, "opml")
        xml.endDocument()
        xml.flush()
    }
}
