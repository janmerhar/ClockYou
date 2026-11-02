package com.bnyro.clock.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class HomeLocation(val latitude: Double, val longitude: Double)
