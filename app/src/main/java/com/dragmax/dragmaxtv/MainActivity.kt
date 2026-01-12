@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.dragmax.dragmaxtv

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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dragmax.dragmaxtv.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var navItems: List<TextView>
    private lateinit var mainContentArea: android.widget.FrameLayout
    private lateinit var leftNavPanel: android.widget.LinearLayout
    private lateinit var rightSidebar: android.widget.LinearLayout
    private lateinit var topBar: android.widget.LinearLayout
    private lateinit var searchTop: android.widget.FrameLayout
    private lateinit var profile: android.widget.FrameLayout
    private lateinit var searchBottom: android.widget.FrameLayout
    private lateinit var statusIndicatorContainer: android.widget.FrameLayout
    private lateinit var tvStatusIndicator: android.widget.TextView
    private var selectedIndex = 0
    private var isFullscreen = false
    private var currentFocusedView: View? = null
    private var lastBackPressTime = 0L
    private val BACK_PRESS_INTERVAL = 4000L // 4 segundos para doble clic
    private val originalLeftPanelWidth = 200 // Ancho original en dp
    private val reducedLeftPanelWidth = 160 // Ancho reducido en dp
    private var isLeftPanelReduced = false
    
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
        topBar = findViewById(R.id.topBar)
        searchTop = findViewById(R.id.flSearchTop)
        profile = findViewById(R.id.flProfile)
        searchBottom = findViewById(R.id.flSearchBottom)
        playerView = findViewById(R.id.playerView)
        statusIndicatorContainer = findViewById(R.id.statusIndicatorContainer)
        tvStatusIndicator = findViewById(R.id.tvStatusIndicator)
        
        // Inicializar ViewModel
        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        
        // Inicializar ExoPlayer con configuración optimizada para LIVE streaming
        initializeExoPlayer()
        
        // Observar ViewModel
        observeViewModel()
        
        // Cargar canales
        android.util.Log.d("MainActivity", "Starting to load channels...")
        viewModel.loadChannels()
        
        // Configurar listeners de foco para todos los elementos navegables
        setupFocusListeners()
        
        // Configurar manejo del botón atrás usando callbacks (recomendado por Android)
        setupBackPressHandler()
        
        // Configurar "TV" como seleccionado por defecto
        updateSelection(0)
        
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
                view.post {
                    if (!mainContentArea.isSelected) {
                        mainContentArea.isSelected = true
                        clearOtherSelections(view)
                    }
                    currentFocusedView = view
                }
            } else if (!hasFocus) {
                view.post {
                    mainContentArea.isSelected = false
                }
            }
        }
        
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
                val currentFocus = window.currentFocus ?: currentFocusedView ?: return super.onKeyDown(keyCode, event)
                handleCenterButton(currentFocus)
                return true
            }
            KeyEvent.KEYCODE_HOME -> {
                // Salir de la app completamente con botón HOME
                finishAffinity()
                return true
            }
            // Las flechas (UP, DOWN, LEFT, RIGHT) se manejan automáticamente por Android
            // usando los atributos nextFocusUp, nextFocusDown, etc. del XML
        }
        return super.onKeyDown(keyCode, event)
    }
    
    // La navegación con las flechas ahora se maneja automáticamente por Android
    // usando los atributos nextFocusUp, nextFocusDown, nextFocusLeft, nextFocusRight del XML
    
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
            mainContentArea -> {
                // Entrar en fullscreen
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
                // Cualquier otro elemento - no hacer nada
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
        
        // Restaurar el tamaño del reproductor si fue modificado
        val params = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        val marginStart = (100 * resources.displayMetrics.density).toInt()
        val marginEnd = (140 * resources.displayMetrics.density).toInt()
        params.setMargins(marginStart, 0, marginEnd, 0)
        mainContentArea.layoutParams = params
        
        // Enfocar el reproductor para mostrar que está activo
        mainContentArea.requestFocus()
    }
    
    private fun enterFullscreen() {
        isFullscreen = true
        // Restaurar ancho del panel antes de ocultarlo
        if (isLeftPanelReduced) {
            isLeftPanelReduced = false
        }
        // Ocultar todos los elementos excepto el reproductor (usar View.GONE para mejor rendimiento)
        leftNavPanel.visibility = View.GONE
        rightSidebar.visibility = View.GONE
        topBar.visibility = View.GONE
        searchBottom.visibility = View.GONE
        
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
        // Restaurar ancho del panel si estaba reducido
        if (isLeftPanelReduced) {
            restoreLeftPanelWidth()
        }
        // Mostrar todos los elementos
        leftNavPanel.visibility = View.VISIBLE
        rightSidebar.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        searchBottom.visibility = View.VISIBLE
        
        // Restaurar el tamaño y posición del reproductor (cachear cálculos)
        val params = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        val marginStart = (100 * resources.displayMetrics.density).toInt()
        val marginEnd = (140 * resources.displayMetrics.density).toInt()
        
        // Solo actualizar si es necesario
        if (params.leftMargin != marginStart || params.rightMargin != marginEnd) {
            params.setMargins(marginStart, 0, marginEnd, 0)
            mainContentArea.layoutParams = params
        }
        
        // Restaurar el foco al item seleccionado del panel
        navItems[selectedIndex].post {
            navItems[selectedIndex].requestFocus()
        }
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
     * Buffer normal para fluidez sin retrasos excesivos
     */
    private fun initializeExoPlayer() {
        // LoadControl optimizado para LIVE streaming con buffer normal
        // Valores balanceados para fluidez y estabilidad
        val loadControl: LoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000,  // minBufferMs: Buffer mínimo de 15 segundos (normal para estabilidad)
                30000,  // maxBufferMs: Buffer máximo de 30 segundos (suficiente para fluidez)
                2500,   // bufferForPlaybackMs: Buffer antes de reproducir (2.5 segundos)
                5000    // bufferForPlaybackAfterRebufferMs: Buffer después de rebuffer (5 segundos)
            )
            .setTargetBufferBytes(C.LENGTH_UNSET) // Sin límite de bytes
            .setPrioritizeTimeOverSizeThresholds(true) // Priorizar tiempo sobre tamaño
            .setBackBuffer(0, false) // Sin buffer hacia atrás (streaming en vivo no lo necesita)
            .build()
        
        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .also { player ->
                // Configurar PlayerView
                playerView.player = player
                playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
                playerView.useController = false // Desactivar controles (no mostrar botones)
                
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
                        // Error en reproducción, intentar refresh
                        android.util.Log.e("MainActivity", "ExoPlayer error: ${error.message}", error)
                        android.util.Log.e("MainActivity", "Error code: ${error.errorCode}, cause: ${error.cause?.message}")
                        handlePlaybackError()
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
                        }
                        is PlayerViewModel.LoadingState.Success -> {
                            showFinalMessage()
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
            while (isMonitoring) {
                delay(2000) // Verificar cada 2 segundos
                val player = exoPlayer
                if (player != null && player.playbackState == Player.STATE_READY && player.isPlaying) {
                    val currentPosition = player.currentPosition
                    // Si la posición no cambia durante 5 segundos, está congelado
                    if (currentPosition == lastPlaybackPosition && lastPlaybackPosition > 0) {
                        // Reproducción congelada, intentar refresh
                        handlePlaybackStuck()
                        isMonitoring = false
                        return@launch
                    }
                    lastPlaybackPosition = currentPosition
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
     * Refresca el canal actual (libera y recarga)
     */
    private fun refreshChannel() {
        currentChannelUrl?.let { url ->
            lifecycleScope.launch {
                exoPlayer?.let { player ->
                    // Liberar MediaItem actual
                    player.stop()
                    player.clearMediaItems()
                    
                    // Pequeña pausa antes de recargar
                    delay(500)
                    
                    // Volver a cargar el mismo stream
                    val mediaItem = MediaItem.fromUri(url)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.playWhenReady = true
                    
                    // Resetear monitoreo
                    bufferingStartTime = 0
                    lastPlaybackPosition = 0
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
}

