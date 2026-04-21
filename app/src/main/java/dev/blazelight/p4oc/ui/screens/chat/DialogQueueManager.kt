package dev.blazelight.p4oc.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.domain.model.Permission
import dev.blazelight.p4oc.domain.model.QuestionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages permission and question dialog queues with SavedStateHandle persistence.
 */
class DialogQueueManager(
    private val savedStateHandle: SavedStateHandle,
    private val json: Json
) {
    private val pendingPermissions = ConcurrentLinkedQueue<Permission>()
    private val pendingQuestions = ConcurrentLinkedQueue<QuestionRequest>()

    private val _pendingPermission = MutableStateFlow<Permission?>(null)
    val pendingPermission: StateFlow<Permission?> = _pendingPermission.asStateFlow()

    private val _pendingQuestion = MutableStateFlow<QuestionRequest?>(null)
    val pendingQuestion: StateFlow<QuestionRequest?> = _pendingQuestion.asStateFlow()

    private val _pendingPermissionsByCallId = MutableStateFlow<Map<String, Permission>>(emptyMap())
    val pendingPermissionsByCallId: StateFlow<Map<String, Permission>> = _pendingPermissionsByCallId.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeoutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    init {
        restorePendingDialogState()
    }

    /**
     * Restore pending question/permission state from SavedStateHandle after process death.
     */
    private fun restorePendingDialogState() {
        // Restore pending question
        savedStateHandle.get<String>(KEY_PENDING_QUESTION)?.let { jsonString ->
            try {
                val question = json.decodeFromString<QuestionRequest>(jsonString)
                _pendingQuestion.value = question
                AppLog.d(TAG, "Restored pending question: ${question.id}")
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to restore pending question", e)
                savedStateHandle.remove<String>(KEY_PENDING_QUESTION)
            }
        }

        // Restore pending questions queue
        savedStateHandle.get<String>(KEY_PENDING_QUESTIONS_QUEUE)?.let { jsonString ->
            try {
                val questions = json.decodeFromString<List<QuestionRequest>>(jsonString)
                pendingQuestions.addAll(questions)
                AppLog.d(TAG, "Restored ${questions.size} queued questions")
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to restore pending questions queue", e)
                savedStateHandle.remove<String>(KEY_PENDING_QUESTIONS_QUEUE)
            }
        }

        // Restore pending permission
        savedStateHandle.get<String>(KEY_PENDING_PERMISSION)?.let { jsonString ->
            try {
                val permission = json.decodeFromString<Permission>(jsonString)
                _pendingPermission.value = permission
                AppLog.d(TAG, "Restored pending permission: ${permission.id}")
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to restore pending permission", e)
                savedStateHandle.remove<String>(KEY_PENDING_PERMISSION)
            }
        }

        // Restore pending permissions queue
        savedStateHandle.get<String>(KEY_PENDING_PERMISSIONS_QUEUE)?.let { jsonString ->
            try {
                val permissions = json.decodeFromString<List<Permission>>(jsonString)
                pendingPermissions.addAll(permissions)
                AppLog.d(TAG, "Restored ${permissions.size} queued permissions")
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to restore pending permissions queue", e)
                savedStateHandle.remove<String>(KEY_PENDING_PERMISSIONS_QUEUE)
            }
        }
    }

    fun enqueuePermission(permission: Permission) {
        // Add to queue for modal (legacy)
        pendingPermissions.offer(permission)
        showNextPermission()

        // Add to callID map for inline rendering
        permission.callID?.let { callId ->
            _pendingPermissionsByCallId.update { it + (callId to permission) }
        }

        permission.callID?.let { callId ->
            timeoutJobs.remove(callId)?.cancel()
            timeoutJobs[callId] = scope.launch {
                delay(PERMISSION_TIMEOUT_MS)
                clearPermissionByCallId(callId)
                AppLog.w(TAG, "Auto-cleared stuck permission for callId=$callId after timeout")
            }
        }
    }

    fun enqueueQuestion(request: QuestionRequest) {
        pendingQuestions.offer(request)
        showNextQuestion()
    }

    fun clearPermission(permissionId: String) {
        // Clear from modal queue (legacy)
        _pendingPermission.value = null
        savedStateHandle.remove<String>(KEY_PENDING_PERMISSION)

        // Clear from legacy queue to prevent reappearing
        pendingPermissions.removeIf { it.id == permissionId }

        showNextPermission()

        // Clear from inline map
        _pendingPermissionsByCallId.value
            .filterValues { it.id == permissionId }
            .keys
            .forEach { key -> timeoutJobs.remove(key)?.cancel() }
        _pendingPermissionsByCallId.update { map ->
            map.filterValues { it.id != permissionId }
        }
    }

    fun clearPermissionByRequestId(requestId: String) {
        // Clear from inline map
        _pendingPermissionsByCallId.value
            .filterValues { it.id == requestId }
            .keys
            .forEach { key -> timeoutJobs.remove(key)?.cancel() }
        _pendingPermissionsByCallId.update { map ->
            map.filterValues { it.id != requestId }
        }
        // Also clear from modal if it matches
        if (_pendingPermission.value?.id == requestId) {
            _pendingPermission.value = null
            savedStateHandle.remove<String>(KEY_PENDING_PERMISSION)
            showNextPermission()
        }
    }

    fun clearPermissionByCallId(callId: String) {
        // Clear from inline map by callId
        timeoutJobs.remove(callId)?.cancel()
        _pendingPermissionsByCallId.update { map ->
            map.filterKeys { it != callId }
        }
    }

    fun clearQuestion() {
        _pendingQuestion.value = null
        savedStateHandle.remove<String>(KEY_PENDING_QUESTION)
        showNextQuestion()
    }

    fun cleanup() {
        timeoutJobs.values.forEach { job -> job.cancel() }
        timeoutJobs.clear()
        scope.cancel()
    }

    private fun showNextPermission() {
        if (_pendingPermission.value == null) {
            pendingPermissions.poll()?.let { permission ->
                _pendingPermission.value = permission
                // Persist to SavedStateHandle for process death survival
                savedStateHandle[KEY_PENDING_PERMISSION] = json.encodeToString(permission)
            }
        }
        // Persist remaining queue
        persistPermissionsQueue()
    }

    private fun showNextQuestion() {
        if (_pendingQuestion.value == null) {
            pendingQuestions.poll()?.let { question ->
                _pendingQuestion.value = question
                // Persist to SavedStateHandle for process death survival
                savedStateHandle[KEY_PENDING_QUESTION] = json.encodeToString(question)
            }
        }
        // Persist remaining queue
        persistQuestionsQueue()
    }

    private fun persistQuestionsQueue() {
        val queueList = pendingQuestions.toList()
        if (queueList.isNotEmpty()) {
            savedStateHandle[KEY_PENDING_QUESTIONS_QUEUE] = json.encodeToString(queueList)
        } else {
            savedStateHandle.remove<String>(KEY_PENDING_QUESTIONS_QUEUE)
        }
    }

    private fun persistPermissionsQueue() {
        val queueList = pendingPermissions.toList()
        if (queueList.isNotEmpty()) {
            savedStateHandle[KEY_PENDING_PERMISSIONS_QUEUE] = json.encodeToString(queueList)
        } else {
            savedStateHandle.remove<String>(KEY_PENDING_PERMISSIONS_QUEUE)
        }
    }

    private companion object {
        const val TAG = "DialogQueueManager"
        const val KEY_PENDING_QUESTION = "pending_question"
        const val KEY_PENDING_QUESTIONS_QUEUE = "pending_questions_queue"
        const val KEY_PENDING_PERMISSION = "pending_permission"
        const val KEY_PENDING_PERMISSIONS_QUEUE = "pending_permissions_queue"
        const val PERMISSION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }
}
