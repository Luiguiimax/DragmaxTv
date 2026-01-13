@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.dragmax.dragmaxtv

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dragmax.dragmaxtv.ui.viewmodel.PlayerViewModel
import com.dragmax.dragmaxtv.data.database.AppDatabase
import com.dragmax.dragmaxtv.data.repository.ChannelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bumptech.glide.Glide
import android.widget.ImageView
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {
    
    private lateinit var navItems: List<TextView>
    private lateinit var mainContentArea: android.widget.FrameLayout
    private lateinit var leftNavPanel: android.widget.LinearLayout
    private lateinit var rightSidebar: android.widget.LinearLayout
    private lateinit var channelsListContainer: android.widget.LinearLayout
    private lateinit var channelsScrollView: android.widget.ScrollView
    private var channelRows: MutableList<LinearLayout> = mutableListOf() // Lista de filas de canales
    private var channelRowToChannelMap: MutableMap<LinearLayout, com.dragmax.dragmaxtv.data.entity.LiveChannel> = mutableMapOf() // Mapa de fila a canal
    private var defaultChannelUrl: String? = null // URL del canal predeterminado
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var topBar: android.widget.LinearLayout
    private lateinit var searchTop: android.widget.FrameLayout
    private lateinit var profile: android.widget.FrameLayout
    private lateinit var searchBottom: android.widget.FrameLayout
    private lateinit var statusIndicatorContainer: android.widget.FrameLayout
    private lateinit var tvStatusIndicator: android.widget.TextView
    private lateinit var refreshLoadingOverlay: android.widget.FrameLayout
    private var selectedIndex = 0
    private var isFullscreen = false
    private var currentFocusedView: View? = null
    
    // Estados guardados antes de entrar en fullscreen para restaurar después
    private var savedLeftPanelReduced = false
    private var savedReproductorReduced = false
    private var savedRightSidebarExpanded = false
    private var savedFocusedView: View? = null
    private var savedSelectedIndex = 0
    private var lastBackPressTime = 0L
    private val BACK_PRESS_INTERVAL = 4000L // 4 segundos para doble clic
    private val originalLeftPanelWidth = 200 // Ancho original en dp
    private val reducedLeftPanelWidth = 140 // Ancho reducido en dp (reducido más)
    private var isLeftPanelReduced = false
    
    // Anchos del reproductor y panel derecho
    private val originalReproductorMarginEnd = 140 // Margen derecho original en dp
    private val reducedReproductorMarginEnd = 200 // Margen derecho cuando se reduce (aumenta para reducir ancho)
    private val originalRightSidebarWidth = 120 // Ancho original del panel derecho en dp
    private val expandedRightSidebarWidth = 180 // Ancho expandido del panel derecho en dp
    
    // ExoPlayer
    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var viewModel: PlayerViewModel
    
    // Sistema de estabilidad para streaming
    private var currentChannelUrl: String? = null
    private var retryCount = 0
    private val MAX_RETRIES = 3
    private var bufferingStartTime: Long = 0
    private val BUFFERING_TIMEOUT = 10000L // 10 segundos
    private var playbackMonitorJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastPlaybackPosition: Long = 0
    private var playbackStuckCheckRunnable: Runnable? = null
    private var hasRefreshedAfterDownload = false // Flag para evitar múltiples refreshes
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inicializar SharedPreferences para guardar el canal predeterminado
        sharedPreferences = getSharedPreferences("DragmaxTvPrefs", MODE_PRIVATE)
        
        // Restaurar canal predeterminado guardado
        defaultChannelUrl = sharedPreferences.getString("default_channel_url", null)
        
        // Ocultar completamente todas las barras del sistema
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Android 10 y anteriores
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
        
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        setContentView(R.layout.activity_main)
        
        // Inicializar vistas
        navItems = listOf(
            findViewById(R.id.tvNavItem1),
            findViewById(R.id.tvNavItem2),
            findViewById(R.id.tvNavItem3),
            findViewById(R.id.tvNavItem4),
            findViewById(R.id.tvNavItem5)
        )
        mainContentArea = findViewById(R.id.mainContentArea)
        leftNavPanel = findViewById(R.id.leftNavPanel)
        rightSidebar = findViewById(R.id.rightSidebar)
        channelsListContainer = findViewById(R.id.channelsListContainer)
        channelsScrollView = findViewById(R.id.channelsScrollView)
        topBar = findViewById(R.id.topBar)
        searchTop = findViewById(R.id.flSearchTop)
        profile = findViewById(R.id.flProfile)
        searchBottom = findViewById(R.id.flSearchBottom)
        playerView = findViewById(R.id.playerView)
        statusIndicatorContainer = findViewById(R.id.statusIndicatorContainer)
        tvStatusIndicator = findViewById(R.id.tvStatusIndicator)
        refreshLoadingOverlay = findViewById(R.id.refreshLoadingOverlay)
        
        // Inicializar ViewModel
        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        
        // Inicializar ExoPlayer con configuración optimizada para LIVE streaming
        initializeExoPlayer()
        
        // Observar ViewModel
        observeViewModel()
        
        // Cargar canales
        android.util.Log.d("MainActivity", "Starting to load channels...")
        // Restaurar canal predeterminado si existe (después de inicializar ExoPlayer)
        defaultChannelUrl?.let { url ->
            android.util.Log.d("MainActivity", "Restoring default channel from preferences")
            // Usar post para asegurar que ExoPlayer esté listo
            lifecycleScope.launch {
                delay(500) // Pequeño delay para asegurar que ExoPlayer esté inicializado
                playChannel(url)
            }
        }
        viewModel.loadChannels()
        
        // Configurar listeners de foco para todos los elementos navegables
        setupFocusListeners()
        
        // Configurar manejo del botón atrás usando callbacks (recomendado por Android)
        setupBackPressHandler()
        
        // Configurar "TV" como seleccionado por defecto
        updateSelection(0)
        
        // Asegurar que el panel izquierdo esté en su forma original al iniciar
        val density = resources.displayMetrics.density
        val originalWidthPx = (originalLeftPanelWidth * density).toInt()
        val params = leftNavPanel.layoutParams as android.widget.FrameLayout.LayoutParams
        params.width = originalWidthPx
        leftNavPanel.layoutParams = params
        isLeftPanelReduced = false
        
        // Ajustar tamaño del texto al ancho original
        adjustTextSizeForPanelWidth(originalWidthPx)
        
        // Enfocar el primer item al iniciar
        navItems[0].requestFocus()
        
        // Mantener las barras ocultas cuando se recibe foco
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                hideSystemBars()
            }
        }
    }
    
    private fun setupFocusListeners() {
        // Listeners para items del panel izquierdo (optimizado para fluidez)
        navItems.forEachIndexed { index, textView ->
            textView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus && !isFullscreen) {
                    // Restaurar ancho del panel izquierdo cuando se selecciona un item del panel
                    if (isLeftPanelReduced) {
                        restoreLeftPanelWidth()
                    }
                    // Restaurar ancho del reproductor y panel derecho cuando el focus vuelve al panel izquierdo
                    restoreReproductorWidth()
                    restoreRightSidebarWidth()
                    // Actualizar de forma asíncrona para mejor fluidez
                    view.post {
                        if (selectedIndex != index) {
                            updateSelection(index)
                        }
                        clearOtherSelections(view)
                        currentFocusedView = view
                    }
                } else if (!hasFocus) {
                    // Limpiar de forma asíncrona
                    view.post {
                        view.isSelected = false
                    }
                    // Si se pierde el foco del panel izquierdo y se selecciona otra cosa, reducir el panel
                    // (esto se manejará en los otros listeners cuando obtengan el foco)
                }
            }
        }
        
        // Listener para el reproductor (optimizado para fluidez)
        mainContentArea.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus && !isFullscreen) {
                // Reducir ancho del panel izquierdo cuando se selecciona el reproductor
                if (!isLeftPanelReduced) {
                    reduceLeftPanelWidth()
                }
                // Reducir ancho del reproductor y aumentar panel derecho
                reduceReproductorWidth()
                expandRightSidebarWidth()
                view.post {
                    if (!mainContentArea.isSelected) {
                        mainContentArea.isSelected = true
                        clearOtherSelections(view)
                    }
                    currentFocusedView = view
                }
            } else if (!hasFocus && !isFullscreen) {
                // Restaurar ancho del panel izquierdo
                if (isLeftPanelReduced) {
                    restoreLeftPanelWidth()
                }
                // Restaurar ancho del reproductor y panel derecho
                restoreReproductorWidth()
                restoreRightSidebarWidth()
                view.post {
                    if (mainContentArea.isSelected) {
                        mainContentArea.isSelected = false
                    }
                }
            }
        }
        
        // Asegurar que el PlayerView NO intercepte el focus del contenedor
        playerView.isFocusable = false
        playerView.isClickable = false
        playerView.isFocusableInTouchMode = false
        
        // Listeners para iconos (optimizado para fluidez)
        val iconViews = listOf(searchTop, profile, rightSidebar, searchBottom)
        iconViews.forEach { view ->
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus && !isFullscreen) {
                    // Reducir ancho del panel izquierdo cuando se selecciona un icono
                    if (!isLeftPanelReduced) {
                        reduceLeftPanelWidth()
                    }
                    v.post {
                        if (!v.isSelected) {
                            v.isSelected = true
                            clearOtherSelections(v)
                        }
                        currentFocusedView = v
                    }
                } else if (!hasFocus) {
                    v.post {
                        v.isSelected = false
                    }
                }
            }
        }
    }
    
    private fun clearOtherSelections(selectedView: View) {
        // Optimizado para mejor fluidez: actualizar solo los que necesitan cambio
        when (selectedView) {
            in navItems -> {
                // Si es un item del panel, limpiar otros items y otros elementos
                navItems.forEachIndexed { index, item ->
                    if (item != selectedView && item.isSelected) {
                        item.isSelected = false
                    }
                }
                if (mainContentArea.isSelected) mainContentArea.isSelected = false
                if (searchTop.isSelected) searchTop.isSelected = false
                if (profile.isSelected) profile.isSelected = false
                if (rightSidebar.isSelected) rightSidebar.isSelected = false
                if (searchBottom.isSelected) searchBottom.isSelected = false
                if (!selectedView.isSelected) selectedView.isSelected = true
            }
            mainContentArea -> {
                navItems.forEach { if (it.isSelected) it.isSelected = false }
                if (searchTop.isSelected) searchTop.isSelected = false
                if (profile.isSelected) profile.isSelected = false
                if (rightSidebar.isSelected) rightSidebar.isSelected = false
                if (searchBottom.isSelected) searchBottom.isSelected = false
                if (!selectedView.isSelected) selectedView.isSelected = true
            }
            else -> {
                // Para iconos y sidebar
                navItems.forEach { if (it.isSelected) it.isSelected = false }
                if (mainContentArea.isSelected) mainContentArea.isSelected = false
                if (searchTop != selectedView && searchTop.isSelected) searchTop.isSelected = false
                if (profile != selectedView && profile.isSelected) profile.isSelected = false
                if (rightSidebar != selectedView && rightSidebar.isSelected) rightSidebar.isSelected = false
                if (searchBottom != selectedView && searchBottom.isSelected) searchBottom.isSelected = false
                if (!selectedView.isSelected) selectedView.isSelected = true
            }
        }
    }
    
    private fun setupBackPressHandler() {
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    // En modo fullscreen, salir del fullscreen
                    exitFullscreen()
                } else {
                    val currentFocus = currentFocusedView
                    // Si el reproductor está seleccionado, volver al panel
                    if (mainContentArea.hasFocus() || mainContentArea.isSelected) {
                        navItems[selectedIndex].requestFocus()
                    } else if (currentFocus in navItems) {
                        // Si está en el panel izquierdo, manejar doble clic para salir
                        handleBackPress()
                    } else {
                        // Para otros elementos, permitir el comportamiento por defecto
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isFullscreen) {
            // En modo fullscreen, solo manejar el botón atrás
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                exitFullscreen()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Obtener el elemento que tiene el foco actualmente (optimizado)
                val currentFocus = window.currentFocus ?: currentFocusedView
                
                // Si el focus está en playerView o mainContentArea (o es hijo de mainContentArea), entrar en fullscreen
                if (currentFocus == playerView || currentFocus == mainContentArea || 
                    (currentFocus != null && currentFocus.parent == mainContentArea)) {
                    enterFullscreen()
                    return true
                }
                
                if (currentFocus != null) {
                    handleCenterButton(currentFocus)
                    return true
                }
                
                // Si no hay focus específico pero el reproductor está visible y seleccionado
                if (mainContentArea.visibility == View.VISIBLE && (mainContentArea.isSelected || mainContentArea.hasFocus())) {
                    enterFullscreen()
                    return true
                }
            }
            KeyEvent.KEYCODE_HOME -> {
                // Salir de la app completamente con botón HOME
                finishAffinity()
                return true
            }
            // Las flechas (UP, DOWN, LEFT, RIGHT) se manejan automáticamente por Android
            // con navegación libre basada en la posición de las vistas
        }
        return super.onKeyDown(keyCode, event)
    }
    
    // La navegación con las flechas es libre: Android maneja automáticamente la navegación
    // basándose en la posición espacial de las vistas focusables
    
    private fun handleCenterButton(currentFocus: View) {
        when (currentFocus) {
            in navItems -> {
                val index = navItems.indexOf(currentFocus)
                when (index) {
                    0 -> {
                        // TV - Mostrar/refrescar MainActivity
                        showMainContent()
                    }
                    else -> {
                        // PELICULAS, SERIES, ANIME, DRAMAS CORTOS - Preparado para funcionalidad futura
                    }
                }
            }
            mainContentArea, playerView -> {
                // Entrar en fullscreen (detecta tanto mainContentArea como playerView)
                enterFullscreen()
            }
            searchTop -> {
                // Búsqueda - Preparado para funcionalidad futura
            }
            profile -> {
                // Perfil - Preparado para funcionalidad futura
            }
            rightSidebar -> {
                // Lista de canales - Preparado para funcionalidad futura
            }
            searchBottom -> {
                // Búsqueda inferior - Preparado para funcionalidad futura
            }
            else -> {
                // Verificar si es un canal del panel derecho
                if (currentFocus is LinearLayout && currentFocus in channelRows) {
                    val selectedChannel = channelRowToChannelMap[currentFocus]
                    if (selectedChannel != null && selectedChannel.url.isNotBlank()) {
                        android.util.Log.d("MainActivity", "Playing selected channel: ${selectedChannel.name}")
                        playChannel(selectedChannel.url)
                    } else {
                        android.util.Log.w("MainActivity", "Selected channel has no URL or not found in map")
                    }
                }
            }
        }
    }
    
    private fun showMainContent() {
        // Asegurarse de que estamos en la vista principal
        // Si estamos en fullscreen, salir primero
        if (isFullscreen) {
            exitFullscreen()
        }
        
        // Refrescar el contenido del reproductor (MainActivity)
        // Asegurarse de que todos los elementos estén visibles
        mainContentArea.visibility = View.VISIBLE
        leftNavPanel.visibility = View.VISIBLE
        rightSidebar.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        searchBottom.visibility = View.VISIBLE
        
        // Restaurar el tamaño del reproductor si fue modificado (usar valores originales)
        val params = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        val marginStart = (100 * resources.displayMetrics.density).toInt()
        val marginEnd = (originalReproductorMarginEnd * resources.displayMetrics.density).toInt()
        params.setMargins(marginStart, 0, marginEnd, 0)
        mainContentArea.layoutParams = params
        
        // Restaurar ancho del panel izquierdo si estaba reducido
        if (isLeftPanelReduced) {
            restoreLeftPanelWidth()
        }
        
        // Restaurar ancho del panel derecho
        val sidebarParams = rightSidebar.layoutParams as android.widget.FrameLayout.LayoutParams
        val sidebarWidth = (originalRightSidebarWidth * resources.displayMetrics.density).toInt()
        if (sidebarParams.width != sidebarWidth) {
            sidebarParams.width = sidebarWidth
            rightSidebar.layoutParams = sidebarParams
        }
        
        // Enfocar el reproductor para mostrar que está activo
        mainContentArea.requestFocus()
    }
    
    private fun enterFullscreen() {
        // GUARDAR ESTADO ACTUAL antes de entrar en fullscreen
        savedLeftPanelReduced = isLeftPanelReduced
        savedFocusedView = currentFocusedView ?: window.currentFocus
        savedSelectedIndex = selectedIndex
        
        // Verificar si el reproductor estaba reducido (margen derecho mayor que el original)
        val reproParams = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        val density = resources.displayMetrics.density
        val originalMarginEndPx = (originalReproductorMarginEnd * density).toInt()
        savedReproductorReduced = reproParams.rightMargin > originalMarginEndPx
        
        // Verificar si el panel derecho estaba expandido
        val sidebarParams = rightSidebar.layoutParams as android.widget.FrameLayout.LayoutParams
        val originalSidebarWidthPx = (originalRightSidebarWidth * density).toInt()
        savedRightSidebarExpanded = sidebarParams.width > originalSidebarWidthPx
        
        isFullscreen = true
        
        // Restaurar ancho del panel izquierdo antes de ocultarlo (sin animación para que sea instantáneo)
        if (isLeftPanelReduced) {
            val targetWidth = (originalLeftPanelWidth * density).toInt()
            val params = leftNavPanel.layoutParams as android.widget.FrameLayout.LayoutParams
            params.width = targetWidth
            leftNavPanel.layoutParams = params
            isLeftPanelReduced = false
        }
        
        // Restaurar ancho del reproductor y panel derecho antes de ocultarlos (sin animación)
        // Restaurar márgenes del reproductor a valores originales
        val marginStart = (100 * density).toInt()
        val marginEnd = (originalReproductorMarginEnd * density).toInt()
        reproParams.setMargins(marginStart, 0, marginEnd, 0)
        mainContentArea.layoutParams = reproParams
        
        // Restaurar ancho del panel derecho
        val sidebarWidth = (originalRightSidebarWidth * density).toInt()
        sidebarParams.width = sidebarWidth
        rightSidebar.layoutParams = sidebarParams
        
        // Ocultar todos los elementos excepto el reproductor (usar View.GONE para mejor rendimiento)
        // Guardar estados de visibilidad para restaurar después
        leftNavPanel.visibility = View.GONE
        rightSidebar.visibility = View.GONE
        topBar.visibility = View.GONE
        searchTop.visibility = View.GONE
        profile.visibility = View.GONE
        searchBottom.visibility = View.GONE
        statusIndicatorContainer.visibility = View.GONE // Ocultar indicador de estado también
        
        // Expandir el reproductor a pantalla completa (optimizado)
        val params = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        if (params.leftMargin != 0 || params.rightMargin != 0 || params.topMargin != 0 || params.bottomMargin != 0) {
            params.setMargins(0, 0, 0, 0)
            mainContentArea.layoutParams = params
        }
        mainContentArea.requestFocus()
    }
    
    private fun exitFullscreen() {
        isFullscreen = false
        
        // Mostrar todos los elementos primero (restaurar visibilidad completa)
        leftNavPanel.visibility = View.VISIBLE
        rightSidebar.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        searchTop.visibility = View.VISIBLE
        profile.visibility = View.VISIBLE
        searchBottom.visibility = View.VISIBLE
        // El indicador de estado se mostrará automáticamente si es necesario (no forzar VISIBLE)
        
        // Restaurar el tamaño y posición del reproductor a valores originales primero
        val density = resources.displayMetrics.density
        val params = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        val marginStart = (100 * density).toInt()
        val marginEnd = (originalReproductorMarginEnd * density).toInt()
        params.setMargins(marginStart, 0, marginEnd, 0)
        mainContentArea.layoutParams = params
        
        // Restaurar ancho del panel derecho a valor original primero
        val sidebarParams = rightSidebar.layoutParams as android.widget.FrameLayout.LayoutParams
        val sidebarWidth = (originalRightSidebarWidth * density).toInt()
        sidebarParams.width = sidebarWidth
        rightSidebar.layoutParams = sidebarParams
        
        // Restaurar ancho del panel izquierdo a valor original primero
        if (isLeftPanelReduced) {
            val leftParams = leftNavPanel.layoutParams as android.widget.FrameLayout.LayoutParams
            val leftWidth = (originalLeftPanelWidth * density).toInt()
            leftParams.width = leftWidth
            leftNavPanel.layoutParams = leftParams
            isLeftPanelReduced = false
        }
        
        // RESTAURAR ESTADOS GUARDADOS (exactamente como estaban antes del fullscreen)
        // Restaurar estado del panel izquierdo
        if (savedLeftPanelReduced) {
            isLeftPanelReduced = true
            reduceLeftPanelWidth()
        }
        
        // Restaurar estado del reproductor
        if (savedReproductorReduced) {
            reduceReproductorWidth()
        }
        
        // Restaurar estado del panel derecho
        if (savedRightSidebarExpanded) {
            expandRightSidebarWidth()
        }
        
        // Restaurar índice seleccionado y foco
        selectedIndex = savedSelectedIndex
        val viewToFocus = savedFocusedView ?: navItems[savedSelectedIndex]
        
        // Restaurar selección visual
        updateSelection(savedSelectedIndex)
        
        // Asegurar que mainContentArea esté visible y con su estado correcto
        mainContentArea.visibility = View.VISIBLE
        mainContentArea.isSelected = false // Resetear selección del reproductor
        
        // Restaurar el foco al elemento que tenía el foco antes del fullscreen
        viewToFocus.post {
            try {
                viewToFocus.requestFocus()
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Error restoring focus: ${e.message}")
                // Fallback: enfocar el primer item del panel izquierdo
                navItems[0].requestFocus()
            }
        }
        
        // Asegurar que todos los estados estén correctos
        // Limpiar cualquier selección residual
        clearOtherSelections(viewToFocus)
        
        android.util.Log.d("MainActivity", "Fullscreen exited, all elements restored")
    }
    
    private fun updateSelection(index: Int) {
        selectedIndex = index
        // Optimizado: actualizar solo los que cambian
        navItems.forEachIndexed { i, textView ->
            val shouldBeSelected = (i == index)
            if (textView.isSelected != shouldBeSelected) {
                textView.isSelected = shouldBeSelected
            }
        }
    }
    
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }
    
    /**
     * Reduce el ancho del panel izquierdo con animación
     */
    private fun reduceLeftPanelWidth() {
        if (isLeftPanelReduced || isFullscreen) return
        
        isLeftPanelReduced = true
        val density = resources.displayMetrics.density
        val targetWidth = (reducedLeftPanelWidth * density).toInt()
        
        val params = leftNavPanel.layoutParams as android.widget.FrameLayout.LayoutParams
        val currentWidth = params.width
        
        // Crear animación de ancho
        android.animation.ValueAnimator.ofInt(currentWidth, targetWidth).apply {
            duration = 250 // 250ms para animación suave
            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Int
                params.width = animatedValue
                leftNavPanel.layoutParams = params
                
                // Ajustar tamaño del texto durante la animación
                adjustTextSizeForPanelWidth(animatedValue)
            }
            start()
        }
    }
    
    /**
     * Restaura el ancho original del panel izquierdo con animación
     */
    private fun restoreLeftPanelWidth() {
        if (!isLeftPanelReduced || isFullscreen) return
        
        isLeftPanelReduced = false
        val density = resources.displayMetrics.density
        val targetWidth = (originalLeftPanelWidth * density).toInt()
        
        val params = leftNavPanel.layoutParams as android.widget.FrameLayout.LayoutParams
        val currentWidth = params.width
        
        // Crear animación de ancho
        android.animation.ValueAnimator.ofInt(currentWidth, targetWidth).apply {
            duration = 250 // 250ms para animación suave
            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Int
                params.width = animatedValue
                leftNavPanel.layoutParams = params
                
                // Ajustar tamaño del texto durante la animación
                adjustTextSizeForPanelWidth(animatedValue)
            }
            start()
        }
    }
    
    /**
     * Ajusta el tamaño del texto de los items del panel según el ancho del panel
     */
    private fun adjustTextSizeForPanelWidth(panelWidthPx: Int) {
        val density = resources.displayMetrics.density
        val originalWidthPx = (originalLeftPanelWidth * density).toInt()
        val reducedWidthPx = (reducedLeftPanelWidth * density).toInt()
        
        // Calcular tamaño del texto basado en el ancho actual
        val originalTextSize = 18f // sp
        val reducedTextSize = 14f // sp
        
        // Interpolar entre tamaño original y reducido
        val textSize = if (panelWidthPx <= reducedWidthPx) {
            reducedTextSize
        } else if (panelWidthPx >= originalWidthPx) {
            originalTextSize
        } else {
            // Interpolación lineal
            val ratio = (panelWidthPx - reducedWidthPx).toFloat() / (originalWidthPx - reducedWidthPx).toFloat()
            reducedTextSize + (originalTextSize - reducedTextSize) * ratio
        }
        
        // Aplicar tamaño a todos los items del panel
        navItems.forEach { textView ->
            textView.textSize = textSize
        }
    }
    
    /**
     * Reduce el ancho del reproductor cuando está seleccionado
     */
    private fun reduceReproductorWidth() {
        if (isFullscreen) return
        
        val density = resources.displayMetrics.density
        val targetMarginEnd = (reducedReproductorMarginEnd * density).toInt()
        
        val params = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        val currentMarginEnd = params.rightMargin
        
        // Crear animación de margen
        android.animation.ValueAnimator.ofInt(currentMarginEnd, targetMarginEnd).apply {
            duration = 250
            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Int
                params.rightMargin = animatedValue
                mainContentArea.layoutParams = params
            }
            start()
        }
    }
    
    /**
     * Restaura el ancho original del reproductor
     */
    private fun restoreReproductorWidth() {
        if (isFullscreen) return
        
        val density = resources.displayMetrics.density
        val targetMarginEnd = (originalReproductorMarginEnd * density).toInt()
        
        val params = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        val currentMarginEnd = params.rightMargin
        
        // Crear animación de margen
        android.animation.ValueAnimator.ofInt(currentMarginEnd, targetMarginEnd).apply {
            duration = 250
            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Int
                params.rightMargin = animatedValue
                mainContentArea.layoutParams = params
            }
            start()
        }
    }
    
    /**
     * Aumenta el ancho del panel derecho cuando el reproductor se reduce
     */
    private fun expandRightSidebarWidth() {
        if (isFullscreen) return
        
        val density = resources.displayMetrics.density
        val targetWidth = (expandedRightSidebarWidth * density).toInt()
        
        val params = rightSidebar.layoutParams as android.widget.FrameLayout.LayoutParams
        val currentWidth = params.width
        
        // Crear animación de ancho
        android.animation.ValueAnimator.ofInt(currentWidth, targetWidth).apply {
            duration = 250
            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Int
                params.width = animatedValue
                rightSidebar.layoutParams = params
            }
            start()
        }
    }
    
    /**
     * Restaura el ancho original del panel derecho
     */
    private fun restoreRightSidebarWidth() {
        if (isFullscreen) return
        
        val density = resources.displayMetrics.density
        val targetWidth = (originalRightSidebarWidth * density).toInt()
        
        val params = rightSidebar.layoutParams as android.widget.FrameLayout.LayoutParams
        val currentWidth = params.width
        
        // Crear animación de ancho
        android.animation.ValueAnimator.ofInt(currentWidth, targetWidth).apply {
            duration = 250
            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Int
                params.width = animatedValue
                rightSidebar.layoutParams = params
            }
            start()
        }
    }
    
    private fun handleBackPress(): Boolean {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastBackPressTime < BACK_PRESS_INTERVAL) {
            // Segundo clic dentro del intervalo, salir de la app
            finishAffinity()
            return true
        } else {
            // Primer clic, mostrar mensaje y reiniciar contador
            lastBackPressTime = currentTime
            showExitMessage()
            return true
        }
    }
    
    private fun showExitHint() {
        // Mostrar mensaje informativo cuando el usuario está en el panel izquierdo
        android.widget.Toast.makeText(
            this,
            "Presiona ATRÁS dos veces para salir",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    private fun showExitMessage() {
        // Mostrar mensaje cuando se presiona BACK por primera vez
        android.widget.Toast.makeText(
            this,
            "Presiona ATRÁS nuevamente para salir",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    /**
     * Inicializa ExoPlayer con configuración optimizada para LIVE streaming
     * Buffer configurado para PRELOAD y evitar congelamientos, NO para ralentizar
     * El buffer pre-carga datos para mantener reproducción fluida cuando hay variaciones de red
     */
    private fun initializeExoPlayer() {
        // Configurar HttpDataSource con timeouts más largos para evitar errores de conexión
        val httpDataSourceFactory: HttpDataSource.Factory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30000) // 30 segundos para conexión (aumentado de 8s por defecto)
            .setReadTimeoutMs(30000) // 30 segundos para lectura
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
        
        // DataSourceFactory con configuración personalizada
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        
        // LoadControl optimizado para LIVE streaming con PRELOAD inteligente
        // El buffer sirve para pre-cargar datos y evitar congelamientos, no para ralentizar
        val loadControl: LoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                10000,  // minBufferMs: Buffer mínimo de 10 segundos (preload para evitar cortes)
                20000,  // maxBufferMs: Buffer máximo de 20 segundos (suficiente preload sin exceso)
                2000,   // bufferForPlaybackMs: Preload mínimo antes de empezar (2 segundos - inicio rápido)
                4000    // bufferForPlaybackAfterRebufferMs: Preload después de rebuffer (4 segundos)
            )
            .setTargetBufferBytes(C.LENGTH_UNSET) // Sin límite de bytes para preload continuo
            .setPrioritizeTimeOverSizeThresholds(true) // Priorizar tiempo sobre tamaño para fluidez
            .setBackBuffer(0, false) // Sin buffer hacia atrás (streaming en vivo no lo necesita)
            .build()
        
        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { player ->
                // Configurar PlayerView
                playerView.player = player
                playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
                playerView.useController = false // Desactivar controles (no mostrar botones)
                
                // IMPORTANTE: Asegurar que PlayerView NO intercepte el focus del contenedor
                playerView.isFocusable = false
                playerView.isClickable = false
                playerView.isFocusableInTouchMode = false
                
                // Activar handleAudioBecomingNoisy
                player.setHandleAudioBecomingNoisy(true)
                
                // Mantener pantalla encendida durante reproducción
                playerView.keepScreenOn = true
                
                // Configurar listener para monitoreo de estado
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                // Iniciar monitoreo de buffering
                                bufferingStartTime = System.currentTimeMillis()
                                checkBufferingTimeout()
                            }
                            Player.STATE_READY -> {
                                // Reproducción lista, resetear contadores
                                bufferingStartTime = 0
                                retryCount = 0
                                startPlaybackMonitor()
                            }
                            Player.STATE_ENDED -> {
                                // Stream terminado, intentar refresh
                                handleStreamEnd()
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // Error en reproducción, analizar tipo de error
                        android.util.Log.e("MainActivity", "ExoPlayer error: ${error.message}", error)
                        android.util.Log.e("MainActivity", "Error code: ${error.errorCode}, cause: ${error.cause?.message}")
                        
                        // Verificar si es un error de conexión/timeout
                        val isConnectionError = error.cause is java.net.SocketTimeoutException ||
                                error.cause is java.net.ConnectException ||
                                error.cause is java.net.UnknownHostException ||
                                (error.cause is androidx.media3.datasource.HttpDataSource.HttpDataSourceException &&
                                 (error.cause as? androidx.media3.datasource.HttpDataSource.HttpDataSourceException)?.type == androidx.media3.datasource.HttpDataSource.HttpDataSourceException.TYPE_OPEN)
                        
                        if (isConnectionError) {
                            android.util.Log.w("MainActivity", "Connection/timeout error detected, will retry...")
                            // Para errores de conexión, dar más tiempo antes de reintentar
                            handler.postDelayed({
                                handlePlaybackError()
                            }, 2000) // Esperar 2 segundos antes de reintentar
                        } else {
                            // Para otros errores, manejar inmediatamente
                            handlePlaybackError()
                        }
                    }
                })
            }
    }
    
    /**
     * Observa el ViewModel para actualizar el reproductor
     */
    private fun observeViewModel() {
        // Observar LiveData para currentChannel
        viewModel.currentChannel.observe(this, Observer<com.dragmax.dragmaxtv.data.entity.LiveChannel?> { channel ->
            android.util.Log.d("MainActivity", "currentChannel observed: ${channel?.name ?: "null"}")
            channel?.let {
                android.util.Log.d("MainActivity", "Channel URL: ${it.url}")
                if (it.url.isNotBlank()) {
                    // Guardar como canal predeterminado si aún no está guardado
                    if (defaultChannelUrl == null) {
                        defaultChannelUrl = it.url
                        // Guardar de forma persistente
                        sharedPreferences.edit().putString("default_channel_url", it.url).apply()
                        android.util.Log.d("MainActivity", "Default channel saved: ${it.name}")
                    }
                    playChannel(it.url)
                } else {
                    android.util.Log.e("MainActivity", "Channel URL is blank for channel: ${it.name}")
                    showError("Error: URL del canal vacía para ${it.name}")
                }
            } ?: run {
                android.util.Log.w("MainActivity", "Channel is null, not playing")
            }
        })
        
        // Observar StateFlow para loadingState
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loadingState.collect { state ->
                    when (state) {
                        is PlayerViewModel.LoadingState.Loading -> {
                            updateStatusIndicator(state)
                            // Resetear flag cuando inicia una nueva descarga
                            if (state.phase == PlayerViewModel.LoadingPhase.DOWNLOADING && state.progress == 0) {
                                hasRefreshedAfterDownload = false
                            }
                        }
                        is PlayerViewModel.LoadingState.Success -> {
                            // Ocultar indicador inmediatamente cuando se completa la descarga
                            hideStatusIndicator()
                            // Cargar canales en el panel derecho
                            loadChannelsToSidebar()
                            
                            // Cuando todas las listas estén al 100%, verificar si el canal necesita ser restaurado
                            // Solo hacer refresh si realmente es necesario (no está reproduciéndose correctamente)
                            if (!hasRefreshedAfterDownload && defaultChannelUrl != null) {
                                hasRefreshedAfterDownload = true
                                android.util.Log.d("MainActivity", "Todas las listas descargadas al 100%, verificando estado del canal...")
                                
                                // Pequeño delay para asegurar que todo esté listo
                                lifecycleScope.launch {
                                    delay(2000) // Esperar 2 segundos después de completar la descarga
                                    
                                    exoPlayer?.let { player ->
                                        val isPlaying = player.playbackState == Player.STATE_READY && player.isPlaying
                                        val hasCurrentChannel = currentChannelUrl != null
                                        val isBuffering = player.playbackState == Player.STATE_BUFFERING
                                        
                                        // Solo hacer refresh si realmente es necesario
                                        if (!hasCurrentChannel || (!isPlaying && !isBuffering)) {
                                            // No hay canal o no está reproduciéndose, reproducir el predeterminado
                                            defaultChannelUrl?.let { url ->
                                                android.util.Log.d("MainActivity", "Canal no está reproduciéndose, iniciando canal predeterminado: $url")
                                                // Resetear contadores antes de reproducir
                                                retryCount = 0
                                                bufferingStartTime = 0
                                                lastPlaybackPosition = 0
                                                playChannel(url)
                                            }
                                        } else {
                                            // El canal está reproduciéndose correctamente, no hacer nada
                                            android.util.Log.d("MainActivity", "Canal ya está reproduciéndose correctamente, no se requiere refresh")
                                        }
                                    } ?: run {
                                        // Si ExoPlayer no está inicializado, reproducir directamente
                                        defaultChannelUrl?.let { url ->
                                            android.util.Log.d("MainActivity", "ExoPlayer no inicializado, reproduciendo canal predeterminado: $url")
                                            retryCount = 0
                                            playChannel(url)
                                        }
                                    }
                                }
                            }
                        }
                        is PlayerViewModel.LoadingState.Error -> {
                            hideStatusIndicator()
                        }
                        PlayerViewModel.LoadingState.Idle -> {
                            hideStatusIndicator()
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Hace scroll automático en el ScrollView para mostrar el canal seleccionado
     */
    private fun scrollToChannel(channelView: View) {
        try {
            val scrollView = channelsScrollView
            val scrollBounds = android.graphics.Rect()
            scrollView.getHitRect(scrollBounds)
            
            // Obtener la posición del canal en el ScrollView
            val channelBounds = android.graphics.Rect()
            channelView.getHitRect(channelBounds)
            
            // Convertir coordenadas relativas al ScrollView
            val location = IntArray(2)
            channelView.getLocationInWindow(location)
            val scrollLocation = IntArray(2)
            scrollView.getLocationInWindow(scrollLocation)
            
            val relativeTop = location[1] - scrollLocation[1] + scrollView.scrollY
            val relativeBottom = relativeTop + channelView.height
            
            // Si el canal está fuera de la vista visible, hacer scroll
            if (relativeTop < scrollView.scrollY) {
                // El canal está arriba de la vista visible, hacer scroll hacia arriba
                scrollView.smoothScrollTo(0, relativeTop - scrollView.paddingTop)
            } else if (relativeBottom > scrollView.scrollY + scrollView.height) {
                // El canal está abajo de la vista visible, hacer scroll hacia abajo
                val scrollTo = relativeBottom - scrollView.height + scrollView.paddingBottom
                scrollView.smoothScrollTo(0, scrollTo)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Error scrolling to channel: ${e.message}")
        }
    }
    
    /**
     * Reproduce un canal con sistema de estabilidad
     */
    private fun playChannel(url: String) {
        android.util.Log.d("MainActivity", "playChannel called with URL: $url")
        
        if (url.isBlank()) {
            android.util.Log.e("MainActivity", "URL is blank, cannot play channel")
            showError("Error: URL del canal vacía")
            return
        }
        
        currentChannelUrl = url
        retryCount = 0
        
        exoPlayer?.let { player ->
            try {
                android.util.Log.d("MainActivity", "Creating MediaItem and preparing player")
                val mediaItem = MediaItem.fromUri(url)
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = true
                android.util.Log.d("MainActivity", "Player prepared and playWhenReady set to true")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error playing channel: ${e.message}", e)
                showError("Error al reproducir: ${e.message}")
            }
        } ?: run {
            android.util.Log.e("MainActivity", "ExoPlayer is null, cannot play channel")
            showError("Error: Reproductor no inicializado")
        }
    }
    
    /**
     * Monitorea el estado de buffering y detecta timeouts
     */
    private fun checkBufferingTimeout() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (bufferingStartTime > 0) {
                val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
                if (bufferingDuration >= BUFFERING_TIMEOUT) {
                    // Buffering por demasiado tiempo, intentar refresh
                    handleBufferingTimeout()
                } else {
                    // Continuar monitoreando
                    checkBufferingTimeout()
                }
            }
        }, 1000) // Verificar cada segundo
    }
    
    /**
     * Monitorea si la reproducción se congela (playback stuck)
     */
    private fun startPlaybackMonitor() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = lifecycleScope.launch {
            var isMonitoring = true
            var stuckCount = 0 // Contador de veces que la posición no cambia
            val requiredStuckChecks = 3 // Requerir 3 verificaciones consecutivas (6 segundos) antes de considerar congelado
            
            while (isMonitoring) {
                delay(2000) // Verificar cada 2 segundos
                val player = exoPlayer
                if (player != null && player.playbackState == Player.STATE_READY && player.isPlaying) {
                    val currentPosition = player.currentPosition
                    // Verificar si la posición cambió (con margen de tolerancia para streams en vivo)
                    val positionChanged = Math.abs(currentPosition - lastPlaybackPosition) > 1000 // Al menos 1 segundo de diferencia
                    
                    if (positionChanged) {
                        // La posición cambió, resetear contador
                        stuckCount = 0
                        lastPlaybackPosition = currentPosition
                    } else if (lastPlaybackPosition > 0) {
                        // La posición no cambió, incrementar contador
                        stuckCount++
                        android.util.Log.d("MainActivity", "Playback position unchanged, stuck count: $stuckCount/$requiredStuckChecks")
                        
                        // Solo considerar congelado después de múltiples verificaciones
                        if (stuckCount >= requiredStuckChecks) {
                            android.util.Log.w("MainActivity", "Playback appears to be stuck, attempting refresh...")
                            handlePlaybackStuck()
                            isMonitoring = false
                            return@launch
                        }
                    } else {
                        // Primera vez, solo guardar posición
                        lastPlaybackPosition = currentPosition
                    }
                } else {
                    // No está reproduciéndose, resetear contador
                    stuckCount = 0
                    lastPlaybackPosition = 0
                }
            }
        }
    }
    
    /**
     * Maneja timeout de buffering
     */
    private fun handleBufferingTimeout() {
        if (retryCount < MAX_RETRIES) {
            retryCount++
            refreshChannel()
        } else {
            // Máximo de reintentos alcanzado
            showError("Error: El stream no responde después de $MAX_RETRIES intentos")
        }
    }
    
    /**
     * Maneja error de reproducción
     */
    private fun handlePlaybackError() {
        if (retryCount < MAX_RETRIES) {
            retryCount++
            refreshChannel()
        } else {
            showError("Error: No se pudo reproducir el canal después de $MAX_RETRIES intentos")
        }
    }
    
    /**
     * Maneja fin de stream
     */
    private fun handleStreamEnd() {
        if (retryCount < MAX_RETRIES) {
            retryCount++
            refreshChannel()
        } else {
            showError("Error: El stream terminó inesperadamente")
        }
    }
    
    /**
     * Maneja reproducción congelada
     */
    private fun handlePlaybackStuck() {
        if (retryCount < MAX_RETRIES) {
            retryCount++
            refreshChannel()
        } else {
            showError("Error: La reproducción se congeló después de $MAX_RETRIES intentos")
        }
    }
    
    /**
     * Refresca el canal actual sin mostrar pantalla negra
     * Mantiene el último frame visible con indicador de carga
     */
    private fun refreshChannel() {
        currentChannelUrl?.let { url ->
            lifecycleScope.launch {
                exoPlayer?.let { player ->
                    // Verificar si realmente necesita refresh (no hacer refresh si está funcionando bien)
                    val isPlaying = player.playbackState == Player.STATE_READY && player.isPlaying
                    val isBuffering = player.playbackState == Player.STATE_BUFFERING
                    
                    // Si está reproduciéndose correctamente, no hacer refresh
                    if (isPlaying && !isBuffering) {
                        android.util.Log.d("MainActivity", "Canal está reproduciéndose correctamente, saltando refresh")
                        return@launch
                    }
                    
                    android.util.Log.d("MainActivity", "Refrescando canal: estado=${player.playbackState}, isPlaying=$isPlaying")
                    
                    // Mostrar indicador de carga sobre el último frame (no detener)
                    refreshLoadingOverlay.visibility = View.VISIBLE
                    
                    // Pausar pero mantener el último frame visible (no hacer stop que causa pantalla negra)
                    val wasPlaying = player.isPlaying
                    player.pause()
                    
                    // Crear listener para ocultar overlay cuando esté listo
                    val refreshListener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                refreshLoadingOverlay.visibility = View.GONE
                                player.removeListener(this) // Remover listener después de usar
                                // Resetear monitoreo después de que esté listo
                                bufferingStartTime = 0
                                lastPlaybackPosition = 0
                            }
                        }
                    }
                    player.addListener(refreshListener)
                    
                    // Pequeña pausa antes de recargar
                    delay(300)
                    
                    // Reemplazar MediaItem sin detener completamente (evita pantalla negra)
                    val mediaItem = MediaItem.fromUri(url)
                    
                    // Si hay items, reemplazar; si no, agregar
                    if (player.mediaItemCount > 0) {
                        player.replaceMediaItem(0, mediaItem)
                    } else {
                        player.setMediaItem(mediaItem)
                    }
                    
                    player.prepare()
                    
                    // Reanudar reproducción si estaba reproduciendo
                    if (wasPlaying) {
                        player.playWhenReady = true
                    }
                }
            }
        }
    }
    
    /**
     * Muestra mensaje de error al usuario
     */
    private fun showError(message: String) {
        android.widget.Toast.makeText(
            this,
            message,
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
    
    /**
     * Actualiza el indicador de estado con el progreso
     */
    private fun updateStatusIndicator(state: PlayerViewModel.LoadingState.Loading) {
        statusIndicatorContainer.visibility = View.VISIBLE
        
        val message = when (state.phase) {
            PlayerViewModel.LoadingPhase.DOWNLOADING -> {
                "Descargando canales… ${state.progress}%"
            }
            PlayerViewModel.LoadingPhase.PREPARING -> {
                "Preparando canales…"
            }
            PlayerViewModel.LoadingPhase.LOADING_FROM_DEVICE -> {
                "Cargando desde el dispositivo… ${state.progress}%"
            }
        }
        
        tvStatusIndicator.text = message
    }
    
    /**
     * Muestra el mensaje final y luego oculta el indicador suavemente
     */
    private fun showFinalMessage() {
        tvStatusIndicator.text = "Los canales aparecerán en breve…"
        statusIndicatorContainer.visibility = View.VISIBLE
        
        // Ocultar suavemente después de 2 segundos
        handler.postDelayed({
            hideStatusIndicator()
        }, 2000)
    }
    
    /**
     * Oculta el indicador de estado con animación suave
     */
    private fun hideStatusIndicator() {
        statusIndicatorContainer.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                statusIndicatorContainer.visibility = View.GONE
                statusIndicatorContainer.alpha = 1f
            }
            .start()
    }
    
    override fun onPause() {
        super.onPause()
        // Detener monitoreo
        playbackMonitorJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        playbackStuckCheckRunnable?.let { handler.removeCallbacks(it) }
        exoPlayer?.pause()
    }
    
    override fun onResume() {
        super.onResume()
        exoPlayer?.playWhenReady = true
        // Reiniciar monitoreo si está reproduciendo
        exoPlayer?.let { player ->
            if (player.playbackState == Player.STATE_READY) {
                startPlaybackMonitor()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Limpiar recursos
        playbackMonitorJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        playbackStuckCheckRunnable?.let { handler.removeCallbacks(it) }
        exoPlayer?.release()
        exoPlayer = null
    }
    
    /**
     * Carga y muestra los canales en el panel derecho con estilo de cuaderno
     * Incluye protección contra sobrecarga de Room
     */
    private fun loadChannelsToSidebar() {
        lifecycleScope.launch {
            try {
                // Protección: timeout de 5 segundos para evitar bloqueos
                val timeoutJob = launch {
                    delay(5000)
                    android.util.Log.w("MainActivity", "Timeout loading channels to sidebar")
                }
                
                val database = AppDatabase.getDatabase(this@MainActivity)
                val repository = ChannelRepository(
                    database.m3uUrlDao(),
                    database.liveChannelDao(),
                    database.listUpdateTimestampDao()
                )
                
                // Cargar canales con protección contra sobrecarga
                val channels = withContext(Dispatchers.IO) {
                    try {
                        // Limitar a máximo 10 canales para evitar sobrecarga
                        repository.getChannelsForSidebar()
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error loading channels from Room: ${e.message}", e)
                        emptyList() // Retornar lista vacía en caso de error
                    }
                }
                
                timeoutJob.cancel() // Cancelar timeout si se completó exitosamente
                
                // Validar que tenemos canales antes de continuar
                if (channels.isEmpty()) {
                    android.util.Log.w("MainActivity", "No channels loaded, skipping sidebar update")
                    return@launch
                }
                
                // Limitar a máximo 10 canales para evitar sobrecarga de UI
                val limitedChannels = channels.take(10)
                
                // Limpiar contenedor de forma segura
                runOnUiThread {
                    try {
                        channelsListContainer.removeAllViews()
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error clearing container: ${e.message}", e)
                    }
                }
                
                // Agregar canales con logo y nombre (estilo de cuaderno)
                val screenHeight = resources.displayMetrics.heightPixels
                val lineHeight = (screenHeight / 10).coerceAtLeast(40) // 10 líneas máximo
                val logoSize = (lineHeight * 0.7).toInt().coerceAtLeast(24) // 70% de la altura de línea
                
                // Procesar canales en el hilo UI de forma segura
                runOnUiThread {
                    try {
                        limitedChannels.forEachIndexed { index, channel ->
                            try {
                                // Crear contenedor horizontal para logo y nombre (focusable y navegable)
                                val channelRow = LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = android.view.Gravity.CENTER_VERTICAL
                                    setPadding(4, 4, 4, 4)
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        lineHeight
                                    )
                                    // Hacer focusable y clickable para navegación libre
                                    isFocusable = true
                                    isClickable = true
                                    isFocusableInTouchMode = false
                                    background = getDrawable(R.drawable.nav_item_background)
                                    
                                    // ID único para navegación
                                    id = View.generateViewId()
                                }
                                
                                // Agregar a la lista de filas ANTES de agregar al contenedor
                                channelRows.add(channelRow)
                                
                                // Agregar al mapa de canales para poder reproducirlos
                                channelRowToChannelMap[channelRow] = channel
                                
                                // ImageView para el logo
                                val logoImageView = ImageView(this@MainActivity).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        logoSize,
                                        logoSize
                                    ).apply {
                                        marginEnd = 8
                                    }
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                    adjustViewBounds = true
                                }
                                
                                // Cargar logo con Glide si existe (con protección mejorada)
                                if (!channel.logo.isNullOrBlank()) {
                                    try {
                                        Glide.with(this@MainActivity)
                                            .load(channel.logo)
                                            .placeholder(android.R.drawable.ic_menu_gallery)
                                            .error(android.R.drawable.ic_menu_gallery)
                                            .timeout(5000) // Timeout de 5 segundos para carga de imagen
                                            .skipMemoryCache(false) // Usar caché de memoria
                                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL) // Caché en disco
                                            .into(logoImageView)
                                    } catch (e: Exception) {
                                        // Error silencioso: usar placeholder
                                        android.util.Log.d("MainActivity", "Glide failed to load logo for ${channel.name}: ${e.message}")
                                        logoImageView.setImageResource(android.R.drawable.ic_menu_gallery)
                                    }
                                } else {
                                    // Si no hay logo, usar un placeholder
                                    logoImageView.setImageResource(android.R.drawable.ic_menu_gallery)
                                }
                                
                                // TextView para el nombre
                                val nameTextView = TextView(this@MainActivity).apply {
                                    text = channel.name
                                    setTextColor(android.graphics.Color.WHITE)
                                    textSize = 12f
                                    gravity = android.view.Gravity.CENTER_VERTICAL
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                    layoutParams = LinearLayout.LayoutParams(
                                        0,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        1f // Peso para que ocupe el espacio restante
                                    )
                                }
                                
                                // Agregar logo y nombre al contenedor
                                channelRow.addView(logoImageView)
                                channelRow.addView(nameTextView)
                                
                                // Agregar el contenedor al listado
                                channelsListContainer.addView(channelRow)
                                
                                // Agregar línea divisoria (excepto para el último)
                                if (index < limitedChannels.size - 1) {
                                    val divider = View(this@MainActivity).apply {
                                        layoutParams = LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            1
                                        )
                                        setBackgroundColor(android.graphics.Color.parseColor("#80FFFFFF"))
                                    }
                                    channelsListContainer.addView(divider)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Error adding channel ${channel.name}: ${e.message}", e)
                                // Continuar con el siguiente canal aunque uno falle
                            }
                        }
                        
                        // Navegación libre: Android manejará automáticamente la navegación
                        // basándose en la posición de las vistas
                        
                        // Configurar listeners de focus para cada canal
                        channelRows.forEach { row ->
                            row.setOnFocusChangeListener { view, hasFocus ->
                                if (hasFocus && !isFullscreen) {
                                    // Reducir ancho del panel izquierdo cuando se selecciona un canal
                                    if (!isLeftPanelReduced) {
                                        reduceLeftPanelWidth()
                                    }
                                    
                                    // Hacer scroll automático para mostrar el canal seleccionado
                                    view.post {
                                        scrollToChannel(view)
                                    }
                                }
                                view.post {
                                    view.isSelected = hasFocus
                                    if (hasFocus) {
                                        clearOtherSelections(view)
                                        currentFocusedView = view
                                    }
                                }
                            }
                        }
                        
                        android.util.Log.d("MainActivity", "Loaded ${limitedChannels.size} channels to sidebar")
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error rendering channels: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error loading channels to sidebar: ${e.message}", e)
            }
        }
    }
}

