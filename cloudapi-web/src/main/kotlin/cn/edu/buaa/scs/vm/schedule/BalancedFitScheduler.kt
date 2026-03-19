package cn.edu.buaa.scs.vm.schedule

import cn.edu.buaa.scs.vm.ScheduleItem
import kotlin.math.abs

internal class BalancedFitScheduler : IScheduler {
    override fun schedule(
        cpu: Int,
        memory: Int,
        diskSize: Long,
        hosts: List<ScheduleItem>
    ): String {
        for (policy in stages) {
            val result = trySchedule(memory, diskSize, hosts, policy)
            if (result != null) return result
        }

        throw NotEnoughResourceForScheduleException("No available host after all scheduling strategies")
    }

    private val stages = listOf(
        SchedulePolicy(0.10, true),
        SchedulePolicy(0.05, true),
        SchedulePolicy(0.0, false)
    )

    private data class SchedulePolicy(
        val minHeadroom: Double,
        val enableImbalancePenalty: Boolean
    )

    private fun getPlatformFactor(platform: String): Double {
        return 1.0
    }

    private fun trySchedule(
        memory: Int,
        diskSize: Long,
        hosts: List<ScheduleItem>,
        policy: SchedulePolicy
    ): String? {
        val candidates = hosts.mapNotNull { item ->
            val host = item.host

            if (!item.canHold(memory, diskSize)) {
                return@mapNotNull null
            }

            val memRemain = host.totalMemMB - host.usedMemMB
            val diskRemain = host.totalStorageBytes - host.usedStorageBytes

            val memAfter = (memRemain - memory) / host.totalMemMB
            val diskAfter = (diskRemain - diskSize).toDouble() / host.totalStorageBytes

            if (memAfter < policy.minHeadroom ||
                diskAfter < policy.minHeadroom
            ) {
                return@mapNotNull null
            }

            val imbalance = abs(memAfter - diskAfter)


            val platformFactor = getPlatformFactor(item.platform)

            val imbalancePenalty =
                imbalance * platformFactor

            item to imbalancePenalty
        }

        if (candidates.isEmpty()) return null

        return candidates.minBy { it.second }.first.platform
    }
}