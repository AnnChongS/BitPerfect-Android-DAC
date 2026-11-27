package com.bitperfect.rootdac.root;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Looper;
import android.os.Process;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Privileged Root (uid=0) AudioPolicy loopback daemon launched via:
 *   CLASSPATH=<base.apk> exec /system/bin/app_process64 /system/bin \
 *       --nice-name=bitperfect_loopback \
 *       com.bitperfect.rootdac.root.RootLoopbackDaemon <targetUid> <sampleRate>
 *
 * Registers an AudioPolicy with pure AudioMix.ROUTE_FLAG_LOOP_BACK (NO RENDER!),
 * matching ONLY targetUid + USAGE_MEDIA/USAGE_UNKNOWN:
 *   - Completely bypasses DuplicatingThread and Phone Speaker FLAG_DEEP_BUFFER bursts
 *     (eliminating r_submix MonoPipe 15ms timeout memset(0) silence dropouts).
 *   - Dedicated r_submix MixerThread at the exact requested sample rate (true Zero-SRC).
 *   - Uses AudioRecord.SUBMIX_FIXED_VOLUME ("fixedVolume") for 0.0 dB bit-exact unity gain.
 *   - Silences phone speaker for Apple Music automatically while streaming PCM to stdout.
 */
public final class RootLoopbackDaemon {

    // Framed binary protocol on raw FileDescriptor.out (little-endian)
    public static final int MAGIC_HEADER = 0x48505042; // "BPPH"
    public static final int MAGIC_DATA = 0x44505042;   // "BPPD"

    // Must match NativeUsbAudioBridge.CAPTURE_FMT_* constants
    private static final int FMT_PCM16_LE = 1;
    private static final int FMT_PCM24_PACKED_LE = 2;
    private static final int FMT_PCM32_LE = 3;
    private static final int FMT_FLOAT32_LE = 4;

    private static final int CHANNELS = 2;

    private static final AtomicBoolean sRunning = new AtomicBoolean(true);
    private static volatile AudioRecord sActiveRecord = null;
    private static volatile AudioManager sAudioManager = null;
    private static volatile Object sActivePolicy = null;

    private static final class FormatCandidate {
        final int androidEncoding;
        final int nativeFormatCode;
        final int bytesPerSample;
        final String name;

        FormatCandidate(int androidEncoding, int nativeFormatCode, int bytesPerSample, String name) {
            this.androidEncoding = androidEncoding;
            this.nativeFormatCode = nativeFormatCode;
            this.bytesPerSample = bytesPerSample;
            this.name = name;
        }
    }

    private static final class RootContextWrapper extends ContextWrapper {
        private final String opPackageName;
        private final AttributionSource attributionSource;

        RootContextWrapper(Context base, String opPackageName) {
            super(base);
            this.opPackageName = opPackageName;
            this.attributionSource = new AttributionSource.Builder(Process.myUid())
                    .setPackageName(opPackageName)
                    .build();
        }

        @Override
        public String getOpPackageName() {
            return opPackageName;
        }

        @Override
        public String getPackageName() {
            return opPackageName;
        }

        @Override
        public AttributionSource getAttributionSource() {
            return attributionSource;
        }
    }

    private static void logErr(String msg) {
        System.err.println(msg);
        System.err.flush();
    }

