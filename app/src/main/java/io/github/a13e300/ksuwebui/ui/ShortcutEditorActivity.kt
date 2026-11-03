package io.github.a13e300.ksuwebui.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.App
import io.github.a13e300.ksuwebui.FileSystemService
import io.github.a13e300.ksuwebui.R
import io.github.a13e300.ksuwebui.data.ModuleIconCache
import io.github.a13e300.ksuwebui.data.ModuleIconLoader
import io.github.a13e300.ksuwebui.data.ModuleWebCache
import io.github.a13e300.ksuwebui.databinding.ActivityShortcutEditorBinding
import io.github.a13e300.ksuwebui.shortcut.IconSource
import io.github.a13e300.ksuwebui.shortcut.LabelMode
import io.github.a13e300.ksuwebui.shortcut.ModuleIdValidator
import io.github.a13e300.ksuwebui.shortcut.ModuleShortcutController
import io.github.a13e300.ksuwebui.shortcut.PinResult
import io.github.a13e300.ksuwebui.shortcut.ShortcutConfig
import io.github.a13e300.ksuwebui.shortcut.ShortcutIconRenderer
import io.github.a13e300.ksuwebui.shortcut.ShortcutStore

class ShortcutEditorActivity : AppCompatActivity(), FileSystemService.Listener {
    private lateinit var binding: ActivityShortcutEditorBinding
    private val viewModel: ShortcutEditorViewModel by viewModels()
    private val store by lazy { ShortcutStore.get(this) }
    private val renderer = ShortcutIconRenderer()
    private val controller by lazy { ModuleShortcutController(this) }
    private var fs: FileSystemManager? = null

    private var moduleId = ""
    private var moduleName = ""
    private var shortcutId: String? = null
    private var bindingReady = false
    private var renderGeneration = 0

    private val previewRunnable = Runnable { renderPreview() }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        App.executor.execute {
            val bitmap = loadBitmap(uri)
            postUi {
                if (bitmap == null) {
                    toast(R.string.shortcut_image_failed)
                } else {
                    ImageCropDialog(this@ShortcutEditorActivity, bitmap) { cropped ->
                        App.executor.execute {
                            val name = store.saveSourceBitmap(cropped)
                            postUi {
                                if (name == null) {
                                    toast(R.string.shortcut_image_failed)
                                } else {
                                    viewModel.imageFile = name
                                    viewModel.source = IconSource.IMAGE
                                    applyStateToUi()
                                    schedulePreview()
                                }
                            }
                        }
                    }.show()
                }
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val resolver = contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityShortcutEditorBinding.inflate(layoutInflater)
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

        moduleId = intent.getStringExtra(EXTRA_MODULE_ID).orEmpty()
        moduleName = intent.getStringExtra(EXTRA_MODULE_NAME) ?: moduleId
        shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID)
        if (!ModuleIdValidator.isValid(moduleId)) {
            finish()
            return
        }

        viewModel.moduleId = moduleId
        viewModel.moduleName = moduleName
        binding.nameInput.filters = arrayOf(InputFilter.LengthFilter(20))
        binding.textInput.filters = arrayOf(InputFilter.LengthFilter(4))

        setupListeners()
        if (viewModel.loaded) {
            binding.save.isEnabled = true
            applyStateToUi()
            schedulePreview()
        } else {
            binding.save.isEnabled = false
            applyStateToUi()
            loadExisting()
        }
        FileSystemService.start(this)
    }

