package com.notenotes.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchAndSortHeader(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    sortMode: SortMode,
    onSortModeChange: (SortMode) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isDateSort = sortMode == SortMode.DATE_DESC || sortMode == SortMode.DATE_ASC
            val dateLabel = if (sortMode == SortMode.DATE_ASC) "Oldest" else "Newest"
            FilterChip(
                selected = isDateSort,
                onClick = {
                    onSortModeChange(
                        if (sortMode == SortMode.DATE_DESC) SortMode.DATE_ASC else SortMode.DATE_DESC
                    )
                },
                label = { Text(dateLabel) },
                leadingIcon = if (isDateSort) {
                    { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                } else null
            )

            val isTitleSort = sortMode == SortMode.TITLE_AZ || sortMode == SortMode.TITLE_ZA
            val titleLabel = if (sortMode == SortMode.TITLE_ZA) "Z ? A" else "A ? Z"
            FilterChip(
                selected = isTitleSort,
                onClick = {
                    onSortModeChange(
                        if (sortMode == SortMode.TITLE_AZ) SortMode.TITLE_ZA else SortMode.TITLE_AZ
                    )
                },
                label = { Text(titleLabel) },
                leadingIcon = if (isTitleSort) {
                    { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                } else null
            )

            FilterChip(
                selected = sortMode == SortMode.RECENT,
                onClick = { onSortModeChange(SortMode.RECENT) },
                label = { Text("Recent") },
                leadingIcon = if (sortMode == SortMode.RECENT) {
                    { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                } else null
            )
        }
        
        if (isSearchExpanded) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search ideas or folders...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = {
                        onSearchQueryChange("")
                        onSearchExpandedChange(false)
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close search")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )
        }
    }
}