    private static void tryExemptHiddenApis() {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
            // app_process64 as root typically already has hidden API enforcement disabled
        }
    }

    private static int getStaticIntField(Class<?> cls, String name, int fallback) {
        try {
            return cls.getField(name).getInt(null);
        } catch (Throwable t) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(null);
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    private static String getStaticStringField(Class<?> cls, String name, String fallback) {
        try {
            Object val = cls.getField(name).get(null);
            return (val instanceof String) ? (String) val : fallback;
        } catch (Throwable t) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                Object val = f.get(null);
                return (val instanceof String) ? (String) val : fallback;
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    private static void writeLeInt(byte[] dst, int offset, int value) {
        dst[offset] = (byte) (value & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        dst[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static Object buildAudioMixingRule(int targetUid) throws Exception {
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> builderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");

        int ruleMatchUsage = getStaticIntField(ruleClass, "RULE_MATCH_ATTRIBUTE_USAGE", 0x1);
        int ruleMatchUid = getStaticIntField(ruleClass, "RULE_MATCH_UID", 0x4);

        Object builder = builderClass.getDeclaredConstructor().newInstance();

        Method addRule = builderClass.getMethod("addRule", AudioAttributes.class, int.class);
        AudioAttributes mediaAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
        AudioAttributes unknownAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        addRule.invoke(builder, mediaAttr, ruleMatchUsage);
        addRule.invoke(builder, unknownAttr, ruleMatchUsage);

        Method addMixRule = builderClass.getMethod("addMixRule", int.class, Object.class);
        addMixRule.invoke(builder, ruleMatchUid, Integer.valueOf(targetUid));

        // IMPORTANT: Do NOT call allowPrivilegedPlaybackCapture(true)!
        // AOSP AudioMix.Builder enforces a hard 16000 Hz mono cap whenever
        // allowPrivilegedMediaPlaybackCapture is true (for accessibility live captioning).
        // Apple Music already permits media capture, so leaving it false enables full
        // stereo Hi-Res 44.1k / 48k / 96k / 192kHz ENCODING_PCM_FLOAT loopback!
        Method build = builderClass.getMethod("build");
        return build.invoke(builder);
    }

    private static Object buildLoopbackAudioMix(Object mixingRule, int sampleRate, int androidEncoding) throws Exception {
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");

        int routeFlagLoopBack = getStaticIntField(mixClass, "ROUTE_FLAG_LOOP_BACK", 0x2);

        Constructor<?> ctor = mixBuilderClass.getConstructor(ruleClass);
        Object mixBuilder = ctor.newInstance(mixingRule);

        // Playback capture mixes MUST use output channel mask (CHANNEL_OUT_STEREO)
        AudioFormat mixFormat = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(androidEncoding)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();

        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, mixFormat);
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, routeFlagLoopBack);
        return mixBuilderClass.getMethod("build").invoke(mixBuilder);
    }

    private static Object buildAudioPolicy(Context ctx, Object audioMix) throws Exception {
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");

        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(ctx);
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, audioMix);
        policyBuilderClass.getMethod("setLooper", Looper.class).invoke(policyBuilder, Looper.getMainLooper());
        return policyBuilderClass.getMethod("build").invoke(policyBuilder);
    }

    private static int registerAudioPolicy(AudioManager am, Object policy) throws Exception {
        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Method registerMethod = AudioManager.class.getMethod("registerAudioPolicy", policyClass);
        Object res = registerMethod.invoke(am, policy);
        return (res instanceof Integer) ? (Integer) res : -1;
    }

    private static void unregisterAudioPolicySafe(AudioManager am, Object policy) {
        if (am == null || policy == null) return;
        try {
            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Method unregisterMethod = AudioManager.class.getMethod("unregisterAudioPolicy", policyClass);
            unregisterMethod.invoke(am, policy);
            logErr("Unregistered Root AudioPolicy cleanly");
        } catch (Throwable t) {
            try {
                Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
                Method unregisterAsync = AudioManager.class.getMethod("unregisterAudioPolicyAsync", policyClass);
                unregisterAsync.invoke(am, policy);
                logErr("Unregistered Root AudioPolicy (async)");
            } catch (Throwable t2) {
                logErr("Failed to unregister AudioPolicy: " + t2);
            }
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        try {
            Method m = cls.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        }
    }

    private static AudioRecord buildCustomSubmixAudioRecord(
            Context ctx,
            Object audioMix,
            int sampleRate,
            FormatCandidate fmt,
            int[] outBufBytes
    ) {
        try {
            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            String reg = (String) findMethod(mixClass, "getRegistration").invoke(audioMix);
            if (reg == null || reg.isEmpty()) {
                logErr("AudioMix.getRegistration() returned empty");
                return null;
            }

            String fixedVolTag = getStaticStringField(AudioRecord.class, "SUBMIX_FIXED_VOLUME", "fixedVolume");
            int remoteSubmixPreset = MediaRecorder.AudioSource.REMOTE_SUBMIX;

            AudioAttributes.Builder ab = new AudioAttributes.Builder();
            Method setInternalPreset = findMethod(AudioAttributes.Builder.class, "setInternalCapturePreset", int.class);
            Method addTag = findMethod(AudioAttributes.Builder.class, "addTag", String.class);
            setInternalPreset.invoke(ab, remoteSubmixPreset);
            addTag.invoke(ab, "addr=" + reg);
            addTag.invoke(ab, fixedVolTag);
            AudioAttributes attr = ab.build();

            AudioFormat inFormat = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(fmt.androidEncoding)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();

            int frameBytes = fmt.bytesPerSample * CHANNELS;
            int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, fmt.androidEncoding);
            if (minBuf <= 0) {
                minBuf = (sampleRate / 20) * frameBytes;
            }
            int target200ms = ((sampleRate * frameBytes) / 5 / frameBytes) * frameBytes;
            int bufBytes = Math.max(minBuf * 4, target200ms);

            AudioRecord.Builder rb = new AudioRecord.Builder()
                    .setAudioFormat(inFormat)
                    .setBufferSizeInBytes(bufBytes)
                    .setContext(ctx);
            Method setAudioAttrs = findMethod(AudioRecord.Builder.class, "setAudioAttributes", AudioAttributes.class);
            setAudioAttrs.invoke(rb, attr);

            AudioRecord rec = rb.build();
            if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                outBufBytes[0] = bufBytes;
                logErr("AudioRecord initialized via Builder (pkg=" + ctx.getOpPackageName()
                        + ", reg=" + reg + ", fixedVolume=true, buf=" + bufBytes + "B)");
                return rec;
            }
            rec.release();
            logErr("AudioRecord Builder state != INITIALIZED (pkg=" + ctx.getOpPackageName() + ")");
            return null;
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            logErr("AudioRecord Builder attempt failed (pkg=" + ctx.getOpPackageName() + "): " + cause);
            return null;
        }
    }

    private static AudioRecord createSinkViaAudioPolicy(
            Object audioPolicy,
            Object audioMix,
            FormatCandidate fmt,
            int[] outBufBytes
    ) {
        try {
            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            Method createSink = policyClass.getMethod("createAudioRecordSink", mixClass);
            AudioRecord rec = (AudioRecord) createSink.invoke(audioPolicy, audioMix);
            if (rec != null && rec.getState() == AudioRecord.STATE_INITIALIZED) {
                outBufBytes[0] = rec.getBufferSizeInFrames() * fmt.bytesPerSample * CHANNELS;
                logErr("AudioRecord initialized via AudioPolicy.createAudioRecordSink (frames="
                        + rec.getBufferSizeInFrames() + ", buf=" + outBufBytes[0] + "B)");
                return rec;
            }
            if (rec != null) rec.release();
            logErr("createAudioRecordSink returned uninitialized AudioRecord");
            return null;
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            logErr("createAudioRecordSink failed: " + cause);
            return null;
        }
    }

    private static void cleanupAndExit(int code) {
        sRunning.set(false);
        AudioRecord rec = sActiveRecord;
        sActiveRecord = null;
        if (rec != null) {
            try {
                if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop();
                }
            } catch (Throwable ignored) {}
            try {
                rec.release();
            } catch (Throwable ignored) {}
        }

        Object policy = sActivePolicy;
        sActivePolicy = null;
        unregisterAudioPolicySafe(sAudioManager, policy);

        try {
            Looper mainLooper = Looper.getMainLooper();
            if (mainLooper != null) {
                mainLooper.quitSafely();
            }
        } catch (Throwable ignored) {}

        System.exit(code);
    }

    public static void main(String[] args) {
        // Reserve raw binary stdout BEFORE any Android framework call can print to System.out!
        final OutputStream rawOut = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 32768);
        System.setOut(System.err);

        if (args == null || args.length < 2) {
            logErr("Usage: RootLoopbackDaemon <targetUid> <sampleRate>");
            System.exit(2);
            return;
        }

        final int targetUid;
        final int requestedRate;
        try {
            targetUid = Integer.parseInt(args[0]);
            requestedRate = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            logErr("Invalid args: " + e.getMessage());
            System.exit(2);
            return;
        }

        tryExemptHiddenApis();
        logErr("RootLoopbackDaemon starting (uid=" + Process.myUid() + ", pid=" + Process.myPid()
                + ", targetUid=" + targetUid + ", requestedRate=" + requestedRate + "Hz)");

        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getDeclaredMethod("systemMain").invoke(null);
            final Context systemContext = (Context) activityThreadClass
                    .getDeclaredMethod("getSystemContext")
                    .invoke(activityThread);

            String canonicalPkg = (Process.myUid() == 0) ? "root"
                    : (Process.myUid() == 2000 ? "com.android.shell" : "android");
            final Context rootContext = new RootContextWrapper(systemContext, canonicalPkg);

            final AudioManager audioManager = (AudioManager) systemContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                logErr("AudioManager is null");
                System.exit(3);
                return;
            }
            sAudioManager = audioManager;

            final Object mixingRule = buildAudioMixingRule(targetUid);

            final FormatCandidate[] candidates = new FormatCandidate[]{
                    new FormatCandidate(AudioFormat.ENCODING_PCM_FLOAT, FMT_FLOAT32_LE, 4, "PCM_FLOAT"),
                    new FormatCandidate(AudioFormat.ENCODING_PCM_32BIT, FMT_PCM32_LE, 4, "PCM_32BIT"),
                    new FormatCandidate(AudioFormat.ENCODING_PCM_24BIT_PACKED, FMT_PCM24_PACKED_LE, 3, "PCM_24BIT_PACKED"),
                    new FormatCandidate(AudioFormat.ENCODING_PCM_16BIT, FMT_PCM16_LE, 2, "PCM_16BIT")
            };

            AudioRecord chosenRecord = null;
            FormatCandidate chosenFmt = null;
            Object chosenPolicy = null;
            int[] outBufBytes = new int[]{0};

            for (FormatCandidate candidate : candidates) {
                Object mix;
                try {
                    mix = buildLoopbackAudioMix(mixingRule, requestedRate, candidate.androidEncoding);
                } catch (Throwable t) {
                    Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                    logErr("AudioMix build failed for " + candidate.name + "@" + requestedRate + "Hz: " + cause);
                    continue;
                }

                // Register AudioPolicy with rootContext first, fallback to systemContext
                Object policy = null;
                int regRc = -1;
                try {
                    policy = buildAudioPolicy(rootContext, mix);
                    regRc = registerAudioPolicy(audioManager, policy);
                } catch (Throwable t) {
                    Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                    logErr("registerAudioPolicy(rootContext, " + candidate.name + ") threw: " + cause);
                }

                if (regRc != 0) {
                    logErr("registerAudioPolicy(rootContext) rc=" + regRc + ", retrying with systemContext...");
                    try {
                        policy = buildAudioPolicy(systemContext, mix);
                        regRc = registerAudioPolicy(audioManager, policy);
                    } catch (Throwable t) {
                        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                        logErr("registerAudioPolicy(systemContext, " + candidate.name + ") threw: " + cause);
                        regRc = -1;
                    }
                }

                if (regRc != 0 || policy == null) {
                    logErr("registerAudioPolicy FAILED for " + candidate.name + "@" + requestedRate + "Hz (rc=" + regRc + ")");
                    continue;
                }

                logErr("AudioPolicy REGISTERED (ROUTE_FLAG_LOOP_BACK, uid=" + targetUid
                        + ", rate=" + requestedRate + "Hz, fmt=" + candidate.name + ")");

                // 3-tier AudioRecord creation (prefer 200ms buffer + SUBMIX_FIXED_VOLUME)
                AudioRecord rec = buildCustomSubmixAudioRecord(rootContext, mix, requestedRate, candidate, outBufBytes);
                if (rec == null) {
                    rec = buildCustomSubmixAudioRecord(systemContext, mix, requestedRate, candidate, outBufBytes);
                }
                if (rec == null) {
                    rec = createSinkViaAudioPolicy(policy, mix, candidate, outBufBytes);
                }

                if (rec != null) {
                    chosenRecord = rec;
                    chosenFmt = candidate;
                    chosenPolicy = policy;
                    break;
                }

                unregisterAudioPolicySafe(audioManager, policy);
            }

            if (chosenRecord == null || chosenFmt == null || chosenPolicy == null) {
                logErr("FATAL: Could not register Root AudioPolicy + AudioRecord for uid="
                        + targetUid + " @ " + requestedRate + "Hz");
                System.exit(4);
                return;
            }

            sActivePolicy = chosenPolicy;
            sActiveRecord = chosenRecord;

            final AudioRecord finalRecord = chosenRecord;
            final FormatCandidate finalFmt = chosenFmt;
            final int finalBufBytes = outBufBytes[0];

            // Watch stdin for EOF / stop signal so parent stop OR crash always unregisters AudioPolicy!
            Thread stdinWatcher = new Thread(() -> {
                try {
                    InputStream in = System.in;
                    while (sRunning.get()) {
                        int b = in.read();
                        if (b == -1 || b == 'Q' || b == '\n') {
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    logErr("Stop signal / stdin EOF received -> shutting down RootLoopbackDaemon...");
                    sRunning.set(false);
                    try {
                        if (finalRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            finalRecord.stop();
                        }
                    } catch (Throwable ignored) {}
                }
            }, "BP_StdinWatch");
            stdinWatcher.setDaemon(true);
            stdinWatcher.start();

            // Dedicated real-time PCM capture thread so MainLooper stays responsive for Binder/AudioPolicy
            Thread captureThread = new Thread(() -> {
                int exitCode = 0;
                try {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                    } catch (Throwable ignored) {}

                    int capTid = Process.myTid();
                    int actualRate = finalRecord.getSampleRate();
                    int frameBytes = finalFmt.bytesPerSample * CHANNELS;
                    int chunkFrames = Math.max(128, actualRate / 50); // 20ms aligned chunk
                    int chunkBytes = chunkFrames * frameBytes;

                    ByteBuffer directBuf = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.LITTLE_ENDIAN);
                    byte[] payloadBuf = new byte[chunkBytes];
                    byte[] header20 = new byte[20];
                    byte[] frameHdr8 = new byte[8];

                    finalRecord.startRecording();
                    logErr("RootLoopback capture ACTIVE (tid=" + capTid + ", rate=" + actualRate
                            + "Hz, fmt=" + finalFmt.name + ", chunk=" + chunkFrames + " frames/" + chunkBytes + "B)");

                    // Send 20-byte handshake header
                    writeLeInt(header20, 0, MAGIC_HEADER);
                    writeLeInt(header20, 4, actualRate);
                    writeLeInt(header20, 8, finalFmt.nativeFormatCode);
                    writeLeInt(header20, 12, finalBufBytes);
                    writeLeInt(header20, 16, capTid);
                    rawOut.write(header20);
                    rawOut.flush();

                    while (sRunning.get()) {
                        directBuf.clear();
                        int bytesRead = finalRecord.read(directBuf, chunkBytes, AudioRecord.READ_BLOCKING);
                        if (bytesRead > 0) {
                            int aligned = (bytesRead / frameBytes) * frameBytes;
                            if (aligned > 0) {
                                directBuf.position(0);
                                directBuf.get(payloadBuf, 0, aligned);
                                writeLeInt(frameHdr8, 0, MAGIC_DATA);
                                writeLeInt(frameHdr8, 4, aligned);
                                rawOut.write(frameHdr8);
                                rawOut.write(payloadBuf, 0, aligned);
                                rawOut.flush();
                            }
                        } else if (bytesRead < 0) {
                            if (sRunning.get()) {
                                logErr("AudioRecord.read returned error " + bytesRead);
                                exitCode = 5;
                            }
                            break;
                        }
                    }
                } catch (IOException ioe) {
                    if (sRunning.get()) {
                        logErr("PCM pipe closed: " + ioe.getMessage());
                    }
                } catch (Throwable t) {
                    if (sRunning.get()) {
                        logErr("Capture thread error: " + t);
                        exitCode = 6;
                    }
                } finally {
                    cleanupAndExit(exitCode);
                }
            }, "BP_RootLoopbackRead");
            captureThread.start();

            // Keep MainLooper alive for AudioPolicy Binder transactions until cleanupAndExit quits it
            Looper.loop();
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            logErr("RootLoopbackDaemon startup FATAL: " + cause);
            t.printStackTrace(System.err);
            cleanupAndExit(10);
        }
    }
}
