package com.meshcentral.agent

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * mdm-channel file operations for the panel's Files view.
 *
 * Ported from the MeshTunnel file-channel handlers so both channels speak one
 * protocol: the same virtual paths (Sdcard tree plus the Images/Audio/Videos
 * MediaStore collections) and the same entry shape ({n,t,s,d}). Deliberately
 * standalone so the desktop file tunnel keeps its own response protocol
 * untouched; keep the two in sync when path semantics change.
 */
object MDMFiles {
    // mdm results are single JSON messages, so upload/download each carry one
    // base64 payload; 2 MB of content keeps a frame comfortably under ws limits.
    const val MAX_BYTES = 2 * 1024 * 1024

    private fun err(msg: String): JSONObject = JSONObject().put("error", msg)

    private fun mediaUri(top: String): Uri? = when (top) {
        "Images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "Audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        "Videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> null
    }

    private fun mimeOf(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "mp4", "mkv" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "txt", "log" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    fun roots(): JSONArray = JSONArray()
        .put(JSONObject().put("n", "Sdcard").put("t", 2))
        .put(JSONObject().put("n", "Images").put("t", 2))
        .put(JSONObject().put("n", "Audio").put("t", 2))
        .put(JSONObject().put("n", "Videos").put("t", 2))

    // List a directory: "" gives the four roots, "Sdcard/..." walks the real
    // tree, and the media roots answer from MediaStore (no subpaths, matching
    // the tunnel's getFolder).
    fun ls(ctx: Context, path: String): Any {
        try {
            if (path == "" || path == "/") return roots()
            val out = JSONArray()
            if (path.startsWith("Sdcard")) {
                val dir = resolveSdcardPath(Environment.getExternalStorageDirectory(), path) ?: return err("invalid path")
                val children = dir.listFiles() ?: return out
                for (c in children) {
                    out.put(JSONObject().put("n", c.name).put("t", if (c.isDirectory) 2 else 3)
                        .put("s", c.length()).put("d", c.lastModified()))
                }
                return out
            }
            val uri = mediaUri(path) ?: return err("invalid path")
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.SIZE
                ),
                null, null, null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val n = cursor.getString(nameCol) ?: continue
                    // MediaStore reports seconds; normalize to epoch ms like the Sdcard walk.
                    out.put(JSONObject().put("n", n).put("t", 3)
                        .put("s", cursor.getLong(sizeCol)).put("d", cursor.getLong(dateCol) * 1000))
                }
            }
            return out
        } catch (e: Exception) {
            return err(e.toString())
        }
    }

    // Delete named entries in a directory. One mdmResult covers the whole set
    // (the tunnel answers per file over its own channel). Media deletes that
    // need the on-device confirmation dialog cannot complete headlessly from
    // an mdm command, so they land in `failed` with a clear reason.
    fun rm(ctx: Context, path: String, names: JSONArray): JSONObject {
        val deleted = JSONArray()
        val failed = JSONArray()
        try {
            if (path.startsWith("Sdcard")) {
                for (i in 0 until names.length()) {
                    val name = names.optString(i, "")
                    val file = resolveSdcardChild(Environment.getExternalStorageDirectory(), path, name)
                    if (file == null) { failed.put(JSONObject().put("name", name).put("error", "invalid name")); continue }
                    try {
                        if (file.delete()) deleted.put(name)
                        else failed.put(JSONObject().put("name", name).put("error", "not found or not writable"))
                    } catch (se: SecurityException) {
                        failed.put(JSONObject().put("name", name).put("error", se.toString()))
                    }
                }
            } else {
                val uri = mediaUri(path) ?: return err("invalid path")
                val wanted = mutableSetOf<String>()
                for (i in 0 until names.length()) wanted.add(names.optString(i))
                val matches = mutableMapOf<String, Uri>()
                ctx.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val n = cursor.getString(nameCol) ?: continue
                        if (n in wanted) matches[n] = ContentUris.withAppendedId(uri, cursor.getLong(idCol))
                    }
                }
                for (i in 0 until names.length()) {
                    val name = names.optString(i)
                    val target = matches[name]
                    if (target == null) { failed.put(JSONObject().put("name", name).put("error", "not found")); continue }
                    try {
                        ctx.contentResolver.delete(target, null, null)
                        deleted.put(name)
                    } catch (se: SecurityException) {
                        failed.put(JSONObject().put("name", name)
                            .put("error", "needs on-device confirmation for this collection"))
                    }
                }
            }
            return JSONObject().put("ok", failed.length() == 0)
                .put("deleted", deleted).put("failed", failed)
        } catch (e: Exception) {
            return err(e.toString())
        }
    }

    // Fetch one file as base64 (mdm result); the tunnel streams bytes over its
    // own channel instead. Anything over MAX_BYTES is refused with its size so
    // the panel can say why instead of timing out.
    fun download(ctx: Context, path: String): JSONObject {
        try {
            var name: String
            var input: InputStream
            if (path.startsWith("Sdcard")) {
                val file = resolveSdcardPath(Environment.getExternalStorageDirectory(), path) ?: return err("invalid path")
                if (!file.isFile) return err("not a file")
                if (file.length() > MAX_BYTES) return err("file too large: ${file.length()} bytes (max $MAX_BYTES)")
                name = file.name
                input = FileInputStream(file)
            } else {
                val segs = path.split("/")
                if (segs.size != 2 || !isSafeFileName(segs[1])) return err("invalid path")
                val uri = mediaUri(segs[0]) ?: return err("invalid path")
                var found: Uri? = null
                ctx.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameCol) == segs[1]) {
                            if (cursor.getLong(sizeCol) > MAX_BYTES) return err("file too large: ${cursor.getLong(sizeCol)} bytes (max $MAX_BYTES)")
                            found = ContentUris.withAppendedId(uri, cursor.getLong(idCol))
                            break
                        }
                    }
                }
                val target = found ?: return err("file not found")
                name = segs[1]
                input = ctx.contentResolver.openInputStream(target) ?: return err("cannot open file")
            }
            val bytes = input.use { it.readBytes() }
            if (bytes.size > MAX_BYTES) return err("file too large: ${bytes.size} bytes (max $MAX_BYTES)")
            return JSONObject()
                .put("name", name)
                .put("size", bytes.size.toLong())
                .put("mime", mimeOf(name))
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (e: Exception) {
            return err(e.toString())
        }
    }

    // Write one file from base64 (mdm result). Sdcard paths write through the
    // canonical path guard; media destinations follow the tunnel's upload
    // rules: extension decides collection on Q+, plain public folder before Q.
    fun upload(ctx: Context, path: String, name: String, data: String): JSONObject {
        if (!isSafeFileName(name)) return err("invalid file name")
        if (data.isEmpty()) return err("missing file data")
        val bytes = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: Exception) {
            return err("invalid base64 data")
        }
        if (bytes.size > MAX_BYTES) return err("file too large: ${bytes.size} bytes (max $MAX_BYTES)")
        try {
            if (path.startsWith("Sdcard")) {
                val file = resolveSdcardChild(Environment.getExternalStorageDirectory(), path, name) ?: return err("invalid path")
                FileOutputStream(file).use { it.write(bytes) }
                return JSONObject().put("ok", true).put("path", "$path/$name").put("size", bytes.size.toLong())
            }
            val ext = name.lowercase().substringAfterLast('.')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val target: Triple<String, String, Uri>? = when (ext) {
                    "jpg", "jpeg" -> Triple("image/jpeg", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    "png" -> Triple("image/png", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    "bmp" -> Triple("image/bmp", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    "mp4" -> Triple("video/mp4", Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    "mp3" -> Triple("audio/mpeg", Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                    "ogg" -> Triple("audio/ogg", Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                    else -> null
                }
                if (target == null) return err("unsupported type for media upload; write under Sdcard instead")
                val (mime, folder, collection) = target
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                val uri = ctx.contentResolver.insert(collection, values) ?: return err("cannot create media entry")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return err("cannot open output")
            } else {
                val dir: File = when (ext) {
                    "jpg", "jpeg", "png", "bmp" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    "mp4", "mkv" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    "mp3", "wav" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }
                val file = File(dir, name)
                FileOutputStream(file).use { it.write(bytes) }
            }
            return JSONObject().put("ok", true).put("path", "$path/$name").put("size", bytes.size.toLong())
        } catch (e: Exception) {
            return err(e.toString())
        }
    }
}
