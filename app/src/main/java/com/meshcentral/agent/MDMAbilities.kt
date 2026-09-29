package com.meshcentral.agent

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Read-only device data collectors.
 *
 * Every method degrades to an empty result when its permission is missing rather than throwing,
 * so the panel can always render a row per capability and show "not granted" instead of an error.
 * All work runs on a background executor; the agent calls these from its own IO thread.
 */
object MDMAbilities {

    private const val TAG = "MDMAbilities"
    private const val DEFAULT_LIMIT = 50
    private const val HARD_LIMIT = 500

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mdm-abilities").apply { isDaemon = true }
    }

    private fun has(context: Context, permission: String): Boolean =
        Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun <T> run(timeoutMs: Long = 20_000, block: () -> T): T? = try {
        val future = executor.submit(block)
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (ex: Exception) {
        Log.w(TAG, "Collector failed", ex)
        null
    }

    private fun limitOf(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, HARD_LIMIT)

    private fun safe(block: () -> String?): String = try { block() ?: "" } catch (ex: Exception) { "" }

    private fun safeBool(block: () -> Boolean): Boolean = try { block() } catch (ex: Exception) { false }

    private fun safeInt(block: () -> Int?): Int = try { block() ?: -1 } catch (ex: Exception) { -1 }

    // ---- Device ------------------------------------------------------------

    fun deviceInfo(context: Context): JSONObject = run {
        val o = JSONObject()
        o.put("manufacturer", Build.MANUFACTURER)
        o.put("brand", Build.BRAND)
        o.put("model", Build.MODEL)
        o.put("device", Build.DEVICE)
        o.put("product", Build.PRODUCT)
        o.put("board", Build.BOARD)
        o.put("hardware", Build.HARDWARE)
        o.put("androidRelease", Build.VERSION.RELEASE)
        o.put("androidSdk", Build.VERSION.SDK_INT)
        o.put("securityPatch", safe { Build.VERSION.SECURITY_PATCH })
        o.put("fingerprint", safe { Build.FINGERPRINT })
        o.put("is64Bit", safeBool { Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" })
        o.put("buildTime", safe { Build.MODEL })

        o.put("displayWidth", safeInt { context.resources.displayMetrics.widthPixels })
        o.put("displayHeight", safeInt { context.resources.displayMetrics.heightPixels })
        o.put("density", context.resources.displayMetrics.density.toDouble())

        o.put("uptimeMs", SystemClock.elapsedRealtime())
        o.put("serial", safe { Build.SERIAL })
        o.put("androidId", safe {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        })

        o.put("storageTotal", safeLong { StatFs(Environment.getDataDirectory().path).totalBytes })
        o.put("storageFree", safeLong { StatFs(Environment.getDataDirectory().path).availableBytes })

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = am?.let {
            val info = ActivityManager.MemoryInfo()
            it.getMemoryInfo(info)
            JSONObject().apply {
                put("total", info.totalMem)
                put("available", info.availMem)
                put("lowMemory", info.lowMemory)
                put("threshold", info.threshold)
            }
        }
        o.put("memory", mem ?: JSONObject())

        o.put("cpuCores", Runtime.getRuntime().availableProcessors())
        o.put("locale", safe { context.resources.configuration.locales.get(0).toLanguageTag() })
        o.put("timezone", safe { java.util.TimeZone.getDefault().id })
        o
    } ?: JSONObject()

    fun batteryInfo(context: Context): JSONObject = run {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val o = JSONObject()
        o.put("level", safeInt { intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) })
        o.put("scale", safeInt { intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) })
        o.put("temperature", safeInt { intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) })
        o.put("charging", safeBool {
            (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) == BatteryManager.BATTERY_STATUS_CHARGING ||
                (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) == BatteryManager.BATTERY_STATUS_FULL
        })
        o.put("plugged", safeInt { intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) })
        o.put("powerSaveMode", safeBool {
            (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true
        })
        o
    } ?: JSONObject()

    private fun safeLong(block: () -> Long): Long = try { block() } catch (ex: Exception) { -1L }

    fun phoneInfo(context: Context): JSONObject = run {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val o = JSONObject()
        o.put("granted", has(context, Manifest.permission.READ_PHONE_STATE))

        if (tm == null) return@run o

        o.put("phoneType", safe { telephonyTypeName(tm) })
        o.put("simCountry", safe { tm.simCountryIso })
        o.put("simOperator", safe { tm.simOperator })
        o.put("simOperatorName", safe { tm.networkOperatorName })
        o.put("simState", safe { simStateName(tm.simState) })
        o.put("networkCountry", safe { tm.networkCountryIso })
        o.put("networkOperator", safe { tm.networkOperator })
        o.put("networkOperatorName", safe { tm.networkOperatorName })
        o.put("networkType", safe { networkTypeName(tm) })
        o.put("networkRoaming", safeBool { tm.isNetworkRoaming })
        o.put("dataEnabled", safeBool {
            if (Build.VERSION.SDK_INT >= 24) tm.isDataEnabled else tm.dataState == TelephonyManager.DATA_CONNECTED
        })
        o.put("dataActivity", safe { tm.dataActivity.toString() })
        o.put("voiceCapable", safeBool { tm.isVoiceCapable })
        o.put("multiSim", safeBool {
            if (Build.VERSION.SDK_INT >= 22) (tm.createForSubscriptionId(1).simState != -1) else false
        })

        if (has(context, Manifest.permission.READ_PHONE_NUMBERS)) {
            o.put("line1Number", safe { tm.line1Number })
            o.put("simSerial", safe { simSerial(tm) })
        }
        if (has(context, Manifest.permission.READ_PHONE_STATE)) {
            o.put("deviceId", safe { tm.deviceId })
            o.put("subscriberId", safe { tm.subscriberId })
            o.put("simSerialNumber", safe { simSerial(tm) })
        }
        o.put("activeSubscriptions", subscriptionSummary(context))
        o
    } ?: JSONObject()

    private fun simSerial(tm: TelephonyManager): String = try {
        tm.simSerialNumber ?: ""
    } catch (ex: Exception) {
        ""
    }

    private fun telephonyTypeName(tm: TelephonyManager): String = when (tm.phoneType) {
        TelephonyManager.PHONE_TYPE_NONE -> "NONE"
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
        else -> "UNKNOWN"
    }

    private fun simStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
        else -> "UNKNOWN"
    }

    @Suppress("DEPRECATION")
    private fun networkTypeName(tm: TelephonyManager): String = when (tm.networkType) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        14 -> "EHRDP"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        else -> "UNKNOWN"
    }

    private fun subscriptionSummary(context: Context): JSONArray {
        val out = JSONArray()
        if (Build.VERSION.SDK_INT < 22) return out
        if (!has(context, Manifest.permission.READ_PHONE_STATE)) return out
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (sm != null && Build.VERSION.SDK_INT >= 29 &&
                context.checkSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") != PackageManager.PERMISSION_GRANTED
            ) {
                // ActiveSubscriptionInfo requires a privileged permission on API 29+.
                out
            } else {
                val list = sm?.activeSubscriptionInfoList ?: emptyList()
                for (sub in list) {
                    out.put(JSONObject().apply {
                        put("id", sub.subscriptionId)
                        put("slotIndex", sub.simSlotIndex)
                        put("displayName", sub.displayName?.toString() ?: "")
                        put("countryIso", sub.countryIso ?: "")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put("mcc", sub.mccString ?: "")
                            put("mnc", sub.mncString ?: "")
                        }
                    })
                }
                out
            }
        } catch (ex: Exception) {
            out
        }
    }

    // ---- Location ----------------------------------------------------------

    fun location(context: Context, maxAgeMs: Long = 120_000L): JSONObject = run {
        val o = JSONObject()
        o.put("granted", has(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            has(context, Manifest.permission.ACCESS_COARSE_LOCATION))

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            o.put("error", "location service unavailable")
            return@run o
        }

        o.put("locationEnabled", safeBool {
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else lm.allProviders.isNotEmpty()
        })
        o.put("providers", enabledProviders(lm))

        var best: Location? = null
        for (provider in o.getJSONArray("providers").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }) {
            val loc = try { lm.getLastKnownLocation(provider) } catch (ex: SecurityException) { null }
            if (loc != null && (best == null || loc.time > best!!.time)) best = loc
        }

        if (best != null && (System.currentTimeMillis() - best!!.time) <= maxAgeMs) {
            putLocation(o, best!!, fresh = true)
        } else {
            val fresh = requestSingleFix(lm)
            if (fresh != null) putLocation(o, fresh, fresh = true) else o.put("error", "no recent fix")
        }
        o
    } ?: JSONObject()

    private fun enabledProviders(lm: LocationManager): JSONArray {
        val out = JSONArray()
        try {
            for (p in lm.getProviders(true)) out.put(p)
        } catch (ex: Exception) { }
        return out
    }

    private fun putLocation(o: JSONObject, loc: Location, fresh: Boolean) {
        o.put("fresh", fresh)
        o.put("latitude", loc.latitude)
        o.put("longitude", loc.longitude)
        o.put("altitude", if (loc.hasAltitude()) loc.altitude else JSONObject.NULL)
        o.put("accuracy", if (loc.hasAccuracy()) loc.accuracy else JSONObject.NULL)
        o.put("bearing", if (loc.hasBearing()) loc.bearing else JSONObject.NULL)
        o.put("speed", if (loc.hasSpeed()) loc.speed else JSONObject.NULL)
        o.put("provider", loc.provider ?: "")
        o.put("time", loc.time)
        o.put("elapsedMs", loc.elapsedRealtimeNanos / 1_000_000)
    }

    @Suppress("MissingPermission")
    private fun requestSingleFix(lm: LocationManager): Location? {
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return null

        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Location? = null
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                result = location
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) { }
            override fun onProviderEnabled(provider: String) { }
            override fun onProviderDisabled(provider: String) { }
        }
        return try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
            latch.await(8, TimeUnit.SECONDS)
            result
        } catch (ex: Exception) {
            null
        } finally {
            try { lm.removeUpdates(listener) } catch (ex: Exception) { }
        }
    }

    // ---- Personal data -----------------------------------------------------

    fun contacts(context: Context, limit: Int?): JSONArray = run {
        if (!has(context, Manifest.permission.READ_CONTACTS)) return@run JSONArray()
        val out = JSONArray()
        val cap = limitOf(limit)
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, projection, null, null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val hasPhoneIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val contactIds = ArrayList<String>()
            while (cursor.moveToNext() && contactIds.size < cap) {
                contactIds.add(cursor.getString(idIdx))
                val entry = JSONObject()
                entry.put("id", cursor.getString(idIdx))
                entry.put("name", cursor.getString(nameIdx) ?: "")
                entry.put("hasPhone", cursor.getInt(hasPhoneIdx) != 0)
                entry.put("phones", JSONArray())
                out.put(entry)
            }
            for (entryIndex in 0 until out.length()) {
                val entry = out.getJSONObject(entryIndex)
                val phones = JSONArray()
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE
                    ),
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                    arrayOf(contactIds[entryIndex]),
                    null
                )?.use { pc ->
                    val numIdx = pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val typeIdx = pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                    while (pc.moveToNext()) {
                        phones.put(JSONObject().apply {
                            put("number", pc.getString(numIdx) ?: "")
                            put("type", phoneTypeName(pc.getInt(typeIdx)))
                        })
                    }
                }
                entry.put("phones", phones)
            }
            out
        } ?: JSONArray()
    } ?: JSONArray()

    private fun phoneTypeName(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "other"
        else -> "unknown"
    }

    fun sms(context: Context, limit: Int?, box: String?): JSONArray = run {
        if (!has(context, Manifest.permission.READ_SMS)) return@run JSONArray()
        val out = JSONArray()
        val cap = limitOf(limit)
        val uri = when (box?.lowercase()) {
            "sent" -> android.provider.Telephony.Sms.Sent.CONTENT_URI
            "inbox" -> android.provider.Telephony.Sms.Inbox.CONTENT_URI
            "draft" -> android.provider.Telephony.Sms.Draft.CONTENT_URI
            else -> android.provider.Telephony.Sms.CONTENT_URI
        }
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.Telephony.Sms._ID,
                android.provider.Telephony.Sms.ADDRESS,
                android.provider.Telephony.Sms.BODY,
                android.provider.Telephony.Sms.DATE,
                android.provider.Telephony.Sms.TYPE,
                android.provider.Telephony.Sms.READ
            ),
            null, null,
            android.provider.Telephony.Sms.DATE + " DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms._ID)
            val addrIdx = cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.DATE)
            val typeIdx = cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.TYPE)
            val readIdx = cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.READ)
            while (cursor.moveToNext() && out.length() < cap) {
                out.put(JSONObject().apply {
                    put("id", cursor.getLong(idIdx))
                    put("address", cursor.getString(addrIdx) ?: "")
                    put("body", cursor.getString(bodyIdx) ?: "")
                    put("date", cursor.getLong(dateIdx))
                    put("type", smsBoxName(cursor.getInt(typeIdx)))
                    put("read", cursor.getInt(readIdx) != 0)
                })
            }
            out
        } ?: JSONArray()
    } ?: JSONArray()

    private fun smsBoxName(type: Int): String = when (type) {
        android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
        android.provider.Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        android.provider.Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        android.provider.Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
        android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
        else -> "unknown"
    }

    fun callLog(context: Context, limit: Int?): JSONArray = run {
        if (!has(context, Manifest.permission.READ_CALL_LOG)) return@run JSONArray()
        val out = JSONArray()
        val cap = limitOf(limit)
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            null, null,
            CallLog.Calls.DATE + " DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext() && out.length() < cap) {
                out.put(JSONObject().apply {
                    put("id", cursor.getLong(idIdx))
                    put("name", cursor.getString(nameIdx) ?: "")
                    put("number", cursor.getString(numIdx) ?: "")
                    put("type", callTypeName(cursor.getInt(typeIdx)))
                    put("date", cursor.getLong(dateIdx))
                    put("durationSec", cursor.getLong(durIdx))
                })
            }
            out
        } ?: JSONArray()
    } ?: JSONArray()

    private fun callTypeName(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "unknown"
    }

    fun calendar(context: Context, limit: Int?): JSONArray = run {
        if (!has(context, Manifest.permission.READ_CALENDAR)) return@run JSONArray()
        val out = JSONArray()
        val cap = limitOf(limit)
        val start = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendQueryParameter(
                android.provider.CalendarContract.Instances.BEGIN,
                start.toString()
            )
            .appendQueryParameter(
                android.provider.CalendarContract.Instances.END,
                (System.currentTimeMillis() + 180L * 24 * 60 * 60 * 1000).toString()
            )
            .build()
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.CalendarContract.Instances.EVENT_ID,
                android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.END,
                android.provider.CalendarContract.Instances.EVENT_LOCATION,
                android.provider.CalendarContract.Instances.ALL_DAY,
                android.provider.CalendarContract.Instances.DESCRIPTION
            ),
            null, null,
            android.provider.CalendarContract.Instances.BEGIN + " DESC"
        )?.use { cursor ->
            val eventIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.EVENT_ID)
            val titleIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.TITLE)
            val beginIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.BEGIN)
            val endIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.END)
            val locIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.EVENT_LOCATION)
            val allDayIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.ALL_DAY)
            val descIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.DESCRIPTION)
            while (cursor.moveToNext() && out.length() < cap) {
                out.put(JSONObject().apply {
                    put("eventId", cursor.getLong(eventIdx))
                    put("title", cursor.getString(titleIdx) ?: "")
                    put("start", cursor.getLong(beginIdx))
                    put("end", cursor.getLong(endIdx))
                    put("location", cursor.getString(locIdx) ?: "")
                    put("description", cursor.getString(descIdx) ?: "")
                    put("allDay", cursor.getInt(allDayIdx) != 0)
                })
            }
            out
        } ?: JSONArray()
    } ?: JSONArray()

    // ---- Inventory ---------------------------------------------------------

    fun apps(context: Context, includeSystem: Boolean, limit: Int?): JSONArray = run {
        val out = JSONArray()
        val cap = limitOf(limit)
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.GET_META_DATA else 0
        val pkgs = try {
            pm.getInstalledPackages(flags)
        } catch (ex: Exception) {
            emptyList<android.content.pm.PackageInfo>()
        }
        val filtered = pkgs.sortedBy { it.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: "" }
        for (pi in filtered) {
            val appInfo = pi.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !includeSystem) continue
            if (out.length() >= cap) break
            out.put(JSONObject().apply {
                put("package", pi.packageName)
                put("version", pi.versionName ?: "")
                put("versionCode", if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong())
                put("label", safe { appInfo.loadLabel(pm).toString() })
                put("system", isSystem)
                put("enabled", appInfo.enabled)
                put("uid", appInfo.uid)
                put("installTime", pi.firstInstallTime)
                put("updateTime", pi.lastUpdateTime)
                put("targetSdk", appInfo.targetSdkVersion)
                put("minSdk", appInfo.minSdkVersion)
            })
        }
        out
    } ?: JSONArray()

    fun usageStats(context: Context, hours: Int, limit: Int?): JSONArray = run {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return@run JSONArray()
        val out = JSONArray()
        val cap = limitOf(limit)
        val since = System.currentTimeMillis() - hours.coerceIn(1, 720).toLong() * 60 * 60 * 1000
        try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, since, System.currentTimeMillis())
            val pm = context.packageManager
            val sorted = stats.sortedByDescending { it.totalTimeInForeground }
            for (s in sorted) {
                if (out.length() >= cap) break
                out.put(JSONObject().apply {
                    put("package", s.packageName)
                    put("label", safe {
                        pm.getApplicationInfo(s.packageName, 0).loadLabel(pm).toString()
                    })
                    put("foregroundMs", s.totalTimeInForeground)
                    put("lastUsed", s.lastTimeUsed)
                })
            }
        } catch (ex: Exception) {
            Log.w(TAG, "usage stats unavailable", ex)
        }
        out
    } ?: JSONArray()

    fun notifications(limit: Int?): JSONArray = try {
        MDMNotificationListenerService.snapshot(limitOf(limit))
    } catch (ex: Exception) {
        JSONArray()
    }

    // ---- Permission state --------------------------------------------------

    fun permissionReport(context: Context): JSONObject {
        val runtime = JSONArray()
        for (p in MDMPermissions.allRuntimePermissions()) {
            runtime.put(JSONObject().apply {
                put("permission", p)
                put("granted", context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED)
            })
        }
        val special = JSONArray()
        for (sa in MDMPermissions.specialAccess) {
            val granted = try { sa.isGranted(context) } catch (ex: Exception) { false }
            special.put(JSONObject().apply {
                put("id", sa.id)
                put("required", sa.required)
                put("granted", granted)
            })
        }
        val out = JSONObject()
        out.put("runtime", runtime)
        out.put("special", special)
        return out
    }
}
       