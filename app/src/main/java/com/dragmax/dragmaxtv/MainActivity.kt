package com.dragmax.dragmaxtv

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {
    
    private lateinit var navItems: List<TextView>
    private lateinit var mainContentArea: android.widget.FrameLayout
    private lateinit var leftNavPanel: android.widget.LinearLayout
    private lateinit var rightSidebar: android.widget.LinearLayout
    private lateinit var topBar: android.widget.LinearLayout
    private lateinit var searchTop: android.widget.FrameLayout
    private lateinit var profile: android.widget.FrameLayout
    private lateinit var searchBottom: android.widget.FrameLayout
    private var selectedIndex = 0
    private var isFullscreen = false
    private var currentFocusedView: View? = null
    private var lastBackPressTime = 0L
    private val BACK_PRESS_INTERVAL = 4000L // 4 segundos para doble clic
    
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
        // Listeners para items del panel izquierdo
        navItems.forEachIndexed { index, textView ->
            textView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !isFullscreen) {
                    updateSelection(index)
                    clearOtherSelections(textView)
                    currentFocusedView = textView
                }
            }
        }
        
        // Listener para el reproductor
        mainContentArea.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isFullscreen) {
                mainContentArea.isSelected = true
                clearOtherSelections(mainContentArea)
                currentFocusedView = mainContentArea
            }
        }
        
        // Listeners para iconos
        searchTop.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isFullscreen) {
                searchTop.isSelected = true
                clearOtherSelections(searchTop)
                currentFocusedView = searchTop
            }
        }
        
        profile.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isFullscreen) {
                profile.isSelected = true
                clearOtherSelections(profile)
                currentFocusedView = profile
            }
        }
        
        rightSidebar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isFullscreen) {
                rightSidebar.isSelected = true
                clearOtherSelections(rightSidebar)
                currentFocusedView = rightSidebar
            }
        }
        
        searchBottom.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isFullscreen) {
                searchBottom.isSelected = true
                clearOtherSelections(searchBottom)
                currentFocusedView = searchBottom
            }
        }
    }
    
    private fun clearOtherSelections(selectedView: View) {
        navItems.forEach { it.isSelected = false }
        mainContentArea.isSelected = false
        searchTop.isSelected = false
        profile.isSelected = false
        rightSidebar.isSelected = false
        searchBottom.isSelected = false
        selectedView.isSelected = true
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
                // Obtener el elemento que tiene el foco actualmente
                val currentFocus = window.currentFocus ?: return super.onKeyDown(keyCode, event)
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
        // Ocultar todos los elementos excepto el reproductor
        leftNavPanel.visibility = View.GONE
        rightSidebar.visibility = View.GONE
        topBar.visibility = View.GONE
        searchBottom.visibility = View.GONE
        
        // Expandir el reproductor a pantalla completa
        val params = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        params.setMargins(0, 0, 0, 0)
        mainContentArea.layoutParams = params
        mainContentArea.requestFocus()
    }
    
    private fun exitFullscreen() {
        isFullscreen = false
        // Mostrar todos los elementos
        leftNavPanel.visibility = View.VISIBLE
        rightSidebar.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        searchBottom.visibility = View.VISIBLE
        
        // Restaurar el tamaño y posición del reproductor
        val params = mainContentArea.layoutParams as android.widget.FrameLayout.LayoutParams
        val marginStart = (100 * resources.displayMetrics.density).toInt()
        val marginEnd = (140 * resources.displayMetrics.density).toInt()
        params.setMargins(marginStart, 0, marginEnd, 0)
        mainContentArea.layoutParams = params
        
        // Restaurar el foco al item seleccionado del panel
        navItems[selectedIndex].requestFocus()
    }
    
    private fun updateSelection(index: Int) {
        selectedIndex = index
        navItems.forEachIndexed { i, textView ->
            textView.isSelected = (i == index)
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
}

