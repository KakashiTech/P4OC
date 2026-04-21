package dev.blazelight.p4oc.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Skill information from the server.
 * Skills are reusable capability modules defined in SKILL.md files.
 */
@Serializable
data class SkillDto(
    val name: String,
    val description: String,
    val content: String? = null,
    @SerialName("isEnabled")
    val isEnabled: Boolean = true,
    val tools: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val author: String? = null,
    val version: String? = null
)
