package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Spacing
import kotlinx.coroutines.launch

data class SkillItem(
    val name: String,
    val description: String,
    val isEnabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillPickerBottomSheet(
    skills: List<SkillItem>,
    onSkillSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false
) {
    val theme = LocalOpenCodeTheme.current
    val scope = rememberCoroutineScope()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.backgroundPanel,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = theme.accent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "Inject Skill",
                    style = MaterialTheme.typography.titleMedium,
                    color = theme.text,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(Spacing.md))
            
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = theme.accent)
                }
            } else if (skills.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No skills available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.textMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(skills, key = { it.name }) { skill ->
                        SkillRow(
                            skill = skill,
                            onClick = {
                                scope.launch {
                                    onSkillSelected(skill.name)
                                    onDismiss()
                                }
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun SkillRow(
    skill: SkillItem,
    onClick: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    
    Surface(
        onClick = onClick,
        enabled = skill.isEnabled,
        modifier = Modifier.fillMaxWidth(),
        color = if (skill.isEnabled) theme.backgroundElement else theme.backgroundPanel,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "@${skill.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (skill.isEnabled) theme.accent else theme.textMuted,
                    fontWeight = FontWeight.Medium
                )
                if (!skill.isEnabled) {
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = "(disabled)",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.textMuted
                    )
                }
            }
            if (skill.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted,
                    maxLines = 2
                )
            }
        }
    }
}
