package com.shaterguy.fc2weeklyranker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ViewportBehaviorContractTest {
    @Test
    fun tagResultsBelongsToTagsOnlyForBottomNavigation() {
        assertEquals("tags", bottomNavigationRoute("tag-results"))
        assertEquals("ranking", bottomNavigationRoute("ranking"))
        assertEquals("detail/{postId}", bottomNavigationRoute("detail/{postId}"))
    }

    @Test
    fun volatilePostAndMediaListsDoNotAnchorViewportToContentIdentity() {
        val main = source("app/src/main/java/com/shaterguy/fc2weeklyranker/MainActivity.kt")
        val search = source("app/src/main/java/com/shaterguy/fc2weeklyranker/ui/SearchScreen.kt")
        val tag = source("app/src/main/java/com/shaterguy/fc2weeklyranker/ui/TagScreen.kt")

        assertFalse(search.contains("items(displayedResults, key"))
        assertFalse(tag.contains("items(displayed, key"))
        assertFalse(tag.contains(".animateItem()"))
        assertFalse(main.contains("itemsIndexed(posts, key = { _, ranked -> ranked.post.id })"))
        assertFalse(main.contains("itemsIndexed(posts, key = { _, post -> post.id })"))
        assertFalse(main.contains("itemsIndexed(directVideos, key"))
        assertFalse(main.contains("item(key = \"detail-tags\")"))
    }

    private fun source(path: String): String {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repository root not found from $start")
        return File(root, path).readText()
    }
}
