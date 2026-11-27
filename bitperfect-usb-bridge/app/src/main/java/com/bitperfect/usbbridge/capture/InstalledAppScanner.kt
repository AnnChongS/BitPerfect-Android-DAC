package com.bitperfect.usbbridge.capture

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

data class InstalledAudioApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isRecommendedAudio: Boolean,
    val icon: ImageBitmap? = null
)

data class ResolvedUidApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: ImageBitmap? = null
)

/**
 * Scans all launchable apps on the device (including non-mainstream audio apps like DLsite Sound
 * and rhythm games) and provides cached UID -> App (label + icon) resolution for Shizuku tracking.
 */
object InstalledAppScanner {

    private const val ICON_PX = 72

    // Curated priority set of music, Hi-Fi local players, ASMR/drama audio apps, and rhythm games
    private val PRIORITY_PACKAGES = linkedSetOf(
        // Major Hi-Fi / Lossless / Streaming
        "com.apple.android.music",
        "com.tencent.qqmusic",
        "com.netease.cloudmusic",
        "com.kugou.android",
        "cn.kuwo.player",
        "com.luna.music",
        "com.aspiro.tidal",
        "com.qobuz.music",
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.amazon.mp3",
        "deezer.android.app",
        "com.soundcloud.android",
        "com.bandcamp.android",
        "com.roon.mobile",
        "tv.plex.labs.plexamp",
        "com.sec.android.app.music",
        "com.miui.player",
        // Local Hi-Fi / Audiophile Players
        "com.foobar2000.foobar2000",
        "com.hiby.music",
        "com.maxmpz.audioplayer",
        "com.extreamsd.usbaudioplayerpro",
        "com.neutroncode.mp",
        "com.aimp.player",
        "app.symfonik.music.player",
        "in.krosbits.musicolet",
        "com.salt.music",
        "com.fiio.music",
        "com.onkyo.jp.musicplayer",
        // Non-mainstream Audio / ASMR / Voice Drama / Video
        "jp.co.eisys.dlsitesound",
        "jp.co.dlsite.dlsitesound",
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "com.google.android.youtube",
        "app.podcast.cosmos",
        "com.ximalaya.ting.android",
        "cn.missevan",
        // Rhythm Games (音游)
        "com.PigeonGames.Phigros",
        "com.PigeonGames.Rizline",
        "moe.low.arc",
        "com.prpr.musedash",
        "com.sega.pjsekai",
        "com.sega.ColorfulStage.en",
        "com.hermes.mk",
        "com.rayark.cytus2",
        "com.rayark.deemo2",
        "com.rayark.pluto",
        "com.xd.rotaeno",
        "sh.ppy.osulazer",
        "com.mugzone.malody",
        "com.c4cat.dynamix"
    )

    private val AUDIO_KEYWORD_HINTS = listOf(
        "music", "audio", "sound", "player", "hifi", "flac", "dlsite",
        "podcast", "radio", "fm", "rhythm", "phigros", "arcaea", "musedash",
        "sekai", "cytus", "deemo", "rotaeno", "malody", "dynamix", "rizline",
        "milthm", "paradigm", "lanota", "orzmic", "kalpa", "bangdream", "d4dj",
        "音乐", "听书", "播客", "猫耳", "喜马拉雅", "漫播"
    )

    private val uidCache = ConcurrentHashMap<Int, ResolvedUidApp>()

    fun scanLaunchableApps(context: Context): List<InstalledAudioApp> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = runCatching {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        }.getOrDefault(emptyList())

        val seenPackages = HashSet<String>()
        val results = ArrayList<InstalledAudioApp>(resolveInfos.size)

        for (ri in resolveInfos) {
            val activityInfo = ri.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            if (pkg == selfPkg || !seenPackages.add(pkg)) continue

            val appInfo = activityInfo.applicationInfo ?: continue
            val label = runCatching {
                ri.loadLabel(pm)?.toString()?.trim()
            }.getOrNull().orEmpty().ifBlank { pkg }

            val isRecommended = isRecommendedAudioApp(pkg, label)
            val icon = runCatching {
                drawableToImageBitmap(ri.loadIcon(pm))
            }.getOrNull()

            val entry = InstalledAudioApp(
                packageName = pkg,
                label = label,
                uid = appInfo.uid,
                isRecommendedAudio = isRecommended,
                icon = icon
            )
            results.add(entry)
            uidCache[appInfo.uid] = ResolvedUidApp(
                uid = appInfo.uid,
                packageName = pkg,
                label = label,
                icon = icon
            )
        }

        return results.sortedWith(
            compareByDescending<InstalledAudioApp> { it.isRecommendedAudio }
                .thenBy { pkgPriorityRank(it.packageName) }
                .thenBy { it.label.lowercase() }
        )
    }

    fun resolvePackageToApp(context: Context, packageName: String): InstalledAudioApp? {
        val pm = context.packageManager
        return runCatching {
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            val label = pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty().ifBlank { packageName }
            val icon = runCatching { drawableToImageBitmap(pm.getApplicationIcon(appInfo)) }.getOrNull()
            val entry = InstalledAudioApp(
                packageName = packageName,
                label = label,
                uid = appInfo.uid,
                isRecommendedAudio = isRecommendedAudioApp(packageName, label),
                icon = icon
            )
            uidCache[appInfo.uid] = ResolvedUidApp(appInfo.uid, packageName, label, icon)
            entry
        }.getOrNull()
    }

    fun resolveUidToApp(context: Context, uid: Int): ResolvedUidApp? {
        if (uid <= 0) return null
        uidCache[uid]?.let { return it }

        val pm = context.packageManager
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
        val pkg = packages?.firstOrNull() ?: return null

        val resolved = runCatching {
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            val label = pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty().ifBlank { pkg }
            val icon = runCatching { drawableToImageBitmap(pm.getApplicationIcon(appInfo)) }.getOrNull()
            ResolvedUidApp(uid = uid, packageName = pkg, label = label, icon = icon)
        }.getOrElse {
            ResolvedUidApp(uid = uid, packageName = pkg, label = pkg, icon = null)
        }
        uidCache[uid] = resolved
        return resolved
    }

    fun clearCache() {
        uidCache.clear()
    }

    private fun isRecommendedAudioApp(pkg: String, label: String): Boolean {
        if (pkg in PRIORITY_PACKAGES) return true
        val lowerPkg = pkg.lowercase()
        val lowerLabel = label.lowercase()
        return AUDIO_KEYWORD_HINTS.any { hint ->
            lowerPkg.contains(hint) || lowerLabel.contains(hint)
        }
    }

    private fun pkgPriorityRank(pkg: String): Int {
        val idx = PRIORITY_PACKAGES.indexOf(pkg)
        return if (idx >= 0) idx else Int.MAX_VALUE
    }

    private fun drawableToImageBitmap(drawable: Drawable?): ImageBitmap? {
        if (drawable == null) return null
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
