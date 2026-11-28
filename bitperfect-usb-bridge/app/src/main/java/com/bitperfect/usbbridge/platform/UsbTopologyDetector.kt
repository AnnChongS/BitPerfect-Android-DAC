package com.bitperfect.usbbridge.platform

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import java.io.File

enum class UsbConnectionTopology {
    /** Directly plugged into the device's USB-C root port (depth = 1, e.g. sysfs 1-1 / devnum 2). */
    DIRECT_ROOT_PORT,

    /** Connected downstream of an external USB Hub / Dock (depth >= 2, e.g. sysfs 1-1.2 / devnum >= 3). */
    VIA_USB_HUB,

    /** Connection topology could not be resolved reliably. */
    UNKNOWN
}

data class UsbTopologyInfo(
    val topology: UsbConnectionTopology,
    val busNum: Int,
    val devNum: Int,
    val isKernelProven: Boolean,
    val detail: String
)

/**
 * Zero-Root USB connection topology detector.
 *
 * Used primarily on Xiaomi XRING O1 (玄戒 O1) devices where the kernel `xring_usb_adsp_v1.ko`
 * driver hijacks direct-plugged USB audio devices into ADSP Host Mode (disabling ARM xHCI IRQs),
 * causing infinite USB permission popups and high risk of system freeze when any non-root app
 * detaches IF#0. Devices plugged behind an external USB Hub bypass ADSP offload, so distinguishing
 * DIRECT_ROOT_PORT vs VIA_USB_HUB lets us hard-block direct USB while permitting Hub mode with an
 * explicit stability & audio-quality disclaimer.
 */
object UsbTopologyDetector {

    private val DEV_BUS_USB_REGEX = Regex("""/dev/bus/usb/(\d+)/(\d+)""")
    private val SYSFS_DIRECT_PORT_REGEX = Regex("""^\d+-\d+$""")
    private val SYSFS_HUB_PORT_REGEX = Regex("""^\d+-\d+(\.\d+)+$""")

