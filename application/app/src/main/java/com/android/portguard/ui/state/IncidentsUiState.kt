package com.android.portguard.ui.state

import com.android.portguard.data.PortGuardIncident

data class IncidentsUiState(
    val loading: Boolean = false,
    val clearing: Boolean = false,
    val rootGranted: Boolean = false,
    val stateDirectoryFound: Boolean = false,
    val incidentsFileFound: Boolean = false,
    val incidents: List<PortGuardIncident> = emptyList(),
    val statusText: String = "",
    val infoMessage: String? = null,
    val errorMessage: String? = null,
)
