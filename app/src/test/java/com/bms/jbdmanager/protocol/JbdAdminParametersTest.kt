package com.bms.jbdmanager.protocol

import com.bms.jbdmanager.model.JbdProtectionParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试管理员参数
//JbdAdminParametersTest 验证管理员白名单参数的单位换算、补码电流和成对保护关系。
class JbdAdminParametersTest {
    private val params = JbdProtectionParams(
        cellOvervoltageV = 3.65,
        cellOvervoltageReleaseV = 3.55,
        cellUndervoltageV = 2.5,
        cellUndervoltageReleaseV = 2.8,
        packOvervoltageV = 62.05,
        packOvervoltageReleaseV = 60.35,
        packUndervoltageV = 42.5,
        packUndervoltageReleaseV = 45.9,
        chargeHighTempC = 55.0,
        chargeHighTempReleaseC = 50.0,
        chargeLowTempC = 0.0,
        chargeLowTempReleaseC = 5.0,
        dischargeHighTempC = 60.0,
        dischargeHighTempReleaseC = 55.0,
        dischargeLowTempC = -20.0,
        dischargeLowTempReleaseC = -15.0
    )

    @Test
    //MARK:测试单位编码
    //验证电压、温度、容量和放电电流转换为协议原始值。
    fun encodesWhitelistedValues() {
        val encoded = JbdAdminParameters.validateAndEncode(
            params,
            mapOf(0 to 50.0, 20 to 3.60, 8 to 58.0, 25 to 120.0)
        ).getOrThrow()
        assertEquals(5000, encoded[0])
        assertEquals(3600, encoded[20])
        assertEquals(3311, encoded[8])
        assertEquals(0xD120, encoded[25])
    }

    @Test
    //MARK:测试关系校验
    //验证过充恢复值高于保护值时在发送前被拒绝。
    fun rejectsUnsafeThresholdRelationship() {
        assertTrue(JbdAdminParameters.validateAndEncode(params, mapOf(21 to 3.70)).isFailure)
    }

    @Test
    //MARK:测试大容量单位
    //验证功能配置 bit12 开启后，容量和电流使用 0.1Ah/0.1A 单位而不是默认 0.01 单位。
    fun encodesLargeCapacityUnit() {
        val largeUnit = params.copy(rawRegisters = mapOf(29 to 0x1000))
        val encoded = JbdAdminParameters.validateAndEncode(largeUnit, mapOf(0 to 500.0, 24 to 120.0)).getOrThrow()
        assertEquals(5000, encoded[0])
        assertEquals(1200, encoded[24])
    }

    @Test
    //MARK:测试按钮步长
    //验证加减按钮按字段步长精确调节，并消除 Double 连续运算产生的显示尾差。
    fun adjustsValueByConfiguredStep() {
        val cellVoltage = JbdAdminParameters.specs.first { it.register == 20 }
        assertEquals(3.605, JbdAdminParameters.adjustedValue(cellVoltage, 3.600, 1), 0.0)
        assertEquals(3.595, JbdAdminParameters.adjustedValue(cellVoltage, 3.600, -1), 0.0)
    }

    @Test
    //MARK:测试按钮边界
    //验证参数达到安全范围边界后不会因继续点击加减按钮而越界。
    fun clampsAdjustedValueToSafeRange() {
        val balanceDelta = JbdAdminParameters.specs.first { it.register == 27 }
        assertEquals(balanceDelta.minimum, JbdAdminParameters.adjustedValue(balanceDelta, balanceDelta.minimum, -1), 0.0)
        assertEquals(balanceDelta.maximum, JbdAdminParameters.adjustedValue(balanceDelta, balanceDelta.maximum, 1), 0.0)
        assertTrue(runCatching { JbdAdminParameters.adjustedValue(balanceDelta, 0.020, 0) }.isFailure)
    }
}
