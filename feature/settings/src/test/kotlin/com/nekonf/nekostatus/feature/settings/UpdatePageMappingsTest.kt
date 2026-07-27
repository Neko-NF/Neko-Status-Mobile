package com.nekonf.nekostatus.feature.settings

import com.nekonf.nekostatus.core.model.UpdateFailureStage
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePageMappingsTest {
    @Test
    fun `failure stages use accurate titles`() {
        assertEquals(
            R.string.settings_update_status_error_check,
            updateErrorTitleResource(UpdateFailureStage.CHECK),
        )
        assertEquals(
            R.string.settings_update_status_error_download,
            updateErrorTitleResource(UpdateFailureStage.DOWNLOAD),
        )
        assertEquals(
            R.string.settings_update_status_error_verify,
            updateErrorTitleResource(UpdateFailureStage.VERIFY),
        )
        assertEquals(R.string.settings_update_status_error, updateErrorTitleResource(null))
    }

    @Test
    fun `failure stages use accurate safe supporting text`() {
        assertEquals(
            R.string.settings_update_status_error_check_support,
            updateErrorSupportingResource(UpdateFailureStage.CHECK),
        )
        assertEquals(
            R.string.settings_update_status_error_download_support,
            updateErrorSupportingResource(UpdateFailureStage.DOWNLOAD),
        )
        assertEquals(
            R.string.settings_update_status_error_verify_support,
            updateErrorSupportingResource(UpdateFailureStage.VERIFY),
        )
        assertEquals(R.string.settings_update_status_error_support, updateErrorSupportingResource(null))
    }
}
