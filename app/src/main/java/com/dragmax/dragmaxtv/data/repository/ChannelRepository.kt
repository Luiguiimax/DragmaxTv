package com.dragmax.dragmaxtv.data.repository

import android.util.Log
import com.dragmax.dragmaxtv.data.dao.LiveChannelDao
import com.dragmax.dragmaxtv.data.dao.ListUpdateTimestampDao
import com.dragmax.dragmaxtv.data.dao.M3UUrlDao
import com.dragmax.dragmaxtv.data.entity.LiveChannel
import com.dragmax.dragmaxtv.data.entity.ListUpdateTimestamp
import com.dragmax.dragmaxtv.data.entity.M3UUrl
import com.dragmax.dragmaxtv.data.parser.M3UParser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ChannelRepository(
    private val m3uUrlDao: M3UUrlDao,
    private val liveChannelDao: LiveChannelDao,
    private val listUpdateTimestampDao: ListUpdateTimestampDao
) {
    // Inicializar Firestore
    // Estructura: Colección "UI_CONFIG" → Documento "lista_de_canales"
    // Campos: urlm3u1, urlm3u2, urlm3u3, urlm3u4, fecha_list
    private val firestore = FirebaseFirestore.getInstance()
    
    /**
     * FASE 1 - LECTURA: Lee UNA sola URL M3U desde Firebase Database
     * No lee la siguiente hasta terminar todo el proceso de la actual
     * Verifica si la URL cambió comparándola con la caché en Room
     */
    suspend fun loadSingleM3UUrlFromFirebase(fieldName: String): M3UUrl? = withContext(Dispatchers.IO) {
        try {
            val urlFromFirebase = getUrlFromFirebase(fieldName)
            if (urlFromFirebase == null || urlFromFirebase.isBlank()) {
                return@withContext null
            }
            
            // Verificar si esta URL ya existe en Room
            val existingUrl = m3uUrlDao.getUrlByFieldName(fieldName)
            
            if (existingUrl != null) {
                // Si la URL no cambió, usar datos cacheados (NO descargar)
                if (existingUrl.url == urlFromFirebase) {
                    // URL no cambió, retornar la existente con sus datos cacheados
                    return@withContext existingUrl
                }
                // Si la URL cambió, retornar nueva URL para descargar
            }
            
            // URL nueva o cambió, retornar para descargar
            M3UUrl(
                url = urlFromFirebase,
                fieldName = fieldName
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Verifica si una URL M3U ya está descargada y actualizada en Room
     */
    suspend fun isM3UUrlCached(fieldName: String): Boolean = withContext(Dispatchers.IO) {
        val existingUrl = m3uUrlDao.getUrlByFieldName(fieldName)
        existingUrl != null && existingUrl.content != null && existingUrl.content.isNotBlank()
    }
    
    /**
     * Obtiene la lista de nombres de campos M3U a procesar
     */
    fun getM3UFieldNames(): List<String> = listOf("urlm3u1", "urlm3u2", "urlm3u3", "urlm3u4")
    
    /**
     * Lee el valor de fecha_list desde Firestore
     * Ruta: UI_CONFIG / lista_de_canales / fecha_list
     */
    suspend fun getFechaListFromFirebase(): Long? = withContext(Dispatchers.IO) {
        try {
            suspendCancellableCoroutine { continuation ->
                // Timeout para evitar que se quede esperando indefinidamente
                val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (!continuation.isCompleted) {
                        Log.e("ChannelRepository", "Timeout reading fecha_list from Firestore (10 seconds)")
                        continuation.resume(null) // Retornar null para usar caché
                    }
                }
                timeoutHandler.postDelayed(timeoutRunnable, 10000) // 10 segundos timeout
                
                firestore.collection("UI_CONFIG")
                    .document("lista_de_canales")
                    .get()
                    .addOnSuccessListener { document: DocumentSnapshot? ->
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        if (document != null && document.exists()) {
                            // fecha_list puede ser Long o Number
                            val timestamp = when {
                                document.get("fecha_list") is Long -> document.getLong("fecha_list")
                                document.get("fecha_list") is Number -> (document.get("fecha_list") as Number).toLong()
                                else -> null
                            }
                            if (timestamp != null) {
                                Log.d("ChannelRepository", "Firestore fecha_list received: $timestamp")
                                continuation.resume(timestamp)
                            } else {
                                Log.w("ChannelRepository", "Firestore fecha_list is null or invalid type")
                                continuation.resume(null)
                            }
                        } else {
                            Log.w("ChannelRepository", "Firestore document 'lista_de_canales' not found")
                            continuation.resume(null)
                        }
                    }
                    .addOnFailureListener { exception: Exception ->
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        Log.e("ChannelRepository", "Firestore error reading fecha_list: ${exception.message}", exception)
                        continuation.resume(null) // Retornar null para usar caché
                    }
                
                continuation.invokeOnCancellation {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                }
            }
        } catch (e: Exception) {
            Log.e("ChannelRepository", "Exception getting fecha_list from Firestore: ${e.message}", e)
            null
        }
    }
    
    /**
     * Obtiene la última fecha guardada en Room
     */
    suspend fun getStoredFechaList(): Long? = withContext(Dispatchers.IO) {
        listUpdateTimestampDao.getTimestamp()?.fechaList
    }
    
    /**
     * Guarda el nuevo valor de fecha_list en Room después de una actualización exitosa
     */
    suspend fun saveFechaList(fechaList: Long) = withContext(Dispatchers.IO) {
        val timestamp = ListUpdateTimestamp(
            id = 1,
            fechaList = fechaList,
            lastUpdated = System.currentTimeMillis()
        )
        listUpdateTimestampDao.insertOrUpdateTimestamp(timestamp)
    }
    
    /**
     * Verifica si la lista necesita actualización comparando fecha_list de Firebase con Room
     * Retorna true si necesita actualización, false si está actualizada
     */
    suspend fun needsUpdate(): Boolean = withContext(Dispatchers.IO) {
        val fechaListFromFirebase = getFechaListFromFirebase()
        val fechaListFromRoom = getStoredFechaList()
        
        // Si no hay fecha en Firebase, no actualizar
        if (fechaListFromFirebase == null) {
            return@withContext false
        }
        
        // Si no hay fecha en Room, necesita actualización
        if (fechaListFromRoom == null) {
            return@withContext true
        }
        
        // Si las fechas son diferentes, necesita actualización
        fechaListFromFirebase != fechaListFromRoom
    }
    
    /**
     * Obtiene una URL específica desde Firestore
     * Ruta: UI_CONFIG / lista_de_canales / campo (urlm3u1, urlm3u2, etc.)
     */
    private suspend fun getUrlFromFirebase(fieldName: String): String? = suspendCancellableCoroutine { continuation ->
        try {
            Log.d("ChannelRepository", "Attempting to read from Firestore: UI_CONFIG/lista_de_canales/$fieldName")
            
            // Timeout para evitar que se quede esperando indefinidamente
            val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (!continuation.isCompleted) {
                    Log.e("ChannelRepository", "Timeout reading from Firestore for $fieldName (10 seconds)")
                    continuation.resume(null) // Retornar null para usar caché
                }
            }
            timeoutHandler.postDelayed(timeoutRunnable, 10000) // 10 segundos timeout
            
            firestore.collection("UI_CONFIG")
                .document("lista_de_canales")
                .get()
                .addOnSuccessListener { document: DocumentSnapshot? ->
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    if (document != null && document.exists()) {
                        val url = document.getString(fieldName)
                        if (url != null && url.isNotBlank()) {
                            Log.d("ChannelRepository", "Firestore data received for $fieldName: ${url.take(50)}...")
                            continuation.resume(url)
                        } else {
                            Log.w("ChannelRepository", "Firestore returned null or empty URL for $fieldName")
                            continuation.resume(null)
                        }
                    } else {
                        Log.w("ChannelRepository", "Firestore document 'lista_de_canales' not found")
                        continuation.resume(null)
                    }
                }
                .addOnFailureListener { exception: Exception ->
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    Log.e("ChannelRepository", "Firestore error for $fieldName: ${exception.message}", exception)
                    continuation.resume(null) // Retornar null para usar caché
                }
            
            continuation.invokeOnCancellation {
                timeoutHandler.removeCallbacks(timeoutRunnable)
            }
        } catch (e: Exception) {
            Log.e("ChannelRepository", "Exception getting URL from Firestore: ${e.message}", e)
            continuation.resume(null)
        }
    }
    
    /**
     * FASE 2 - DESCARGA Y FILTRO: Descarga la lista M3U, la parsea y filtra SOLO canales en vivo
     * Procesa línea por línea para evitar OutOfMemoryError con archivos grandes
     * IMPORTANTE: Solo descarga si la URL no está en caché o si cambió
     * Conserva el ID existente si la URL ya está en Room para no borrar canales de otras fuentes
     */
    suspend fun downloadAndFilterM3U(m3uUrl: M3UUrl): M3UUrl = withContext(Dispatchers.IO) {
        try {
            // Verificar si ya existe en Room con contenido
            val existingUrl = m3uUrlDao.getUrlByFieldName(m3uUrl.fieldName)
            
            // Si ya existe y tiene contenido, y la URL no cambió, retornar directamente (NO descargar)
            if (existingUrl != null && existingUrl.content != null && existingUrl.content.isNotBlank() && existingUrl.url == m3uUrl.url) {
                return@withContext existingUrl
            }
            
            // Descargar y procesar M3U línea por línea (evita OutOfMemoryError)
            // NO guardamos el contenido completo, solo procesamos y guardamos canales en vivo
            val finalM3uUrl = downloadAndProcessM3UStreaming(m3uUrl, existingUrl)
            
            finalM3uUrl
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
    
    /**
     * Descarga y procesa M3U línea por línea para evitar OutOfMemoryError
     * Solo guarda los canales en vivo, no el contenido completo del M3U
     */
    private suspend fun downloadAndProcessM3UStreaming(m3uUrl: M3UUrl, existingUrl: M3UUrl?): M3UUrl = withContext(Dispatchers.IO) {
        val urlString = m3uUrl.url
        Log.d("ChannelRepository", "Downloading and processing M3U streaming from: $urlString")
        
        try {
            val url = URL(urlString)
            
            // Verificar que sea HTTPS o HTTP
            if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
                throw IllegalArgumentException("URL must start with http:// or https://")
            }
            
            // Configurar conexión con timeouts
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.apply {
                connectTimeout = 20000 // 20 segundos
                readTimeout = 120000 // 120 segundos (archivos grandes pueden tardar)
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Connection", "keep-alive")
            }
            
            // Verificar código de respuesta
            val responseCode = connection.responseCode
            Log.d("ChannelRepository", "HTTP Response Code: $responseCode for URL: ${urlString.take(80)}...")
            
            if (responseCode !in 200..299) {
                val errorMessage = connection.responseMessage
                throw java.io.IOException("HTTP Error: $responseCode - $errorMessage")
            }
            
            // Obtener o crear el ID de la fuente M3U ANTES de procesar
            val finalM3uUrl = if (existingUrl != null) {
                // Usar el ID existente
                existingUrl.copy(
                    url = m3uUrl.url,
                    downloadedAt = System.currentTimeMillis()
                )
            } else {
                // Insertar nueva URL (sin contenido) para obtener el ID
                val tempUrl = m3uUrl.copy(content = "#EXTM3U\n") // Contenido mínimo
                val newId = m3uUrlDao.insertUrl(tempUrl)
                tempUrl.copy(id = newId)
            }
            
            // Asegurar que tenemos un ID válido
            if (finalM3uUrl.id == 0L) {
                throw IllegalStateException("M3U URL ID is invalid: ${finalM3uUrl.id}")
            }
            
            // Procesar línea por línea y filtrar canales en vivo
            var lineCount = 0
            var liveChannelsCount = 0
            var currentName: String? = null
            var currentGroup: String? = null
            var currentLogo: String? = null
            val liveChannels = mutableListOf<LiveChannel>()
            
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().forEach { line ->
                    lineCount++
                    val trimmedLine = line.trim()
                    
                    // Detectar inicio de entrada (#EXTINF)
                    if (trimmedLine.startsWith("#EXTINF:")) {
                        val extinfData = M3UParser.parseExtInf(trimmedLine)
                        currentName = extinfData.name
                        currentGroup = extinfData.group
                        currentLogo = extinfData.logo
                    }
                    // Detectar URL (no empieza con #)
                    else if (!trimmedLine.startsWith("#") && trimmedLine.isNotEmpty() && currentName != null) {
                        val currentUrl = trimmedLine
                        
                        // Capturar valores en variables locales para evitar problemas de smart cast
                        val name = currentName
                        val group = currentGroup
                        val logo = currentLogo
                        
                        // Verificar si es un canal en vivo válido
                        if (name != null && M3UParser.isLiveChannel(name, group, currentUrl)) {
                            liveChannels.add(
                                LiveChannel(
                                    name = name,
                                    url = currentUrl,
                                    group = group,
                                    logo = logo,
                                    m3uSourceId = finalM3uUrl.id
                                )
                            )
                            liveChannelsCount++
                        }
                        
                        // Resetear para la siguiente entrada
                        currentName = null
                        currentGroup = null
                        currentLogo = null
                    }
                    
                    // Log de progreso cada 10000 líneas
                    if (lineCount % 10000 == 0) {
                        Log.d("ChannelRepository", "Processed $lineCount lines, found $liveChannelsCount live channels so far...")
                    }
                }
            }
            
            Log.d("ChannelRepository", "M3U processed successfully: $lineCount total lines, $liveChannelsCount live channels found")
            
            // Guardar canales en vivo directamente en Room (sin guardar el M3U completo)
            if (liveChannels.isNotEmpty()) {
                // Eliminar canales antiguos de esta fuente
                liveChannelDao.deleteChannelsBySource(finalM3uUrl.id)
                // Insertar nuevos canales
                liveChannelDao.insertChannels(liveChannels)
                Log.d("ChannelRepository", "Saved ${liveChannels.size} live channels to Room")
            }
            
            // Actualizar la URL en Room (sin contenido completo para ahorrar memoria)
            val updatedM3uUrl = finalM3uUrl.copy(
                content = "#EXTM3U\n", // Solo guardamos un marcador, no el contenido completo
                downloadedAt = System.currentTimeMillis()
            )
            
            if (existingUrl != null) {
                m3uUrlDao.updateUrl(updatedM3uUrl)
            } else {
                m3uUrlDao.updateUrl(updatedM3uUrl)
            }
            
            updatedM3uUrl
        } catch (e: java.net.SocketTimeoutException) {
            Log.e("ChannelRepository", "Timeout downloading M3U from $urlString: ${e.message}")
            throw java.io.IOException("Timeout downloading M3U: ${e.message}", e)
        } catch (e: java.net.UnknownHostException) {
            Log.e("ChannelRepository", "Unknown host for M3U URL $urlString: ${e.message}")
            throw java.io.IOException("Cannot resolve host: ${e.message}", e)
        } catch (e: OutOfMemoryError) {
            Log.e("ChannelRepository", "OutOfMemoryError while processing M3U: ${e.message}")
            throw java.io.IOException("M3U file too large to process: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("ChannelRepository", "Error downloading/processing M3U from $urlString: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Descarga el contenido de una URL M3U de forma controlada
     * DEPRECATED: Usar downloadAndProcessM3UStreaming para archivos grandes
     * Solo usar para archivos pequeños (< 10MB)
     */
    @Deprecated("Use downloadAndProcessM3UStreaming for large files to avoid OutOfMemoryError")
    private suspend fun downloadM3UContent(urlString: String): String = withContext(Dispatchers.IO) {
        Log.d("ChannelRepository", "Downloading M3U from: $urlString")
        
        try {
            val url = URL(urlString)
            
            // Verificar que sea HTTPS o HTTP
            if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
                throw IllegalArgumentException("URL must start with http:// or https://")
            }
            
            // Configurar conexión con timeouts y seguir redirecciones
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.apply {
                connectTimeout = 20000 // 20 segundos
                readTimeout = 60000 // 60 segundos (M3U pueden ser grandes)
                instanceFollowRedirects = true // Seguir redirecciones
                requestMethod = "GET"
                // Headers para compatibilidad con servidores M3U
                setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Connection", "keep-alive")
            }
            
            // Verificar código de respuesta
            val responseCode = connection.responseCode
            Log.d("ChannelRepository", "HTTP Response Code: $responseCode for URL: ${urlString.take(80)}...")
            
            if (responseCode !in 200..299) {
                val errorMessage = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: connection.responseMessage
                } catch (e: Exception) {
                    connection.responseMessage
                }
                throw java.io.IOException("HTTP Error: $responseCode - $errorMessage")
            }
            
            // Leer contenido con buffer controlado
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val content = reader.readText()
                val lineCount = content.lines().size
                Log.d("ChannelRepository", "M3U downloaded successfully, size: ${content.length} bytes, lines: $lineCount")
                
                if (content.isBlank()) {
                    throw java.io.IOException("M3U content is empty")
                }
                
                if (!content.contains("#EXTM3U") && !content.contains("#EXTINF")) {
                    Log.w("ChannelRepository", "M3U content may not be valid (missing #EXTM3U or #EXTINF)")
                }
                
                content
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e("ChannelRepository", "Timeout downloading M3U from $urlString: ${e.message}")
            throw java.io.IOException("Timeout downloading M3U: ${e.message}", e)
        } catch (e: java.net.UnknownHostException) {
            Log.e("ChannelRepository", "Unknown host for M3U URL $urlString: ${e.message}")
            throw java.io.IOException("Cannot resolve host: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("ChannelRepository", "Error downloading M3U from $urlString: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * FASE 3 - CACHE EN ROOM: Retorna los canales en vivo ya guardados en Room
     * NOTA: Con el nuevo sistema de streaming, los canales ya se guardan durante la descarga
     * Este método ahora solo retorna los canales que ya están en Room para esta fuente
     */
    suspend fun processAndCacheLiveChannels(m3uUrl: M3UUrl): List<LiveChannel> = withContext(Dispatchers.IO) {
        // Asegurar que tenemos el ID correcto
        val finalM3uUrl = if (m3uUrl.id == 0L) {
            m3uUrlDao.getUrlByFieldName(m3uUrl.fieldName) ?: return@withContext emptyList()
        } else {
            m3uUrl
        }
        
        // Los canales ya fueron guardados durante downloadAndProcessM3UStreaming
        // Simplemente retornar los canales que ya están en Room para esta fuente
        val liveChannels = liveChannelDao.getChannelsBySource(finalM3uUrl.id)
        Log.d("ChannelRepository", "Retrieved ${liveChannels.size} live channels from Room for source ${finalM3uUrl.id}")
        liveChannels
    }
    
    /**
     * Busca "Caracol FHD" en el grupo "COLOMBIA" desde Room
     * Si no existe, devuelve el primer canal del grupo "COLOMBIA"
     */
    suspend fun getCaracolFHDOrFirstColombia(): LiveChannel? = withContext(Dispatchers.IO) {
        // Primero intentar encontrar "Caracol FHD" en "COLOMBIA"
        val caracolFHD = liveChannelDao.getChannelByGroupAndName("COLOMBIA", "Caracol FHD")
        if (caracolFHD != null) {
            return@withContext caracolFHD
        }
        
        // Si no existe, obtener el primer canal de "COLOMBIA"
        liveChannelDao.getFirstChannelByGroup("COLOMBIA")
    }
    
    /**
     * FASE 4 - CARGA: Lee los canales SOLO desde Room
     * NUNCA reproduce desde la red directamente
     * Usa estos datos para ExoPlayer
     */
    suspend fun loadChannelFromRoom(): LiveChannel? = withContext(Dispatchers.IO) {
        // Buscar "Caracol FHD" en "COLOMBIA" o el primer canal de "COLOMBIA"
        getCaracolFHDOrFirstColombia()
    }
    
    /**
     * Obtiene todos los canales en vivo como Flow
     */
    fun getAllLiveChannels(): Flow<List<LiveChannel>> {
        return liveChannelDao.getAllChannels()
    }
    
    /**
     * Busca un canal por nombre (sin importar el grupo)
     */
    suspend fun findChannelByName(channelName: String): LiveChannel? = withContext(Dispatchers.IO) {
        // Intentar búsqueda con LIKE
        var channel = liveChannelDao.getChannelByName("%$channelName%")
        if (channel != null) return@withContext channel
        
        // Si no se encuentra, intentar variaciones
        val variations = listOf(
            "$channelName%",
            "%$channelName"
        )
        for (variation in variations) {
            channel = liveChannelDao.getChannelByName(variation)
            if (channel != null) return@withContext channel
        }
        null
    }
    
    /**
     * Obtiene canales aleatorios
     */
    suspend fun getRandomChannels(count: Int): List<LiveChannel> = withContext(Dispatchers.IO) {
        liveChannelDao.getRandomChannels(count)
    }
    
    /**
     * Obtiene la lista de canales para el panel derecho
     * Incluye: Caracol FHD, Win Sports + FHD, RCN FHD, NTN24, DIRECTV SPORTS 2 CO, y canales aleatorios
     * Protegido contra sobrecarga de Room
     */
    suspend fun getChannelsForSidebar(): List<LiveChannel> = withContext(Dispatchers.IO) {
        try {
            val channels = mutableListOf<LiveChannel>()
            
            // 1. Caracol FHD (COLOMBIA) - con protección
            try {
                val caracol = liveChannelDao.getChannelByGroupAndName("COLOMBIA", "Caracol FHD")
                if (caracol != null) channels.add(caracol)
            } catch (e: Exception) {
                Log.w("ChannelRepository", "Error loading Caracol FHD: ${e.message}")
            }
            
            // 2. Win Sports + FHD (buscar en COLOMBIA) - con protección
            try {
                val winSports = liveChannelDao.getChannelByGroupAndName("COLOMBIA", "Win Sports + FHD")
                    ?: findChannelByName("Win Sports + FHD")
                if (winSports != null) channels.add(winSports)
            } catch (e: Exception) {
                Log.w("ChannelRepository", "Error loading Win Sports + FHD: ${e.message}")
            }
            
            // 3. RCN FHD (buscar en COLOMBIA) - con protección
            try {
                val rcn = liveChannelDao.getChannelByGroupAndName("COLOMBIA", "RCN FHD")
                    ?: findChannelByName("RCN FHD")
                if (rcn != null) channels.add(rcn)
            } catch (e: Exception) {
                Log.w("ChannelRepository", "Error loading RCN FHD: ${e.message}")
            }
            
            // 4. NTN24 (buscar en COLOMBIA) - con protección
            try {
                val ntn24 = liveChannelDao.getChannelByGroupAndName("COLOMBIA", "NTN24")
                    ?: findChannelByName("NTN24")
                if (ntn24 != null) channels.add(ntn24)
            } catch (e: Exception) {
                Log.w("ChannelRepository", "Error loading NTN24: ${e.message}")
            }
            
            // 5. DIRECTV SPORTS 2 CO (buscar en cualquier grupo) - con protección
            try {
                val directv = findChannelByName("DIRECTV SPORTS 2 CO")
                    ?: findChannelByName("DIRECTV SPORTS 2")
                if (directv != null) channels.add(directv)
            } catch (e: Exception) {
                Log.w("ChannelRepository", "Error loading DIRECTV SPORTS 2 CO: ${e.message}")
            }
            
            // 6. Completar hasta 10 con canales aleatorios (excluyendo los ya agregados) - con protección
            try {
                val existingIds = channels.map { it.id }.toMutableSet() // MutableSet para poder agregar IDs
                val needed = 10 - channels.size
                
                if (needed > 0) {
                    // Obtener más canales aleatorios de los necesarios para tener opciones
                    // Obtener al menos 30 canales aleatorios para tener suficientes opciones únicas
                    val randomChannels = liveChannelDao.getRandomChannels(30)
                    
                    for (randomChannel in randomChannels) {
                        if (channels.size >= 10) break
                        // Solo agregar si no está ya en la lista
                        if (randomChannel.id !in existingIds) {
                            channels.add(randomChannel)
                            existingIds.add(randomChannel.id) // Actualizar set de IDs existentes
                        }
                    }
                    
                    // Si aún no hay 10 canales después de la primera ronda, intentar otra vez con más canales
                    if (channels.size < 10) {
                        val stillNeeded = 10 - channels.size
                        val moreRandomChannels = liveChannelDao.getRandomChannels(stillNeeded * 5) // Obtener 5 veces más de los necesarios
                        for (randomChannel in moreRandomChannels) {
                            if (channels.size >= 10) break
                            if (randomChannel.id !in existingIds) {
                                channels.add(randomChannel)
                                existingIds.add(randomChannel.id)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ChannelRepository", "Error loading random channels: ${e.message}")
            }
            
            // Asegurar máximo 10 canales y retornar (puede ser menos si no hay suficientes canales en la BD)
            Log.d("ChannelRepository", "Total channels for sidebar: ${channels.size}")
            channels.take(10)
        } catch (e: Exception) {
            Log.e("ChannelRepository", "Error in getChannelsForSidebar: ${e.message}", e)
            emptyList() // Retornar lista vacía en caso de error crítico
        }
    }
}

