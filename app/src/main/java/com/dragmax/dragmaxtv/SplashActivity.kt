package com.dragmax.dragmaxtv

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore

class SplashActivity : AppCompatActivity() {
    private lateinit var tvDragmax: GradientStrokeTextView
    private lateinit var tvVersion: GradientStrokeTextView
    private lateinit var ivGifBackground: ImageView
    private lateinit var progressCircle: android.widget.ProgressBar
    private val fullText = "Dragmax Tv"
    private var currentIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private val firestore = FirebaseFirestore.getInstance()
    
    // Tiempos en milisegundos
    private val TYPEWRITER_DURATION = 4000L // 4 segundos para la animación de máquina de escribir
    private val PULSE_DURATION = 1000L // 1 segundo para el pulso
    private val TOTAL_SPLASH_DURATION = 10000L // 10 segundos totales
    
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
        
        setContentView(R.layout.activity_splash)
        
        tvDragmax = findViewById(R.id.tvDragmax)
        tvVersion = findViewById(R.id.tvVersion)
        ivGifBackground = findViewById(R.id.ivGifBackground)
        progressCircle = findViewById(R.id.progressCircle)
        
        // Cargar GIF desde Firestore (no bloquea el splash)
        loadGifFromFirestore()
        
        // Obtener y mostrar la versión automáticamente
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }
        tvVersion.text = "v$versionName"
        
        // Posicionar la versión de forma independiente, bastante más abajo
        tvDragmax.post {
            tvDragmax.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    tvDragmax.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    
                    // Obtener dimensiones de la pantalla
                    val screenHeight = resources.displayMetrics.heightPixels
                    val screenWidth = resources.displayMetrics.widthPixels
                    
                    // El texto principal está centrado, obtener su posición
                    val dragmaxLeft = tvDragmax.left
                    val dragmaxWidth = tvDragmax.width
                    val dragmaxCenterX = dragmaxLeft + (dragmaxWidth / 2)
                    
                    // Calcular posición de la versión de forma independiente
                    val versionParams = tvVersion.layoutParams as android.widget.FrameLayout.LayoutParams
                    
                    // Posicionar la versión un poco más abajo del texto principal
                    val topMarginPercent = 0.60f
                    val targetY = screenHeight * topMarginPercent
                    
                    // Primero centrar horizontalmente
                    tvVersion.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(screenWidth, android.view.View.MeasureSpec.AT_MOST),
                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                    )
                    val versionWidth = tvVersion.measuredWidth
                    
                    // Calcular el centro horizontal de la versión (centro de la pantalla)
                    val versionCenterX = screenWidth / 2
                    
                    // Configurar layout params
                    versionParams.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    versionParams.topMargin = targetY.toInt()
                    versionParams.leftMargin = 0
                    versionParams.rightMargin = 0
                    
                    tvVersion.layoutParams = versionParams
                    
                    // Posicionar el círculo de progreso debajo de la versión usando la posición calculada
                    positionProgressCircle(targetY.toInt(), versionCenterX)
                    
                    // Forzar el layout
                    tvVersion.post {
                        tvVersion.translationY = 0f // Asegurar que no hay translation
                        tvVersion.requestLayout()
                        tvVersion.invalidate()
                    }
                }
            })
        }
        
        // Configurar los gradientes (fill y stroke) para ambos textos
        setupTextGradients()
        
        // Mostrar el círculo de progreso desde el inicio
        progressCircle.visibility = View.VISIBLE
        
        // Iniciar animación de máquina de escribir (dura 4 segundos)
        startTypewriterAnimation()
        
        // Mantener las barras ocultas cuando se recibe foco
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                hideSystemBars()
            }
        }
        
        // Navegar a MainActivity después de 8 segundos
        handler.postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }, TOTAL_SPLASH_DURATION)
    }
    
    private fun setupTextGradients() {
        // Configurar gradiente de relleno (amarillo -> anaranjado -> rojo) de izquierda a derecha
        val fillColors = intArrayOf(
            android.graphics.Color.parseColor("#FFD700"), // Amarillo
            android.graphics.Color.parseColor("#FF6600"), // Anaranjado
            android.graphics.Color.parseColor("#FF0000")  // Rojo
        )
        tvDragmax.setFillGradient(fillColors)
        tvVersion.setFillGradient(fillColors)
        
        // Configurar gradiente de borde (azul oscuro -> azul claro)
        val strokeColors = intArrayOf(
            android.graphics.Color.parseColor("#000080"), // Azul oscuro
            android.graphics.Color.parseColor("#4169E1")  // Azul claro
        )
        tvDragmax.setStrokeGradient(strokeColors, 8f) // Borde de 8px
        tvVersion.setStrokeGradient(strokeColors, 4f) // Borde de 4px para la versión (más pequeño)
    }
    
    private fun startTypewriterAnimation() {
        currentIndex = 0
        tvDragmax.text = ""
        
        // Calcular velocidad para que dure exactamente 4 segundos
        val delayPerLetter = TYPEWRITER_DURATION / fullText.length
        
        val runnable = object : Runnable {
            override fun run() {
                if (currentIndex < fullText.length) {
                    val textToShow = fullText.substring(0, currentIndex + 1)
                    tvDragmax.text = textToShow
                    currentIndex++
                    handler.postDelayed(this, delayPerLetter)
                } else {
                    // Cuando termine la animación de máquina de escribir, iniciar el pulso
                    startPulseAnimation()
                }
            }
        }
        handler.postDelayed(runnable, 300) // Iniciar después de 300ms
    }
    
    private fun startPulseAnimation() {
        // Asegurar que el texto completo esté visible
        tvDragmax.text = fullText
        
        // Mostrar la versión durante la palpitación
        tvVersion.visibility = View.VISIBLE
        
        // El círculo de progreso ya está visible desde el inicio
        
        // Crear animación de pulso (escalar hacia arriba y abajo) para ambos textos
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.15f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.15f, 1.0f)
        
        val pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(tvDragmax, scaleX, scaleY)
        pulseAnimator.duration = PULSE_DURATION
        pulseAnimator.interpolator = AccelerateDecelerateInterpolator()
        pulseAnimator.repeatCount = 2 // 2 repeticiones = 3 pulsos totales (1 segundo aprox)
        pulseAnimator.start()
        
        // Aplicar la misma animación a la versión
        val pulseAnimatorVersion = ObjectAnimator.ofPropertyValuesHolder(tvVersion, scaleX, scaleY)
        pulseAnimatorVersion.duration = PULSE_DURATION
        pulseAnimatorVersion.interpolator = AccelerateDecelerateInterpolator()
        pulseAnimatorVersion.repeatCount = 2
        pulseAnimatorVersion.start()
    }
    
    /**
     * Posiciona el círculo de progreso debajo de la versión con mínimo espacio
     */
    private fun positionProgressCircle(versionTopMargin: Int, versionCenterX: Int) {
        // Obtener altura de la versión
        tvVersion.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val versionHeight = tvVersion.measuredHeight
        
        // Convertir 16dp a píxeles para el espacio mínimo entre versión y progreso
        val spaceDp = 16
        val spacePx = (spaceDp * resources.displayMetrics.density).toInt()
        
        val progressParams = progressCircle.layoutParams as android.widget.FrameLayout.LayoutParams
        // Posicionar debajo de la versión: topMargin de versión + altura de versión + espacio
        progressParams.topMargin = versionTopMargin + versionHeight + spacePx
        progressParams.gravity = android.view.Gravity.TOP
        
        // Centrar horizontalmente con respecto a la versión
        progressCircle.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val progressWidth = progressCircle.measuredWidth
        progressParams.leftMargin = versionCenterX - (progressWidth / 2)
        
        progressCircle.layoutParams = progressParams
        progressCircle.requestLayout()
        progressCircle.invalidate()
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
     * Carga el GIF desde Firestore de forma asíncrona sin bloquear el splash
     */
    private fun loadGifFromFirestore() {
        // Cargar desde Firestore: UI_CONFIG / splashlluviastrellas / campo "gif"
        firestore.collection("UI_CONFIG")
            .document("splashlluviastrellas")
            .get()
            .addOnSuccessListener { document: DocumentSnapshot? ->
                if (document != null && document.exists()) {
                    val gifUrl = document.getString("gif")
                    if (!gifUrl.isNullOrBlank()) {
                        // Cargar el GIF usando Glide
                        loadGifWithGlide(gifUrl)
                    } else {
                        // Si no hay URL, usar fondo degradado de respaldo
                        Log.w("SplashActivity", "GIF URL vacía en Firestore")
                        useFallbackBackground()
                    }
                } else {
                    // Documento no existe, usar fondo degradado de respaldo
                    Log.w("SplashActivity", "Documento no encontrado en Firestore")
                    useFallbackBackground()
                }
            }
            .addOnFailureListener { exception: Exception ->
                // Error al cargar desde Firestore, usar fondo degradado de respaldo
                Log.e("SplashActivity", "Error al cargar GIF desde Firestore", exception)
                useFallbackBackground()
            }
    }
    
    /**
     * Carga el GIF usando Glide con soporte para animación en bucle
     */
    private fun loadGifWithGlide(gifUrl: String) {
        // Mostrar el ImageView inmediatamente para que aparezca tan pronto como se cargue
        ivGifBackground.visibility = View.VISIBLE
        
        Glide.with(this)
            .asGif()
            .load(gifUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL) // Cachear el GIF
            .centerCrop() // Ajustar al tamaño de la pantalla
            .into(ivGifBackground)
    }
    
    /**
     * Usa el fondo degradado como respaldo si el GIF no se puede cargar
     */
    private fun useFallbackBackground() {
        // Ocultar el ImageView y usar el fondo degradado del FrameLayout
        ivGifBackground.visibility = View.GONE
        // El fondo degradado ya está configurado en el layout (splash_background)
    }
}

