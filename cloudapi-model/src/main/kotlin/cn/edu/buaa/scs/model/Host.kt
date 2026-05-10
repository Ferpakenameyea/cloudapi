package cn.edu.buaa.scs.model

data class Host(
    val ip: String,
    val status: String,
    val totalMemMB: Double,
    val usedMemMB: Double,
    val totalCPUMhz: Double,
    val usedCPUMhz: Double,
    val totalStorageBytes: Long,
    val usedStorageBytes: Long,
    val count: Int,
    val platform: String // "sangfor" | "vcenter"
)
