package com.bnyro.clock.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class LocationSample(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val timestampMillis: Long)
