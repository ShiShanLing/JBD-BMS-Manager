package com.bms.jbdmanager.trip

//MARK:判断行程
//shouldAttemptTripServiceStart 计算或控制行程，并保持 BMS 行程与纯 GPS 行程的数据边界。
internal fun shouldAttemptTripServiceStart(
    tripIsTracking: Boolean,
    serviceIsRunning: Boolean,
    nowMillis: Long,
    lastAttemptAtMillis: Long,
    retryIntervalMillis: Long
): Boolean = tripIsTracking && !serviceIsRunning &&
    nowMillis - lastAttemptAtMillis >= retryIntervalMillis
