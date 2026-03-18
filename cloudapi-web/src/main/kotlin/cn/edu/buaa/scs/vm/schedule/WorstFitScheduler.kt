package cn.edu.buaa.scs.vm.schedule

import cn.edu.buaa.scs.vm.ScheduleItem
import cn.edu.buaa.scs.vm.cpuWeight
import cn.edu.buaa.scs.vm.diskWeight
import cn.edu.buaa.scs.vm.memoryWeight
import kotlin.math.abs

internal class WorstFitScheduler : IScheduler {
    override fun schedule(
        cpu: Int,
        memory: Int,
        diskSize: Long,
        hosts: List<ScheduleItem>
    ): String {
        for (policy in stages) {
            val result = trySchedule(cpu, memory, diskSize, hosts, policy)
            if (result != null) return result
        }

        throw IllegalStateException("No available host after all scheduling strategies")
    }

    private val stages = listOf(
        SchedulePolicy(0.10, true),
        SchedulePolicy(0.05, true),
        SchedulePolicy(0.0, false)
    )

    private data class SchedulePolicy(
        val minHeadroom: Double,            // 安全水位
        val enableImbalancePenalty: Boolean // 是否启用均衡惩罚
    )

    private fun getPlatformFactor(platform: String): Double {
        return when (platform) {
            "vcenter" -> 1.0
            "sangfor" -> 0.98
            else -> 1.0
        }
    }

    private fun trySchedule(
        cpu: Int,
        memory: Int,
        diskSize: Long,
        hosts: List<ScheduleItem>,
        policy: SchedulePolicy
    ): String? {

        val totalWeight = cpuWeight + memoryWeight + diskWeight

        val candidates = hosts.mapNotNull { item ->
            val host = item.host

            val cpuRemain = host.totalCPUMhz - host.usedCPUMhz
            val memRemain = host.totalMemMB - host.usedMemMB
            val diskRemain = host.totalStorageBytes - host.usedStorageBytes

            // 必须放得下
            if (cpuRemain < cpu || memRemain < memory || diskRemain < diskSize) {
                return@mapNotNull null
            }

            // 放进去之后的剩余比例
            val cpuAfter = (cpuRemain - cpu) / host.totalCPUMhz
            val memAfter = (memRemain - memory) / host.totalMemMB
            val diskAfter = (diskRemain - diskSize).toDouble() / host.totalStorageBytes

            // 安全水位（after）
            if (cpuAfter < policy.minHeadroom ||
                memAfter < policy.minHeadroom ||
                diskAfter < policy.minHeadroom
            ) {
                return@mapNotNull null
            }

            // Worst Fit 核心评分
            val score =
                cpuAfter * (cpuWeight / totalWeight) +
                        memAfter * (memoryWeight / totalWeight) +
                        diskAfter * (diskWeight / totalWeight)

            // 资源均衡惩罚
            val imbalancePenalty = abs(cpuAfter - memAfter) + abs(memAfter - diskAfter)

            // 平台均衡因子
            val platformFactor = getPlatformFactor(item.platform)

            val finalScore =
                score * platformFactor -
                        (if (policy.enableImbalancePenalty) imbalancePenalty * 0.3 else 0.0)

            item to finalScore
        }

        if (candidates.isEmpty()) return null

        return candidates.maxBy { it.second }.first.platform
    }
}