    fun detect(
        device: UsbDevice,
        allUsbDevices: Collection<UsbDevice>,
        socVendor: SocVendor
    ): UsbTopologyInfo {
        val (busNum, devNum) = parseBusAndDevNum(device.deviceName)

        // Layer 1: Authoritative Linux sysfs char-device symlink (/sys/dev/char/189:<minor>)
        // Direct root port resolves to ".../usb1/1-1"; Hub downstream resolves to ".../usb1/1-1/1-1.2"
        if (busNum > 0 && devNum > 0) {
            val minor = (busNum - 1) * 128 + (devNum - 1)
            val charLink = runCatching { File("/sys/dev/char/189:$minor").canonicalPath }.getOrNull()
            if (!charLink.isNullOrBlank()) {
                val tail = charLink.substringAfterLast('/')
                when {
                    SYSFS_HUB_PORT_REGEX.matches(tail) -> {
                        return UsbTopologyInfo(
                            topology = UsbConnectionTopology.VIA_USB_HUB,
                            busNum = busNum,
                            devNum = devNum,
                            isKernelProven = true,
                            detail = "sysfs=$tail (bus=$busNum,dev=$devNum)"
                        )
                    }
                    SYSFS_DIRECT_PORT_REGEX.matches(tail) -> {
                        return UsbTopologyInfo(
                            topology = UsbConnectionTopology.DIRECT_ROOT_PORT,
                            busNum = busNum,
                            devNum = devNum,
                            isKernelProven = true,
                            detail = "sysfs=$tail (bus=$busNum,dev=$devNum)"
                        )
                    }
                }
            }
        }

        // Layer 2: Authoritative /sys/bus/usb/devices scan (if readable under OEM SELinux)
        if (busNum > 0 && devNum > 0) {
            val sysBusDir = File("/sys/bus/usb/devices")
            val entries = runCatching { sysBusDir.listFiles() }.getOrNull()
            if (entries != null && entries.isNotEmpty()) {
                for (entry in entries) {
                    val name = entry.name
                    if (!SYSFS_DIRECT_PORT_REGEX.matches(name) && !SYSFS_HUB_PORT_REGEX.matches(name)) {
                        continue
                    }
                    val eBus = readTrimmedInt(File(entry, "busnum"))
                    val eDev = readTrimmedInt(File(entry, "devnum"))
                    if (eBus == busNum && eDev == devNum) {
                        val topo = if (SYSFS_HUB_PORT_REGEX.matches(name)) {
                            UsbConnectionTopology.VIA_USB_HUB
                        } else {
                            UsbConnectionTopology.DIRECT_ROOT_PORT
                        }
                        return UsbTopologyInfo(
                            topology = topo,
                            busNum = busNum,
                            devNum = devNum,
                            isKernelProven = true,
                            detail = "usb_sysfs=$name (bus=$busNum,dev=$devNum)"
                        )
                    }
                }
                // Check if root port "<busNum>-1" is itself a USB Hub (bDeviceClass == 09)
                val rootPortClass = runCatching {
                    File(sysBusDir, "$busNum-1/bDeviceClass").readText().trim().lowercase()
                }.getOrNull()
                if (rootPortClass == "09") {
                    return UsbTopologyInfo(
                        topology = UsbConnectionTopology.VIA_USB_HUB,
                        busNum = busNum,
                        devNum = devNum,
                        isKernelProven = true,
                        detail = "root_port_hub=$busNum-1 (bus=$busNum,dev=$devNum)"
                    )
                }
            }
        }

        // Layer 3: XRING kernel ADSP offload indicator (/sys/devices/platform/soc/soc:xr_usb_adsp/supp_adsp)
        // Non-zero ("1"/"2") proves kernel xring_usb_adsp_v1 actively holds ADSP offload for this DAC.
        if (socVendor == SocVendor.XRING) {
            val suppAdsp = runCatching {
                File("/sys/devices/platform/soc/soc:xr_usb_adsp/supp_adsp").readText().trim()
            }.getOrNull()
            if (!suppAdsp.isNullOrEmpty() && suppAdsp != "0") {
                return UsbTopologyInfo(
                    topology = UsbConnectionTopology.DIRECT_ROOT_PORT,
                    busNum = busNum,
                    devNum = devNum,
                    isKernelProven = true,
                    detail = "xr_usb_adsp/supp_adsp=$suppAdsp (bus=$busNum,dev=$devNum)"
                )
            }
        }

        // Layer 4: Pure Android Zero-Root UsbManager topology deduction (works even under strict SELinux)
        val sameBusDevices = allUsbDevices.filter { other ->
            val (oBus, _) = parseBusAndDevNum(other.deviceName)
            busNum <= 0 || oBus == busNum
        }

        // 4a. Explicit USB Hub class device present in deviceList
        if (sameBusDevices.any { it.deviceClass == UsbConstants.USB_CLASS_HUB }) {
            return UsbTopologyInfo(
                topology = UsbConnectionTopology.VIA_USB_HUB,
                busNum = busNum,
                devNum = devNum,
                isKernelProven = false,
                detail = "hub_class_detected (bus=$busNum,dev=$devNum)"
            )
        }

        // 4b. Multiple simultaneous USB peripherals on a single-port bus physically requires a Hub
        if (sameBusDevices.size >= 2) {
            return UsbTopologyInfo(
                topology = UsbConnectionTopology.VIA_USB_HUB,
                busNum = busNum,
                devNum = devNum,
                isKernelProven = false,
                detail = "multi_usb_same_bus=${sameBusDevices.size} (bus=$busNum,dev=$devNum)"
            )
        }

        // 4c. Linux xHCI devnum rule on single-port OTG root hub:
        //   - devnum == 1 is always the Root Hub
        //   - devnum == 2 is mathematically guaranteed to be directly plugged into the Root Hub
        //     (an external USB Hub MUST enumerate at devnum 2 before any downstream DAC can enumerate)
        //   - devnum >= 3 when Android UsbHostManager filters out USB_CLASS_HUB indicates the Hub
        //     occupied devnum 2 and the DAC enumerated downstream at devnum >= 3
        //   (Backed up by a runtime ADSP-reset circuit breaker on XRING for defense-in-depth.)
        return when {
            devNum == 2 -> UsbTopologyInfo(
                topology = UsbConnectionTopology.DIRECT_ROOT_PORT,
                busNum = busNum,
                devNum = devNum,
                isKernelProven = false,
                detail = "root_port_devnum=2 (bus=$busNum,dev=$devNum)"
            )
            devNum >= 3 -> UsbTopologyInfo(
                topology = UsbConnectionTopology.VIA_USB_HUB,
                busNum = busNum,
                devNum = devNum,
                isKernelProven = false,
                detail = "downstream_devnum=$devNum (bus=$busNum,dev=$devNum)"
            )
            else -> UsbTopologyInfo(
                topology = UsbConnectionTopology.UNKNOWN,
                busNum = busNum,
                devNum = devNum,
                isKernelProven = false,
                detail = "unresolved (${device.deviceName})"
            )
        }
    }

    /**
     * Fail-closed XRING direct-USB protection policy:
     * On Xiaomi XRING devices, direct-plugged DACs are locked by default (Hub is allowed with
     * disclaimer). Users on newer XRING phones / future XRING O3 / newer ROMs can explicitly
     * unlock direct USB for the current plug session via the high-friction red disclaimer dialog;
     * if the runtime ADSP-reset circuit breaker trips, it always locks back immediately.
     */
    fun isBlockedForXringDirectUsb(
        socVendor: SocVendor,
        info: UsbTopologyInfo,
        circuitBreakerTripped: Boolean,
        directTestOverrideUnlocked: Boolean = false
    ): Boolean {
        if (socVendor != SocVendor.XRING) return false
        if (circuitBreakerTripped) return true
        if (directTestOverrideUnlocked) return false
        return info.topology != UsbConnectionTopology.VIA_USB_HUB
    }

    fun isXringHubDisclaimerRequired(
        socVendor: SocVendor,
        info: UsbTopologyInfo,
        circuitBreakerTripped: Boolean
    ): Boolean {
        if (socVendor != SocVendor.XRING) return false
        if (circuitBreakerTripped) return false
        return info.topology == UsbConnectionTopology.VIA_USB_HUB
    }

    private fun parseBusAndDevNum(deviceName: String?): Pair<Int, Int> {
        if (deviceName.isNullOrBlank()) return -1 to -1
        val m = DEV_BUS_USB_REGEX.find(deviceName) ?: return -1 to -1
        val bus = m.groupValues[1].toIntOrNull() ?: -1
        val dev = m.groupValues[2].toIntOrNull() ?: -1
        return bus to dev
    }

    private fun readTrimmedInt(file: File): Int {
        return runCatching { file.readText().trim().toInt() }.getOrDefault(-1)
    }
}
