package com.AnimeFire

import com.lagradost.cloudstream3.ShowStatus

fun getStatus(t: String?): ShowStatus {
    if (t == null) return ShowStatus.Completed
    
    val status = t.trim().lowercase()
    
    return when {
        status.contains("em lançamento") ||
        status.contains("lançando") ||
        status.contains("em andamento") ||
        status.contains("atualizando") ||
        status.contains("ongoing") ||
        status.contains("lançamento") -> ShowStatus.Ongoing
        
        status.contains("concluído") ||
        status.contains("completo") ||
        status.contains("completado") ||
        status.contains("terminado") ||
        status.contains("finished") -> ShowStatus.Completed
        
        else -> ShowStatus.Completed
    }
}
