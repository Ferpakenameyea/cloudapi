package cn.edu.buaa.scs.model

data class Host(
    val ip: String,
    val status: String,
    // bytes
    val totalMem: Double,
    // MB
    val usedMem: Double,
    // Mhz
    val totalCPU: Double,
    // Mhz
    val usedCPU: Double,
    // Bytes
    val totalStorage: Long,
    // Bytes
    val usedStorage: Long,
    val count: Int,
)
