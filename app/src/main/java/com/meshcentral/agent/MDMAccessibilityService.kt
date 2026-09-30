package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Input-injection and UI-automation back end.
 *
 * Android exposes no other way to drive another app's UI. Everything that needs a tap, a swipe,
 * typed text, a key press or a view-tree read goes through here, so the class keeps a single
 * static handle that the agent can reach without an explicit binder handshake.
 */
class MDMAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MDMAccessibilityService"
        const val ACTION_ACCESSIBILITY_CONNECTED = "com.meshcentral.agent.ACCESSIBILITY_CONNECTED"
        const val ACTION_ACCESSIBILITY_DISCONNECTED = "com.meshcentral.agent.ACCESSIBILITY_DISCONNECTED"

        private const val GESTURE_TIMEOUT_MS = 3000L
        private const val MAX_TREE_NODES = 2000
        private const val MAX_TREE_DEPTH = 30

        @Volatile
        private var instance: MDMAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(context.packageName)
        }

        fun getInstance(): MDMAccessibilityService? = instance

        fun isConnected(): Boolean = instance != null

        // ---- Input primitives -------------------------------------------------

        fun tap(x: Int, y: Int): Boolean = instance?.doTap(x, y) == true

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
            instance?.doSwipe(x1, y1, x2, y2, durationMs) == true

        fun inputText(text: String): Boolean = instance?.doInputText(text) == true

        fun appendText(text: String): Boolean = instance?.doAppendText(text) == true

        fun pressKey(keyCode: Int): Boolean = instance?.doKey(keyCode) == true

        fun globalAction(actionId: String): Boolean = instance?.doGlobalAction(actionId) == true

        fun launchApp(packageName: String): Boolean = instance?.doLaunchApp(packageName) == true

        fun setTextOnNode(nodeId: String, text: String): Boolean =
            instance?.doSetTextOnNode(nodeId, text) == true

        fun clickNode(nodeId: String): Boolean = instance?.doClickNode(nodeId) == true

        // ---- Read-only views --------------------------------------------------

        fun foregroundPackage(): String? = instance?.currentPackage()

        fun activeWindowInfo(): JSONObject? = instance?.windowInfo()

        fun uiTree(maxNodes: Int): JSONObject? = instance?.dumpTree(maxNodes)

        fun findNodes(query: String, byText: Boolean, limit: Int): JSONArray? =
            instance?.doFindNodes(query, byText, limit)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
        sendBroadcast(Intent(ACTION_ACCESSIBILITY_CONNECTED).setPackage(packageName))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The agent polls for state rather than streaming events; nothing to do per event.
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
        sendBroadcast(Intent(ACTION_ACCESSIBILITY_DISCONNECTED).setPackage(packageName))
        super.onDestroy()
    }

    // ---- Gesture plumbing ---------------------------------------------------

    private fun runGesture(path: Path, durationMs: Long): Boolean {
        return try {
            val latch = CountDownLatch(1)
            val builder = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            val dispatched = dispatchGesture(
                builder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) { latch.countDown() }
                    override fun onCancelled(description: GestureDescription?) { latch.countDown() }
                },
                Handler(Looper.getMainLooper())
            )
            if (!dispatched) return false
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            true
        } catch (ex: Exception) {
            Log.w(TAG, "Gesture failed", ex)
            false
        }
    }

    private fun doTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return runGesture(path, 60L)
    }

    private fun doSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return runGesture(path, durationMs.coerceIn(50L, 5000L))
    }

    private fun doInputText(text: String): Boolean {
        val node = focusedNode() ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        var ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return ok
    }

    private fun doKey(keyCode: Int): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        android.view.KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        android.view.KeyEvent.KEYCODE_ESCAPE -> performGlobalAction(GLOBAL_ACTION_BACK)
        android.view.KeyEvent.KEYCODE_ENTER -> try {
            focusedNode()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } catch (ex: Exception) {
            false
        }
        android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_FORWARD_DEL -> doDeleteBackward()
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> moveSelectionBy(-1)
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> moveSelectionBy(1)
        android.view.KeyEvent.KEYCODE_MOVE_HOME -> moveSelectionTo(0)
        android.view.KeyEvent.KEYCODE_MOVE_END -> moveSelectionTo(Int.MAX_VALUE)
        else -> false
    }

    private fun editableNode(): AccessibilityNodeInfo? {
        val node = focusedNode() ?: return null
        if (!node.isEditable) return null
        return node
    }

    private fun setSelection(node: AccessibilityNodeInfo, pos: Int): Boolean {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun currentSelection(node: AccessibilityNodeInfo, length: Int): Pair<Int, Int> {
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0) { start = length; end = length }
        return start.coerceIn(0, length) to end.coerceIn(0, length)
    }

    private fun moveSelectionBy(delta: Int): Boolean {
        val node = editableNode() ?: return false
        val cur = node.text?.toString() ?: return false
        val (start, end) = currentSelection(node, cur.length)
        val target = if (start == end) (start + delta).coerceIn(0, cur.length)
        else if (delta < 0) start else end
        return setSelection(node, target)
    }

    private fun moveSelectionTo(target: Int): Boolean {
        val node = editableNode() ?: return false
        val cur = node.text?.toString() ?: return false
        return setSelection(node, target.coerceIn(0, cur.length))
    }

    private fun doDeleteBackward(): Boolean {
        val node = editableNode() ?: return false
        val cur = node.text?.toString() ?: return false
        if (cur.isEmpty()) return false
        val (start, end) = currentSelection(node, cur.length)
        val (ns, ne) = if (start != end) start to end
        else if (start > 0) (start - 1) to start
        else return false
        val next = cur.substring(0, ns) + cur.substring(ne)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        setSelection(node, ns)
        return true
    }

    private fun doAppendText(text: String): Boolean {
        val node = editableNode() ?: return false
        val cur = node.text?.toString() ?: ""
        val (start, end) = currentSelection(node, cur.length)
        val next = cur.substring(0, start) + text + cur.substring(end)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        setSelection(node, start + text.length)
        return true
    }

    private fun doGlobalAction(actionId: String): Boolean {
        val id = when (actionId) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quickSettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "powerMenu" -> GLOBAL_ACTION_POWER_DIALOG
            "lockScreen" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return try { performGlobalAction(id) } catch (ex: Exception) { false }
    }

    private fun doLaunchApp(packageName: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { startActivity(launch); true } catch (ex: Exception) { false }
    }

    private fun doSetTextOnNode(nodeId: String, text: String): Boolean {
        val node = findByNodeId(nodeId) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun doClickNode(nodeId: String): Boolean {
        val node = findByNodeId(nodeId) ?: return false
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = node.parent ?: return false
        return parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---- Reads --------------------------------------------------------------

    private fun focusedNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused ?: root
    }

    private fun currentPackage(): String? {
        rootInActiveWindow?.packageName?.toString()?.let { return it }
        return windows.firstOrNull()?.root?.packageName?.toString()
    }

    private fun windowInfo(): JSONObject? {
        val root = rootInActiveWindow ?: return null
        val out = JSONObject()
        out.put("package", root.packageName?.toString() ?: "")
        out.put("class", root.className?.toString() ?: "")
        out.put("id", nodeIdOf(root))
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            out.put("focusedId", nodeIdOf(focused))
            out.put("focusedText", focused.text?.toString() ?: "")
        }
        out.put("width", displayWidth())
        out.put("height", displayHeight())
        return out
    }

    private fun displayWidth(): Int {
        val metrics = resources.displayMetrics
        return metrics.widthPixels
    }

    private fun displayHeight(): Int {
        val metrics = resources.displayMetrics
        return metrics.heightPixels
    }

    private fun nodeIdOf(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= 33) {
            node.uniqueId?.let { return it }
        }
        return node.viewIdResourceName ?: "${node.hashCode()}"
    }

    private fun findByNodeId(nodeId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (nodeIdOf(node) == nodeId) return node
            if (depth >= MAX_TREE_DEPTH) continue
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                stack.add(child to depth + 1)
            }
        }
        return null
    }

    private fun doFindNodes(query: String, byText: Boolean, limit: Int): JSONArray {
        val out = JSONArray()
        val cap = limit.coerceIn(1, 200)
        val root = rootInActiveWindow ?: return out
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty() && out.length() < cap) {
            val (node, depth) = stack.removeLast()
            val haystack = if (byText) node.text?.toString() else node.contentDescription?.toString()
            if (haystack != null && haystack.contains(query, ignoreCase = true)) {
                out.put(describeNode(node))
                if (out.length() >= cap) break
            }
            if (depth >= MAX_TREE_DEPTH) continue
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                stack.add(child to depth + 1)
            }
        }
        return out
    }

    private fun describeNode(node: AccessibilityNodeInfo): JSONObject {
        val o = JSONObject()
        o.put("id", nodeIdOf(node))
        o.put("class", node.className?.toString() ?: "")
        o.put("text", node.text?.toString() ?: "")
        o.put("desc", node.contentDescription?.toString() ?: "")
        o.put("pkg", node.packageName?.toString() ?: "")
        o.put("clickable", node.isClickable)
        o.put("editable", node.isEditable)
        o.put("enabled", node.isEnabled)
        o.put("focused", node.isFocused)
        o.put("checked", node.isChecked)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        o.put("x", rect.left)
        o.put("y", rect.top)
        o.put("w", rect.width())
        o.put("h", rect.height())
        return o
    }

    private fun dumpTree(maxNodes: Int): JSONObject? {
        val root = rootInActiveWindow ?: return null
        val out = JSONArray()
        val cap = maxNodes.coerceIn(1, MAX_TREE_NODES)
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty() && out.length() < cap) {
            val (node, depth) = stack.removeLast()
            val entry = describeNode(node)
            entry.put("depth", depth)
            out.put(entry)
            if (depth >= MAX_TREE_DEPTH) continue
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                stack.add(child to depth + 1)
            }
        }
        val result = JSONObject()
        result.put("package", root.packageName?.toString() ?: "")
        result.put("nodes", out)
        result.put("truncated", out.length() >= cap)
        return result
    }
}
