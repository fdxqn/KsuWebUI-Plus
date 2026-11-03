package io.github.a13e300.ksuwebui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.MainThread
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import java.util.concurrent.CopyOnWriteArraySet

class FileSystemService : RootService() {
    override fun onBind(intent: Intent): IBinder {
        return FileSystemManager.getService()
    }

    interface Listener {
        fun onServiceAvailable(fs: FileSystemManager)
        fun onLaunchFailed()
    }

    companion object {
        private const val ROOT_TIMEOUT_MS = 8_000L

        private sealed class Status {
            data object Uninitialized : Status()
            data object CheckRoot : Status()
            data class ServiceAvailable(val fs: FileSystemManager) : Status()
        }

        private var status: Status = Status.Uninitialized
        private var timeout: Runnable? = null

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(p0: ComponentName, p1: IBinder) {
                timeout?.let(App.handler::removeCallbacks)
                timeout = null
                val fs = FileSystemManager.getRemote(p1)
                status = Status.ServiceAvailable(fs)
                pendingListeners.forEach { listener ->
                    listener.onServiceAvailable(fs)
                    pendingListeners.remove(listener)
                }
            }

            override fun onServiceDisconnected(p0: ComponentName) {
                timeout?.let(App.handler::removeCallbacks)
                timeout = null
                status = Status.Uninitialized
            }
        }
        private val pendingListeners = CopyOnWriteArraySet<Listener>()

        @MainThread
        fun start(listener: Listener) {
            (status as? Status.ServiceAvailable)?.let {
                listener.onServiceAvailable(it.fs)
                return
            }
            pendingListeners.add(listener)
            if (status == Status.Uninitialized) {
                launchService()
            }
        }

        @MainThread
        fun prewarm() {
            if (status == Status.Uninitialized) {
                launchService()
            }
        }

        private fun launchService() {
            status = Status.CheckRoot
            val timeoutRunnable = Runnable {
                if (status == Status.CheckRoot) {
                    status = Status.Uninitialized
                    failPending()
                }
            }
            timeout = timeoutRunnable
            App.handler.postDelayed(timeoutRunnable, ROOT_TIMEOUT_MS)
            try {
                bind(Intent(App.instance, FileSystemService::class.java), connection)
            } catch (_: Exception) {
                timeout?.let(App.handler::removeCallbacks)
                timeout = null
                status = Status.Uninitialized
                failPending()
            }
        }

        private fun failPending() {
            pendingListeners.forEach { listener ->
                listener.onLaunchFailed()
                pendingListeners.remove(listener)
            }
        }

        fun removeListener(listener: Listener) {
            pendingListeners.remove(listener)
        }
    }
}