    private fun setupListeners() {
        binding.nameInput.doAfterTextChanged {
            if (!bindingReady) return@doAfterTextChanged
            viewModel.label = it?.toString().orEmpty()
            schedulePreview()
        }
        binding.followModule.setOnCheckedChangeListener { _, checked ->
            if (!bindingReady) return@setOnCheckedChangeListener
            viewModel.labelMode = if (checked) LabelMode.FOLLOW_MODULE else LabelMode.CUSTOM
            binding.nameInput.isEnabled = !checked
            if (checked) {
                viewModel.label = moduleName
                binding.nameInput.setText(moduleName)
            }
            schedulePreview()
        }
        binding.sourceModule.setOnClickListener { selectSource(IconSource.FOLLOW_MODULE) }
        binding.sourceImage.setOnClickListener { selectSource(IconSource.IMAGE) }
        binding.sourceSolid.setOnClickListener { selectSource(IconSource.SOLID) }
        binding.pickImage.setOnClickListener {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.backgroundHex.doAfterTextChanged {
            if (!bindingReady) return@doAfterTextChanged
            val text = it?.toString().orEmpty()
            val parsed = runCatching { Color.parseColor(text) }.getOrNull()
            if (parsed == null) {
                binding.backgroundHexLayout.error = getString(R.string.shortcut_invalid_hex)
            } else {
                binding.backgroundHexLayout.error = null
                viewModel.background = parsed
                if (viewModel.source != IconSource.SOLID) {
                    viewModel.source = IconSource.SOLID
                    renderSourceSelection()
                }
                updateSourceUi()
                buildColorRows()
                schedulePreview()
            }
        }
        binding.textInput.doAfterTextChanged {
            if (!bindingReady) return@doAfterTextChanged
            viewModel.text = it?.toString().orEmpty().take(4)
            schedulePreview()
        }
        binding.cancel.setOnClickListener { finish() }
        binding.save.setOnClickListener { save() }
    }

    private fun loadExisting() {
        App.executor.execute {
            var existing = store.get(shortcutId ?: ShortcutConfig.idFor(moduleId))
            if (existing != null) {
                val isPinned = existing.shortcutId in controller.pinnedIds()
                if (existing.pinned != isPinned) {
                    existing = existing.copy(pinned = isPinned)
                    store.upsert(existing)
                }
            }
            postUi {
                viewModel.load(existing, moduleName)
                binding.save.isEnabled = true
                applyStateToUi()
                schedulePreview()
            }
        }
    }

    override fun onServiceAvailable(fs: FileSystemManager) {
        this.fs = fs
        val loader = ModuleIconLoader(fs)
        App.executor.execute {
            val icon = loader.load(moduleId)
            postUi {
                viewModel.moduleIcon = icon
                schedulePreview()
            }
        }
    }

    override fun onLaunchFailed() {
        // The editor still works with a generated monogram.
    }

    private fun selectSource(source: IconSource) {
        if (!bindingReady || viewModel.source == source) return
        viewModel.source = source
        renderSourceSelection()
        updateSourceUi()
        schedulePreview()
    }

    private fun renderSourceSelection() {
        styleSegment(binding.sourceModule, viewModel.source == IconSource.FOLLOW_MODULE)
        styleSegment(binding.sourceImage, viewModel.source == IconSource.IMAGE)
        styleSegment(binding.sourceSolid, viewModel.source == IconSource.SOLID)
    }

    private fun styleSegment(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = 18f * resources.displayMetrics.density
            setColor(getColor(if (selected) R.color.oneui_primary else android.R.color.transparent))
        }
        view.setTextColor(getColor(if (selected) android.R.color.white else R.color.editor_text))
    }

    private fun applyStateToUi() {
        bindingReady = false
        binding.nameInput.setText(viewModel.label)
        binding.followModule.isChecked = viewModel.labelMode == LabelMode.FOLLOW_MODULE
        binding.nameInput.isEnabled = viewModel.labelMode == LabelMode.CUSTOM
        renderSourceSelection()
        binding.textInput.setText(viewModel.text)
        binding.backgroundHex.setText(colorToHex(viewModel.background))
        binding.save.setText(
            if (viewModel.existing?.pinned == true) R.string.shortcut_update else R.string.shortcut_save
        )
        updateSourceUi()
        buildColorRows()
        updateImagePreview()
        bindingReady = true
    }

    private fun updateSourceUi() {
        binding.imageSection.isVisible = viewModel.source == IconSource.IMAGE
        binding.backgroundSection.isVisible = true
    }

    private fun buildColorRows() {
        buildColorRow(binding.backgroundColors, viewModel.background) { color ->
            viewModel.background = color
            if (viewModel.source != IconSource.SOLID) {
                viewModel.source = IconSource.SOLID
                renderSourceSelection()
            }
            binding.backgroundHex.setText(colorToHex(color))
            updateSourceUi()
            buildColorRows()
            schedulePreview()
        }
    }

