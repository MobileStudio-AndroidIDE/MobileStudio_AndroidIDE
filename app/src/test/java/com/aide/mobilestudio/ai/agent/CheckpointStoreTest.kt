package com.aide.mobilestudio.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File

class CheckpointStoreTest {

    private fun newRoot(): File = Files.createTempDirectory("checkpoints").toFile()

    private fun turnIds(store: CheckpointStore, sessionId: String): List<String> =
        listOfNotNull(store.pendingUndoTurn(sessionId)?.turnId)

    // ------------------------------------------------------------ capture

    @Test
    fun `capture stores pre-edit content and groups by turn`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "a.kt").apply { writeText("line1\nline2\n") }

        assertTrue(store.capture("ses1", "turn1", file))
        file.writeText("line1\nline2-changed\nline3\n")

        val turn = store.pendingUndoTurn("ses1")
        assertNotNull(turn)
        assertEquals("turn1", turn!!.turnId)
        assertEquals(1, turn.entries.size)
        assertEquals("line1\nline2\n", turn.entries[0].content)
        assertEquals(file.absolutePath, turn.entries[0].path)
    }

    @Test
    fun `first capture of a path within a turn wins`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "a.kt").apply { writeText("original") }

        store.capture("ses1", "turn1", file)
        file.writeText("edited once")
        store.capture("ses1", "turn1", file)

        val entries = store.pendingUndoTurn("ses1")!!.entries
        assertEquals(1, entries.size)
        assertEquals("original", entries[0].content)
    }

    @Test
    fun `missing file is captured as created`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "new.kt")

        assertTrue(store.capture("ses1", "turn1", file))
        file.writeText("created by agent")

        val entry = store.pendingUndoTurn("ses1")!!.entries.single()
        assertNull(entry.content)
        assertFalse(entry.path.isEmpty())
    }

    @Test
    fun `oversized files are skipped`() {
        val root = newRoot()
        val store = CheckpointStore(root, maxFileBytes = 64)
        val file = File(root, "big.txt").apply { writeText("x".repeat(65)) }

        assertFalse(store.capture("ses1", "turn1", file))
        assertNull(store.pendingUndoTurn("ses1"))
    }

    @Test
    fun `separate sessions are isolated`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "a.kt").apply { writeText("content") }

        assertTrue(store.capture("ses1", "t", file))
        assertNull(store.pendingUndoTurn("ses2"))
        assertEquals("content", store.pendingUndoTurn("ses1")!!.entries.single().content)
    }

    // ------------------------------------------------- ring + multi-file groups

    @Test
    fun `ring prunes oldest turns beyond maxEntries`() {
        val root = newRoot()
        val store = CheckpointStore(root, maxEntries = 2)
        val f1 = File(root, "1.txt").apply { writeText("one") }
        val f2 = File(root, "2.txt").apply { writeText("two") }
        val f3 = File(root, "3.txt").apply { writeText("three") }

        store.capture("ses1", "t1", f1)
        store.capture("ses1", "t2", f2)
        store.capture("ses1", "t3", f3)

        assertEquals("t3", store.pendingUndoTurn("ses1")!!.turnId)
        // t1 and t2 are pruned (maxEntries=2 keeps at most the two newest, and
        // pruning never removes the newest turn)
        val outcome = store.undo("ses1")
        assertTrue(outcome is CheckpointOutcome.Undone)
        // After undoing t3, only t2 remains undoable; t1 is gone.
        assertEquals("t2", store.pendingUndoTurn("ses1")!!.turnId)
    }

    @Test
    fun `undo restores the whole multi-file turn`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val a = File(root, "a.kt").apply { writeText("A0") }
        val b = File(root, "b.kt").apply { writeText("B0") }

        store.capture("ses1", "turn1", a)
        store.capture("ses1", "turn1", b)
        a.writeText("A1")
        b.writeText("B1")

        val outcome = store.undo("ses1")
        assertTrue(outcome is CheckpointOutcome.Undone)
        assertEquals(2, (outcome as CheckpointOutcome.Undone).files.size)
        assertEquals("A0", a.readText())
        assertEquals("B0", b.readText())
    }

    // ------------------------------------------------------------ undo/redo

    @Test
    fun `undo then redo cycles the same turn`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "a.kt").apply { writeText("before") }
        store.capture("ses1", "turn1", file)
        file.writeText("after")

        val undone = store.undo("ses1")
        assertTrue(undone is CheckpointOutcome.Undone)
        assertEquals("before", file.readText())
        assertNull(store.pendingUndoTurn("ses1"))
        assertNotNull(store.pendingRedoTurn("ses1"))

        val redone = store.redo("ses1")
        assertTrue(redone is CheckpointOutcome.Redone)
        assertEquals("after", file.readText())
        // redo makes the turn undoable again
        assertEquals("turn1", store.pendingUndoTurn("ses1")!!.turnId)
    }

    @Test
    fun `undo of a created file deletes it and redo restores it`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "generated.kt")

        store.capture("ses1", "turn1", file) // file did not exist
        file.writeText("generated content")

        val undone = store.undo("ses1")
        assertTrue(undone is CheckpointOutcome.Undone)
        assertFalse(file.exists())

        val redone = store.redo("ses1")
        assertTrue(redone is CheckpointOutcome.Redone)
        assertEquals("generated content", file.readText())
    }

    @Test
    fun `undo with nothing pending reports it`() {
        val store = CheckpointStore(newRoot())
        assertEquals(CheckpointOutcome.NothingToUndo, store.undo("sesX"))
        assertEquals(CheckpointOutcome.NothingToRedo, store.redo("sesX"))
    }

    @Test
    fun `a new capture discards the redo history`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "a.kt").apply { writeText("v0") }
        store.capture("ses1", "t1", file)
        file.writeText("v1")
        store.undo("ses1")
        assertEquals("v0", file.readText())

        // fresh edit after the undo
        store.capture("ses1", "t2", file)
        file.writeText("v2")

        assertEquals(CheckpointOutcome.NothingToRedo, store.redo("ses1"))
        // and the new edit is the undoable one: t2 captured the post-first-undo
        // state (v0), so undoing it lands back on v0.
        val outcome = store.undo("ses1")
        assertTrue(outcome is CheckpointOutcome.Undone)
        assertEquals("v0", file.readText())
    }

    @Test
    fun `undo stacks - repeated undos walk back through turns`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "a.kt").apply { writeText("v0") }
        store.capture("ses1", "t1", file)
        file.writeText("v1")
        store.capture("ses1", "t2", file)
        file.writeText("v2")

        store.undo("ses1")
        assertEquals("v1", file.readText())
        store.undo("ses1")
        assertEquals("v0", file.readText())
        // redo re-applies t2 (most recently undone), not t1: t2's edit left the
        // file at v2, which is what undo stashed as the redo payload.
        store.redo("ses1")
        assertEquals("v2", file.readText())
    }

    @Test
    fun `failed restore is reported and retriable`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val good = File(root, "good.txt").apply { writeText("g0") }
        val bad = File(root, "bad.txt").apply { writeText("b0") }
        store.capture("ses1", "t1", good)
        store.capture("ses1", "t1", bad)
        good.writeText("g1")
        bad.writeText("b1")

        val outcome = store.undo("ses1") { path, content ->
            if (path == bad.absolutePath) false else CheckpointStore.defaultRestore(path, content)
        }
        assertTrue(outcome is CheckpointOutcome.Failed)
        // the already-restored file is rolled forward to its edited content
        assertEquals("g1", good.readText())
        // turn is still pending, so the user can retry after fixing the cause
        assertEquals("t1", store.pendingUndoTurn("ses1")!!.turnId)
    }

    // --------------------------------------------------------- describeUndo

    @Test
    fun `describeUndo reports line deltas against current content`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "a.kt").apply { writeText("one\ntwo\n") }
        store.capture("ses1", "t1", file)
        file.writeText("one\ntwo\nthree\nfour\n")

        val deltas = store.describeUndo("ses1")
        assertEquals(1, deltas.size)
        val delta = deltas[0]
        assertEquals(file.absolutePath, delta.path)
        assertEquals(2, delta.linesBefore)
        assertEquals(4, delta.linesAfter)
        assertEquals(2, delta.lineDelta)
        assertTrue(delta.existedBefore)
    }

    @Test
    fun `describeUndo flags created files`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "new.kt")
        store.capture("ses1", "t1", file)
        file.writeText("a\nb\n")

        val delta = store.describeUndo("ses1").single()
        assertEquals(0, delta.linesBefore)
        assertEquals(2, delta.linesAfter)
        assertFalse(delta.existedBefore)
    }

    // ------------------------------------------------------ persistence

    @Test
    fun `checkpoints survive a store restart (persistence round-trip)`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "persist.txt").apply { writeText("before") }
        store.capture("ses9", "turn-42", file)
        file.writeText("after")

        // "App restart": a brand-new store instance over the same directory.
        val revived = CheckpointStore(root)
        val turn = revived.pendingUndoTurn("ses9")
        assertNotNull(turn)
        assertEquals("turn-42", turn!!.turnId)
        assertEquals("before", turn.entries.single().content)

        val outcome = revived.undo("ses9")
        assertTrue(outcome is CheckpointOutcome.Undone)
        assertEquals("before", file.readText())

        // and redo still works after the restart
        val redone = CheckpointStore(root).redo("ses9")
        assertTrue(redone is CheckpointOutcome.Redone)
        assertEquals("after", file.readText())
    }

    @Test
    fun `clear removes all state of a session`() {
        val root = newRoot()
        val store = CheckpointStore(root)
        val file = File(root, "a.txt").apply { writeText("x") }
        store.capture("ses1", "t1", file)
        store.clear("ses1")
        assertNull(store.pendingUndoTurn("ses1"))
        assertEquals(CheckpointOutcome.NothingToUndo, store.undo("ses1"))
    }

    @Test
    fun `lineCount matches the delta convention`() {
        assertEquals(0, CheckpointStore.lineCount(""))
        assertEquals(1, CheckpointStore.lineCount("a"))
        assertEquals(3, CheckpointStore.lineCount("a\nb\nc"))
    }
}
