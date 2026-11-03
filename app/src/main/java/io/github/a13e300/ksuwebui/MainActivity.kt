package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.data.ModuleCache
import io.github.a13e300.ksuwebui.data.ModuleIconCache
import io.github.a13e300.ksuwebui.data.ModuleIconLoader
import io.github.a13e300.ksuwebui.data.ModuleWebCache
import io.github.a13e300.ksuwebui.data.ModuleInfo
import io.github.a13e300.ksuwebui.data.ModuleRepository
import io.github.a13e300.ksuwebui.databinding.ActivityMainBinding
import io.github.a13e300.ksuwebui.databinding.ItemModuleBinding
import io.github.a13e300.ksuwebui.shortcut.LabelMode
import io.github.a13e300.ksuwebui.shortcut.ModuleLaunchContract
import io.github.a13e300.ksuwebui.shortcut.ModuleShortcutController
import io.github.a13e300.ksuwebui.shortcut.ShortcutConfig
import io.github.a13e300.ksuwebui.shortcut.ShortcutIconRenderer
import io.github.a13e300.ksuwebui.shortcut.ShortcutStore
import io.github.a13e300.ksuwebui.ui.MainSettingsSheet
import io.github.a13e300.ksuwebui.ui.ModuleActionsSheet
import io.github.a13e300.ksuwebui.ui.ShortcutEditorActivity

