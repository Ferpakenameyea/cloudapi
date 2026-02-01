package cn.edu.buaa.scs.vm

import cn.edu.buaa.scs.model.TaskData
import cn.edu.buaa.scs.model.virtualMachines
import cn.edu.buaa.scs.storage.mysql
import cn.edu.buaa.scs.task.Task
import cn.edu.buaa.scs.utils.jsonMapper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import org.ktorm.dsl.eq
import org.ktorm.entity.find

class VMTask(taskData: TaskData) : Task(taskData) {

    enum class Type {
        Create, Delete
    }

    data class Content(
        @JsonProperty("type") var type: Type,
        @JsonProperty("data") var data: String,
    )

    companion object {
        fun vmCreateTask(options: CreateVmOptions): TaskData {
            val data = jsonMapper.writeValueAsString(Content(Type.Create, jsonMapper.writeValueAsString(options)))
            return TaskData.create(Task.Type.VirtualMachine, data)
        }

        fun vmDeleteTask(uuid: String): TaskData {
            val data = jsonMapper.writeValueAsString(Content(Type.Delete, uuid))
            return TaskData.create(Task.Type.VirtualMachine, data)
        }
    }

    override suspend fun internalProcess(): Result<Unit> {
        val content = jsonMapper.readValue<Content>(taskData.data)
        return when (content.type) {
            Type.Create -> {
                // TODO: this seems to be default on vcenter
                val options = jsonMapper.readValue<CreateVmOptions>(content.data)
                vmClient.createVM(options).map { }
            }

            Type.Delete -> {
                val vm = mysql.virtualMachines.find { it.uuid.eq(content.data) }
                if (vm == null) {
                    return Result.success(Unit)
                }
                val client = getVmClient(vm.platform)
                client.deleteVM(content.data)
            }
        }
    }

}