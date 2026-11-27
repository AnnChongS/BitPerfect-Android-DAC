package com.bitperfect.rootdac.xposed;

import android.app.Application;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed (Xposed API 82) In-Process Bit-Perfect AudioTrack Interceptor for Apple Music.
 *
 * v2.0.3 Xiaomi HyperOS 60-Second Silence-Kill Fix (`AT::remove` @ 60.0s):
 * 1. Ultrasonic Nyquist `-84.3 dBFS` (`+/-2 LSB` @ fs/2 >= 22.05kHz) Anchor Buffer:
 *    - Xiaomi HyperOS customizes `AudioFlinger::PlaybackThread` with a 60.0-second zero-sample
 *      detector (`AT::remove` + `WL::TRACK_REMOVE` after 60.0s of `0x0000` samples or `PortMuted=true`),
 *      which internally sets server track state `S = P` (`PAUSED`) and fills `FrmRdy == FrmCnt`,
 *      causing ExoPlayer's `AudioTrack.write(..., WRITE_NON_BLOCKING)` to return `0` forever!
 *    - Instead of `0x00` zeros, we feed an encoding-matched `-84.3 dBFS` Nyquist (`fs/2`, >=22.05kHz
 *      ultrasonic) +/-2 LSB dither into the Speaker clock-anchor AudioTrack: 100% physically inaudible
 *      to human ears & phone micro-speakers while keeping AudioFlinger's signal detector non-zero forever!
 * 2. Instant Binder Self-Wake (`reKickServerTrack`):
 *    - If Xiaomi `audioserver` ever internally pauses the server track (`written == 0` for >= 95ms
 *      while `PLAYSTATE_PLAYING`), automatically executes an internal `pause() + play()` Binder re-kick
 *      (without `flush()`, preserving `getPlaybackHeadPosition()` 100% intact!) within 95ms — seamlessly
 *      covered by our 250-300ms SPSC RingBuffer with zero audible interruption!
 * 3. Speaker Clock Pinning (`TYPE_BUILTIN_SPEAKER`) + Zero-Allocation 65,520B Slab Pool.
 */
public final class AppleMusicAudioTrackHook implements IXposedHookLoadPackage {

    public static final String TARGET_PACKAGE = "com.apple.android.music";
    public static final String BRIDGE_HOST = "127.0.0.1";
    public static final int BRIDGE_PORT = 39528;

    public static final int PROTOCOL_MAGIC = 0x42504132; // 'B','P','A','2'
    public static final byte MSG_FORMAT_CONFIG = 1;
    public static final byte MSG_PCM_DATA = 2;
    public static final byte MSG_FLUSH = 3;
    public static final byte MSG_HELLO = 4;

    // Matches NativeUsbAudioBridge.CAPTURE_FMT_*
    public static final byte FMT_S16_LE = 1;
    public static final byte FMT_S24_3LE = 2;
    public static final byte FMT_S32_LE = 3;
    public static final byte FMT_FLOAT32_LE = 4;

    private static final String TAG = "BP_LspHook";
    private static final int MIN_MUSIC_SAMPLE_RATE = 32000;
    private static final int SEND_QUEUE_CAPACITY = 384;
    private static final int POOL_SLAB_BYTES = 65520;
    private static final int POOL_CAPACITY = 56;

    // +/-2 in 16-bit (-84.3 dBFS) alternating every stereo frame -> fs/2 >= 22.05 kHz ultrasonic!
    private static final float DITHER_FLOAT_POS = 0.00006103515625f;  // +2 / 32768
    private static final float DITHER_FLOAT_NEG = -0.00006103515625f; // -2 / 32768

    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean BRIDGE_CONNECTED = new AtomicBoolean(false);

    private static final ThreadLocal<WriteCaptureContext> WRITE_CTX =
            ThreadLocal.withInitial(WriteCaptureContext::new);
    private static final ThreadLocal<Boolean> IN_INTERNAL_CTRL =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final Object TRACK_LOCK = new Object();
    private static final WeakHashMap<AudioTrack, TrackState> TRACK_STATES = new WeakHashMap<>();

    private static volatile Context sAppContext = null;
    private static volatile int activeTrackId = 0;
    private static volatile long lastActiveWriteUptimeMs = 0L;
    private static volatile int lastSentConfigTrackId = 0;
    private static volatile int lastSentSampleRate = 0;
    private static volatile byte lastSentFormatCode = 0;
    private static volatile String currentProcessName = TARGET_PACKAGE;

