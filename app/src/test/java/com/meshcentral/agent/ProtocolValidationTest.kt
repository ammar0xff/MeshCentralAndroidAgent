package com.meshcentral.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProtocolValidationTest {
    @Test
    fun acceptsValidMeshServerLink() {
        assertTrue(isMeshServerLinkValid("mc://mesh.example.com,certificateHash,deviceGroup"))
    }

    @Test
    fun rejectsMalformedMeshServerLinks() {
        assertFalse(isMeshServerLinkValid("https://mesh.example.com,certificateHash,deviceGroup"))
        assertFalse(isMeshServerLinkValid("mc://localhost,certificateHash,deviceGroup"))
        assertFalse(isMeshServerLinkValid("mc://mesh.example.com,ab,deviceGroup"))
        assertFalse(isMeshServerLinkValid("mc://mesh.example.com,certificateHash,ab"))
    }

    @Test
    fun acceptsAbsentOrMatchingTunnelUsage() {
        assertTrue(isTunnelUsageAllowed(null, 2))
        assertTrue(isTunnelUsageAllowed(5, 5))
    }

    @Test
    fun rejectsMismatchedTunnelUsage() {
        assertFalse(isTunnelUsageAllowed(2, 5))
    }

    @Test
    fun resolvesPathsContainedBySdcardRoot() {
        val root = File("build/test-sdcard").canonicalFile

        assertEquals(root, resolveSdcardPath(root, "Sdcard"))
        assertEquals(File(root, "Pictures/photo.jpg"), resolveSdcardPath(root, "Sdcard/Pictures/photo.jpg"))
        assertEquals(File(root, "Pictures/photo.jpg"), resolveSdcardChild(root, "Sdcard/Pictures", "photo.jpg"))
    }

    @Test
    fun rejectsExternalStorageTraversalAndUnsafeNames() {
        val root = File("build/test-sdcard").canonicalFile

        assertNull(resolveSdcardPath(root, "Sdcard/../private.txt"))
        assertNull(resolveSdcardPath(root, "Sdcard\\..\\private.txt"))
        assertNull(resolveSdcardPath(root, "Other/photo.jpg"))
        assertNull(resolveSdcardChild(root, "Sdcard/Pictures", "../private.txt"))
        assertNull(resolveSdcardChild(root, "Sdcard/Pictures", "nested/photo.jpg"))
        assertFalse(isSafeFileName(".."))
    }

    @Test
    fun normalizesPercentEncodedServerLinks() {
        assertEquals(
            "mc://mesh.example.com,cert\$id,deviceGroup",
            normalizeServerLink("mc://mesh.example.com,cert%24id,deviceGroup")
        )
        assertEquals(
            "mc://mesh.example.com,hash@one,deviceGroup",
            normalizeServerLink("mc://mesh.example.com,hash%40one,deviceGroup")
        )
    }

    @Test
    fun passesThroughPlainAndMalformedLinks() {
        val plain = "mc://mesh.example.com,certificateHash,deviceGroup"
        assertEquals(plain, normalizeServerLink(plain))
        val broken = "100% ready"
        assertEquals(broken, normalizeServerLink(broken))
    }

    @Test
    fun enforcesServerLinkPartShape() {
        assertFalse(isMeshServerLinkValid(""))
        assertFalse(isMeshServerLinkValid("mc://sh,hash,group"))       // server part too short
        assertFalse(isMeshServerLinkValid("mc://a.example,h11"))       // only two parts
        assertFalse(isMeshServerLinkValid("mc://a.example,h1,g11"))    // hash part too short
        assertFalse(isMeshServerLinkValid("mc://a.example,h11,g1"))    // group part too short
        assertTrue(isMeshServerLinkValid("mc://srv.example,cert,group"))
        assertTrue(isMeshServerLinkValid("mc://a.example,h11,g11,extra")) // extra segments allowed
    }

    @Test
    fun validatesSafeFileNames() {
        assertFalse(isSafeFileName(""))
        assertFalse(isSafeFileName("."))
        assertFalse(isSafeFileName("/"))
        assertFalse(isSafeFileName("\\"))
        assertFalse(isSafeFileName("a\u0000b"))
        assertFalse(isSafeFileName("dir/file"))
        assertFalse(isSafeFileName("dir\\file"))
        assertTrue(isSafeFileName("notes.txt"))
        assertTrue(isSafeFileName(".hidden"))
        assertTrue(isSafeFileName("New Folder (2)"))
    }

    @Test
    fun rejectsSdcardPathsWithBadPrefixOrNullByte() {
        val root = File("build/test-sdcard").canonicalFile

        assertNull(resolveSdcardPath(root, "SdcardX/y.txt"))           // prefix must be Sdcard or Sdcard/
        assertNull(resolveSdcardPath(root, "Sdcard\u0000/x.txt"))      // null byte never escapes
        assertEquals(root, resolveSdcardPath(root, "Sdcard/"))         // trailing slash lands on root
    }

    @Test
    fun rejectsEmptyOrDotChildNames() {
        val root = File("build/test-sdcard").canonicalFile

        assertNull(resolveSdcardChild(root, "Sdcard", ""))
        assertNull(resolveSdcardChild(root, "Sdcard", "."))
        assertNull(resolveSdcardChild(root, "Sdcard", ".."))
    }
}