@SuppressLint("NotifyDataSetChanged")
class MainActivity : AppCompatActivity(), FileSystemService.Listener, ModuleActionsSheet.Listener {
    private lateinit var binding: ActivityMainBinding
    private var moduleList = emptyList<ModuleInfo>()
    private lateinit var adapter: Adapter
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val store by lazy { ShortcutStore.get(this) }
    private val controller by lazy { ModuleShortcutController(this) }
    private val iconRenderer = ShortcutIconRenderer()
    private var iconLoader: ModuleIconLoader? = null
    private var editingModuleId: String? = null
    private var resumedOnce = false

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            editingModuleId?.let { ModuleIconCache.remove(it) }
            editingModuleId = null
            preloadShortcutIcons(moduleList) { adapter.notifyDataSetChanged() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appbar) { v, insets ->
            val cutoutAndBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = cutoutAndBars.left, top = cutoutAndBars.top, right = cutoutAndBars.right)
            return@setOnApplyWindowInsetsListener insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { v, insets ->
            val cutoutAndBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = cutoutAndBars.left, bottom = cutoutAndBars.bottom, right = cutoutAndBars.right)
            return@setOnApplyWindowInsetsListener insets
        }

        adapter = Adapter()
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener {
            refresh(manual = true)
        }
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.settings).setOnMenuItemClickListener {
            MainSettingsSheet(this, prefs) { refresh() }.show()
            true
        }
        return true
    }

    private fun refresh(manual: Boolean = false) {
        val cached = ModuleCache.read(this)
        val showDisabled = prefs.getBoolean("show_disabled", false)
        moduleList = if (showDisabled) cached else cached.filter { it.enabled }
        preloadShortcutIcons(moduleList) { adapter.notifyDataSetChanged() }
        binding.swipeRefresh.isRefreshing = manual
        if (moduleList.isEmpty()) {
            binding.info.setText(R.string.loading)
            binding.info.isVisible = true
        } else {
            binding.info.isVisible = false
        }
        FileSystemService.start(this)
    }

    private fun preloadShortcutIcons(modules: List<ModuleInfo>, onReady: () -> Unit) {
        val missing = modules.filter { ModuleIconCache.get(it.id) == null }
        if (missing.isEmpty()) {
            onReady()
            return
        }
        App.executor.execute {
            for (module in missing) {
                val icon = store.listForModule(module.id)
                    .firstOrNull()
                    ?.let { store.loadIcon(it) }
                    ?: continue
                ModuleIconCache.put(module.id, icon)
            }
            App.handler.post(onReady)
        }
    }

    override fun onServiceAvailable(fs: FileSystemManager) {
        val repository = ModuleRepository(fs)
        val loader = ModuleIconLoader(fs)
        App.executor.submit {
            val allModules = repository.listModules(includeDisabled = true)
            ModuleCache.write(this, allModules)
            val mods = if (prefs.getBoolean("show_disabled", false)) {
                allModules
            } else {
                allModules.filter { it.enabled }
            }
            syncFollowModuleLabels(allModules, loader)
            reconcilePinnedState()
            refreshPinnedShortcuts(loader)
            mods.filter { ModuleIconCache.get(it.id) == null }.forEach { module ->
                store.listForModule(module.id)
                    .firstOrNull()
                    ?.let { store.loadIcon(it) }
                    ?.let { ModuleIconCache.put(module.id, it) }
            }
            runOnUiThread {
                iconLoader = loader
                moduleList = mods
                adapter.notifyDataSetChanged()
                binding.swipeRefresh.isRefreshing = false
                if (mods.isEmpty()) {
                    binding.info.setText(R.string.no_modules)
                    binding.info.isVisible = true
                } else {
                    binding.info.isVisible = false
                }
            }
            store.load().values.map { it.moduleId }.distinct().forEach { moduleId ->
                if (ModuleWebCache.webroot(this, moduleId) == null) {
                    ModuleWebCache.copy(this, fs, moduleId)
                }
            }
        }
    }

    private fun syncFollowModuleLabels(modules: List<ModuleInfo>, loader: ModuleIconLoader) {
        val configs = store.load().values.filter { it.labelMode == LabelMode.FOLLOW_MODULE }
        if (configs.isEmpty()) return
        val byId = modules.associateBy { it.id }
        for (config in configs) {
            val module = byId[config.moduleId] ?: continue
            if (!module.enabled || config.label == module.name) continue

            val updated = config.copy(
                label = module.name,
                updatedAt = System.currentTimeMillis()
            )
            val moduleIcon = loader.load(module.id)
            val sourceImage = updated.icon.imageFile?.let { name ->
                store.sourceFile(name)?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
            }
            val bitmap = iconRenderer.render(updated, moduleIcon, sourceImage)
            if (store.saveIcon(updated, bitmap) == null) continue
            if (!store.upsert(updated)) continue
            if (updated.pinned) controller.update(updated, bitmap)
        }
    }

    private fun reconcilePinnedState() {
        val pinned = controller.pinnedIds()
        for (config in store.load().values) {
            val isPinned = config.shortcutId in pinned
            if (config.pinned != isPinned) {
                store.upsert(config.copy(pinned = isPinned))
            }
        }
    }

    private fun refreshPinnedShortcuts(loader: ModuleIconLoader) {
        if (prefs.getBoolean(KEY_SHORTCUT_REFRESH, false)) return
        val pinned = controller.pinnedIds()
        var complete = true
        for (config in store.load().values) {
            val moduleIcon = loader.load(config.moduleId)
            val sourceImage = config.icon.imageFile?.let { name ->
                store.sourceFile(name)?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
            }
            val bitmap = iconRenderer.render(config, moduleIcon, sourceImage)
            if (store.saveIcon(config, bitmap) == null) {
                complete = false
                continue
            }
            if (config.shortcutId in pinned && !controller.update(config, bitmap)) {
                complete = false
            }
        }
        if (complete) prefs.edit().putBoolean(KEY_SHORTCUT_REFRESH, true).apply()
    }

    override fun onLaunchFailed() {
        binding.swipeRefresh.isRefreshing = false
        if (moduleList.isEmpty()) {
            binding.info.setText(R.string.please_grant_root)
            binding.info.isVisible = true
        } else {
            Toast.makeText(this, R.string.please_grant_root, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActions(module: ModuleInfo) {
        App.executor.execute {
            val pinned = controller.pinnedIds()
            var shortcut = store.listForModule(module.id).firstOrNull()
            if (shortcut != null) {
                val isPinned = shortcut.shortcutId in pinned
                if (shortcut.pinned != isPinned) {
                    shortcut = shortcut.copy(pinned = isPinned)
                    store.upsert(shortcut)
                }
            }
            val cachedIcon = ModuleIconCache.get(module.id)
            val icon = cachedIcon
                ?: shortcut?.let { store.loadIcon(it) }?.also { ModuleIconCache.put(module.id, it) }
                ?: iconLoader?.load(module.id)?.also { ModuleIconCache.put(module.id, it) }
            val finalShortcut = shortcut
            App.handler.post {
                if (isFinishing || isDestroyed) return@post
                ModuleActionsSheet(this, module, icon, finalShortcut, this).show()
            }
        }
    }

    private fun launchEditor(module: ModuleInfo, shortcut: ShortcutConfig?) {
        editingModuleId = module.id
        val intent = Intent(this, ShortcutEditorActivity::class.java)
            .putExtra(ShortcutEditorActivity.EXTRA_MODULE_ID, module.id)
            .putExtra(ShortcutEditorActivity.EXTRA_MODULE_NAME, module.name)
        if (shortcut != null) {
            intent.putExtra(ShortcutEditorActivity.EXTRA_SHORTCUT_ID, shortcut.shortcutId)
        }
        editorLauncher.launch(intent)
    }

    override fun onOpen(module: ModuleInfo) {
        startActivity(ModuleLaunchContract.webUiIntent(this, module.id, false))
    }

    override fun onAddShortcut(module: ModuleInfo) {
        launchEditor(module, null)
    }

    override fun onEditShortcut(module: ModuleInfo, shortcut: ShortcutConfig) {
        launchEditor(module, shortcut)
    }

    override fun onRemoveShortcut(module: ModuleInfo, shortcut: ShortcutConfig) {
        controller.disable(shortcut.shortcutId, getString(R.string.shortcut_removed))
        App.executor.execute {
            val current = store.get(shortcut.shortcutId) ?: shortcut
            store.upsert(current.copy(pinned = false))
        }
        Toast.makeText(this, R.string.shortcut_removed_hint, Toast.LENGTH_LONG).show()
    }

    class ViewHolder(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    inner class Adapter : RecyclerView.Adapter<ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemModuleBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }

        override fun getItemCount(): Int = moduleList.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = moduleList[position]
            holder.binding.name.text = item.name
            holder.binding.author.text = resources.getString(R.string.author, item.author)
            holder.binding.version.text = resources.getString(R.string.version, item.version)
            holder.binding.desc.text = item.description

            holder.binding.moduleIcon.tag = item.id
            val cachedIcon = ModuleIconCache.get(item.id)
            holder.binding.moduleIcon.setImageBitmap(
                cachedIcon ?: iconRenderer.monogram(item.name, item.id, dp(44))
            )
            if (cachedIcon == null) {
                val loader = iconLoader
                App.executor.execute {
                    val shortcutIcon = store.listForModule(item.id)
                        .firstOrNull()
                        ?.let { store.loadIcon(it) }
                    val bitmap = shortcutIcon ?: loader?.load(item.id)
                    if (bitmap != null) {
                        ModuleIconCache.put(item.id, bitmap)
                        App.handler.post {
                            if (holder.binding.moduleIcon.tag == item.id) {
                                holder.binding.moduleIcon.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }

            holder.binding.root.setOnClickListener {
                startActivity(ModuleLaunchContract.webUiIntent(this@MainActivity, item.id, false))
            }
            holder.binding.root.setOnLongClickListener {
                showActions(item)
                true
            }
            holder.binding.more.setOnClickListener {
                showActions(item)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (resumedOnce) {
            preloadShortcutIcons(moduleList) { adapter.notifyDataSetChanged() }
        }
        resumedOnce = true
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
    }

    companion object {
        private const val KEY_SHORTCUT_REFRESH = "shortcut_refresh_v1"
    }
}
