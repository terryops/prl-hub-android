package com.prl.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PendingExportStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun aNewStoreCanFinishTheExportAfterProcessRecreation() {
        val directory = File(temporary.root, "pending-exports")
        val text = """{"language":"中文", "label":"矿机 🦪"}"""
        val name = PendingExportStore(directory).stage(text)

        val restored = PendingExportStore(directory)
        restored.discardExcept(name)
        assertEquals(text, restored.open(name).bufferedReader().use { it.readText() })
        restored.discard(name)
        assertFalse(File(directory, name).exists())
    }

    @Test
    fun fullSizeExportSurvivesWithoutPuttingTextInSavedState() {
        val directory = File(temporary.root, "pending-exports")
        val text = "中".repeat(2 * 1024 * 1024)
        val name = PendingExportStore(directory).stage(text)

        assertTrue(name.length < 100)
        assertEquals(text, PendingExportStore(directory).open(name).bufferedReader().use { it.readText() })
    }

    @Test
    fun abandonedExportsAreRemovedWhileTheRestoredPickerKeepsItsPayload() {
        val directory = File(temporary.root, "pending-exports")
        val store = PendingExportStore(directory)
        val abandoned = store.stage("old task")
        val restored = store.stage("current picker")

        store.discardExcept(restored)
        assertFalse(File(directory, abandoned).exists())
        assertEquals("current picker", store.open(restored).bufferedReader().use { it.readText() })
        store.discardExcept(null)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun savedStateCannotReadOrDeleteFilesOutsideTheStagingDirectory() {
        val directory = File(temporary.root, "pending-exports")
        val other = File(temporary.root, "settings.txt").apply { writeText("keep") }
        val store = PendingExportStore(directory)

        store.discard("../settings.txt")
        try {
            store.open("../settings.txt").close()
            throw AssertionError("Opened a file outside the staging directory")
        } catch (_: IOException) {
            assertEquals("keep", other.readText())
        }
    }
}
