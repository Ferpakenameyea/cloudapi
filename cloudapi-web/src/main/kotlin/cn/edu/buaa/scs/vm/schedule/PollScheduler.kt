package cn.edu.buaa.scs.vm.schedule

import cn.edu.buaa.scs.vm.ScheduleItem
import okhttp3.internal.immutableListOf

internal class PollScheduler : IScheduler {

    private companion object {
        val platforms: List<String> = immutableListOf("vcenter", "sangfor")
        var index = 0

        fun next(): String {
            val result = platforms[index]
            index = (index + 1) % platforms.size
            return result
        }
    }

    override fun schedule(
        cpu: Int,
        memory: Int,
        diskSize: Long,
        hosts: List<ScheduleItem>
    ): String {
        return next()
    }
}