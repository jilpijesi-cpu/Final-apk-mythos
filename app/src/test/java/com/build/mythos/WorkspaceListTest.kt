package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceListTest {
    @Test fun parseProjectsDanFilesSertaFormatUkuran() {
        assertTrue(WorkspaceList.isSafeName("mythos-2.43"))
        assertFalse(WorkspaceList.isSafeName("../rahasia"))
        assertEquals("1.5 KB", WorkspaceList.formatSize(1536))

        val projects = WorkspaceList.parseProjects(
            "1700000000.0\t/home/.mythos/chats/c1/projects/aktif/aplikasi-a\n" +
                "1700000100.0\t/home/.mythos/chats/c1/projects/arsip/aplikasi-b",
            "/home/.mythos/chats/c1"
        )
        assertEquals(2, projects.size)
        assertEquals("aplikasi-b", projects.first().name)
        assertTrue(projects.first().archived)
    }
}
