package com.nekonf.nekostatus.feature.overview

import androidx.lifecycle.ViewModel
import com.nekonf.nekostatus.core.data.ReportingStateStore
import com.nekonf.nekostatus.core.data.SessionRepository
import com.nekonf.nekostatus.core.model.ReportingHealth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class OverviewViewModel
    @Inject
    constructor(
        reportingStateStore: ReportingStateStore,
        sessionRepository: SessionRepository,
    ) : ViewModel() {
        val health: StateFlow<ReportingHealth> = reportingStateStore.health
        val deviceCredential = sessionRepository.deviceCredential
    }
