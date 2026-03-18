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
import cn.edu.buaa.scs.vm.schedule.IScheduler
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.runBlocking
import org.ktorm.dsl.eq
import org.ktorm.entity.firstOrNull
import java.util.Collections

internal val cpuWeight: Double = application.getConfigString("schedule.weight.cpu", default = "1.0").toDouble()
internal val diskWeight: Double = application.getConfigString("schedule.weight.disk", default = "1.0").toDouble()
internal val memoryWeight: Double = application.getConfigString("schedule.weight.memory", default = "1.0").toDouble()
private val scheduler: IScheduler = IScheduler.getScheduler()

private val alternates: List<AlternateItem> = getAlternates()
private fun getAlternates(): List<AlternateItem> {
    val log = logger("schedule-init")()
    val raw = application.getConfigList("vm.schedule.alternate", default = Collections.emptyList())
    val list = raw.map {
        log.info("Parsing item: {}", it)
        jsonMapper.readValue(it, AlternateItem::class.java)
    }.filter { it.valid }

    log.info("Found {} alternate pairs for scheduling", list.count())

    return list
}

fun reschedule(vmApply: VmApply): String {
    val log = logger("schedule")()
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

    val platform = scheduler.schedule(
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


internal data class ScheduleItem(
    val host: Host,
    val platform: String,
)

internal data class AlternateItem(
    @field:JsonProperty("sangfor_uuid")
    val sangforUuid: String?,
    @field:JsonProperty("vcenter_uuid")
    val vcenterUuid: String?
) {
    val isComplete: Boolean get() = sangforUuid != null && vcenterUuid != null
    val valid: Boolean get() = sangforUuid != null || vcenterUuid != null
}