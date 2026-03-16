package cn.edu.buaa.scs.vm

import cn.edu.buaa.scs.application
import cn.edu.buaa.scs.model.Host
import cn.edu.buaa.scs.model.VmApply
import cn.edu.buaa.scs.utils.getConfigString
import kotlinx.coroutines.runBlocking

fun schedule(vmApply: VmApply): String {
    val vcenter = vmClient
    val sangfor = sfClient

    val hosts = mutableListOf<ScheduleItem>()
    runBlocking {
        vcenter.getHosts().onSuccess {
            val mapped = it.map { ScheduleItem(it, "vcenter") }
            hosts.addAll(mapped)
        }
        sangfor.getHosts().onSuccess {
            val mapped = it.map { ScheduleItem(it, "sangfor") }
            hosts.addAll(mapped)
        }
    }

    return scheduleOnPlatforms(
        vmApply.cpu,
        vmApply.memory,
        vmApply.diskSize,
        hosts
    )
}

private fun scheduleOnPlatforms(
    cpu: Int,
    memory: Int,
    diskSize: Long,
    hosts: List<ScheduleItem>
): String {
    val filtered = hosts.filter {
        val host = it.host

        val acceptable = host.totalCPU - host.usedCPU > cpu &&
                host.totalMem - host.usedMem > memory &&
                host.totalStorage - host.usedStorage > diskSize

        acceptable
    }

    return worstFit(filtered)
}

private fun worstFit(filteredHosts: List<ScheduleItem>): String {
    val decidedHost = filteredHosts.maxBy {
        val host = it.host
        val cpuAvailablePercentage = (host.totalCPU - host.usedCPU) / host.totalCPU
        val memoryAvailablePercentage = (host.totalMem - host.usedMem) / host.totalMem
        val storageAvailablePercentage = (host.totalStorage - host.usedStorage) / host.totalStorage

        val score = cpuAvailablePercentage * cpuWeight +
                storageAvailablePercentage * diskWeight +
                memoryAvailablePercentage * memoryWeight

        score
    }

    return decidedHost.platform
}

private val cpuWeight: Double = application.getConfigString("schedule.weight.cpu", default = "1.0").toDouble()
private val diskWeight: Double = application.getConfigString("schedule.weight.disk", default = "1.0").toDouble()
private val memoryWeight: Double = application.getConfigString("schedule.weight.memory", default = "1.0").toDouble()

private data class ScheduleItem(
    val host: Host,
    val platform: String,
)
