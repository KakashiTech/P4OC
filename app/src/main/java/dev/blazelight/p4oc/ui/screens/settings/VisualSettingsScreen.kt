package dev.blazelight.p4oc.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.datastore.VisualSettings
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.ToolState
import dev.blazelight.p4oc.ui.components.TuiTopBar
import dev.blazelight.p4oc.ui.components.TuiStepper
import dev.blazelight.p4oc.ui.components.TuiSwitch
import dev.blazelight.p4oc.ui.components.toolwidgets.ToolGroupWidget
import dev.blazelight.p4oc.ui.components.toolwidgets.ToolWidgetState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


class VisualSettingsViewModel constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {
    
    private val _settings = MutableStateFlow(VisualSettings())
    val settings: StateFlow<VisualSettings> = _settings.asStateFlow()
    
    private val _themeName = MutableStateFlow(SettingsDataStore.DEFAULT_THEME_NAME)
    val themeName: StateFlow<String> = _themeName.asStateFlow()
    
    private val _themeMode = MutableStateFlow("system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()
    
    val availableThemes = listOf(
        "catppuccin" to "Catppuccin Mocha",
        "catppuccin-macchiato" to "Catppuccin Macchiato",
        "catppuccin-frappe" to "Catppuccin Frappé",
        "dracula" to "Dracula",
        "gruvbox" to "Gruvbox",
        "nord" to "Nord",
        "opencode" to "OpenCode",
        "tokyonight" to "Tokyo Night",
        "xterm" to "XTerm 256",
        "hotdogstand" to "Hot Dog Stand"
    )
    
    init {
        viewModelScope.launch {
            settingsDataStore.visualSettings.collect { saved ->
                _settings.value = saved
            }
        }
        viewModelScope.launch {
            settingsDataStore.themeName.collect { name ->
                _themeName.value = name
            }
        }
        viewModelScope.launch {
            settingsDataStore.themeMode.collect { mode ->
                _themeMode.value = mode
            }
        }
    }
    
    fun updateThemeName(name: String) {
        _themeName.value = name
        viewModelScope.launch {
            settingsDataStore.setThemeName(name)
        }
    }
    
    fun updateThemeMode(mode: String) {
        _themeMode.value = mode
        viewModelScope.launch {
            settingsDataStore.setThemeMode(mode)
        }
    }
    
    private fun persistSettings(newSettings: VisualSettings) {
        _settings.value = newSettings
        viewModelScope.launch {
            settingsDataStore.updateVisualSettings(newSettings)
        }
    }
    
    fun updateFontSize(size: Int) {
        persistSettings(_settings.value.copy(fontSize = size.coerceIn(10, 24)))
    }
    
    fun updateCodeBlockFontSize(size: Int) {
        persistSettings(_settings.value.copy(codeBlockFontSize = size.coerceIn(8, 20)))
    }
    
    fun toggleLineNumbers() {
        persistSettings(_settings.value.copy(showLineNumbers = !_settings.value.showLineNumbers))
    }
    
    
    fun toggleReasoningExpanded() {
        persistSettings(_settings.value.copy(reasoningExpandedByDefault = !_settings.value.reasoningExpandedByDefault))
    }

    fun toggleOpenSubAgentInNewTab() {
        persistSettings(_settings.value.copy(openSubAgentInNewTab = !_settings.value.openSubAgentInNewTab))
    }
    
    fun updateToolWidgetDefaultState(state: String) {
        persistSettings(_settings.value.copy(toolWidgetDefaultState = state))
    }
    
    fun resetToDefaults() {
        persistSettings(VisualSettings())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualSettingsScreen(
    viewModel: VisualSettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val themeName by viewModel.themeName.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    
    val theme = LocalOpenCodeTheme.current
    Scaffold(
        containerColor = theme.background,
        topBar = {
            TuiTopBar(
                title = stringResource(R.string.visual_settings_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    TextButton(onClick = viewModel::resetToDefaults, shape = RectangleShape) {
                        Text(stringResource(R.string.visual_settings_reset))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = stringResource(R.string.visual_settings_theme)) {
                ThemeModeSelector(
                    selected = themeMode,
                    onSelect = viewModel::updateThemeMode
                )
                
                Spacer(Modifier.height(Spacing.md))
                
                ThemeSelector(
                    selected = themeName,
                    options = viewModel.availableThemes,
                    onSelect = viewModel::updateThemeName
                )
            }
            
            SettingsSection(title = stringResource(R.string.visual_settings_text)) {
                FontSizeSlider(
                    label = stringResource(R.string.visual_settings_message_font_size),
                    value = settings.fontSize,
                    onValueChange = viewModel::updateFontSize,
                    range = 10..24
                )
                
                FontSizeSlider(
                    label = stringResource(R.string.visual_settings_code_font_size),
                    value = settings.codeBlockFontSize,
                    onValueChange = viewModel::updateCodeBlockFontSize,
                    range = 8..20
                )
            }
            
            SettingsSection(title = stringResource(R.string.visual_settings_code_display)) {
                SettingsSwitch(
                    title = stringResource(R.string.visual_settings_show_line_numbers),
                    subtitle = stringResource(R.string.visual_settings_show_line_numbers_desc),
                    checked = settings.showLineNumbers,
                    onCheckedChange = { viewModel.toggleLineNumbers() },
                    icon = Icons.Default.FormatListNumbered
                )
                
            }
            
            SettingsSection(title = stringResource(R.string.visual_settings_message_display)) {
                SettingsSwitch(
                    title = stringResource(R.string.visual_settings_expand_reasoning),
                    subtitle = stringResource(R.string.visual_settings_expand_reasoning_desc),
                    checked = settings.reasoningExpandedByDefault,
                    onCheckedChange = { viewModel.toggleReasoningExpanded() },
                    icon = Icons.Default.Psychology
                )
                
                SettingsSwitch(
                    title = stringResource(R.string.visual_settings_open_sub_agent_new_tab),
                    subtitle = stringResource(R.string.visual_settings_open_sub_agent_new_tab_desc),
                    checked = settings.openSubAgentInNewTab,
                    onCheckedChange = { viewModel.toggleOpenSubAgentInNewTab() },
                    icon = Icons.Default.Tab
                )
            }
            
            SettingsSection(title = stringResource(R.string.visual_settings_tool_mode_label)) {
                ToolWidgetStateSelector(
                    selected = settings.toolWidgetDefaultState,
                    onSelect = viewModel::updateToolWidgetDefaultState
                )
                
                Spacer(Modifier.height(Spacing.lg))
                
                // Live preview of all three states
                ToolWidgetPreviewSection(selectedState = settings.toolWidgetDefaultState)
            }
            
            Spacer(Modifier.height(Spacing.md))
            
            PreviewCard(settings = settings)
            
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = theme.accent,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            colors = CardDefaults.cardColors(
                containerColor = theme.backgroundElement
            )
        ) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content
            )
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun FontSizeSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TuiStepper(
            value = value,
            onValueChange = onValueChange,
            range = range,
            step = 1,
            valueLabel = "${value}sp"
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(
    selected: String,
    onSelect: (String) -> Unit
) {
    val modes = listOf(
        "system" to "System",
        "light" to "Light",
        "dark" to "Dark"
    )
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        modes.forEach { (id, label) ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(label) },
                modifier = Modifier.weight(1f),
                shape = RectangleShape
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.visual_settings_color_theme)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Icon(
            icon,
            contentDescription = stringResource(R.string.cd_decorative),
            tint = theme.textMuted
        )
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted
                )
            }
        }
        TuiSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PreviewCard(settings: VisualSettings) {
    val theme = LocalOpenCodeTheme.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.md),
        colors = CardDefaults.cardColors(
            containerColor = theme.backgroundElement
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.visual_settings_preview),
                style = MaterialTheme.typography.titleSmall,
                color = theme.accent
            )
            
            Surface(
                color = theme.accent.copy(alpha = 0.2f),
                shape = RectangleShape
            ) {
                Text(
                    text = stringResource(R.string.visual_settings_sample_message),
                    modifier = Modifier.padding(Spacing.lg),
                    fontSize = settings.fontSize.sp
                )
            }
            
            Surface(
                color = theme.backgroundPanel,
                shape = RectangleShape
            ) {
                Text(
                    "fun example(): String {\n    return \"Hello\"\n}",
                    modifier = Modifier.padding(Spacing.lg),
                    fontSize = settings.codeBlockFontSize.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolWidgetStateSelector(
    selected: String,
    onSelect: (String) -> Unit
) {
    val states = listOf(
        "oneline" to "Oneline (minimal)",
        "compact" to "Compact (summary)",
        "expanded" to "Expanded (full details)"
    )
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.visual_settings_tool_mode_label),
            style = MaterialTheme.typography.bodyMedium
        )
        states.forEach { (id, label) ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape
            )
        }
    }
}

@Composable
private fun ToolWidgetPreviewSection(selectedState: String) {
    val theme = LocalOpenCodeTheme.current

    val now = System.currentTimeMillis()
    val previewTools = remember {
        listOf(
            Part.Tool(
                id = "prev-1", sessionID = "", messageID = "", callID = "prev-1",
                toolName = "read",
                state = ToolState.Completed(
                    input = buildJsonObject { put("filePath", "Theme.kt") },
                    output = "data class OpenCodeTheme(...)",
                    title = "Read Theme.kt",
                    startedAt = now - 120, endedAt = now - 10
                )
            ),
            Part.Tool(
                id = "prev-2", sessionID = "", messageID = "", callID = "prev-2",
                toolName = "read",
                state = ToolState.Completed(
                    input = buildJsonObject { put("filePath", "Colors.kt") },
                    output = "object SemanticColors {...}",
                    title = "Read Colors.kt",
                    startedAt = now - 100, endedAt = now - 8
                )
            ),
            Part.Tool(
                id = "prev-3", sessionID = "", messageID = "", callID = "prev-3",
                toolName = "edit",
                state = ToolState.Completed(
                    input = buildJsonObject {
                        put("filePath", "Theme.kt")
                        put("oldString", "val primary = Color(...)")
                        put("newString", "val primary = theme.primary")
                    },
                    output = "File edited successfully",
                    title = "Edit Theme.kt",
                    startedAt = now - 80, endedAt = now - 5,
                    metadata = buildJsonObject { put("linesAdded", 1); put("linesRemoved", 1) }
                )
            ),
            Part.Tool(
                id = "prev-4", sessionID = "", messageID = "", callID = "prev-4",
                toolName = "bash",
                state = ToolState.Completed(
                    input = buildJsonObject { put("command", "./gradlew build") },
                    output = "BUILD SUCCESSFUL in 8s",
                    title = "./gradlew build",
                    startedAt = now - 60, endedAt = now
                )
            )
        )
    }

    val widgetState = when (selectedState) {
        "oneline"  -> ToolWidgetState.ONELINE
        "compact"  -> ToolWidgetState.COMPACT
        else       -> ToolWidgetState.EXPANDED
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.visual_settings_preview),
            style = MaterialTheme.typography.labelMedium,
            color = theme.textMuted
        )

        ToolGroupWidget(
            tools = previewTools,
            defaultState = widgetState,
            onToolApprove = {},
            onToolDeny = {},
            modifier = Modifier.fillMaxWidth()
        )

        val hint = when (selectedState) {
            "oneline"  -> stringResource(R.string.visual_settings_tap_to_expand)
            "compact"  -> stringResource(R.string.visual_settings_shows_paths)
            else       -> stringResource(R.string.visual_settings_shows_full_output)
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = theme.textMuted
        )
    }
}
