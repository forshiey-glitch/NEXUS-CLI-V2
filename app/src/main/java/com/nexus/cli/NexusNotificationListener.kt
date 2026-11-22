package com.nexus.cli

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NexusNotificationListener : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private val component by lazy { ComponentName(this, NexusNotificationListener::class.java) }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        MediaRepository.onSessions(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            sessionManager = msm
            msm.addOnActiveSessionsChangedListener(sessionsListener, component)
            MediaRepository.setAccess(true)
            MediaRepository.onSessions(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            MediaRepository.setAccess(false)
        }
    }

    override fun onListenerDisconnected() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
            // abaikan
        }
        sessionManager = null
        MediaRepository.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshSessions()
    }

    private fun refreshSessions() {
        try {
            val msm = sessionManager ?: return
            MediaRepository.onSessions(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // izin dicabut
        }
    }
}