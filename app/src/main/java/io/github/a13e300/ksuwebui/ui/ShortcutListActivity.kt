package io.github.a13e300.ksuwebui.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.App
import io.github.a13e300.ksuwebui.FileSystemService
import io.github.a13e300.ksuwebui.R
import io.github.a13e300.ksuwebui.data.ModuleInfo
import io.github.a13e300.ksuwebui.data.ModuleRepository
import io.github.a13e300.ksuwebui.databinding.ActivityShortcutListBinding
import io.github.a13e300.ksuwebui.databinding.ItemShortcutBinding
import io.github.a13e300.ksuwebui.shortcut.ModuleShortcutController
import io.github.a13e300.ksuwebui.shortcut.PinResult
import io.github.a13e300.ksuwebui.shortcut.ShortcutConfig
import io.github.a13e300.ksuwebui.shortcut.ShortcutIconRenderer
import io.github.a13e300.ksuwebui.shortcut.ShortcutStore

@SuppressLint("NotifyDataSetChanged")
class ShortcutListActivity : AppCompatActivity(), FileSystemService.Listener {
    private lateinit var binding: ActivityShortcutListBinding
    private val store by lazy { ShortcutStore.get(this) }
    private val controller by lazy { ModuleShortcutController(this) }
    private val renderer = ShortcutIconRenderer()
    private val adapter = Adapter()
    private var rows: List<Row> = emptyList()

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityShortcutListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        rows = emptyList()
        adapter.notifyDataSetChanged()
        binding.empty.setText(R.string.loading)
        binding.empty.isVisible = true
        FileSystemService.start(this)
    }

    override fun onServiceAvailable(fs: FileSystemManager) {
        val repository = ModuleRepository(fs)
        App.executor.execute {
            val modules = repository.listModules(includeDisabled = true).associateBy { it.id }
            val pinned = controller.pinnedIds()
            val next = store.load().values
                .sortedBy { it.createdAt }
                .map { config ->
                    val module = modules[config.moduleId]
                    val icon = store.loadIcon(config)
                        ?: renderer.monogram(config.label, config.moduleId, dp(48))
                    Row(config, module, icon, config.shortcutId in pinned)
                }
            postUi {
                rows = next
                adapter.notifyDataSetChanged()
                binding.empty.setText(R.string.shortcut_list_empty)
                binding.empty.isVisible = next.isEmpty()
            }
        }
    }

    override fun onLaunchFailed() {
        rows = emptyList()
        adapter.notifyDataSetChanged()
        binding.empty.setText(R.string.please_grant_root)
        binding.empty.isVisible = true
    }

    private fun openEditor(module: ModuleInfo, config: ShortcutConfig) {
        editorLauncher.launch(
            Intent(this, ShortcutEditorActivity::class.java)
                .putExtra(ShortcutEditorActivity.EXTRA_MODULE_ID, module.id)
                .putExtra(ShortcutEditorActivity.EXTRA_MODULE_NAME, module.name)
                .putExtra(ShortcutEditorActivity.EXTRA_SHORTCUT_ID, config.shortcutId)
        )
    }

    private fun reAdd(row: Row) {
        val module = row.module ?: return
        if (!module.enabled) return
        App.executor.execute {
            val icon = store.loadIcon(row.config)
                ?: renderer.monogram(row.config.label, row.config.moduleId, dp(192))
            val result = controller.pin(row.config, icon)
            postUi {
                when (result) {
                    PinResult.Requested -> toast(R.string.shortcut_pin_requested)
                    PinResult.Restored -> {
                        App.executor.execute {
                            store.upsert(row.config.copy(pinned = true))
                            postUi {
                                toast(R.string.shortcut_restored)
                                refresh()
                            }
                        }
                    }
                    PinResult.Unsupported -> showMessage(
                        R.string.shortcut_pin_unsupported_title,
                        R.string.shortcut_pin_unsupported_message
                    )
                    is PinResult.Failed -> showMessage(
                        R.string.shortcut_pin_failed_title,
                        result.message
                    )
                }
            }
        }
    }

    private fun confirmDelete(row: Row) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shortcut_delete_confirm_title)
            .setMessage(R.string.shortcut_delete_confirm_message)
            .setNegativeButton(R.string.shortcut_cancel, null)
            .setPositiveButton(R.string.shortcut_delete) { _, _ ->
                controller.disable(row.config.shortcutId, getString(R.string.shortcut_removed))
                App.executor.execute {
                    store.delete(row.config.shortcutId)
                    postUi {
                        toast(R.string.shortcut_deleted)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun statusText(row: Row): Int {
        return when {
            row.module == null -> R.string.shortcut_status_missing
            !row.module.enabled -> R.string.shortcut_status_disabled
            row.pinned -> R.string.shortcut_status_pinned
            else -> R.string.shortcut_status_not_pinned
        }
    }

    private fun showMessage(title: Int, message: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.shortcut_ok, null)
            .show()
    }

    private fun showMessage(title: Int, message: CharSequence) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.shortcut_ok, null)
            .show()
    }

    private fun postUi(block: () -> Unit) {
        App.handler.post {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
    }

    private data class Row(
        val config: ShortcutConfig,
        val module: ModuleInfo?,
        val icon: Bitmap,
        val pinned: Boolean
    )

    private inner class Adapter : RecyclerView.Adapter<ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemShortcutBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = rows[position]
            val context = holder.itemView.context
            holder.binding.icon.setImageBitmap(row.icon)
            holder.binding.label.text = row.config.label
            holder.binding.moduleId.text = context.getString(
                R.string.shortcut_module_id,
                row.config.moduleId
            )
            holder.binding.status.setText(statusText(row))

            holder.binding.edit.isVisible = row.module?.enabled == true
            holder.binding.readd.isVisible = row.module?.enabled == true && !row.pinned
            holder.binding.delete.isVisible = true

            holder.binding.edit.setOnClickListener {
                row.module?.let { openEditor(it, row.config) }
            }
            holder.binding.readd.setOnClickListener { reAdd(row) }
            holder.binding.delete.setOnClickListener { confirmDelete(row) }
        }
    }

    private class ViewHolder(val binding: ItemShortcutBinding) : RecyclerView.ViewHolder(binding.root)
}
