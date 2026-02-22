package dev.blazelight.p4oc.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.domain.model.Permission
import dev.blazelight.p4oc.domain.model.Question
import dev.blazelight.p4oc.domain.model.QuestionOption
import dev.blazelight.p4oc.domain.model.QuestionRequest
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DialogQueueManagerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mockkObject(AppLog)
        every { AppLog.d(any(), any<String>()) } returns Unit
        every { AppLog.d(any(), any<() -> String>()) } returns Unit
        every { AppLog.e(any(), any<String>()) } returns Unit
        every { AppLog.e(any(), any<String>(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(AppLog)
    }

    @Test
    fun enqueuePermission_showsImmediately_whenNoCurrentPermission() {
        val handle = SavedStateHandle()
        val manager = DialogQueueManager(handle, json)
        val permission = permission(id = "p1", callId = "c1")

        manager.enqueuePermission(permission)

        assertEquals(permission, manager.pendingPermission.value)
        assertEquals(json.encodeToString(permission), handle.get<String>(KEY_PENDING_PERMISSION))
    }

    @Test
    fun enqueuePermission_queues_whenPermissionAlreadyShowing() {
        val handle = SavedStateHandle()
        val manager = DialogQueueManager(handle, json)
        val first = permission(id = "p1", callId = "c1")
        val second = permission(id = "p2", callId = "c2")

        manager.enqueuePermission(first)
        manager.enqueuePermission(second)

        assertEquals(first, manager.pendingPermission.value)
        val queuedJson = handle.get<String>(KEY_PENDING_PERMISSIONS_QUEUE)
        assertNotNull(queuedJson)
        assertTrue(queuedJson!!.contains("p2"))
    }

    @Test
    fun clearPermission_advancesToNextInQueue() {
        val handle = SavedStateHandle()
        val manager = DialogQueueManager(handle, json)
        val first = permission(id = "p1", callId = "c1")
        val second = permission(id = "p2", callId = "c2")

        manager.enqueuePermission(first)
        manager.enqueuePermission(second)
        manager.clearPermission(first.id)

        assertEquals(second, manager.pendingPermission.value)
    }

    @Test
    fun clearPermission_clears_whenQueueEmpty() {
        val handle = SavedStateHandle()
        val manager = DialogQueueManager(handle, json)
        val only = permission(id = "p1", callId = "c1")

        manager.enqueuePermission(only)
        manager.clearPermission(only.id)

        assertNull(manager.pendingPermission.value)
        assertNull(handle.get<String>(KEY_PENDING_PERMISSION))
        assertNull(handle.get<String>(KEY_PENDING_PERMISSIONS_QUEUE))
    }

    @Test
    fun permission_persistedToSavedStateHandle_forProcessDeath() {
        val handle = SavedStateHandle()
        val manager = DialogQueueManager(handle, json)
        val permission = permission(id = "p1", callId = "c1")

        manager.enqueuePermission(permission)

        assertEquals(json.encodeToString(permission), handle.get<String>(KEY_PENDING_PERMISSION))
    }

    @Test
    fun restoresPendingPermission_fromSavedStateHandle_onInit() {
        val permission = permission(id = "p1", callId = "c1")
        val handle = SavedStateHandle(
            mapOf(KEY_PENDING_PERMISSION to json.encodeToString(permission))
        )

        val manager = DialogQueueManager(handle, json)

        assertEquals(permission, manager.pendingPermission.value)
    }

    @Test
    fun restoresPermissionQueue_fromSavedStateHandle_onInit() {
        val current = permission(id = "p-current", callId = "c-current")
        val queued = listOf(permission(id = "p-queued", callId = "c-queued"))
        val handle = SavedStateHandle(
            mapOf(
                KEY_PENDING_PERMISSION to json.encodeToString(current),
                KEY_PENDING_PERMISSIONS_QUEUE to json.encodeToString(queued)
            )
        )

        val manager = DialogQueueManager(handle, json)
        manager.clearPermission(current.id)

        assertEquals("p-queued", manager.pendingPermission.value?.id)
    }

    @Test
    fun enqueuePermission_addsToPendingPermissionsByCallId_map() {
        val handle = SavedStateHandle()
        val manager = DialogQueueManager(handle, json)
        val permission = permission(id = "p1", callId = "call-1")

        manager.enqueuePermission(permission)

        assertEquals(permission, manager.pendingPermissionsByCallId.value["call-1"])
    }

    @Test
    fun corruptSavedStateHandleData_doesNotCrash() {
        val handle = SavedStateHandle(
            mapOf(
                KEY_PENDING_PERMISSION to "{not-json}",
                KEY_PENDING_PERMISSIONS_QUEUE to "[bad-json",
                KEY_PENDING_QUESTION to "{bad-json}",
                KEY_PENDING_QUESTIONS_QUEUE to "[bad-json"
            )
        )

        val manager = DialogQueueManager(handle, json)

        assertNull(manager.pendingPermission.value)
        assertNull(manager.pendingQuestion.value)
        assertNull(handle.get<String>(KEY_PENDING_PERMISSION))
        assertNull(handle.get<String>(KEY_PENDING_PERMISSIONS_QUEUE))
        assertNull(handle.get<String>(KEY_PENDING_QUESTION))
        assertNull(handle.get<String>(KEY_PENDING_QUESTIONS_QUEUE))
    }

    private fun permission(id: String, callId: String?): Permission {
        return Permission(
            id = id,
            type = "read",
            patterns = listOf("*.kt"),
            sessionID = "session-1",
            messageID = "message-1",
            callID = callId,
            title = "Allow read",
            metadata = buildJsonObject { },
            always = emptyList()
        )
    }

    @Test
    fun enqueueQuestion_showsImmediately_whenNoCurrentQuestion() {
        val handle = SavedStateHandle()
        val manager = DialogQueueManager(handle, json)
        val question = questionRequest(id = "q1")

        manager.enqueueQuestion(question)

        assertEquals(question, manager.pendingQuestion.value)
        assertEquals(json.encodeToString(question), handle.get<String>(KEY_PENDING_QUESTION))
    }

    @Test
    fun clearQuestion_advancesToNextInQueue() {
        val handle = SavedStateHandle()
        val manager = DialogQueueManager(handle, json)
        val first = questionRequest(id = "q1")
        val second = questionRequest(id = "q2")

        manager.enqueueQuestion(first)
        manager.enqueueQuestion(second)
        assertEquals(first, manager.pendingQuestion.value)

        manager.clearQuestion()
        assertEquals(second, manager.pendingQuestion.value)
    }

    @Test
    fun clearQuestion_clears_whenQueueEmpty() {
        val handle = SavedStateHandle()
        val manager = DialogQueueManager(handle, json)
        val only = questionRequest(id = "q1")

        manager.enqueueQuestion(only)
        manager.clearQuestion()

        assertNull(manager.pendingQuestion.value)
        assertNull(handle.get<String>(KEY_PENDING_QUESTION))
    }

    private fun questionRequest(id: String): QuestionRequest {
        return QuestionRequest(
            id = id,
            sessionID = "session-1",
            questions = listOf(
                Question(
                    header = "Header",
                    question = "Q?",
                    options = listOf(QuestionOption(label = "Yes", description = ""))
                )
            )
        )
    }

    private companion object {
        const val KEY_PENDING_QUESTION = "pending_question"
        const val KEY_PENDING_QUESTIONS_QUEUE = "pending_questions_queue"
        const val KEY_PENDING_PERMISSION = "pending_permission"
        const val KEY_PENDING_PERMISSIONS_QUEUE = "pending_permissions_queue"
    }
}
