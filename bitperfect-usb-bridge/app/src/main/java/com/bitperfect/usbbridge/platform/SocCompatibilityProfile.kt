package com.bitperfect.usbbridge.platform

import android.annotation.SuppressLint
import android.os.Build

enum class SocVendor(
    val displayNameEn: String,
    val displayNameZh: String
) {
    QUALCOMM("Snapdragon", "高通骁龙"),
    XRING("Xiaomi XRING", "小米玄戒"),
    MEDIATEK("MediaTek Dimensity", "联发科天玑"),
    EXYNOS("Samsung Exynos", "三星 Exynos"),
    TENSOR("Google Tensor", "Google Tensor"),
    GENERIC_ARM("Universal ARM", "通用 ARM 平台")
}

/**
 * Runtime SoC & vendor audio-HAL compatibility profile.
 *
 * Isolates Qualcomm-only PAL DeepBuffer pre-warm strictly to Qualcomm Snapdragon chips, while
 * enabling pure standard Android AudioPlaybackCapture + safe progressive libusb teardown across
 * Xiaomi XRING (玄戒), Samsung Exynos, MediaTek Dimensity, Google Tensor, and generic ARM SoCs.
 */
data class SocCompatibilityProfile(
    val vendor: SocVendor,
    val socDisplayName: String,
    val deviceModel: String,
    val rawBoardPlatform: String,
    val rawHardware: String,
    val useQualcommPalPreWarm: Boolean,
    val rebindSettleDelayMs: Long
) {
    fun headerBadge(isZh: Boolean): String {
        val chip = socDisplayName.ifBlank { if (isZh) vendor.displayNameZh else vendor.displayNameEn }
        return if (useQualcommPalPreWarm) {
            if (isZh) "$chip · PAL 护航" else "$chip · PAL Guard"
        } else {
            if (isZh) "$chip · 通用纯净 USB" else "$chip · Universal Pure USB"
        }
    }

    fun strategyDescription(isZh: Boolean): String {
        return when (vendor) {
            SocVendor.QUALCOMM -> if (isZh) {
                "高通骁龙模式：启用 PAL DeepBuffer 时钟锚定 + 两阶段无重置 USB 接管"
            } else {
                "Qualcomm mode: PAL DeepBuffer clock anchor + progressive zero-reset USB takeover"
            }
            SocVendor.XRING -> if (isZh) {
                "玄戒 XRING 模式：跳过高通 PAL 预热 · 标准 AudioRecord + 渐进式无重置 USB 接管"
            } else {
                "Xiaomi XRING mode: Skipping Qualcomm PAL · Pure AudioRecord + progressive safe USB takeover"
            }
            SocVendor.EXYNOS -> if (isZh) {
                "三星 Exynos 模式：跳过高通 PAL 预热 · 避让 Capture IRQ + 渐进式无重置 USB 接管"
            } else {
                "Samsung Exynos mode: Skipping Qualcomm PAL · Pure AudioRecord + safe Exynos USB teardown"
            }
            SocVendor.MEDIATEK -> if (isZh) {
                "联发科天玑模式：跳过高通 PAL 预热 · 避让 mtk-usb-offload + 渐进式无重置 USB 接管"
            } else {
                "MediaTek Dimensity mode: Skipping Qualcomm PAL · Safe progressive USB detach (no port reset)"
            }
            SocVendor.TENSOR -> if (isZh) {
                "Google Tensor 模式：跳过高通 PAL 预热 · 原生 AIDL AudioRecord + 渐进式无重置 USB 接管"
            } else {
                "Google Tensor mode: Skipping Qualcomm PAL · Native AIDL AudioRecord + safe USB takeover"
            }
            SocVendor.GENERIC_ARM -> if (isZh) {
                "通用 ARM 平台模式：跳过高通 PAL 预热 · 标准 AudioPlaybackCapture + 渐进式无重置 USB 接管"
            } else {
                "Universal ARM mode: Skipping Qualcomm PAL · Standard AudioPlaybackCapture + safe USB takeover"
            }
        }
    }

    fun diagnosticSummary(): String {
        return "SoC=${vendor.name} ($socDisplayName) | Device=$deviceModel | " +
            "platform=$rawBoardPlatform | hw=$rawHardware | palPreWarm=$useQualcommPalPreWarm"
    }

    companion object {
        @SuppressLint("PrivateApi")
        private fun getSystemProp(key: String): String {
            return runCatching {
                val cls = Class.forName("android.os.SystemProperties")
                val method = cls.getMethod("get", String::class.java, String::class.java)
                (method.invoke(null, key, "") as? String)?.trim().orEmpty()
            }.getOrDefault("")
        }

        fun detect(): SocCompatibilityProfile {
            val socMfg = runCatching { Build.SOC_MANUFACTURER.orEmpty().trim() }.getOrDefault("")
            val socModel = runCatching { Build.SOC_MODEL.orEmpty().trim() }.getOrDefault("")
            val propSocMfg = getSystemProp("ro.soc.manufacturer")
            val propSocModel = getSystemProp("ro.soc.model")
            val boardPlatform = getSystemProp("ro.board.platform")
            val propHardware = getSystemProp("ro.hardware")
            val hardware = Build.HARDWARE.orEmpty().trim()
            val board = Build.BOARD.orEmpty().trim()
            val deviceMfg = Build.MANUFACTURER.orEmpty().trim()
            val deviceModelStr = buildString {
                if (deviceMfg.isNotEmpty() && !Build.MODEL.startsWith(deviceMfg, ignoreCase = true)) {
                    append(deviceMfg).append(' ')
                }
                append(Build.MODEL.orEmpty().trim())
            }.trim()

            val effectiveSocMfg = socMfg.ifBlank { propSocMfg }
            val effectiveSocModel = socModel.ifBlank { propSocModel }
            val combinedSoc = listOf(
                effectiveSocMfg,
                effectiveSocModel,
                boardPlatform,
                propHardware,
                hardware,
                board
            ).joinToString(" ").lowercase()

            val mfgLower = effectiveSocMfg.lowercase()
            val platformLower = boardPlatform.lowercase()
            val hwLower = hardware.lowercase()

            val vendor = when {
                // 1. Xiaomi XRING (玄戒 O1 etc.) — check SOC_MANUFACTURER / SOC_MODEL / platform (NOT device Build.MANUFACTURER!)
                combinedSoc.contains("xring") ||
                    mfgLower == "xiaomi" ||
                    mfgLower.contains("xring") ||
                    platformLower == "o1" ||
                    platformLower.startsWith("xr") -> SocVendor.XRING

                // 2. Qualcomm Snapdragon (including Samsung / Xiaomi devices using Snapdragon)
                mfgLower.contains("qti") ||
                    mfgLower.contains("qualcomm") ||
                    combinedSoc.contains("qualcomm") ||
                    combinedSoc.contains("snapdragon") ||
                    combinedSoc.contains("qcom") ||
                    Regex("""\b(sm|sdm|qcs|qcm|msm)\d{3,4}""").containsMatchIn(combinedSoc) ||
                    platformLower in setOf(
                        "sun", "pakala", "canoe", "pineapple", "kalama", "taro",
                        "lahaina", "kona", "crow", "cliffs", "volcano", "niobe", "pitti"
                    ) -> SocVendor.QUALCOMM

                // 3. MediaTek Dimensity / Helio
                mfgLower.contains("mediatek") ||
                    mfgLower.contains("mtk") ||
                    combinedSoc.contains("mediatek") ||
                    combinedSoc.contains("dimensity") ||
                    combinedSoc.contains("helio") ||
                    Regex("""\bmt\d{4}""").containsMatchIn(combinedSoc) -> SocVendor.MEDIATEK

                // 4. Samsung Exynos
                mfgLower.contains("samsung") ||
                    mfgLower.contains("exynos") ||
                    mfgLower.contains("s.lsi") ||
                    combinedSoc.contains("exynos") ||
                    Regex("""\bs5e\d{4}""").containsMatchIn(combinedSoc) ||
                    hwLower.startsWith("samsungexynos") ||
                    platformLower.startsWith("s5e") -> SocVendor.EXYNOS

                // 5. Google Tensor
                mfgLower.contains("google") ||
                    combinedSoc.contains("tensor") ||
                    combinedSoc.contains("whitechapel") ||
                    Regex("""\bgs[12]01\b""").containsMatchIn(combinedSoc) ||
                    platformLower in setOf("zuma", "zumapro", "laguna", "cloudripper", "slider") -> SocVendor.TENSOR

                else -> SocVendor.GENERIC_ARM
            }

            val displayName = when {
                effectiveSocModel.isNotBlank() && effectiveSocMfg.isNotBlank() &&
                    !effectiveSocModel.startsWith(effectiveSocMfg, ignoreCase = true) &&
                    effectiveSocMfg.length <= 12 -> {
                    val cleanMfg = if (effectiveSocMfg.equals("QTI", ignoreCase = true)) "Qualcomm" else effectiveSocMfg
                    "$cleanMfg $effectiveSocModel"
                }
                effectiveSocModel.isNotBlank() -> effectiveSocModel
                boardPlatform.isNotBlank() -> "${vendor.displayNameEn} ($boardPlatform)"
                else -> vendor.displayNameEn
            }

            return SocCompatibilityProfile(
                vendor = vendor,
                socDisplayName = displayName,
                deviceModel = deviceModelStr.ifBlank { "Android" },
                rawBoardPlatform = boardPlatform.ifBlank { board.ifBlank { "unknown" } },
                rawHardware = hardware.ifBlank { propHardware.ifBlank { "unknown" } },
                useQualcommPalPreWarm = (vendor == SocVendor.QUALCOMM),
                rebindSettleDelayMs = if (vendor == SocVendor.QUALCOMM) 320L else 280L
            )
        }
    }
}
