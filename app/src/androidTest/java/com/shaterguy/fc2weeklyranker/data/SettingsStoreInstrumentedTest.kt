package com.shaterguy.fc2weeklyranker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaterguy.fc2weeklyranker.domain.ContentMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreInstrumentedTest {
    @Test
    fun modeFavoriteTagsRemainSeparated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = "selfrun-dev36-mode-tag"
        val store = SettingsStore(context)
        if (tag in store.favoriteTags(ContentMode.FC2).first()) store.toggleFavoriteTag(tag, ContentMode.FC2)
        if (tag in store.favoriteTags(ContentMode.JAV).first()) store.toggleFavoriteTag(tag, ContentMode.JAV)
        try {
            store.toggleFavoriteTag(tag, ContentMode.FC2)
            assertTrue(tag in store.favoriteTags(ContentMode.FC2).first())
            assertFalse(tag in store.favoriteTags(ContentMode.JAV).first())
            store.toggleFavoriteTag(tag, ContentMode.JAV)
            assertTrue(tag in store.favoriteTags(ContentMode.JAV).first())
        } finally {
            val cleanup = SettingsStore(context)
            if (tag in cleanup.favoriteTags(ContentMode.FC2).first()) cleanup.toggleFavoriteTag(tag, ContentMode.FC2)
            if (tag in cleanup.favoriteTags(ContentMode.JAV).first()) cleanup.toggleFavoriteTag(tag, ContentMode.JAV)
        }
    }

    @Test
    fun javFavoriteTagPersistsAcrossStoreWrappers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = "selfrun-dev33-persistence-marker"
        val first = SettingsStore(context)
        if (tag in first.javFavoriteTags.first()) first.toggleJavFavoriteTag(tag)
        assertFalse(tag in first.javFavoriteTags.first())

        try {
            first.toggleJavFavoriteTag(tag)
            assertTrue(tag in first.javFavoriteTags.first())

            val reopened = SettingsStore(context)
            assertTrue(tag in reopened.javFavoriteTags.first())
        } finally {
            val cleanup = SettingsStore(context)
            if (tag in cleanup.javFavoriteTags.first()) cleanup.toggleJavFavoriteTag(tag)
        }
    }
}