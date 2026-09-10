package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Test

//MARK:测试健康记录
//CapacityHealthRecordTest 验证 CapacityHealthRecord 的正常流程、边界输入和需要长期保持的回归行为。
class CapacityHealthRecordTest {
    @Test
    //MARK:测试容量
    //验证SOH使用rated容量capturedfrombms场景的关键输出，防止后续修改破坏既有行为。
    fun sohUsesRatedCapacityCapturedFromBms() {
        val record = CapacityHealthRecord(
            id = 1L,
            recordedAtMillis = 1L,
            measuredDischargeAh = 48.0,
            ratedCapacityAh = 60.0
        )

        assertEquals(80.0, record.sohPercent, 0.001)
    }
}
