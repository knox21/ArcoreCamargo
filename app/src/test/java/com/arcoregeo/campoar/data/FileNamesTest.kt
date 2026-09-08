package com.arcoregeo.campoar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test
    fun `keeps real ifc names`() {
        assertEquals("CMI_Santa_Rosa.ifc", FileNames.sanitize("CMI_Santa_Rosa.ifc"))
        assertEquals("obra.ifc", FileNames.sanitize("primary:Download/obra.ifc"))
        assertEquals("plano.kmz", FileNames.sanitize("/storage/emulated/0/Download/plano.kmz"))
    }

    @Test
    fun `rejects content provider ids`() {
        assertTrue(FileNames.looksLikeContentId("msf:39"))
        assertTrue(FileNames.looksLikeContentId("document:1234"))
        assertEquals("archivo", FileNames.sanitize("msf:39"))
        assertEquals("modelo.ifc", FileNames.sanitize("39", "modelo.ifc"))
        assertFalse(FileNames.looksLikeContentId("edificio.ifc"))
    }
}
