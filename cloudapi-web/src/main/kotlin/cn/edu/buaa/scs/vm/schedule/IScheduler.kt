package cn.edu.buaa.scs.vm.schedule

import cn.edu.buaa.scs.application
import cn.edu.buaa.scs.utils.getConfigString
import cn.edu.buaa.scs.utils.logger
import cn.edu.buaa.scs.vm.ScheduleItem
import java.util.Collections

private val schedulerMap: Map<String, () -> IScheduler> = initSchedulerMap()

internal interface IScheduler {
    fun schedule(cpu: Int, memory: Int, diskSize: Long, hosts: List<ScheduleItem>): String

    companion object {
        fun getScheduler(): IScheduler {
            val log = logger("schedule-init")()
            val policy = application.getConfigString("schedule.policy", default = "worst_fit")
            val provider = schedulerMap[policy]
            if (provider == null) {
                log.warn("Unrecognized schedule policy id: {}, registered schedulers are: {}",
                    policy,
                    schedulerMap.keys.joinToString(separator = ", "))
                log.warn("Using worst fit schedule as fallback")
                return WorstFitScheduler()
            }

            return provider()
        }
    }
}

private fun initSchedulerMap(): Map<String, () -> IScheduler> {
    val map = HashMap<String, () -> IScheduler>()
    map["worst_fit"] = { WorstFitScheduler() }
    map["best_fit"] = { BestFitScheduler() }
    map["first_fit"] = { FirstFitScheduler() }
    map["balanced_fit"] = { BalancedFitScheduler() }

    return Collections.unmodifiableMap(map)
}