package com.bms.jbdmanager.trip

internal fun shouldAttemptTripServiceStart(
    tripIsTracking: Boolean,
    serviceIsRunning: Boolean,
    nowMillis: Long,
    lastAttemptAtMillis: Long,
    retryIntervalMillis: Long
): Boolean = tripIsTracking && !serviceIsRunning &&
    nowMillis - lastAttemptAtMillis >= retryIntervalMillis
