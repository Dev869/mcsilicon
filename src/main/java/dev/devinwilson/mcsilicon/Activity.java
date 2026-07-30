package dev.devinwilson.mcsilicon;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/**
 * Process-wide latency hints via {@code NSProcessInfo}.
 *
 * <p>Two separate things macOS does for battery life that cost frame consistency: App Nap throttles
 * processes it judges idle, and <em>timer coalescing</em> deliberately slews wakeups so several land
 * together and the CPU can stay asleep longer. Coalescing is the interesting one here — it smears
 * exactly the short, regular wakeups a render loop depends on.
 *
 * <p>{@code beginActivityWithOptions:} with {@code NSActivityLatencyCritical} opts the process out
 * of both. The returned token has to stay alive for as long as the guarantee is wanted, which for a
 * game is the whole process, so it is retained and held in a static.
 *
 * <p>Reached through {@code objc_msgSend} rather than a framework binding. JNA's {@link Function}
 * derives the call signature from the arguments actually passed, which is what arm64 requires —
 * objc_msgSend is not variadic there, and a wrongly-typed call is a crash rather than a wrong
 * answer. Only integers and pointers cross the boundary here, no structs or floats.
 */
public final class Activity {

    /** NSActivityOptions from {@code <Foundation/NSProcessInfo.h>}. */
    private static final long NS_ACTIVITY_IDLE_SYSTEM_SLEEP_DISABLED = 1L << 20;
    private static final long NS_ACTIVITY_USER_INITIATED =
            0x00FFFFFFL | NS_ACTIVITY_IDLE_SYSTEM_SLEEP_DISABLED;
    /**
     * Deliberately <em>not</em> plain {@code NSActivityUserInitiated}: that bundles
     * {@code IdleSystemSleepDisabled}, which would stop the Mac idle-sleeping for as long as the
     * game is open — including sitting on the main menu. Frame pacing does not need that, and
     * quietly disabling someone's sleep is not a trade this mod gets to make on their behalf.
     * Minecraft already holds off display sleep on its own while a world is loaded.
     */
    private static final long NS_ACTIVITY_USER_INITIATED_ALLOWING_IDLE_SYSTEM_SLEEP =
            NS_ACTIVITY_USER_INITIATED & ~NS_ACTIVITY_IDLE_SYSTEM_SLEEP_DISABLED;
    private static final long NS_ACTIVITY_LATENCY_CRITICAL = 0xFF00000000L;

    /**
     * Retained activity token. Held for the life of the process on purpose: releasing it ends the
     * guarantee, and a local would let the collector do that at an arbitrary frame.
     */
    private static volatile Pointer token;

    /** Non-null when the last attempt failed; surfaced in the tuning report. */
    public static volatile String error;

    public static boolean active() {
        return token != null;
    }

    /**
     * Disables App Nap and timer coalescing for this process.
     *
     * <p>Idempotent — a second call while a token is already held is a no-op rather than a second
     * overlapping activity.
     *
     * @return true if the token was granted
     */
    public static synchronized boolean beginLatencyCritical() {
        if (token != null) return true;
        if (!Darwin.IS_MACOS) {
            error = "not macOS";
            return false;
        }
        try {
            NativeLibrary objc = NativeLibrary.getInstance("objc");
            // NSProcessInfo lives in Foundation, which is not guaranteed to be loaded this early —
            // pre-launch runs before any AWT or Cocoa class has pulled it in.
            NativeLibrary.getInstance("/System/Library/Frameworks/Foundation.framework/Foundation");

            Function getClass = objc.getFunction("objc_getClass");
            Function sel = objc.getFunction("sel_registerName");
            Function msgSend = objc.getFunction("objc_msgSend");

            Pointer processInfoClass = getClass.invokePointer(new Object[]{"NSProcessInfo"});
            Pointer stringClass = getClass.invokePointer(new Object[]{"NSString"});
            if (processInfoClass == null || stringClass == null) {
                error = "objc_getClass returned null - Foundation not loaded";
                return false;
            }

            Pointer processInfo = msgSend.invokePointer(new Object[]{
                    processInfoClass, sel.invokePointer(new Object[]{"processInfo"})});
            Pointer reason = msgSend.invokePointer(new Object[]{
                    stringClass, sel.invokePointer(new Object[]{"stringWithUTF8String:"}),
                    "mcsilicon: render loop frame pacing"});
            if (processInfo == null || reason == null) {
                error = "could not build the NSProcessInfo call";
                return false;
            }

            Pointer activity = msgSend.invokePointer(new Object[]{
                    processInfo,
                    sel.invokePointer(new Object[]{"beginActivityWithOptions:reason:"}),
                    NS_ACTIVITY_USER_INITIATED_ALLOWING_IDLE_SYSTEM_SLEEP
                            | NS_ACTIVITY_LATENCY_CRITICAL,
                    reason});
            if (activity == null) {
                error = "beginActivityWithOptions: returned nil";
                return false;
            }

            // Returned autoreleased. Nothing here drains a pool, but relying on that to keep the
            // object alive for hours is not a bet worth taking.
            msgSend.invokePointer(new Object[]{activity, sel.invokePointer(new Object[]{"retain"})});
            token = activity;
            error = null;
            return true;
        } catch (Throwable t) {
            error = t.toString();
            return false;
        }
    }

    private Activity() {
    }
}
