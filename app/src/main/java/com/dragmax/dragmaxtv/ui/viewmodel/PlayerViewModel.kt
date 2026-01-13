package com.dragmax.dragmaxtv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.dragmax.dragmaxtv.data.database.AppDatabase
import com.dragmax.dragmaxtv.data.entity.LiveChannel
import com.dragmax.dragmaxtv.data.repository.ChannelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    
    private val database = AppDatabase.getDatabase(application)
    private val repository = ChannelRepository(
        database.m3uUrlDao(),
        database.liveChannelDao(),
        database.listUpdateTimestampDao()
    )
    
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()
    
    private val _currentChannel = MutableLiveData<LiveChannel?>()
    val currentChannel: LiveData<LiveChannel?> = _currentChannel
    
    /**
     * Inicia el proceso completo de carga de canales
     * Lógica:
     * 1. Si NO existen canales en Room → descargar y hacer todo el proceso
     * 2. Si HAY canales en Room:
     *    - Si fecha_list de Firebase es DIFERENTE a la de Room → hacer el proceso completo
     *    - Si fecha_list es IGUAL → NO hacer proceso, solo cargar desde Room
     */
    fun loadChannels() {
        viewModelScope.launch {
            try {
                android.util.Log.d("PlayerViewModel", "loadChannels() started")
                // Verificar si hay canales en Room
                _loadingState.value = LoadingState.Loading("Verificando canales en caché...", 0, 0, LoadingPhase.DOWNLOADING)
                val channelFromRoom = repository.loadChannelFromRoom()
                android.util.Log.d("PlayerViewModel", "Channel from Room: ${channelFromRoom?.name ?: "null"}")
                
                if (channelFromRoom == null) {
                    // NO existen canales en Room → descargar y hacer todo el proceso
                    android.util.Log.d("PlayerViewModel", "No channels in Room, starting download process")
                    val fechaListFromFirebase = try {
                        repository.getFechaListFromFirebase()
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerViewModel", "Error getting fecha_list from Firebase: ${e.message}", e)
                        null
                    }
                    val fieldNames = repository.getM3UFieldNames()
                    var foundChannel = false
                    var totalChannelsProcessed = 0
                    var totalChannelsExpected = 0
                    
                    // Primero, estimar total de canales (contar canales en todas las listas)
                    // Por ahora, usaremos un estimado basado en el número de listas
                    // En una implementación real, podrías contar líneas #EXTINF antes de procesar
                    totalChannelsExpected = fieldNames.size * 100 // Estimado: ~100 canales por lista
                    android.util.Log.d("PlayerViewModel", "Processing ${fieldNames.size} M3U lists")
                    
                    // Procesar UNA M3U a la vez (estrictamente secuencial)
                    // IMPORTANTE: Todo el procesamiento en Dispatchers.IO para no bloquear UI ni ExoPlayer
                    for ((index, fieldName) in fieldNames.withIndex()) {
                        if (foundChannel && _currentChannel.value != null) {
                            // Si ya hay canal reproduciéndose, continuar procesando en background sin interrumpir
                            android.util.Log.d("PlayerViewModel", "Canal reproduciéndose, continuando procesamiento en background...")
                        }
                        
                        try {
                            // FASE 1 - LECTURA (en background)
                            val m3uUrl = withContext(Dispatchers.IO) {
                                repository.loadSingleM3UUrlFromFirebase(fieldName)
                            }
                            if (m3uUrl == null) continue
                            
                            // FASE 2 - DESCARGA Y FILTRO (en background, no bloquea ExoPlayer)
                            val downloadedM3u = withContext(Dispatchers.IO) {
                                repository.downloadAndFilterM3U(m3uUrl)
                            }
                            
                            // FASE 3 - CACHE EN ROOM (en background)
                            val liveChannels = withContext(Dispatchers.IO) {
                                repository.processAndCacheLiveChannels(downloadedM3u)
                            }
                            
                            // Actualizar progreso basado en canales procesados
                            totalChannelsProcessed += liveChannels.size
                            val progress = if (totalChannelsExpected > 0) {
                                ((totalChannelsProcessed.toFloat() / totalChannelsExpected.toFloat()) * 100).toInt().coerceIn(0, 100)
                            } else {
                                ((index + 1).toFloat() / fieldNames.size.toFloat() * 100).toInt()
                            }
                            
                            _loadingState.value = LoadingState.Loading(
                                "Descargando canales…",
                                progress = progress,
                                total = totalChannelsExpected,
                                phase = LoadingPhase.DOWNLOADING
                            )
                            
                            // Si llegamos al 100% de descarga
                            if (progress >= 100 || index == fieldNames.size - 1) {
                                _loadingState.value = LoadingState.Loading(
                                    "Preparando canales…",
                                    progress = 100,
                                    total = totalChannelsExpected,
                                    phase = LoadingPhase.PREPARING
                                )
                                kotlinx.coroutines.delay(500)
                            }
                            
                            // FASE 4 - CARGA
                            if (!foundChannel && liveChannels.isNotEmpty()) {
                                _loadingState.value = LoadingState.Loading(
                                    "Cargando desde el dispositivo…",
                                    progress = 50,
                                    total = 100,
                                    phase = LoadingPhase.LOADING_FROM_DEVICE
                                )
                                
                                val preferredChannel = repository.getCaracolFHDOrFirstColombia()
                                if (preferredChannel != null) {
                                    _loadingState.value = LoadingState.Loading(
                                        "Cargando desde el dispositivo…",
                                        progress = 100,
                                        total = 100,
                                        phase = LoadingPhase.LOADING_FROM_DEVICE
                                    )
                                    _currentChannel.postValue(preferredChannel)
                                    foundChannel = true
                                    // NO establecer Success aquí - se establecerá al final de TODO el procesamiento
                                }
                            } else if (_currentChannel.value != null) {
                                // Ya hay canal reproduciéndose - NO interrumpir
                                foundChannel = true
                            }
                            
                            kotlinx.coroutines.delay(300)
                            
                        } catch (e: Exception) {
                            android.util.Log.e("PlayerViewModel", "Error procesando M3U $fieldName: ${e.message}", e)
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                    
                    // Guardar fecha_list después de actualización exitosa
                    if (fechaListFromFirebase != null) {
                        withContext(Dispatchers.IO) {
                            repository.saveFechaList(fechaListFromFirebase)
                        }
                    }
                    
                    // Si no encontramos canal, intentar desde Room
                    if (!foundChannel) {
                        val finalChannel = withContext(Dispatchers.IO) {
                            repository.loadChannelFromRoom()
                        }
                        if (finalChannel != null) {
                            // Solo actualizar si no hay canal reproduciéndose
                            if (_currentChannel.value == null) {
                                _currentChannel.postValue(finalChannel)
                            }
                        } else {
                            _loadingState.value = LoadingState.Error("No se encontraron canales en vivo")
                            return@launch
                        }
                    }
                    
                    // IMPORTANTE: Establecer Success SOLO cuando TODO el procesamiento esté completamente terminado
                    _loadingState.value = LoadingState.Success("Canales listos")
                    
                } else {
                    // HAY canales en Room → verificar fecha_list
                    android.util.Log.d("PlayerViewModel", "Channels found in Room, checking for updates")
                    _loadingState.value = LoadingState.Loading("Verificando actualizaciones...", 0, 0, LoadingPhase.DOWNLOADING)
                    val needsUpdate = try {
                        repository.needsUpdate()
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerViewModel", "Error checking if update needed: ${e.message}", e)
                        // Si hay error verificando actualización, usar canales de Room
                        false
                    }
                    
                    if (!needsUpdate) {
                        // fecha_list es IGUAL → NO hacer proceso, solo cargar desde Room
                        android.util.Log.d("PlayerViewModel", "No update needed, loading from cache: ${channelFromRoom.name}")
                        // Solo actualizar si no hay canal reproduciéndose
                        if (_currentChannel.value == null) {
                            _currentChannel.postValue(channelFromRoom)
                        }
                        // Establecer Success cuando todo esté listo
                        _loadingState.value = LoadingState.Success("Canales listos")
                        return@launch
                    }
                    
                    // fecha_list es DIFERENTE → hacer el proceso completo
                    val fechaListFromFirebase = repository.getFechaListFromFirebase()
                    val fieldNames = repository.getM3UFieldNames()
                    var foundChannel = false
                    var totalChannelsProcessed = 0
                    val totalChannelsExpected = fieldNames.size * 100 // Estimado
                    
                    // Procesar UNA M3U a la vez (en background, sin interrumpir canal actual)
                    val currentChannelValue = _currentChannel.value
                    val hasActiveChannel = currentChannelValue != null
                    
                    for ((index, fieldName) in fieldNames.withIndex()) {
                        // Si hay canal reproduciéndose, continuar procesando en background sin interrumpir
                        if (foundChannel && hasActiveChannel) {
                            android.util.Log.d("PlayerViewModel", "Canal reproduciéndose (${currentChannelValue?.name}), procesando lista ${index + 1}/${fieldNames.size} en background...")
                        }
                        
                        try {
                            // FASE 1 - LECTURA (en background)
                            val m3uUrl = withContext(Dispatchers.IO) {
                                repository.loadSingleM3UUrlFromFirebase(fieldName)
                            }
                            if (m3uUrl == null) continue
                            
                            // Si la URL ya está en caché, procesar directamente (en background)
                            if (m3uUrl.content != null && m3uUrl.content.isNotBlank()) {
                                val liveChannels = withContext(Dispatchers.IO) {
                                    repository.processAndCacheLiveChannels(m3uUrl)
                                }
                                totalChannelsProcessed += liveChannels.size
                                
                                val progress = if (totalChannelsExpected > 0) {
                                    ((totalChannelsProcessed.toFloat() / totalChannelsExpected.toFloat()) * 100).toInt().coerceIn(0, 100)
                                } else {
                                    ((index + 1).toFloat() / fieldNames.size.toFloat() * 100).toInt()
                                }
                                
                                _loadingState.value = LoadingState.Loading(
                                    "Descargando canales…",
                                    progress = progress,
                                    total = totalChannelsExpected,
                                    phase = LoadingPhase.DOWNLOADING
                                )
                                
                                if (progress >= 100 || index == fieldNames.size - 1) {
                                    _loadingState.value = LoadingState.Loading(
                                        "Preparando canales…",
                                        progress = 100,
                                        total = totalChannelsExpected,
                                        phase = LoadingPhase.PREPARING
                                    )
                                    kotlinx.coroutines.delay(500)
                                }
                                
                                // CRÍTICO: Solo actualizar canal si NO hay uno reproduciéndose
                                if (!foundChannel && liveChannels.isNotEmpty() && !hasActiveChannel) {
                                    _loadingState.value = LoadingState.Loading(
                                        "Cargando desde el dispositivo…",
                                        progress = 50,
                                        total = 100,
                                        phase = LoadingPhase.LOADING_FROM_DEVICE
                                    )
                                    
                                    val preferredChannel = withContext(Dispatchers.IO) {
                                        repository.getCaracolFHDOrFirstColombia()
                                    }
                                    if (preferredChannel != null) {
                                        _loadingState.value = LoadingState.Loading(
                                            "Cargando desde el dispositivo…",
                                            progress = 100,
                                            total = 100,
                                            phase = LoadingPhase.LOADING_FROM_DEVICE
                                        )
                                        _currentChannel.postValue(preferredChannel)
                                        foundChannel = true
                                        // NO establecer Success aquí - se establecerá al final de TODO el procesamiento
                                    }
                                } else if (hasActiveChannel) {
                                    // Ya hay canal reproduciéndose - NO interrumpir, solo actualizar caché
                                    android.util.Log.d("PlayerViewModel", "Canal ${currentChannelValue?.name} reproduciéndose - actualizando caché sin interrumpir")
                                    foundChannel = true // Marcar como encontrado pero NO cambiar el canal actual
                                }
                                continue
                            }
                            
                            // FASE 2 - DESCARGA Y FILTRO (en background)
                            val downloadedM3u = withContext(Dispatchers.IO) {
                                repository.downloadAndFilterM3U(m3uUrl)
                            }
                            
                            // FASE 3 - CACHE EN ROOM (en background)
                            val liveChannels = withContext(Dispatchers.IO) {
                                repository.processAndCacheLiveChannels(downloadedM3u)
                            }
                            totalChannelsProcessed += liveChannels.size
                            
                            val progress = if (totalChannelsExpected > 0) {
                                ((totalChannelsProcessed.toFloat() / totalChannelsExpected.toFloat()) * 100).toInt().coerceIn(0, 100)
                            } else {
                                ((index + 1).toFloat() / fieldNames.size.toFloat() * 100).toInt()
                            }
                            
                            _loadingState.value = LoadingState.Loading(
                                "Descargando canales…",
                                progress = progress,
                                total = totalChannelsExpected,
                                phase = LoadingPhase.DOWNLOADING
                            )
                            
                            if (progress >= 100 || index == fieldNames.size - 1) {
                                _loadingState.value = LoadingState.Loading(
                                    "Preparando canales…",
                                    progress = 100,
                                    total = totalChannelsExpected,
                                    phase = LoadingPhase.PREPARING
                                )
                                kotlinx.coroutines.delay(500)
                            }
                            
                            // FASE 4 - CARGA (solo si no hay canal reproduciéndose)
                            if (!foundChannel && liveChannels.isNotEmpty() && !hasActiveChannel) {
                                _loadingState.value = LoadingState.Loading(
                                    "Cargando desde el dispositivo…",
                                    progress = 50,
                                    total = 100,
                                    phase = LoadingPhase.LOADING_FROM_DEVICE
                                )
                                
                                val preferredChannel = withContext(Dispatchers.IO) {
                                    repository.getCaracolFHDOrFirstColombia()
                                }
                                if (preferredChannel != null) {
                                    _loadingState.value = LoadingState.Loading(
                                        "Cargando desde el dispositivo…",
                                        progress = 100,
                                        total = 100,
                                        phase = LoadingPhase.LOADING_FROM_DEVICE
                                    )
                                    _currentChannel.postValue(preferredChannel)
                                    foundChannel = true
                                    // NO establecer Success aquí - se establecerá al final de TODO el procesamiento
                                }
                            } else if (hasActiveChannel) {
                                // Ya hay canal reproduciéndose - NO interrumpir
                                foundChannel = true
                            }
                            
                            kotlinx.coroutines.delay(300)
                            
                        } catch (e: Exception) {
                            android.util.Log.e("PlayerViewModel", "Error procesando M3U $fieldName: ${e.message}", e)
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                    
                    // Guardar fecha_list después de actualización exitosa
                    if (fechaListFromFirebase != null) {
                        withContext(Dispatchers.IO) {
                            repository.saveFechaList(fechaListFromFirebase)
                        }
                    }
                    
                    // Si no encontramos canal, usar el de Room
                    if (!foundChannel) {
                        val finalChannel = withContext(Dispatchers.IO) {
                            repository.loadChannelFromRoom()
                        }
                        if (finalChannel != null) {
                            // Solo actualizar si no hay canal reproduciéndose
                            if (_currentChannel.value == null) {
                                _currentChannel.postValue(finalChannel)
                            }
                        }
                    }
                    
                    // IMPORTANTE: Establecer Success SOLO cuando TODO el procesamiento esté completamente terminado
                    _loadingState.value = LoadingState.Success("Canales listos")
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "Error en loadChannels: ${e.message}", e)
                _loadingState.value = LoadingState.Error("Error: ${e.message}")
            }
        }
    }
    
    sealed class LoadingState {
        object Idle : LoadingState()
        data class Loading(
            val message: String,
            val progress: Int = 0,
            val total: Int = 0,
            val phase: LoadingPhase = LoadingPhase.DOWNLOADING
        ) : LoadingState()
        data class Success(val message: String) : LoadingState()
        data class Error(val message: String) : LoadingState()
    }
    
    enum class LoadingPhase {
        DOWNLOADING,      // Descargando canales (M3U → Room)
        PREPARING,        // Preparando canales (100% descarga)
        LOADING_FROM_DEVICE // Cargando desde dispositivo (Room → ExoPlayer)
    }
}

