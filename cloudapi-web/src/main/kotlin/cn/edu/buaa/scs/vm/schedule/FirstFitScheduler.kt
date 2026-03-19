package cn.edu.buaa.scs.vm.schedule

import cn.edu.buaa.scs.vm.ScheduleItem

internal class FirstFitScheduler : IScheduler {
    override fun schedule(
        cpu: Int,
        memory: Int,
        diskSize: Long,
        hosts: List<ScheduleItem>
    ): String {
        val platform = hosts.firstOrNull { it.canHold(memory, diskSize) }?.platform
        if (platform == null) {
            throw NotEnoughResourceForScheduleException(
                "Cannot find host that can hold apply with" +
                " cpu: $cpu, memory: $memory, diskSize: $diskSize")
        }

        return platform
    }
}