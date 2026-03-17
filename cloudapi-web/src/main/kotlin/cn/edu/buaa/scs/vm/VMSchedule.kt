package cn.edu.buaa.scs.vm

import cn.edu.buaa.scs.application
import cn.edu.buaa.scs.model.Host
import cn.edu.buaa.scs.model.VmApply
import cn.edu.buaa.scs.model.virtualMachines
import cn.edu.buaa.scs.storage.mysql
import cn.edu.buaa.scs.utils.getConfigList
import cn.edu.buaa.scs.utils.getConfigString
import cn.edu.buaa.scs.utils.jsonMapper
import cn.edu.buaa.scs.utils.logger
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.runBlocking
import org.ktorm.dsl.eq
import org.ktorm.entity.firstOrNull
import java.util.Collections
import kotlin.math.abs

private val alternates: List<AlternateItem> = getAlternates()
private val log = logger("schedule")()
private fun getAlternates(): List<AlternateItem> {
    val raw = application.getConfigList("vm.schedule.alternate", default = Collections.emptyList())
    val list = raw.map {
        jsonMapper.readValue(it, AlternateItem::class.java)
    }.filter { it.valid }

    log.info("Found {} alternate pairs for scheduling", list.count())

    return list
}

fun reschedule(vmApply: VmApply): String {
    val templateUuid = vmApply.templateUuid
    val alternate = alternates.firstOrNull { it.vcenterUuid == templateUuid || it.sangforUuid == templateUuid }
    val templateVm = mysql.virtualMachines
        .firstOrNull { it.uuid.eq(templateUuid) }
    if (templateVm == null) {
        throw IllegalArgumentException("Cannot find template with uuid $templateUuid")
    }
    log.info("Rescheduling for vm application id: {}, description: {}. original with template name: {} (id: {})",
        vmApply.id,
        vmApply.description,
        templateVm.name,
        templateUuid
    )

    // 如果没有替代（没有调度的可能，直接原平台运行）
    if (alternate == null || !alternate.isComplete) {
        log.warn("No schedule alternate found for vm application id: {}, description: {}, using original platform." +
        " add scheduling pairs in application.conf for scheduling between platforms.",
            vmApply.id,
            vmApply.description)
        return templateVm.platform
    }

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

    val platform = worstFitSchedule(
        vmApply.cpu,
        vmApply.memory,
        vmApply.diskSize,
        hosts
    )

    // 前面检查了 !alternate.isComplete 所以必定不能为 null
    val newTemplateUuid: String = (if (platform == "sangfor") alternate.sangforUuid else alternate.vcenterUuid)!!

    // 转移到新平台上，使用替代镜像
    if (vmApply.templateUuid != newTemplateUuid) {
        log.info("Rescheduling apply (id: {})(description: {}) from template {} to template {}",
            vmApply.id,
            vmApply.description,
            vmApply.templateUuid,
            newTemplateUuid)
        vmApply.templateUuid = newTemplateUuid
        vmApply.flushChanges()
    }
    return platform
}

// 三阶段 fallback（逐渐放宽限制）
private val stages = listOf(
    SchedulePolicy(0.10, true),
    SchedulePolicy(0.05, true),
    SchedulePolicy(0.0, false)
)

private fun worstFitSchedule(
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

private val cpuWeight: Double = application.getConfigString("schedule.weight.cpu", default = "1.0").toDouble()
private val diskWeight: Double = application.getConfigString("schedule.weight.disk", default = "1.0").toDouble()
private val memoryWeight: Double = application.getConfigString("schedule.weight.memory", default = "1.0").toDouble()

private data class ScheduleItem(
    val host: Host,
    val platform: String,
)

data class AlternateItem(
    @field:JsonProperty("sangfor_uuid")
    val sangforUuid: String?,
    @field:JsonProperty("vcenter_uuid")
    val vcenterUuid: String?
) {
    val isComplete: Boolean get() = sangforUuid != null && vcenterUuid != null
    val valid: Boolean get() = sangforUuid != null || vcenterUuid != null
}

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