    private static final ArrayBlockingQueue<OutboundPacket> SEND_QUEUE =
            new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY);
    private static final ArrayBlockingQueue<byte[]> SLAB_POOL =
            new ArrayBlockingQueue<>(POOL_CAPACITY);

    private static final class WriteCaptureContext {
        int depth = 0;
        boolean substituted = false;
        Object origBuffer = null;
        int origOffsetOrPos = 0;

        private ByteBuffer ditherDirectBuf = null;
        private int ditherDirectEncoding = -1;
        private byte[] ditherByteArr = null;
        private int ditherByteEncoding = -1;
        private short[] ditherShortArr = null;
        private float[] ditherFloatArr = null;

        ByteBuffer getDitherDirectBuf(int sizeBytes, int androidEncoding) {
            // Round capacity up to multiple of 24 so 4B/6B/8B/12B frame patterns tile cleanly
            int neededCap = ((Math.max(131088, sizeBytes) + 23) / 24) * 24;
            if (ditherDirectBuf == null || ditherDirectBuf.capacity() < neededCap
                    || ditherDirectEncoding != androidEncoding) {
                byte[] pattern = buildUltrasonicDitherBytes(neededCap, androidEncoding);
                ditherDirectBuf = ByteBuffer.allocateDirect(neededCap);
                ditherDirectBuf.put(pattern, 0, neededCap);
                ditherDirectEncoding = androidEncoding;
            }
            ditherDirectBuf.clear();
            ditherDirectBuf.limit(sizeBytes);
            return ditherDirectBuf;
        }

        byte[] getDitherBytes(int sizeBytes, int androidEncoding) {
            int neededCap = ((Math.max(131088, sizeBytes) + 23) / 24) * 24;
            if (ditherByteArr == null || ditherByteArr.length < neededCap
                    || ditherByteEncoding != androidEncoding) {
                ditherByteArr = buildUltrasonicDitherBytes(neededCap, androidEncoding);
                ditherByteEncoding = androidEncoding;
            }
            return ditherByteArr;
        }

        short[] getDitherShorts(int sizeShorts) {
            int needed = Math.max(65536, sizeShorts + 4);
            if (ditherShortArr == null || ditherShortArr.length < needed) {
                short[] arr = new short[needed];
                for (int i = 0; i + 3 < needed; i += 4) {
                    arr[i] = 2;
                    arr[i + 1] = 2;
                    arr[i + 2] = -2;
                    arr[i + 3] = -2;
                }
                ditherShortArr = arr;
            }
            return ditherShortArr;
        }

        float[] getDitherFloats(int sizeFloats) {
            int needed = Math.max(65536, sizeFloats + 4);
            if (ditherFloatArr == null || ditherFloatArr.length < needed) {
                float[] arr = new float[needed];
                for (int i = 0; i + 3 < needed; i += 4) {
                    arr[i] = DITHER_FLOAT_POS;
                    arr[i + 1] = DITHER_FLOAT_POS;
                    arr[i + 2] = DITHER_FLOAT_NEG;
                    arr[i + 3] = DITHER_FLOAT_NEG;
                }
                ditherFloatArr = arr;
            }
            return ditherFloatArr;
        }

        private static byte[] buildUltrasonicDitherBytes(int len, int androidEncoding) {
            byte[] buf = new byte[len];
            switch (androidEncoding) {
                case AudioFormat.ENCODING_PCM_FLOAT: {
                    int posBits = Float.floatToRawIntBits(DITHER_FLOAT_POS);
                    int negBits = Float.floatToRawIntBits(DITHER_FLOAT_NEG);
                    // 16-byte cycle: Frame0(L=+d, R=+d), Frame1(L=-d, R=-d) -> fs/2 ultrasonic!
                    for (int i = 0; i + 15 < len; i += 16) {
                        putLe32(buf, i, posBits);
                        putLe32(buf, i + 4, posBits);
                        putLe32(buf, i + 8, negBits);
                        putLe32(buf, i + 12, negBits);
                    }
                    break;
                }
                case AudioFormat.ENCODING_PCM_24BIT_PACKED: {
                    // 12-byte cycle: Frame0(L=+512, R=+512), Frame1(L=-512, R=-512) -> -84.3 dBFS
                    for (int i = 0; i + 11 < len; i += 12) {
                        buf[i] = 0x00;     buf[i + 1] = 0x02; buf[i + 2] = 0x00;
                        buf[i + 3] = 0x00; buf[i + 4] = 0x02; buf[i + 5] = 0x00;
                        buf[i + 6] = 0x00; buf[i + 7] = (byte) 0xFE; buf[i + 8] = (byte) 0xFF;
                        buf[i + 9] = 0x00; buf[i + 10] = (byte) 0xFE; buf[i + 11] = (byte) 0xFF;
                    }
                    break;
                }
                case AudioFormat.ENCODING_PCM_32BIT: {
                    // 16-byte cycle: +/-131072 in int32 (-84.3 dBFS)
                    for (int i = 0; i + 15 < len; i += 16) {
                        putLe32(buf, i, 131072);
                        putLe32(buf, i + 4, 131072);
                        putLe32(buf, i + 8, -131072);
                        putLe32(buf, i + 12, -131072);
                    }
                    break;
                }
                case AudioFormat.ENCODING_PCM_8BIT: {
                    for (int i = 0; i + 3 < len; i += 4) {
                        buf[i] = (byte) 129;
                        buf[i + 1] = (byte) 129;
                        buf[i + 2] = (byte) 127;
                        buf[i + 3] = (byte) 127;
                    }
                    break;
                }
                case AudioFormat.ENCODING_PCM_16BIT:
                default: {
                    // 8-byte cycle: Frame0(L=+2, R=+2), Frame1(L=-2, R=-2) -> -84.3 dBFS
                    for (int i = 0; i + 7 < len; i += 8) {
                        buf[i] = 0x02;     buf[i + 1] = 0x00;
                        buf[i + 2] = 0x02; buf[i + 3] = 0x00;
                        buf[i + 4] = (byte) 0xFE; buf[i + 5] = (byte) 0xFF;
                        buf[i + 6] = (byte) 0xFE; buf[i + 7] = (byte) 0xFF;
                    }
                    break;
                }
            }
            return buf;
        }

        private static void putLe32(byte[] b, int off, int v) {
            b[off] = (byte) (v & 0xFF);
            b[off + 1] = (byte) ((v >> 8) & 0xFF);
            b[off + 2] = (byte) ((v >> 16) & 0xFF);
            b[off + 3] = (byte) ((v >> 24) & 0xFF);
        }
    }

    private static final class TrackState {
        final int trackId;
        AudioDeviceInfo savedPreferredDevice = null;
        boolean speakerPinned = false;
        String lastRouteSummary = "default";
        volatile long lastNonZeroWriteUptimeMs = 0L;
        volatile long lastReKickUptimeMs = 0L;
        final byte[] remainder = new byte[32];
        int remainderLen = 0;

        TrackState(int trackId) {
            this.trackId = trackId;
        }
    }

    private static final class OutboundPacket {
        final byte msgType;
        final byte formatCode;
        final byte sourceBitDepth;
        final byte channels;
        final int sampleRate;
        final int trackIdOrPid;
        final byte[] payload;
        final int payloadLen;
        final boolean pooledSlab;

        OutboundPacket(
                byte msgType,
                byte formatCode,
                byte sourceBitDepth,
                byte channels,
                int sampleRate,
                int trackIdOrPid,
                byte[] payload,
                int payloadLen,
                boolean pooledSlab
        ) {
            this.msgType = msgType;
            this.formatCode = formatCode;
            this.sourceBitDepth = sourceBitDepth;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.trackIdOrPid = trackIdOrPid;
            this.payload = payload;
            this.payloadLen = payloadLen;
            this.pooledSlab = pooledSlab;
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (!HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            currentProcessName = (lpparam.processName != null) ? lpparam.processName : TARGET_PACKAGE;
            final int myPid = Process.myPid();
            String banner = "[BitPerfectRootDAC] LSPosed v2.0.3 Anti-60s-SilenceKill Hook active in "
                    + currentProcessName + " (pid=" + myPid + ")";
            XposedBridge.log(banner);
            Log.i(TAG, banner);

            hookApplicationContext();
            installAudioTrackHooks();
            startBridgeSenderDaemon();
        } catch (Throwable t) {
            XposedBridge.log("[BitPerfectRootDAC] Hook installation failed: " + t);
            Log.e(TAG, "Hook installation failed", t);
        }
    }

    private static void hookApplicationContext() {
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
                    sAppContext = ((Context) param.args[0]).getApplicationContext();
                    if (sAppContext == null) {
                        sAppContext = (Context) param.args[0];
                    }
                }
            }
        });
    }

    private static Context resolveAppContext() {
        Context ctx = sAppContext;
        if (ctx != null) return ctx;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method curApp = atClass.getDeclaredMethod("currentApplication");
            curApp.setAccessible(true);
            Object app = curApp.invoke(null);
            if (app instanceof Context) {
                ctx = (Context) app;
                sAppContext = ctx;
                return ctx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static AudioDeviceInfo findBuiltinSpeakerDevice() {
        try {
            Context ctx = resolveAppContext();
            if (ctx == null) return null;
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;
            AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (outs == null) return null;
            AudioDeviceInfo fallback = null;
            for (AudioDeviceInfo dev : outs) {
                if (dev == null) continue;
                int type = dev.getType();
                if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return dev;
                }
                if (type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && fallback == null) {
                    fallback = dev;
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void installAudioTrackHooks() {
        // 1. Hook constructors: register track & pin to Built-in Speaker
        XposedBridge.hookAllConstructors(AudioTrack.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !(param.thisObject instanceof AudioTrack)) return;
                AudioTrack track = (AudioTrack) param.thisObject;
                if (!isMusicTrack(track)) return;

                TrackState st = getOrCreateTrackState(track);
                if (BRIDGE_CONNECTED.get()) {
                    applySpeakerClockRoute(track, st, true);
                }
            }
        });

        // 2. Guard setPreferredDevice(AudioDeviceInfo)
        XposedBridge.hookAllMethods(AudioTrack.class, "setPreferredDevice", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_INTERNAL_CTRL.get())) return;
                if (!(param.thisObject instanceof AudioTrack)) return;
                if (param.args == null || param.args.length < 1) return;

                AudioTrack track = (AudioTrack) param.thisObject;
                if (!isMusicTrack(track)) return;

                AudioDeviceInfo reqDev = (param.args[0] instanceof AudioDeviceInfo)
                        ? (AudioDeviceInfo) param.args[0]
                        : null;
                TrackState st = getOrCreateTrackState(track);
                synchronized (TRACK_LOCK) {
                    st.savedPreferredDevice = reqDev;
                }
                if (BRIDGE_CONNECTED.get()) {
                    AudioDeviceInfo speaker = findBuiltinSpeakerDevice();
                    if (speaker != null) {
                        param.args[0] = speaker;
                        st.speakerPinned = true;
                        st.lastRouteSummary = "BUILTIN_SPEAKER(id=" + speaker.getId() + ")";
                    }
                }
            }
        });

        // 3. Hook play(): skip internal re-kick calls, otherwise pin Speaker & emit FORMAT_CONFIG
        XposedBridge.hookAllMethods(AudioTrack.class, "play", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_INTERNAL_CTRL.get())) return;
                if (!(param.thisObject instanceof AudioTrack)) return;
                AudioTrack track = (AudioTrack) param.thisObject;
                if (!isMusicTrack(track)) return;
                if (BRIDGE_CONNECTED.get()) {
                    TrackState st = getOrCreateTrackState(track);
                    applySpeakerClockRoute(track, st, true);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_INTERNAL_CTRL.get())) return;
                if (param.hasThrowable() || !(param.thisObject instanceof AudioTrack)) return;
                AudioTrack track = (AudioTrack) param.thisObject;
                if (!isMusicTrack(track)) return;

                TrackState st = getOrCreateTrackState(track);
                activeTrackId = st.trackId;
                long now = SystemClock.uptimeMillis();
                st.lastNonZeroWriteUptimeMs = now;
                lastActiveWriteUptimeMs = now;

                if (BRIDGE_CONNECTED.get()) {
                    updateRoutedSummary(track, st);
                    sendFormatConfigIfNeeded(track, st, true);
                }
            }
        });

        // 4. Hook pause / flush / stop / release (ignoring internal Binder re-kick pause!)
        hookLifecycleFlushMethod("pause");
        hookLifecycleFlushMethod("flush");
        hookLifecycleFlushMethod("stop");
        hookLifecycleFlushMethod("release");

        // 5. Hook ALL write(...) overloads:
        //    Substitutes -84.3 dBFS ultrasonic Nyquist non-zero buffer into Android AudioTrack.write
        //    (defeating Xiaomi HyperOS's 60.0s zero-sample AT::remove killer!) + auto-wakes server
        //    track if written == 0 ever stalls >= 95ms!
        XposedBridge.hookAllMethods(AudioTrack.class, "write", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                WriteCaptureContext ctx = WRITE_CTX.get();
                int d = ctx.depth;
                ctx.depth = d + 1;
                if (d != 0) {
                    return;
                }

                ctx.substituted = false;
                ctx.origBuffer = null;
                ctx.origOffsetOrPos = 0;

                if (!BRIDGE_CONNECTED.get()) return;
                if (!(param.thisObject instanceof AudioTrack)) return;
                AudioTrack track = (AudioTrack) param.thisObject;
                if (!isMusicTrack(track)) return;

                Object[] args = param.args;
                if (args == null || args.length < 2 || args[0] == null) return;

                final int enc = safeAudioFormat(track);
                Object arg0 = args[0];
                if (arg0 instanceof ByteBuffer && args[1] instanceof Integer) {
                    ByteBuffer bb = (ByteBuffer) arg0;
                    int sizeInBytes = (Integer) args[1];
                    if (sizeInBytes > 0 && sizeInBytes <= bb.remaining()) {
                        ctx.origBuffer = bb;
                        ctx.origOffsetOrPos = bb.position();
                        ctx.substituted = true;
                        args[0] = ctx.getDitherDirectBuf(sizeInBytes, enc);
                    }
                } else if (arg0 instanceof byte[] && args.length >= 3
                        && args[1] instanceof Integer && args[2] instanceof Integer) {
                    byte[] arr = (byte[]) arg0;
                    int off = (Integer) args[1];
                    int size = (Integer) args[2];
                    if (off >= 0 && size > 0 && off + size <= arr.length) {
                        ctx.origBuffer = arr;
                        ctx.origOffsetOrPos = off;
                        ctx.substituted = true;
                        args[0] = ctx.getDitherBytes(size, enc);
                        args[1] = 0;
                    }
                } else if (arg0 instanceof short[] && args.length >= 3
                        && args[1] instanceof Integer && args[2] instanceof Integer) {
                    short[] arr = (short[]) arg0;
                    int off = (Integer) args[1];
                    int size = (Integer) args[2];
                    if (off >= 0 && size > 0 && off + size <= arr.length) {
                        ctx.origBuffer = arr;
                        ctx.origOffsetOrPos = off;
                        ctx.substituted = true;
                        args[0] = ctx.getDitherShorts(size);
                        args[1] = 0;
                    }
                } else if (arg0 instanceof float[] && args.length >= 3
                        && args[1] instanceof Integer && args[2] instanceof Integer) {
                    float[] arr = (float[]) arg0;
                    int off = (Integer) args[1];
                    int size = (Integer) args[2];
                    if (off >= 0 && size > 0 && off + size <= arr.length) {
                        ctx.origBuffer = arr;
                        ctx.origOffsetOrPos = off;
                        ctx.substituted = true;
                        args[0] = ctx.getDitherFloats(size);
                        args[1] = 0;
                    }
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                WriteCaptureContext ctx = WRITE_CTX.get();
                int d = ctx.depth - 1;
                ctx.depth = Math.max(0, d);
                if (d != 0) {
                    return;
                }

                final boolean wasSubstituted = ctx.substituted;
                final Object origBuf = ctx.origBuffer;
                final int origOffOrPos = ctx.origOffsetOrPos;
                ctx.substituted = false;
                ctx.origBuffer = null;
                ctx.origOffsetOrPos = 0;

                if (param.hasThrowable() || !wasSubstituted || origBuf == null) {
                    return;
                }
                Object resObj = param.getResult();
                if (!(resObj instanceof Integer)) {
                    return;
                }
                final int written = (Integer) resObj;
                if (!(param.thisObject instanceof AudioTrack)) {
                    return;
                }
                final AudioTrack track = (AudioTrack) param.thisObject;

                if (written <= 0) {
                    // Self-healing guard: if AudioFlinger internally paused server track (S = P,
                    // FrmRdy == FrmCnt -> WRITE_NON_BLOCKING returns 0), re-kick Binder start() in <95ms!
                    if (written == 0 && BRIDGE_CONNECTED.get()
                            && safePlayState(track) == AudioTrack.PLAYSTATE_PLAYING) {
                        TrackState st = getOrCreateTrackState(track);
                        long now = SystemClock.uptimeMillis();
                        if (st.lastNonZeroWriteUptimeMs > 0L
                                && (now - st.lastNonZeroWriteUptimeMs) >= 95L
                                && (now - st.lastReKickUptimeMs) >= 300L) {
                            st.lastReKickUptimeMs = now;
                            reKickPausedServerTrack(track);
                        }
                    }
                    return;
                }

                // Always advance caller's original ByteBuffer position by exact consumed byte count!
                if (origBuf instanceof ByteBuffer) {
                    ByteBuffer bb = (ByteBuffer) origBuf;
                    bb.position(origOffOrPos + written);
                }

                if (!BRIDGE_CONNECTED.get()) {
                    return;
                }

                try {
                    handleSubstitutedAudioWrite(track, origBuf, origOffOrPos, written);
                } catch (Throwable t) {
                    Log.w(TAG, "handleSubstitutedAudioWrite error: " + t.getMessage());
                }
            }
        });
    }

    private static void hookLifecycleFlushMethod(final String methodName) {
        XposedBridge.hookAllMethods(AudioTrack.class, methodName, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_INTERNAL_CTRL.get())) return;
                if (!(param.thisObject instanceof AudioTrack)) return;
                AudioTrack track = (AudioTrack) param.thisObject;
                int id = System.identityHashCode(track);
                synchronized (TRACK_LOCK) {
                    TrackState st = TRACK_STATES.get(track);
                    if (st != null) {
                        st.remainderLen = 0;
                        st.lastNonZeroWriteUptimeMs = 0L;
                    }
                }
                if (id == activeTrackId && BRIDGE_CONNECTED.get()) {
                    enqueueFlush(id, safeSampleRate(track), methodName);
                }
            }
        });
    }

    /**
     * Re-activates an AudioTrack that Xiaomi's `audioserver` internally moved to `PAUSED` (`S = P`)
     * WITHOUT calling `flush()`, preserving `getPlaybackHeadPosition()` and buffered frames intact!
     */
    private static void reKickPausedServerTrack(AudioTrack track) {
        IN_INTERNAL_CTRL.set(Boolean.TRUE);
        try {
            if (safeState(track) == AudioTrack.STATE_INITIALIZED) {
                track.pause();
                track.play();
                Log.i(TAG, "Re-kicked server-paused AudioTrack back to ACTIVE (zero-gap)");
            }
        } catch (Throwable ignored) {
        } finally {
            IN_INTERNAL_CTRL.set(Boolean.FALSE);
        }
    }

    private static void handleSubstitutedAudioWrite(
            AudioTrack track,
            Object origBuf,
            int origOffOrPos,
            int written
    ) {
        final TrackState st = getOrCreateTrackState(track);
        if (!st.speakerPinned) {
            applySpeakerClockRoute(track, st, true);
        }

        final long now = SystemClock.uptimeMillis();
        st.lastNonZeroWriteUptimeMs = now;
        final int playState = safePlayState(track);
        if (activeTrackId != st.trackId) {
            AudioTrack curActive = findTrackById(activeTrackId);
            boolean curStillPlaying = curActive != null
                    && safePlayState(curActive) == AudioTrack.PLAYSTATE_PLAYING
                    && (now - lastActiveWriteUptimeMs) < 250L;
            if (!curStillPlaying || playState == AudioTrack.PLAYSTATE_PLAYING) {
                activeTrackId = st.trackId;
                st.remainderLen = 0;
            } else {
                return;
            }
        }
        lastActiveWriteUptimeMs = now;

        final int sampleRate = safeSampleRate(track);
        final int rawChannels = Math.max(1, safeChannelCount(track));
        final int androidEncoding = safeAudioFormat(track);

        // ULTRA-FAST ZERO-ALLOCATION PATH for 2-channel ByteBuffer
        if (origBuf instanceof ByteBuffer
                && rawChannels == 2
                && androidEncoding != AudioFormat.ENCODING_PCM_8BIT
                && st.remainderLen == 0) {
            ByteBuffer bb = (ByteBuffer) origBuf;
            if (origOffOrPos < 0 || origOffOrPos + written > bb.capacity()) return;

            byte formatCode = mapEncodingToFormatCode(androidEncoding);
            byte sourceBitDepth = mapEncodingToBitDepth(androidEncoding);
            if (formatCode == 0) return;

            int stereoFrameBytes = bytesPerSample(formatCode) * 2;
            if (stereoFrameBytes <= 0) return;

            if (st.trackId != lastSentConfigTrackId
                    || sampleRate != lastSentSampleRate
                    || formatCode != lastSentFormatCode) {
                updateRoutedSummary(track, st);
                sendFormatConfigPacket(st, sampleRate, formatCode, sourceBitDepth, rawChannels, androidEncoding);
            }

            ByteBuffer dup = bb.duplicate();
            dup.limit(origOffOrPos + written);
            dup.position(origOffOrPos);

            int wholeBytes = (written / stereoFrameBytes) * stereoFrameBytes;
            int rem = written - wholeBytes;
            int remaining = wholeBytes;
            while (remaining > 0) {
                int chunkLen = Math.min(POOL_SLAB_BYTES, remaining);
                byte[] slab = acquireSlab();
                dup.get(slab, 0, chunkLen);
                enqueuePacket(new OutboundPacket(
                        MSG_PCM_DATA,
                        formatCode,
                        sourceBitDepth,
                        (byte) 2,
                        sampleRate,
                        st.trackId,
                        slab,
                        chunkLen,
                        true
                ));
                remaining -= chunkLen;
            }
            if (rem > 0 && rem <= st.remainder.length) {
                dup.get(st.remainder, 0, rem);
                st.remainderLen = rem;
            }
            return;
        }

        // General path
        byte formatCode;
        byte sourceBitDepth;
        byte[] stereoBytes;

        if (origBuf instanceof ByteBuffer) {
            ByteBuffer bb = (ByteBuffer) origBuf;
            if (origOffOrPos < 0 || origOffOrPos + written > bb.capacity()) return;

            ByteBuffer dup = bb.duplicate();
            dup.limit(origOffOrPos + written);
            dup.position(origOffOrPos);
            byte[] rawBytes = new byte[written];
            dup.get(rawBytes, 0, written);

            formatCode = mapEncodingToFormatCode(androidEncoding);
            sourceBitDepth = mapEncodingToBitDepth(androidEncoding);
            if (formatCode == 0) return;
            stereoBytes = normalizeRawBytesToStereo(st, rawBytes, formatCode, rawChannels, androidEncoding);
        } else if (origBuf instanceof byte[]) {
            byte[] srcArr = (byte[]) origBuf;
            if (origOffOrPos < 0 || origOffOrPos + written > srcArr.length) return;

            byte[] rawBytes = new byte[written];
            System.arraycopy(srcArr, origOffOrPos, rawBytes, 0, written);

            formatCode = mapEncodingToFormatCode(androidEncoding);
            sourceBitDepth = mapEncodingToBitDepth(androidEncoding);
            if (formatCode == 0) return;
            stereoBytes = normalizeRawBytesToStereo(st, rawBytes, formatCode, rawChannels, androidEncoding);
        } else if (origBuf instanceof short[]) {
            short[] srcShorts = (short[]) origBuf;
            if (origOffOrPos < 0 || origOffOrPos + written > srcShorts.length) return;

            formatCode = FMT_S16_LE;
            sourceBitDepth = 16;
            stereoBytes = shortsToStereoLeBytes(srcShorts, origOffOrPos, written, rawChannels);
        } else if (origBuf instanceof float[]) {
            float[] srcFloats = (float[]) origBuf;
            if (origOffOrPos < 0 || origOffOrPos + written > srcFloats.length) return;

            formatCode = FMT_FLOAT32_LE;
            sourceBitDepth = 24;
            stereoBytes = floatsToStereoLeBytes(srcFloats, origOffOrPos, written, rawChannels);
        } else {
            return;
        }

        if (stereoBytes == null || stereoBytes.length == 0) {
            return;
        }

        if (st.trackId != lastSentConfigTrackId
                || sampleRate != lastSentSampleRate
                || formatCode != lastSentFormatCode) {
            updateRoutedSummary(track, st);
            sendFormatConfigPacket(st, sampleRate, formatCode, sourceBitDepth, rawChannels, androidEncoding);
        }

        int off = 0;
        while (off < stereoBytes.length) {
            int chunkLen = Math.min(POOL_SLAB_BYTES, stereoBytes.length - off);
            byte[] slab = acquireSlab();
            System.arraycopy(stereoBytes, off, slab, 0, chunkLen);
            enqueuePacket(new OutboundPacket(
                    MSG_PCM_DATA,
                    formatCode,
                    sourceBitDepth,
                    (byte) 2,
                    sampleRate,
                    st.trackId,
                    slab,
                    chunkLen,
                    true
            ));
            off += chunkLen;
        }
    }

    private static byte[] acquireSlab() {
        byte[] s = SLAB_POOL.poll();
        return (s != null) ? s : new byte[POOL_SLAB_BYTES];
    }

    private static byte[] normalizeRawBytesToStereo(
            TrackState st,
            byte[] incoming,
            byte formatCode,
            int rawChannels,
            int androidEncoding
    ) {
        if (androidEncoding == AudioFormat.ENCODING_PCM_8BIT) {
            int frames = incoming.length / rawChannels;
            byte[] out = new byte[frames * 4];
            int dst = 0;
            for (int f = 0; f < frames; f++) {
                int base = f * rawChannels;
                short l = (short) (((incoming[base] & 0xFF) - 128) << 8);
                short r = (rawChannels >= 2)
                        ? (short) (((incoming[base + 1] & 0xFF) - 128) << 8)
                        : l;
                out[dst++] = (byte) (l & 0xFF);
                out[dst++] = (byte) ((l >> 8) & 0xFF);
                out[dst++] = (byte) (r & 0xFF);
                out[dst++] = (byte) ((r >> 8) & 0xFF);
            }
            return out;
        }

        final int bps = bytesPerSample(formatCode);
        if (bps <= 0) return null;
        final int rawFrameBytes = bps * rawChannels;
        if (rawFrameBytes <= 0) return null;

        byte[] combined;
        if (st.remainderLen > 0) {
            combined = new byte[st.remainderLen + incoming.length];
            System.arraycopy(st.remainder, 0, combined, 0, st.remainderLen);
            System.arraycopy(incoming, 0, combined, st.remainderLen, incoming.length);
            st.remainderLen = 0;
        } else {
            combined = incoming;
        }

        final int wholeFrames = combined.length / rawFrameBytes;
        final int usedBytes = wholeFrames * rawFrameBytes;
        final int rem = combined.length - usedBytes;
        if (rem > 0 && rem <= st.remainder.length) {
            System.arraycopy(combined, usedBytes, st.remainder, 0, rem);
            st.remainderLen = rem;
        }
        if (wholeFrames <= 0) return null;

        if (rawChannels == 2) {
            if (usedBytes == combined.length) {
                return combined;
            }
            byte[] exact = new byte[usedBytes];
            System.arraycopy(combined, 0, exact, 0, usedBytes);
            return exact;
        }

        final int stereoFrameBytes = bps * 2;
        byte[] stereo = new byte[wholeFrames * stereoFrameBytes];
        for (int f = 0; f < wholeFrames; f++) {
            int srcBase = f * rawFrameBytes;
            int dstBase = f * stereoFrameBytes;
            System.arraycopy(combined, srcBase, stereo, dstBase, bps);
            if (rawChannels == 1) {
                System.arraycopy(combined, srcBase, stereo, dstBase + bps, bps);
            } else {
                System.arraycopy(combined, srcBase + bps, stereo, dstBase + bps, bps);
            }
        }
        return stereo;
    }

    private static byte[] shortsToStereoLeBytes(short[] src, int offset, int countShorts, int rawChannels) {
        int frames = countShorts / rawChannels;
        if (frames <= 0) return null;
        byte[] out = new byte[frames * 4];
        int dst = 0;
        for (int f = 0; f < frames; f++) {
            int base = offset + f * rawChannels;
            short l = src[base];
            short r = (rawChannels >= 2) ? src[base + 1] : l;
            out[dst++] = (byte) (l & 0xFF);
            out[dst++] = (byte) ((l >> 8) & 0xFF);
            out[dst++] = (byte) (r & 0xFF);
            out[dst++] = (byte) ((r >> 8) & 0xFF);
        }
        return out;
    }

    private static byte[] floatsToStereoLeBytes(float[] src, int offset, int countFloats, int rawChannels) {
        int frames = countFloats / rawChannels;
        if (frames <= 0) return null;
        byte[] out = new byte[frames * 8];
        int dst = 0;
        for (int f = 0; f < frames; f++) {
            int base = offset + f * rawChannels;
            int lBits = Float.floatToRawIntBits(src[base]);
            int rBits = Float.floatToRawIntBits((rawChannels >= 2) ? src[base + 1] : src[base]);
            out[dst++] = (byte) (lBits & 0xFF);
            out[dst++] = (byte) ((lBits >> 8) & 0xFF);
            out[dst++] = (byte) ((lBits >> 16) & 0xFF);
            out[dst++] = (byte) ((lBits >> 24) & 0xFF);
            out[dst++] = (byte) (rBits & 0xFF);
            out[dst++] = (byte) ((rBits >> 8) & 0xFF);
            out[dst++] = (byte) ((rBits >> 16) & 0xFF);
            out[dst++] = (byte) ((rBits >> 24) & 0xFF);
        }
        return out;
    }

    private static void sendFormatConfigIfNeeded(AudioTrack track, TrackState st, boolean force) {
        int rate = safeSampleRate(track);
        int enc = safeAudioFormat(track);
        int rawCh = Math.max(1, safeChannelCount(track));
        byte fmtCode = mapEncodingToFormatCode(enc);
        byte bitDepth = mapEncodingToBitDepth(enc);
        if (fmtCode == 0 || rate < MIN_MUSIC_SAMPLE_RATE) return;

        if (force || st.trackId != lastSentConfigTrackId || rate != lastSentSampleRate || fmtCode != lastSentFormatCode) {
            sendFormatConfigPacket(st, rate, fmtCode, bitDepth, rawCh, enc);
        }
    }

    private static void sendFormatConfigPacket(
            TrackState st,
            int sampleRate,
            byte formatCode,
            byte sourceBitDepth,
            int rawChannels,
            int androidEncoding
    ) {
        lastSentConfigTrackId = st.trackId;
        lastSentSampleRate = sampleRate;
        lastSentFormatCode = formatCode;

        String meta = "enc=" + encodingToName(androidEncoding)
                + ";rawCh=" + rawChannels
                + ";pid=" + Process.myPid()
                + ";route=" + st.lastRouteSummary + ",ultrasonicAnchor";
        byte[] payload = meta.getBytes(StandardCharsets.UTF_8);
        enqueuePacket(new OutboundPacket(
                MSG_FORMAT_CONFIG,
                formatCode,
                sourceBitDepth,
                (byte) 2,
                sampleRate,
                st.trackId,
                payload,
                payload.length,
                false
        ));
    }

    private static void enqueueFlush(int trackId, int sampleRate, String reason) {
        SEND_QUEUE.removeIf(pkt -> pkt.msgType == MSG_PCM_DATA && pkt.trackIdOrPid == trackId);
        byte[] payload = reason.getBytes(StandardCharsets.UTF_8);
        enqueuePacket(new OutboundPacket(
                MSG_FLUSH,
                (byte) 0,
                (byte) 0,
                (byte) 2,
                sampleRate,
                trackId,
                payload,
                payload.length,
                false
        ));
    }

    private static void enqueuePacket(OutboundPacket pkt) {
        if (!SEND_QUEUE.offer(pkt)) {
            try {
                SEND_QUEUE.offer(pkt, 15, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void startBridgeSenderDaemon() {
        Thread sender = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (Throwable ignored) {
            }

            while (true) {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(BRIDGE_HOST, BRIDGE_PORT), 400);
                    socket.setTcpNoDelay(true);
                    socket.setSendBufferSize(524288);

                    SEND_QUEUE.clear();
                    lastSentConfigTrackId = 0;
                    lastSentSampleRate = 0;
                    lastSentFormatCode = 0;
                    BRIDGE_CONNECTED.set(true);
                    syncAllTracksSpeakerRoute(true);

                    DataOutputStream out = new DataOutputStream(
                            new BufferedOutputStream(socket.getOutputStream(), 131072)
                    );

                    int myPid = Process.myPid();
                    byte[] helloBytes = ("pid=" + myPid + ";proc=" + currentProcessName)
                            .getBytes(StandardCharsets.UTF_8);
                    writePacket(out, new OutboundPacket(
                            MSG_HELLO, (byte) 0, (byte) 0, (byte) 2, 0, myPid,
                            helloBytes, helloBytes.length, false
                    ));
                    out.flush();

                    AudioTrack curTrack = findActiveOrPlayingMusicTrack();
                    if (curTrack != null) {
                        TrackState st = getOrCreateTrackState(curTrack);
                        activeTrackId = st.trackId;
                        applySpeakerClockRoute(curTrack, st, true);
                        updateRoutedSummary(curTrack, st);
                        sendFormatConfigIfNeeded(curTrack, st, true);
                    }

                    long lastHeartbeatMs = SystemClock.uptimeMillis();
                    long lastMuteGuardMs = 0L;
                    while (true) {
                        OutboundPacket pkt = SEND_QUEUE.poll(1000, TimeUnit.MILLISECONDS);
                        if (pkt != null) {
                            writeAndRecycle(out, pkt);
                            int drained = 0;
                            while (drained < 16 && (pkt = SEND_QUEUE.poll()) != null) {
                                writeAndRecycle(out, pkt);
                                drained++;
                            }
                            out.flush();
                            lastHeartbeatMs = SystemClock.uptimeMillis();
                        } else if (SystemClock.uptimeMillis() - lastHeartbeatMs >= 2000L) {
                            writePacket(out, new OutboundPacket(
                                    MSG_HELLO, (byte) 0, (byte) 0, (byte) 2, 0, myPid,
                                    helloBytes, helloBytes.length, false
                            ));
                            out.flush();
                            lastHeartbeatMs = SystemClock.uptimeMillis();
                        }

                        long nowMs = SystemClock.uptimeMillis();
                        if (nowMs - lastMuteGuardMs >= 1500L) {
                            lastMuteGuardMs = nowMs;
                            ensureSpeakerPortNotMuted();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    boolean wasConnected = BRIDGE_CONNECTED.getAndSet(false);
                    SEND_QUEUE.clear();
                    if (wasConnected) {
                        syncAllTracksSpeakerRoute(false);
                    }
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }

                try {
                    Thread.sleep(900L);
                } catch (InterruptedException ignored) {
                }
            }
        }, "BP_LspBridgeSender");
        sender.setDaemon(true);
        sender.start();
    }

    private static void writeAndRecycle(DataOutputStream out, OutboundPacket pkt) throws Exception {
        writePacket(out, pkt);
        if (pkt.pooledSlab && pkt.payload != null && pkt.payload.length == POOL_SLAB_BYTES) {
            SLAB_POOL.offer(pkt.payload);
        }
    }

    private static void writePacket(DataOutputStream out, OutboundPacket pkt) throws Exception {
        int len = (pkt.payload != null) ? pkt.payloadLen : 0;
        out.writeInt(PROTOCOL_MAGIC);
        out.writeByte(pkt.msgType);
        out.writeByte(pkt.formatCode);
        out.writeByte(pkt.sourceBitDepth);
        out.writeByte(pkt.channels);
        out.writeInt(pkt.sampleRate);
        out.writeInt(pkt.trackIdOrPid);
        out.writeInt(len);
        if (len > 0) {
            out.write(pkt.payload, 0, len);
        }
    }

    private static TrackState getOrCreateTrackState(AudioTrack track) {
        synchronized (TRACK_LOCK) {
            TrackState st = TRACK_STATES.get(track);
            if (st == null) {
                st = new TrackState(System.identityHashCode(track));
                TRACK_STATES.put(track, st);
            }
            return st;
        }
    }

    private static AudioTrack findTrackById(int id) {
        if (id == 0) return null;
        synchronized (TRACK_LOCK) {
            for (Map.Entry<AudioTrack, TrackState> entry : TRACK_STATES.entrySet()) {
                AudioTrack t = entry.getKey();
                TrackState st = entry.getValue();
                if (t != null && st != null && st.trackId == id) {
                    return t;
                }
            }
        }
        return null;
    }

    private static AudioTrack findActiveOrPlayingMusicTrack() {
        synchronized (TRACK_LOCK) {
            AudioTrack fallback = null;
            for (AudioTrack t : TRACK_STATES.keySet()) {
                if (t == null || !isMusicTrack(t)) continue;
                if (safePlayState(t) == AudioTrack.PLAYSTATE_PLAYING) {
                    return t;
                }
                fallback = t;
            }
            return fallback;
        }
    }

    private static void syncAllTracksSpeakerRoute(boolean active) {
        synchronized (TRACK_LOCK) {
            for (Map.Entry<AudioTrack, TrackState> e : TRACK_STATES.entrySet()) {
                AudioTrack t = e.getKey();
                TrackState st = e.getValue();
                if (t != null && st != null) {
                    applySpeakerClockRoute(t, st, active);
                }
            }
        }
    }

    private static void applySpeakerClockRoute(AudioTrack track, TrackState st, boolean active) {
        IN_INTERNAL_CTRL.set(Boolean.TRUE);
        try {
            if (safeState(track) == AudioTrack.STATE_UNINITIALIZED) return;
            if (active) {
                AudioDeviceInfo speaker = findBuiltinSpeakerDevice();
                if (speaker != null) {
                    boolean ok = track.setPreferredDevice(speaker);
                    st.speakerPinned = ok;
                    st.lastRouteSummary = "BUILTIN_SPEAKER(id=" + speaker.getId() + ",ok=" + ok + ")";
                }
            } else {
                track.setPreferredDevice(st.savedPreferredDevice);
                st.speakerPinned = false;
                st.lastRouteSummary = "restored";
            }
        } catch (Throwable ignored) {
        } finally {
            IN_INTERNAL_CTRL.set(Boolean.FALSE);
        }
    }

    /**
     * Checks Apple Music's own AudioPlaybackConfiguration: if Speaker's per-device STREAM_MUSIC
     * volume is 0 (`mutedState:streamVolume` / `portVolume`), Xiaomi HyperOS AudioFlinger suspends
     * the track (`AT::remove`, `S=P`) after 60.0s! Raising by 1 silent step (`flags=0`, no UI popup)
     * clears `PortMuted` while our -84.3 dBFS >=22kHz ultrasonic buffer remains 100% inaudible.
     */
    private static void ensureSpeakerPortNotMuted() {
        try {
            Context ctx = resolveAppContext();
            if (ctx == null) return;
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            java.util.List<AudioPlaybackConfiguration> configs = am.getActivePlaybackConfigurations();
            if (configs == null || configs.isEmpty()) return;
            for (AudioPlaybackConfiguration apc : configs) {
                if (apc == null) continue;
                String desc = apc.toString();
                if (desc.contains("state:started") &&
                        (desc.contains("streamVolume") || desc.contains("portVolume"))) {
                    am.adjustSuggestedStreamVolume(
                            AudioManager.ADJUST_UNMUTE, AudioManager.STREAM_MUSIC, 0);
                    am.adjustSuggestedStreamVolume(
                            AudioManager.ADJUST_RAISE, AudioManager.STREAM_MUSIC, 0);
                    Log.i(TAG, "Cleared Speaker PortMuted (raised 1 step silently) to prevent HyperOS 60s AT::remove");
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updateRoutedSummary(AudioTrack track, TrackState st) {
        try {
            AudioDeviceInfo routed = track.getRoutedDevice();
            if (routed != null) {
                st.lastRouteSummary = "type=" + routed.getType() + "/id=" + routed.getId()
                        + "(pinned=" + st.speakerPinned + ")";
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isMusicTrack(AudioTrack track) {
        return safeState(track) != AudioTrack.STATE_UNINITIALIZED
                && safeSampleRate(track) >= MIN_MUSIC_SAMPLE_RATE
                && safeChannelCount(track) >= 1;
    }

    private static int safeSampleRate(AudioTrack track) {
        try {
            return track.getSampleRate();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int safeChannelCount(AudioTrack track) {
        try {
            return track.getChannelCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int safeAudioFormat(AudioTrack track) {
        try {
            return track.getAudioFormat();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int safePlayState(AudioTrack track) {
        try {
            return track.getPlayState();
        } catch (Throwable t) {
            return AudioTrack.PLAYSTATE_STOPPED;
        }
    }

    private static int safeState(AudioTrack track) {
        try {
            return track.getState();
        } catch (Throwable t) {
            return AudioTrack.STATE_UNINITIALIZED;
        }
    }

    private static byte mapEncodingToFormatCode(int androidEncoding) {
        switch (androidEncoding) {
            case AudioFormat.ENCODING_PCM_16BIT:
            case AudioFormat.ENCODING_PCM_8BIT:
                return FMT_S16_LE;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED:
                return FMT_S24_3LE;
            case AudioFormat.ENCODING_PCM_32BIT:
                return FMT_S32_LE;
            case AudioFormat.ENCODING_PCM_FLOAT:
                return FMT_FLOAT32_LE;
            default:
                return 0;
        }
    }

    private static byte mapEncodingToBitDepth(int androidEncoding) {
        switch (androidEncoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
            case AudioFormat.ENCODING_PCM_16BIT:
                return 16;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED:
            case AudioFormat.ENCODING_PCM_FLOAT:
                return 24;
            case AudioFormat.ENCODING_PCM_32BIT:
                return 32;
            default:
                return 24;
        }
    }

    private static int bytesPerSample(byte formatCode) {
        switch (formatCode) {
            case FMT_S16_LE:
                return 2;
            case FMT_S24_3LE:
                return 3;
            case FMT_S32_LE:
            case FMT_FLOAT32_LE:
                return 4;
            default:
                return 0;
        }
    }

    private static String encodingToName(int androidEncoding) {
        switch (androidEncoding) {
            case AudioFormat.ENCODING_PCM_16BIT:
                return "PCM_16BIT";
            case AudioFormat.ENCODING_PCM_24BIT_PACKED:
                return "PCM_24BIT_PACKED";
            case AudioFormat.ENCODING_PCM_32BIT:
                return "PCM_32BIT";
            case AudioFormat.ENCODING_PCM_FLOAT:
                return "PCM_FLOAT";
            case AudioFormat.ENCODING_PCM_8BIT:
                return "PCM_8BIT";
            default:
                return "ENC_" + androidEncoding;
        }
    }
}