    private fun buildColorRow(container: LinearLayout, selected: Int, onPick: (Int) -> Unit) {
        container.removeAllViews()
        val ringColor = getColor(R.color.oneui_primary)
        PALETTE.forEach { color ->
            val isSelected = color == selected
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        if (isSelected) dp(2) else dp(1),
                        if (isSelected) ringColor else 0x22000000
                    )
                }
                contentDescription = colorToHex(color)
                setOnClickListener { onPick(color) }
            }
            container.addView(
                swatch,
                LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(6) }
            )
        }
    }

    private fun updateImagePreview() {
        val name = viewModel.imageFile
        if (name == null) {
            binding.imagePreview.setImageDrawable(null)
            binding.imagePreviewCard.isVisible = false
            return
        }
        binding.imagePreviewCard.isVisible = true
        App.executor.execute {
            val bitmap = store.sourceFile(name)?.let { BitmapFactory.decodeFile(it.absolutePath) }
            postUi {
                if (viewModel.imageFile == name) binding.imagePreview.setImageBitmap(bitmap)
            }
        }
    }

    private fun schedulePreview() {
        App.handler.removeCallbacks(previewRunnable)
        App.handler.postDelayed(previewRunnable, 150)
    }

    private fun renderPreview() {
        val generation = ++renderGeneration
        val config = viewModel.buildConfig(System.currentTimeMillis())
        val moduleIcon = viewModel.moduleIcon
        val imageFile = viewModel.imageFile
        App.executor.execute {
            val sourceImage = imageFile?.let {
                store.sourceFile(it)?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
            }
            val bitmap = try {
                renderer.renderPreview(config, moduleIcon, sourceImage, 216)
            } catch (_: Exception) {
                null
            }
            postUi {
                if (generation == renderGeneration && bitmap != null) {
                    viewModel.preview = bitmap
                    binding.preview.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun save() {
        if (!binding.save.isEnabled) return
        binding.save.isEnabled = false
        binding.save.setText(R.string.shortcut_saving)

        val config = viewModel.buildConfig(System.currentTimeMillis())
        val moduleIcon = viewModel.moduleIcon
        val cacheFs = fs
        App.executor.execute {
            val sourceImage = config.icon.imageFile?.let {
                store.sourceFile(it)?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
            }
            val bitmap = try {
                renderer.render(config, moduleIcon, sourceImage)
            } catch (_: Exception) {
                null
            }
            val saved = bitmap != null &&
                store.saveIcon(config, bitmap) != null &&
                store.upsert(config)
            if (!saved) {
                postUi {
                    binding.save.isEnabled = true
                    binding.save.setText(
                        if (viewModel.existing?.pinned == true) {
                            R.string.shortcut_update
                        } else {
                            R.string.shortcut_save
                        }
                    )
                    toast(R.string.shortcut_save_failed)
                }
                return@execute
            }

            cacheFs?.let { ModuleWebCache.copy(this, it, config.moduleId) }
            ModuleIconCache.remove(config.moduleId)

            if (viewModel.existing?.pinned == true) {
                val updated = controller.update(config, bitmap!!)
                postUi {
                    if (updated) {
                        finishWithResult(config.shortcutId)
                    } else {
                        showMessage(
                            R.string.shortcut_update_failed_title,
                            R.string.shortcut_update_failed_message
                        ) { finishWithResult(config.shortcutId) }
                    }
                }
            } else {
                val result = controller.pin(config, bitmap!!)
                postUi { handlePinResult(result, config.shortcutId) }
            }
        }
    }

    private fun handlePinResult(result: PinResult, shortcutId: String) {
        when (result) {
            PinResult.Requested -> {
                toast(R.string.shortcut_pin_requested)
                finishWithResult(shortcutId)
            }

            PinResult.Restored -> {
                App.executor.execute {
                    store.get(shortcutId)?.let { store.upsert(it.copy(pinned = true)) }
                }
                toast(R.string.shortcut_restored)
                finishWithResult(shortcutId)
            }

            PinResult.Unsupported -> showMessage(
                R.string.shortcut_pin_unsupported_title,
                R.string.shortcut_pin_unsupported_message
            ) { finishWithResult(shortcutId) }

            is PinResult.Failed -> showMessage(
                R.string.shortcut_pin_failed_title,
                result.message
            ) { finishWithResult(shortcutId) }
        }
    }

    private fun showMessage(@StringRes title: Int, @StringRes message: Int, onDismiss: () -> Unit) {
        showMessage(title, getString(message), onDismiss)
    }

    private fun showMessage(@StringRes title: Int, message: CharSequence, onDismiss: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.shortcut_ok) { _, _ -> onDismiss() }
            .setCancelable(false)
            .show()
    }

    private fun finishWithResult(shortcutId: String) {
        setResult(RESULT_OK, Intent().putExtra(RESULT_SHORTCUT_ID, shortcutId))
        finish()
    }

    private fun postUi(block: () -> Unit) {
        App.handler.post {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun toast(@StringRes message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun colorToHex(color: Int): String {
        val value = color.toLong() and 0xFFFFFFFFL
        return "#" + value.toString(16).padStart(8, '0')
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        App.handler.removeCallbacks(previewRunnable)
        FileSystemService.removeListener(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
        const val EXTRA_MODULE_NAME = "module_name"
        const val EXTRA_SHORTCUT_ID = "shortcut_id"
        const val RESULT_SHORTCUT_ID = "shortcut_id"

        private val PALETTE = intArrayOf(
            0xFF111827.toInt(),
            0xFF2563EB.toInt(),
            0xFF059669.toInt(),
            0xFFDC2626.toInt(),
            0xFF7C3AED.toInt(),
            0xFFEA580C.toInt(),
            0xFF0891B2.toInt(),
            0xFFFFFFFF.toInt()
        )
    }
}
