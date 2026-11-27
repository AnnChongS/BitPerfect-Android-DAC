package com.bitperfect.usbbridge.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.bitperfect.usbbridge.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

sealed class ShizukuConnectionState {
    data object NotRunning : ShizukuConnectionState()
    data object PermissionRequired : ShizukuConnectionState()
    data object Binding : ShizukuConnectionState()
    data class Ready(val uid: Int) : ShizukuConnectionState()
    data class Error(val message: String) : ShizukuConnectionState()

    val isReady: Boolean
        get() = this is Ready

    val shortBadge: String
        get() = when (this) {
            is NotRunning -> "Shizuku 未运行"
            is PermissionRequired -> "Shizuku 待授权"
            is Binding -> "Shizuku 绑定中..."
            is Ready -> if (uid == 2000) "Shizuku ADB (uid=2000)" else "Shizuku (uid=$uid)"
            is Error -> "Shizuku 异常"
        }
}

/**
 * Manages Shizuku 13.1.5 binder lifecycle, runtime permission, and AIDL UserService (uid=2000).
 * Completely zero-Root; degrades gracefully when Shizuku is not installed or stopped.
 */
class ShizukuController(
    context: Context,
    private val onLog: (level: Int, msg: String) -> Unit
) {
    companion object {
        private const val SHIZUKU_PERMISSION_REQ_CODE = 0x5A17
    }

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<ShizukuConnectionState>(ShizukuConnectionState.NotRunning)
    val state: StateFlow<ShizukuConnectionState> = _state.asStateFlow()

    @Volatile
    private var service: IShizukuAudioService? = null

    @Volatile
    private var listenersRegistered = false

    @Volatile
    private var bindingInProgress = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuAudioUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shizuku_audio")
        .tag("bitperfect_shizuku_audio")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindingInProgress = false
            if (binder == null || !binder.isBinderAlive) {
                service = null
                _state.value = ShizukuConnectionState.Error("UserService binder dead")
                onLog(3, "Shizuku UserService 连接返回无效 Binder")
                return
            }
            try {
                val svc = IShizukuAudioService.Stub.asInterface(binder)
                val uid = svc.uid
                service = svc
                _state.value = ShizukuConnectionState.Ready(uid)
                onLog(1, "Shizuku UserService 已就绪 (uid=$uid, 免 Root ADB 模式)")
            } catch (e: Throwable) {
                service = null
                val msg = "Shizuku UserService 握手失败: ${e.message}"
                _state.value = ShizukuConnectionState.Error(msg)
                onLog(3, msg)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bindingInProgress = false
            service = null
            onLog(2, "Shizuku UserService 已断开连接")
            refreshStatus()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        onLog(1, "检测到 Shizuku 服务在线 (Binder Received)")
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        bindingInProgress = false
        service = null
        _state.value = ShizukuConnectionState.NotRunning
        onLog(2, "Shizuku 服务已停止 (Binder Dead)")
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_PERMISSION_REQ_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            onLog(1, "Shizuku 授权已通过，正在绑定 UserService...")
            bindUserServiceIfReady()
        } else {
            _state.value = ShizukuConnectionState.PermissionRequired
            onLog(2, "用户拒绝了 Shizuku 授权（仍可使用纯无 Root 手动模式）")
        }
    }

    fun start() {
        if (listenersRegistered) {
            refreshStatus()
            return
        }
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            listenersRegistered = true
        }.onFailure { e ->
            onLog(2, "Shizuku 监听器初始化跳过: ${e.message}")
        }
        refreshStatus()
    }

    fun stop() {
        unbindUserService()
        if (listenersRegistered) {
            runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
            runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
            listenersRegistered = false
        }
    }

    fun refreshStatus() {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            service = null
            bindingInProgress = false
            _state.value = ShizukuConnectionState.NotRunning
            return
        }

        val currentSvc = service
        if (currentSvc != null && currentSvc.asBinder().isBinderAlive) {
            val uid = runCatching { currentSvc.uid }.getOrDefault(2000)
            _state.value = ShizukuConnectionState.Ready(uid)
            return
        }

        val granted = hasPermission()
        if (!granted) {
            _state.value = ShizukuConnectionState.PermissionRequired
            return
        }

        bindUserServiceIfReady()
    }

    fun hasPermission(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestPermission() {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            _state.value = ShizukuConnectionState.NotRunning
            onLog(2, "未检测到运行中的 Shizuku 服务，请先在 Shizuku App 中通过无线调试启动")
            return
        }
        if (hasPermission()) {
            bindUserServiceIfReady()
            return
        }
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                onLog(2, "请在 Shizuku App 中手动允许 BitPerfect USB Bridge 的授权")
            }
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ_CODE)
        }.onFailure { e ->
            onLog(3, "请求 Shizuku 权限异常: ${e.message}")
        }
    }

    private fun bindUserServiceIfReady() {
        val existing = service
        if (existing != null && existing.asBinder().isBinderAlive) {
            return
        }
        if (bindingInProgress) return
        bindingInProgress = true
        _state.value = ShizukuConnectionState.Binding
        runCatching {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        }.onFailure { e ->
            bindingInProgress = false
            val msg = "绑定 Shizuku UserService 失败: ${e.message}"
            _state.value = ShizukuConnectionState.Error(msg)
            onLog(3, msg)
        }
    }

    fun unbindUserService() {
        val svc = service
        service = null
        bindingInProgress = false
        if (svc != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        }
    }

    private fun activeService(): IShizukuAudioService? {
        val svc = service ?: return null
        return if (svc.asBinder().isBinderAlive) svc else {
            service = null
            refreshStatus()
            null
        }
    }

    fun dumpAudioPlayback(targetUid: Int): String? {
        val svc = activeService() ?: return null
        return runCatching { svc.dumpAudioPlayback(targetUid) }.getOrNull()
    }

    fun dumpAudioPolicySubmix(): String? {
        val svc = activeService() ?: return null
        return runCatching { svc.dumpAudioPolicySubmix() }.getOrNull()
    }

    fun applyBackgroundKeepAlive(extraPackages: List<String> = emptyList()): String? {
        val svc = activeService() ?: return null
        val csv = buildList {
            add(appContext.packageName)
            extraPackages.forEach { pkg ->
                val clean = pkg.trim()
                if (clean.isNotEmpty() && clean != appContext.packageName) {
                    add(clean)
                }
            }
        }.distinct().take(6).joinToString(",")

        return runCatching {
            val res = svc.applyBackgroundKeepAlive(csv)
            onLog(1, "Shizuku 后台保活已下发: $res")
            res
        }.onFailure { e ->
            onLog(2, "Shizuku 后台保活失败: ${e.message}")
        }.getOrNull()
    }

    fun dumpDiagnosticsSnapshot(targetUid: Int): String? {
        val svc = activeService() ?: return null
        return runCatching {
            svc.dumpDiagnosticsSnapshot(targetUid, appContext.packageName)
        }.onFailure { e ->
            onLog(3, "Shizuku 抓取诊断快照失败: ${e.message}")
        }.getOrNull()
    }
}
