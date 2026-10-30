package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-rootfs-reinstall-bridge] Rootfs 管理 → 安装/重置 deletes and
 * re-extracts the whole rootfs, but nothing told [PRootKernel] that the tree it
 * had booted into was gone. `isBooted` stayed true, so `applyDefaultMountOverlay()`
 * and `installHandlerStubs()` never re-ran and the process was left with an
 * empty `/usr/local/bin`: every `android-*` and `minis-*` command answered
 * `not found` until the app was restarted.
 *
 * The re-boot itself needs a device, so these pin the three properties whose
 * absence caused the report — plus the one cold-kernel path that a JVM test can
 * actually execute.
 */
class RootfsReinstallRebootTest {

    /** Sources are CRLF in this working tree; multi-line needles are not. */
    private fun read(rel: String) = ProductionSources.read(rel).replace("\r\n", "\n")

    @Test
    fun `invalidateBoot drops the flag instead of trying to redo the work itself`() {
        val kernel = read("sandbox/PRootKernel.kt")
        val body = kernel.substringAfter("fun invalidateBoot() {").substringBefore("\n    }")
        assertTrue(
            "invalidateBoot() must clear isBooted so the next execution runs " +
                "bootLocked() and re-lays every rootfs-side artifact in order.",
            body.contains("isBooted = false"),
        )
        assertTrue(
            "invalidateBoot() must no-op on a cold kernel — flipping the flag and " +
                "logging on every install click would hide the real re-boots.",
            body.contains("if (!isBooted) return"),
        )
    }

    @Test
    fun `a cold kernel survives invalidation`() {
        // The device-only half of the story starts here: nothing may be booted
        // in a unit-test JVM, and the guard above must return before it logs.
        assertFalse("test JVM must not have a booted kernel", PRootKernel.isBooted)
        PRootKernel.invalidateBoot()
        assertFalse(PRootKernel.isBooted)
    }

    @Test
    fun `every rootfs-replacing action in the settings screen invalidates the boot`() {
        val vm = read("ui/sandbox/RootfsManagementViewModel.kt")

        val install = vm.substringAfter("manager.installIfNeeded()").substringBefore("Rootfs installed successfully")
        assertTrue(
            "install() re-extracts the rootfs; without invalidateBoot() the running " +
                "process keeps a stock tree with no bridge stubs.",
            install.contains("PRootKernel.invalidateBoot()"),
        )

        val reset = vm.substringAfter("manager.reset(keepUserData)").substringBefore("Rootfs reset")
        assertTrue(
            "resetRootfs() deletes the rootfs outright, so it needs the same " +
                "invalidation as install().",
            reset.contains("PRootKernel.invalidateBoot()"),
        )

        // Both calls sit in the try{} AFTER the await: a failed extract leaves
        // the previous boot alone rather than pretending a half-written tree is
        // usable.
        val installBlock = vm.substringAfter("fun install(context: Context)").substringBefore("fun resetRootfs")
        assertTrue(
            "invalidateBoot() must run only after a successful installIfNeeded(),",
            installBlock.indexOf("PRootKernel.invalidateBoot()") >
                installBlock.indexOf("manager.installIfNeeded()"),
        )
        assertFalse(
            "…and must not sit in the catch{} of a failed install.",
            installBlock.substringAfter("catch (e: Exception)")
                .contains("PRootKernel.invalidateBoot()"),
        )
    }

    @Test
    fun `bootLocked still lays down what the extract wiped`() {
        val kernel = read("sandbox/PRootKernel.kt")
        val boot = kernel.substringAfter("private suspend fun bootLocked(context: Context) {")
            .substringBefore("\n    fun addBindMount")
        assertTrue(
            "re-running boot must restore the assets/default_mount overlay, or the " +
                "shipped wrappers (minis-open, xdg-open, minis-mcp-cli) stay missing.",
            boot.contains("rootfsManager.applyDefaultMountOverlay()"),
        )
        assertTrue(
            "re-running boot must restore the handler stubs, or PATH cannot find " +
                "the android-*/minis-* offload commands.",
            boot.contains("installHandlerStubs(rootfsManager.rootfsDir)"),
        )
        assertTrue(
            "boot must still set isBooted at the very end — invalidateBoot() relies " +
                "on boot() being re-entrant.",
            boot.contains("isBooted = true"),
        )
    }
}
