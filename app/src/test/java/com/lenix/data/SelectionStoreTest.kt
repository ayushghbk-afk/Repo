package com.lenix.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelectionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `save and load roundtrip`() {
        val file = tmp.newFile("selected_instance")
        val store = SelectionStore(file)

        store.save("debian-ab12cd")

        assertEquals("debian-ab12cd", store.load())
    }

    @Test
    fun `missing file loads as null`() {
        val store = SelectionStore(tmp.root.resolve("does-not-exist"))

        assertNull(store.load())
    }

    @Test
    fun `blank content loads as null`() {
        val file = tmp.newFile("selected_instance")
        file.writeText("   ")
        val store = SelectionStore(file)

        assertNull(store.load())
    }

    @Test
    fun `save overwrites the previous selection`() {
        val file = tmp.newFile("selected_instance")
        val store = SelectionStore(file)

        store.save("first")
        store.save("second")

        assertEquals("second", store.load())
    }

    @Test
    fun `clear removes the selection`() {
        val file = tmp.newFile("selected_instance")
        val store = SelectionStore(file)
        store.save("debian-ab12cd")

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `save works when the parent directory does not exist yet`() {
        val store = SelectionStore(tmp.root.resolve("nested/dir/selected_instance"))

        store.save("debian-ab12cd")

        assertEquals("debian-ab12cd", store.load())
    }
}
