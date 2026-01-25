package cn.edu.buaa.scs.vm.sangfor

class SangforAsyncTaskException(taskId: String, val description: String)
    : Exception("A sangfor async task failed, taskId: $taskId, message:\"$description\"") {
}