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
import androidx.media3.exoplayer.audio.AudioSink
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import com.bumptech.glide.Glide
import android.widget.ImageView
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {
    
    /**
     * Dispatcher dedicado para operaciones de Room (completamente separado del hilo principal)
     * 
     * SEPARACIÓN CRÍTICA:
     * - Hilo principal: Solo para ExoPlayer y UI (reproducción sin pausas)
     * - roomDispatcher: Solo para operaciones de Room (base de datos)
     * 
     * Esto asegura que:
     * 1. Las operaciones de Room NO bloqueen la reproducción
     * 2. La reproducción NO espere operaciones de Room
     * 3. No hay saturación de ROM durante operaciones de base de datos
     * 4. ExoPlayer tiene prioridad absoluta en el hilo principal
     */
    private val roomDispatcher: ExecutorCoroutineDispatcher by lazy {
        // Thread pool dedicado con prioridad baja para no interferir con ExoPlayer
        Executors.newFixedThreadPool(2, java.util.concurrent.ThreadFactory { r ->
            val thread = Thread(r, "RoomDatabaseThread")
            thread.priority = Thread.NORM_PRIORITY - 1 // Prioridad ligeramente menor que el hilo principal
            thread.isDaemon = true // No bloquear cierre de app
            thread
        }).asCoroutineDispatcher()
    }
    
    private lateinit var navItems: List<TextView>
    private lateinit var mainContentArea: android.widget.FrameLayout
    private lateinit var leftNavPanel: android.widget.LinearLayout
    private lateinit var rightSidebar: android.widget.LinearLayout
    private lateinit var channelsListContainer: android.widget.LinearLayout
    private lateinit var channelsScrollView: android.widget.ScrollView
    private var channelRows: MutableList<LinearLayout> = mutableListOf() // Lista de filas de canales
    private var channelRowToChannelMap: MutableMap<LinearLayout, com.dragmax.dragmaxtv.data.entity.LiveChannel> = mutableMapOf() // Mapa de fila a canal
    private var defaultChannelUrl: String? = null // URL del canal predeterminado
    private var pendingDefaultChannelUrl: String? = null // Canal predeterminado pendiente de reproducir (esperando carga completa)
    private var areChannelsFullyLoaded: Boolean = false // Indica si todos los canales están completamente cargados
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
    private var savedMainContentAreaParams: android.view.ViewGroup.LayoutParams? = null // Parámetros originales
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
    
    // Sistema de estabilidad para streaming - reintentos ilimitados
    private var currentChannelUrl: String? = null
    private var bufferingStartTime: Long = 0
    private val BUFFERING_TIMEOUT = 10000L // 10 segundos
    private var playbackMonitorJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastPlaybackPosition: Long = 0
    private var playbackStuckCheckRunnable: Runnable? = null
    
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
        // Guardar canal predeterminado para reproducir después de que todos los canales estén cargados
        // NO reproducir inmediatamente para evitar saturar la ROM durante la carga
        defaultChannelUrl?.let { url ->
            android.util.Log.d("MainActivity", "Default channel saved, will play after all channels are loaded: $url")
            pendingDefaultChannelUrl = url // Guardar para reproducir después
        }
        // Iniciar carga de canales (prioridad sobre reproducción)
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
            // Asegurar que todos los elementos sean focusable y clickable
            textView.isFocusable = true
            textView.isFocusableInTouchMode = true
            textView.isClickable = true
            
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
            
            // Agregar onClick para que funcione con ENTER
            textView.setOnClickListener {
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
        }
        
        // Asegurar que mainContentArea sea focusable y clickable
        mainContentArea.isFocusable = true
        mainContentArea.isFocusableInTouchMode = true
        mainContentArea.isClickable = true
        
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
        
        // onClick para mainContentArea (alternar fullscreen)
        mainContentArea.setOnClickListener {
            if (!isFullscreen) {
                enterFullscreen()
            } else {
                exitFullscreen()
            }
        }
        
        // Configurar PlayerView para que pueda recibir foco y eventos de teclado
        // Esto permite que el control remoto pueda activar fullscreen con ENTER
        playerView.isFocusable = true
        playerView.isClickable = true
        playerView.isFocusableInTouchMode = true
        
        // Listener para clic táctil en PlayerView (alternar fullscreen)
        playerView.setOnClickListener {
            if (!isFullscreen) {
                enterFullscreen()
            } else {
                exitFullscreen()
            }
        }
        
        // Listener para manejar teclas en PlayerView (alternar fullscreen)
        playerView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BUTTON_SELECT,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (!isFullscreen) {
                            enterFullscreen()
                        } else {
                            exitFullscreen()
                        }
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        
        // Listeners para iconos (optimizado para fluidez)
        val iconViews = listOf(searchTop, profile, rightSidebar, searchBottom)
        iconViews.forEach { view ->
            // Asegurar que todos los iconos sean focusable y clickable
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.isClickable = true
            
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
            
            // Agregar onClick para que funcione con ENTER (preparado para funcionalidad futura)
            view.setOnClickListener {
                // Preparado para funcionalidad futura
                android.util.Log.d("MainActivity", "Icon clicked: ${view.javaClass.simpleName}")
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
        android.util.Log.d("MainActivity", "onKeyDown: keyCode=$keyCode, isFullscreen=$isFullscreen")
        
        if (isFullscreen) {
            // En modo fullscreen, solo manejar el botón atrás
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                exitFullscreen()
                return true
            }
            // También permitir salir con botón central en fullscreen
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
                exitFullscreen()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, 
            KeyEvent.KEYCODE_ENTER, 
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                return handleCenterButtonPress()
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
    
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // También manejar en onKeyUp como respaldo
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
            keyCode == KeyEvent.KEYCODE_ENTER || 
            keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!isFullscreen) {
                return handleCenterButtonPress()
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
                exitFullscreen()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
    
    private fun handleCenterButtonPress(): Boolean {
        // Obtener el elemento que tiene el foco actualmente
        val currentFocus = window.currentFocus ?: currentFocusedView
        
        android.util.Log.d("MainActivity", "Center button pressed, currentFocus: ${currentFocus?.javaClass?.simpleName}, id: ${currentFocus?.id}")
        
        // Si la vista enfocada tiene un OnClickListener, ejecutarlo directamente
        if (currentFocus != null && currentFocus.hasOnClickListeners()) {
            android.util.Log.d("MainActivity", "Executing onClick for focused view: ${currentFocus.javaClass.simpleName}")
            currentFocus.performClick()
            return true
        }
        
        // Verificar si es un canal del panel derecho primero (tiene prioridad)
        // isUserSelection=true para reproducir inmediatamente (URL ya está en memoria)
        if (currentFocus is LinearLayout && currentFocus in channelRows) {
            val selectedChannel = channelRowToChannelMap[currentFocus]
            if (selectedChannel != null && selectedChannel.url.isNotBlank()) {
                android.util.Log.d("MainActivity", "Playing selected channel: ${selectedChannel.name}")
                playChannel(selectedChannel.url, isUserSelection = true)
                return true
            }
        }
        
        // Verificar si el focus está en playerView, mainContentArea o cualquier hijo de mainContentArea
        val isInPlayer = currentFocus == playerView || 
                        currentFocus == mainContentArea || 
                        (currentFocus != null && isViewChildOf(currentFocus, mainContentArea)) ||
                        (mainContentArea.hasFocus() || mainContentArea.isSelected)
        
        if (isInPlayer) {
            android.util.Log.d("MainActivity", "Entering fullscreen from player area (focus: ${currentFocus?.javaClass?.simpleName})")
            enterFullscreen()
            return true
        }
        
        // Manejar otros elementos con foco
        if (currentFocus != null) {
            handleCenterButton(currentFocus)
            return true
        }
        
        // Fallback: Si no hay focus específico pero el reproductor está visible y seleccionado
        if (mainContentArea.visibility == View.VISIBLE && (mainContentArea.isSelected || mainContentArea.hasFocus())) {
            android.util.Log.d("MainActivity", "Entering fullscreen from fallback (mainContentArea selected)")
            enterFullscreen()
            return true
        }
        
        android.util.Log.w("MainActivity", "Center button pressed but no action taken - currentFocus is null")
        return false
    }
    
    // La navegación con las flechas es libre: Android maneja automáticamente la navegación
    // basándose en la posición espacial de las vistas focusables
    
    /**
     * Verifica si una vista es hija de otra vista
     */
    private fun isViewChildOf(child: View, parent: View): Boolean {
        var current: View? = child.parent as? View
        while (current != null) {
            if (current == parent) return true
            current = current.parent as? View
        }
        return false
    }
    
    private fun handleCenterButton(currentFocus: View) {
        android.util.Log.d("MainActivity", "handleCenterButton called for: ${currentFocus.javaClass.simpleName}")
        
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
                android.util.Log.d("MainActivity", "Entering fullscreen from handleCenterButton")
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
                // Verificar si es un canal del panel derecho (ya manejado arriba, pero por si acaso)
                // isUserSelection=true para reproducir inmediatamente (URL ya está en memoria)
                if (currentFocus is LinearLayout && currentFocus in channelRows) {
                    val selectedChannel = channelRowToChannelMap[currentFocus]
                    if (selectedChannel != null && selectedChannel.url.isNotBlank()) {
                        android.util.Log.d("MainActivity", "Playing selected channel from handleCenterButton: ${selectedChannel.name}")
                        playChannel(selectedChannel.url, isUserSelection = true)
                    } else {
                        android.util.Log.w("MainActivity", "Selected channel has no URL or not found in map")
                    }
                } else {
                    android.util.Log.w("MainActivity", "Unknown focus view: ${currentFocus.javaClass.simpleName}")
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
        
        // GUARDAR PARÁMETROS ORIGINALES de mainContentArea (sin mover el view)
        savedMainContentAreaParams = mainContentArea.layoutParams
        
        isFullscreen = true
        
        // Ocultar todos los elementos excepto el reproductor
        leftNavPanel.visibility = View.GONE
        rightSidebar.visibility = View.GONE
        topBar.visibility = View.GONE
        searchTop.visibility = View.GONE
        profile.visibility = View.GONE
        searchBottom.visibility = View.GONE
        statusIndicatorContainer.visibility = View.GONE
        
        // Obtener las dimensiones completas de la pantalla
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // Obtener el contenedor raíz para posicionar mainContentArea sobre todo
        val rootContainer = findViewById<android.view.ViewGroup>(android.R.id.content)
        
        // Traer mainContentArea al frente para que esté sobre todos los demás elementos
        mainContentArea.bringToFront()
        
        // Cambiar parámetros de layout para ocupar toda la pantalla
        // Usar coordenadas absolutas para ignorar el contenedor padre
        val fullscreenParams = android.widget.FrameLayout.LayoutParams(
            screenWidth,
            screenHeight
        )
        fullscreenParams.setMargins(0, 0, 0, 0)
        fullscreenParams.gravity = android.view.Gravity.NO_GRAVITY
        mainContentArea.layoutParams = fullscreenParams
        
        // Posicionar mainContentArea en (0, 0) para ocupar toda la pantalla
        mainContentArea.x = 0f
        mainContentArea.y = 0f
        
        // Asegurar que mainContentArea no tenga padding
        mainContentArea.setPadding(0, 0, 0, 0)
        
        // Aumentar elevación para que esté por encima de todo
        mainContentArea.elevation = 1000f
        
        // Expandir playerView al 100% dentro de mainContentArea (sin márgenes, sin padding)
        val playerParams = playerView.layoutParams as android.widget.FrameLayout.LayoutParams
        playerParams.setMargins(0, 0, 0, 0)
        playerParams.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        playerParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        playerView.layoutParams = playerParams
        
        // Asegurar que playerView no tenga padding
        playerView.setPadding(0, 0, 0, 0)
        
        // Ocultar el overlay de refresh si está visible
        refreshLoadingOverlay.visibility = View.GONE
        
        // Ocultar barras del sistema para fullscreen completo
        hideSystemBars()
        
        // Dar foco al playerView para que pueda recibir eventos de teclado
        playerView.requestFocus()
        
        android.util.Log.d("MainActivity", "Entered fullscreen - mainContentArea positioned to occupy 100% of screen without moving container")
    }
    
    private fun exitFullscreen() {
        isFullscreen = false
        
        // Restaurar parámetros originales de mainContentArea
        savedMainContentAreaParams?.let { originalParams ->
            mainContentArea.layoutParams = originalParams
        } ?: run {
            // Si no hay parámetros guardados, usar los valores por defecto del layout
            val density = resources.displayMetrics.density
            val params = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            params.setMargins(
                (100 * density).toInt(),
                0,
                (originalReproductorMarginEnd * density).toInt(),
                0
            )
            mainContentArea.layoutParams = params
        }
        
        // Restaurar posición original (x, y)
        mainContentArea.x = 0f
        mainContentArea.y = 0f
        
        // Restaurar elevación original
        mainContentArea.elevation = 0f
        
        // Mostrar todos los elementos (restaurar visibilidad completa)
        leftNavPanel.visibility = View.VISIBLE
        rightSidebar.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        searchTop.visibility = View.VISIBLE
        profile.visibility = View.VISIBLE
        searchBottom.visibility = View.VISIBLE
        
        // Restaurar playerView a su tamaño original (con margen de 4dp)
        val density = resources.displayMetrics.density
        val playerParams = playerView.layoutParams as android.widget.FrameLayout.LayoutParams
        val marginPx = (4 * density).toInt()
        playerParams.setMargins(marginPx, marginPx, marginPx, marginPx)
        playerParams.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        playerParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        playerView.layoutParams = playerParams
        
        // Restaurar ancho del panel derecho a valor original
        val sidebarParams = rightSidebar.layoutParams as android.widget.FrameLayout.LayoutParams
        val sidebarWidth = (originalRightSidebarWidth * density).toInt()
        sidebarParams.width = sidebarWidth
        rightSidebar.layoutParams = sidebarParams
        
        // Restaurar ancho del panel izquierdo a valor original
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
        
        // Limpiar referencias guardadas
        savedMainContentAreaParams = null
        
        android.util.Log.d("MainActivity", "Fullscreen exited, mainContentArea restored to original position")
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
     * Inicializa ExoPlayer con configuración mínima absoluta para máxima fluidez
     * Buffer eliminado al máximo posible (1ms mínimo técnico requerido por ExoPlayer)
     * Solo reintentos ilimitados cuando se detecta congelamiento
     * 
     * NOTA: Los logs "no latch time for frame" y "CCodecBufferChannel" son generados por el sistema
     * Android y no se pueden eliminar desde la aplicación. Son normales en streams en vivo y no
     * afectan la reproducción. Para ocultarlos en Logcat, usar filtro: -tag:CCodecBufferChannel
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
        
        // Sin buffer - configuración mínima absoluta para máxima fluidez
        val loadControl: LoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1,      // minBufferMs: Mínimo absoluto técnico (1ms) - ExoPlayer requiere al menos 1ms
                1,      // maxBufferMs: Mínimo absoluto técnico (1ms)
                1,      // bufferForPlaybackMs: Mínimo absoluto antes de empezar (1ms)
                1       // bufferForPlaybackAfterRebufferMs: Mínimo absoluto después de rebuffer (1ms)
            )
            .setTargetBufferBytes(1) // Mínimo absoluto de bytes (1 byte)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false) // Sin buffer hacia atrás
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
                
                // Configurar PlayerView para que pueda recibir foco y eventos de teclado
                // Esto permite que el control remoto pueda activar fullscreen con ENTER
                playerView.isFocusable = true
                playerView.isClickable = true
                playerView.isFocusableInTouchMode = true
                
                // Listener para clic táctil en PlayerView (alternar fullscreen)
                playerView.setOnClickListener {
                    if (!isFullscreen) {
                        enterFullscreen()
                    } else {
                        exitFullscreen()
                    }
                }
                
                // Listener para manejar teclas en PlayerView (alternar fullscreen)
                playerView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_BUTTON_SELECT,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (!isFullscreen) {
                                    enterFullscreen()
                                } else {
                                    exitFullscreen()
                                }
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                
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
                                // Monitoreo de congelamiento desactivado temporalmente para verificar fluidez
                                // startPlaybackMonitor()
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
                        
                        // Verificar si es un error de discontinuidad de audio (UnexpectedDiscontinuityException)
                        val isAudioDiscontinuity = error.cause is androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException
                        
                        if (isAudioDiscontinuity) {
                            android.util.Log.w("MainActivity", "Audio timestamp discontinuity detected, attempting to recover...")
                            // Para discontinuidades de audio, intentar recuperar sin detener la reproducción
                            // Esto es común en streams en vivo y generalmente se puede ignorar
                            handler.postDelayed({
                                // Intentar recuperar simplemente reanudando la reproducción
                                exoPlayer?.let { player ->
                                    if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                                        // Si el player está en un estado válido, solo continuar
                                        player.playWhenReady = true
                                    } else {
                                        // Si el estado no es válido, refrescar el canal
                                        handlePlaybackError()
                                    }
                                }
                            }, 500) // Esperar 500ms antes de recuperar
                            return
                        }
                        
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
                            // Para otros errores, reintentar inmediatamente
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
            android.util.Log.d("MainActivity", "currentChannel observed: ${channel?.name ?: "null"}, areChannelsFullyLoaded: $areChannelsFullyLoaded")
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
                    // OPTIMIZACIÓN: Si los canales ya están cargados, tratar como selección de usuario
                    // Esto asegura que se recargue correctamente si está pegado
                    if (areChannelsFullyLoaded) {
                        playChannel(it.url, isUserSelection = true)
                    } else {
                        playChannel(it.url)
                    }
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
                        }
                        is PlayerViewModel.LoadingState.Success -> {
                            // Ocultar indicador inmediatamente cuando se completa la descarga
                            hideStatusIndicator()
                            
                            // Marcar que todos los canales están completamente cargados
                            areChannelsFullyLoaded = true
                            android.util.Log.d("MainActivity", "All channels fully loaded, ready to play default channel")
                            
                            // Esperar un momento adicional para asegurar que la Room esté completamente lista
                            // y que no haya operaciones pendientes que puedan saturar la ROM
                            delay(1000) // 1 segundo adicional para estabilizar la Room
                            
                            // Ahora sí reproducir el canal predeterminado si está pendiente
                            // Usar isUserSelection=true porque los canales ya están cargados
                            pendingDefaultChannelUrl?.let { url ->
                                android.util.Log.d("MainActivity", "Playing pending default channel after full load: $url")
                                playChannel(url, isUserSelection = true)
                                pendingDefaultChannelUrl = null // Limpiar pendiente
                            }
                            
                            // Cargar canales en el panel derecho (después de reproducir para no interferir)
                            loadChannelsToSidebar()
                        }
                        is PlayerViewModel.LoadingState.Error -> {
                            hideStatusIndicator()
                            // Aún así, intentar reproducir el canal predeterminado si está pendiente
                            // (puede haber canales en caché)
                            if (!areChannelsFullyLoaded) {
                                areChannelsFullyLoaded = true // Marcar como cargado para permitir reproducción
                                pendingDefaultChannelUrl?.let { url ->
                                    android.util.Log.w("MainActivity", "Error loading channels, but attempting to play default channel: $url")
                                    delay(1000)
                                    // Usar isUserSelection=true para forzar recarga limpia
                                    playChannel(url, isUserSelection = true)
                                    pendingDefaultChannelUrl = null
                                }
                            }
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
     * 
     * IMPORTANTE: Esta función NO espera operaciones de Room ni bloquea el hilo principal.
     * La URL ya está en memoria cuando se llama, por lo que la reproducción es inmediata.
     * Las operaciones de Room se ejecutan en un dispatcher dedicado completamente separado.
     * 
     * @param url URL del canal a reproducir
     * @param isUserSelection Si es true, reproduce inmediatamente SIN RESTRICCIONES
     *                        (canal del panel derecho ya cargado desde Room)
     *                        Si es false, espera a que los canales estén cargados (canal predeterminado)
     */
    private fun playChannel(url: String, isUserSelection: Boolean = false) {
        android.util.Log.d("MainActivity", "playChannel called with URL: $url, areChannelsFullyLoaded: $areChannelsFullyLoaded, isUserSelection: $isUserSelection")
        
        if (url.isBlank()) {
            android.util.Log.e("MainActivity", "URL is blank, cannot play channel")
            showError("Error: URL del canal vacía")
            return
        }
        
        // Si es una selección manual del usuario (canal del panel derecho):
        // Los canales ya están cargados desde Room, reproducir INMEDIATAMENTE sin restricciones
        if (isUserSelection) {
            android.util.Log.d("MainActivity", "User selection from sidebar - channel already loaded from Room, playing immediately")
            // NO verificar areChannelsFullyLoaded porque el canal ya está en memoria desde Room
            
            // CRÍTICO: Detener completamente el canal anterior antes de reproducir el nuevo
            // Esto evita superposición de canales y que se quede pegado
            exoPlayer?.let { player ->
                lifecycleScope.launch {
                    try {
                        android.util.Log.d("MainActivity", "Playing channel: $url (current: $currentChannelUrl)")
                        
                        // Detener completamente el canal anterior
                        player.stop()
                        player.clearMediaItems()
                        
                        // Pequeña pausa para asegurar limpieza
                        kotlinx.coroutines.delay(150L)
                        
                        // Actualizar URL del canal actual
                        currentChannelUrl = url
                        
                        // Crear y configurar nuevo MediaItem
                        android.util.Log.d("MainActivity", "Creating MediaItem and preparing player")
                        val mediaItem = MediaItem.fromUri(url)
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.playWhenReady = true
                        
                        android.util.Log.d("MainActivity", "Player prepared and playWhenReady set to true")
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error stopping/playing channel: ${e.message}", e)
                        showError("Error al cambiar de canal: ${e.message}")
                    }
                }
            } ?: run {
                android.util.Log.e("MainActivity", "ExoPlayer is null, cannot play channel")
                showError("Error: Reproductor no inicializado")
            }
            return
        }
        
        // Para canal predeterminado: esperar a que todos los canales estén cargados
        if (!areChannelsFullyLoaded) {
            android.util.Log.d("MainActivity", "Channels not fully loaded yet, deferring playback to avoid ROM saturation")
            pendingDefaultChannelUrl = url // Guardar para reproducir después
            return
        }
        
        // CRÍTICO: Detener completamente el canal anterior antes de reproducir el nuevo
        // Esto evita superposición de canales y que se quede pegado
        exoPlayer?.let { player ->
            lifecycleScope.launch {
                try {
                    android.util.Log.d("MainActivity", "Playing channel: $url (current: $currentChannelUrl)")
                    
                    // Detener completamente el canal anterior
                    player.stop()
                    player.clearMediaItems()
                    
                    // Pequeña pausa para asegurar limpieza
                    kotlinx.coroutines.delay(150L)
                    
                    // Actualizar URL del canal actual
                    currentChannelUrl = url
                    
                    // Crear y configurar nuevo MediaItem
                    android.util.Log.d("MainActivity", "Creating MediaItem and preparing player")
                    val mediaItem = MediaItem.fromUri(url)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.playWhenReady = true
                    
                    android.util.Log.d("MainActivity", "Player prepared and playWhenReady set to true")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error stopping/playing channel: ${e.message}", e)
                    showError("Error al cambiar de canal: ${e.message}")
                }
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
            
            while (isMonitoring) {
                delay(1000) // Verificar cada 1 segundo
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
                        android.util.Log.d("MainActivity", "Playback position unchanged, stuck count: $stuckCount/3")
                        
                        // Detectar congelamiento después de 3 segundos sin movimiento (3 verificaciones de 1 segundo)
                        if (stuckCount >= 3) {
                            android.util.Log.w("MainActivity", "Playback congelado detectado (3 segundos sin movimiento), restaurando canal...")
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
     * Ya no hace refresh automático, solo registra el error
     */
    private fun handleBufferingTimeout() {
        android.util.Log.w("MainActivity", "Buffering timeout detectado, esperando detección de congelamiento")
        // No hacer refresh automático, solo esperar a que se detecte congelamiento real
    }
    
    /**
     * Maneja error de reproducción
     * No restaura automáticamente, espera a que se detecte congelamiento
     */
    private fun handlePlaybackError() {
        android.util.Log.w("MainActivity", "Error de reproducción detectado, esperando detección de congelamiento")
        // No restaurar automáticamente, solo cuando se detecte congelamiento real
    }
    
    /**
     * Maneja fin de stream
     * Restaura el canal ya que el stream terminó (equivalente a congelamiento)
     */
    private fun handleStreamEnd() {
        android.util.Log.w("MainActivity", "Stream terminó, restaurando canal...")
        refreshChannel() // Restaurar cuando el stream termina
    }
    
    /**
     * Maneja reproducción congelada - restaura el canal
     */
    private fun handlePlaybackStuck() {
        android.util.Log.w("MainActivity", "Reproducción congelada detectada, restaurando canal...")
        refreshChannel() // Restaurar cuando se detecta congelamiento
    }
    
    /**
     * Refresca el canal actual sin mostrar pantalla negra
     * Mantiene el último frame visible con indicador de carga
     */
    private fun refreshChannel() {
        currentChannelUrl?.let { url ->
            lifecycleScope.launch {
                exoPlayer?.let { player ->
                    val currentState = player.playbackState
                    val isPlaying = currentState == Player.STATE_READY && player.isPlaying
                    val isBuffering = currentState == Player.STATE_BUFFERING
                    val isEnded = currentState == Player.STATE_ENDED
                    
                    // Si está reproduciéndose correctamente y no terminó, no hacer refresh
                    if (isPlaying && !isBuffering && !isEnded) {
                        android.util.Log.d("MainActivity", "Canal está reproduciéndose correctamente, saltando refresh")
                        return@launch
                    }
                    
                    android.util.Log.d("MainActivity", "Refrescando canal: estado=$currentState, isPlaying=$isPlaying, isEnded=$isEnded")
                    
                    // Mostrar indicador de carga sobre el último frame (no detener)
                    refreshLoadingOverlay.visibility = View.VISIBLE
                    
                    // Si el stream terminó, necesitamos forzar la restauración
                    if (isEnded) {
                        android.util.Log.d("MainActivity", "Stream terminado, forzando restauración completa...")
                        // Limpiar items y preparar para nueva carga
                        player.stop()
                        player.clearMediaItems()
                    } else {
                        // Pausar pero mantener el último frame visible (no hacer stop que causa pantalla negra)
                        player.pause()
                    }
                    
                    // Crear listener para ocultar overlay cuando esté listo
                    val refreshListener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                refreshLoadingOverlay.visibility = View.GONE
                                player.removeListener(this) // Remover listener después de usar
                                // Resetear monitoreo después de que esté listo
                                bufferingStartTime = 0
                                lastPlaybackPosition = 0
                                // Monitoreo de congelamiento desactivado temporalmente para verificar fluidez
                                // startPlaybackMonitor()
                            }
                        }
                    }
                    player.addListener(refreshListener)
                    
                    // Pequeña pausa antes de recargar
                    delay(300)
                    
                    // Crear y configurar nuevo MediaItem
                    val mediaItem = MediaItem.fromUri(url)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.playWhenReady = true // Forzar reproducción
                    
                    android.util.Log.d("MainActivity", "Canal restaurado, preparando reproducción...")
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
        // Monitoreo de congelamiento desactivado temporalmente para verificar fluidez
        // exoPlayer?.let { player ->
        //     if (player.playbackState == Player.STATE_READY) {
        //         startPlaybackMonitor()
        //     }
        // }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Limpiar recursos
        playbackMonitorJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        playbackStuckCheckRunnable?.let { handler.removeCallbacks(it) }
        exoPlayer?.release()
        exoPlayer = null
        // Cerrar dispatcher dedicado de Room
        roomDispatcher.close()
    }
    
    /**
     * Ejecuta operaciones de Room en un dispatcher dedicado (separado del hilo principal)
     * Esto asegura que las operaciones de base de datos NO bloqueen la reproducción
     * 
     * @param block Operación de Room a ejecutar
     * @return Resultado de la operación
     */
    private suspend fun <T> executeRoomOperation(block: suspend () -> T): T {
        return withContext(roomDispatcher) {
            // Yield para dar prioridad a la reproducción (ExoPlayer)
            kotlinx.coroutines.yield()
            block()
        }
    }
    
    /**
     * Carga y muestra los canales en el panel derecho con estilo de cuaderno
     * Incluye protección contra sobrecarga de Room y NO interfiere con la reproducción
     * OPTIMIZACIÓN: Espera a que todos los canales estén completamente cargados
     * OPTIMIZACIÓN: Usa dispatcher dedicado de Room (separado del hilo principal)
     */
    private fun loadChannelsToSidebar() {
        // Diferir la carga para no interferir con la reproducción activa
        lifecycleScope.launch {
            // OPTIMIZACIÓN: Esperar a que todos los canales estén completamente cargados
            // Esto evita saturar la ROM durante la carga inicial
            while (!areChannelsFullyLoaded) {
                delay(500) // Verificar cada 500ms
            }
            
            // Esperar un momento adicional para asegurar que la Room esté completamente lista
            delay(1000) // 1 segundo adicional para estabilizar la Room
            
            // Verificar que no esté reproduciendo activamente antes de cargar
            exoPlayer?.let { player ->
                if (player.playbackState == Player.STATE_BUFFERING) {
                    // Si está buffering, esperar más tiempo
                    delay(2000) // Reducido de 3000 a 2000 ya que los canales ya están cargados
                }
            }
            
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
                
                // Cargar canales con protección contra sobrecarga - en dispatcher dedicado de Room
                // SEPARADO del hilo principal para NO bloquear la reproducción
                val channels = try {
                    executeRoomOperation {
                        // Limitar a máximo 10 canales para evitar sobrecarga
                        repository.getChannelsForSidebar()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error loading channels from Room: ${e.message}", e)
                    emptyList() // Retornar lista vacía en caso de error
                }
                
                timeoutJob.cancel() // Cancelar timeout si se completó exitosamente
                
                // Validar que tenemos canales antes de continuar
                if (channels.isEmpty()) {
                    android.util.Log.w("MainActivity", "No channels loaded, skipping sidebar update")
                    return@launch
                }
                
                // Limitar a máximo 10 canales para evitar sobrecarga de UI
                val limitedChannels = channels.take(10)
                
                // Limpiar contenedor de forma segura - en hilo UI pero con yield
                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.yield() // Dar prioridad a otras operaciones UI
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
                
                // Procesar canales en el hilo UI de forma segura - con yield entre cada canal
                withContext(Dispatchers.Main) {
                    try {
                        limitedChannels.forEachIndexed { index, channel ->
                            // Yield cada 2 canales para no bloquear la UI
                            if (index % 2 == 0 && index > 0) {
                                kotlinx.coroutines.yield()
                            }
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
                                    isFocusableInTouchMode = true
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
                                
                                // Cargar logo con Glide si existe (optimizado para no bloquear)
                                // Usar placeholder primero y cargar de forma asíncrona
                                logoImageView.setImageResource(android.R.drawable.ic_menu_gallery)
                                if (!channel.logo.isNullOrBlank()) {
                                    // Cargar de forma asíncrona sin bloquear
                                    logoImageView.post {
                                        try {
                                            Glide.with(this@MainActivity)
                                                .load(channel.logo)
                                                .placeholder(android.R.drawable.ic_menu_gallery)
                                                .error(android.R.drawable.ic_menu_gallery)
                                                .timeout(2000) // Timeout reducido a 2 segundos para fallar rápido
                                                .skipMemoryCache(false)
                                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                                .override(logoSize, logoSize) // Limitar tamaño para carga más rápida
                                                .into(logoImageView)
                                        } catch (e: Exception) {
                                            // Error silencioso: mantener placeholder
                                            // No loguear para evitar spam en logs
                                            // Glide maneja automáticamente los errores con .error()
                                        }
                                    }
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
                        
                        // Configurar listeners de focus y clic para cada canal
                        channelRows.forEach { row ->
                            // Listener de focus para navegación visual
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
                            
                            // Listener de clic directo para reproducir el canal
                            // Funciona tanto con control remoto (OK/Center) como con clic táctil
                            // isUserSelection=true para reproducir inmediatamente (URL ya está en memoria)
                            row.setOnClickListener { view ->
                                val channel = channelRowToChannelMap[view]
                                if (channel != null && channel.url.isNotBlank()) {
                                    android.util.Log.d("MainActivity", "Channel clicked: ${channel.name}, URL: ${channel.url}")
                                    playChannel(channel.url, isUserSelection = true)
                                    // Dar focus al reproductor después de reproducir
                                    mainContentArea.requestFocus()
                                } else {
                                    android.util.Log.w("MainActivity", "Clicked channel has no URL or not found in map")
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

