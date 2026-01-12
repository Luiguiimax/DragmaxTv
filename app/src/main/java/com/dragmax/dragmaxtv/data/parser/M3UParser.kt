package com.dragmax.dragmaxtv.data.parser

import com.dragmax.dragmaxtv.data.entity.LiveChannel
import java.util.regex.Pattern

object M3UParser {
    
    /**
     * Parsea el contenido M3U y filtra SOLO canales en vivo
     * Excluye películas, series y VOD
     */
    fun parseM3U(content: String, m3uSourceId: Long): List<LiveChannel> {
        val channels = mutableListOf<LiveChannel>()
        val lines = content.lines()
        
        var currentName: String? = null
        var currentUrl: String? = null
        var currentGroup: String? = null
        var currentLogo: String? = null
        
        for (line in lines) {
            val trimmedLine = line.trim()
            
            // Detectar inicio de entrada (#EXTINF)
            if (trimmedLine.startsWith("#EXTINF:")) {
                // Extraer información del EXTINF
                val extinfData = parseExtInf(trimmedLine)
                currentName = extinfData.name
                currentGroup = extinfData.group
                currentLogo = extinfData.logo
            }
            // Detectar URL (no empieza con #)
            else if (!trimmedLine.startsWith("#") && trimmedLine.isNotEmpty() && currentName != null) {
                currentUrl = trimmedLine
                
                // Verificar si es un canal en vivo válido
                if (isLiveChannel(currentName, currentGroup, currentUrl)) {
                    channels.add(
                        LiveChannel(
                            name = currentName,
                            url = currentUrl,
                            group = currentGroup,
                            logo = currentLogo,
                            m3uSourceId = m3uSourceId
                        )
                    )
                }
                
                // Resetear para la siguiente entrada
                currentName = null
                currentUrl = null
                currentGroup = null
                currentLogo = null
            }
        }
        
        return channels
    }
    
    /**
     * Parsea la línea #EXTINF para extraer nombre, grupo y logo
     */
    fun parseExtInf(extinfLine: String): ExtInfData {
        // Formato: #EXTINF:-1 tvg-id="..." tvg-name="..." tvg-logo="..." group-title="...",Nombre del Canal
        var name = ""
        var group: String? = null
        var logo: String? = null
        
        // Extraer nombre (después de la última coma)
        val nameMatch = Pattern.compile(",(.+)$").matcher(extinfLine)
        if (nameMatch.find()) {
            name = nameMatch.group(1)?.trim() ?: ""
        }
        
        // Extraer group-title
        val groupMatch = Pattern.compile("group-title=\"([^\"]+)\"").matcher(extinfLine)
        if (groupMatch.find()) {
            group = groupMatch.group(1)?.trim()
        }
        
        // Extraer logo
        val logoMatch = Pattern.compile("tvg-logo=\"([^\"]+)\"").matcher(extinfLine)
        if (logoMatch.find()) {
            logo = logoMatch.group(1)?.trim()
        }
        
        return ExtInfData(name, group, logo)
    }
    
    /**
     * Verifica si es un canal en vivo válido
     * FILTRO ESTRICTO: Excluye películas, series, VOD y cualquier categoría que no sea TV en vivo
     * Esto es crítico para no saturar la base de datos
     */
    fun isLiveChannel(name: String, group: String?, url: String): Boolean {
        val nameLower = name.lowercase()
        val groupLower = group?.lowercase() ?: ""
        val urlLower = url.lowercase()
        
        // Palabras clave que indican que NO es un canal en vivo (FILTRO ESTRICTO)
        val excludeKeywords = listOf(
            // Películas
            "movie", "pelicula", "película", "film", "cine", "cinema",
            // Series
            "series", "serie", "show", "tv show", "programa",
            // VOD
            "vod", "video on demand", "on demand", "demand",
            // Otros
            "catchup", "timeshift", "archive", "archivo",
            "documental", "documentary", "docu"
        )
        
        // Verificar en nombre (FILTRO ESTRICTO)
        if (excludeKeywords.any { nameLower.contains(it) }) {
            return false
        }
        
        // Verificar en grupo (FILTRO ESTRICTO)
        if (excludeKeywords.any { groupLower.contains(it) }) {
            return false
        }
        
        // Verificar que la URL sea válida (debe ser http/https)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false
        }
        
        // Excluir extensiones de archivo de video (indican VOD)
        val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm")
        if (videoExtensions.any { urlLower.contains(it) && !urlLower.contains("m3u8") }) {
            return false
        }
        
        // Aceptar solo URLs de streaming en vivo (m3u8, ts, etc.)
        val liveStreamPatterns = listOf("m3u8", ".ts", "stream", "live")
        val hasLiveStreamPattern = liveStreamPatterns.any { urlLower.contains(it) }
        
        // Si no tiene patrón de streaming en vivo, rechazar
        if (!hasLiveStreamPattern && !urlLower.contains("http")) {
            return false
        }
        
        return true
    }
    
    data class ExtInfData(
        val name: String,
        val group: String?,
        val logo: String?
    )
}

