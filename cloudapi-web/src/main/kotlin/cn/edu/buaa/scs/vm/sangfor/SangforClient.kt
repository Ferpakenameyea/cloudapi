package cn.edu.buaa.scs.vm.sangfor

import cn.edu.buaa.scs.application
import cn.edu.buaa.scs.cache.authRedis

import cn.edu.buaa.scs.error.NotFoundException
import cn.edu.buaa.scs.model.Host
import cn.edu.buaa.scs.model.VirtualMachine
import cn.edu.buaa.scs.model.applySangforExtraInfo
import cn.edu.buaa.scs.utils.getConfigString
import cn.edu.buaa.scs.utils.getValueByKey
import cn.edu.buaa.scs.utils.jsonMapper
import cn.edu.buaa.scs.utils.logger
import cn.edu.buaa.scs.utils.schedule.retry
import cn.edu.buaa.scs.utils.schedule.waitForDone
import cn.edu.buaa.scs.utils.setExpireKey
import cn.edu.buaa.scs.vm.CreateVmOptions
import cn.edu.buaa.scs.vm.IVMClient
import cn.edu.buaa.scs.vm.sangfor.SangforClient.addAuthorization
import cn.edu.buaa.scs.vm.sangfor.SangforClient.configureHeader
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ktorm.jackson.KtormModule
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

object SangforClient : IVMClient {

    internal val username = application.getConfigString("vm.sangfor.username")
    internal val password = application.getConfigString("vm.sangfor.password")

    // admin password is the password of username 'admin'
    // some api needs priority of administrator to continue
    internal val adminPassword = application.getConfigString("vm.sangfor.adminPassword")

    // aCMPAuthToken is for request header
    // Sangfor http api needs header:
    // 'Cookie: aCMPAuthToken=<some string>'
    internal val aCMPAuthToken = UUID.randomUUID().toString()

    private val tokenLock = Mutex()
    private val createLock = Mutex()

    internal val client by lazy {
        HttpClient(CIO) {
            defaultRequest {
                url(application.getConfigString("vm.sangfor.url"))
            }
            install(ContentNegotiation) {
                jackson {
                    registerModule(KtormModule())
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 20_000
            }
            install(HttpRequestRetry) {
                maxRetries = 10
            }
            engine {
                https {
                    trustManager = object: X509TrustManager {
                        override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) { }

                        override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) { }

                        override fun getAcceptedIssuers(): Array<X509Certificate>? = null
                    }
                }
            }
        }
    }

    private suspend fun encryptPassword(password: String): String {
        val publicKeyResponse = client.get("janus/public-key") {
            configureHeader()
        }
        val body = publicKeyResponse.body<String>()
        val publicKeyHex = jsonMapper.readTree(body)
            .get("data")
            .get("public_key")
            .textValue()
            .trim()

        return SangforRSA.encrypt(password, publicKeyHex)
    }

    internal suspend fun connect(user: String, password: String): String {
        val encryptedPassword = encryptPassword(password)

        val requestBody = """
                {
                    "auth": {
                        "passwordCredentials": {
                            "username": "$user",
                            "password": "$encryptedPassword"
                        }
                    }
                }
                """.trimIndent()

        val response = client.post("janus/authenticate") {
            configureHeader()
            setBody(requestBody)
        }

        val body : String = response.body()
        return jsonMapper.readTree(body)
            .get("data")
            .get("access")
            .get("token")
            .get("id")
            .textValue()
    }

    internal suspend fun getToken(): SangforToken {
        authRedis.getValueByKey("sangfor_token")?.let { return SangforToken(it) }
        tokenLock.lock()
        try {
            // 再次检查 Redis，避免其他协程已经写入
            authRedis.getValueByKey("sangfor_token")?.let { return SangforToken(it) }

            val token = connect(username, password)
            authRedis.setExpireKey("sangfor_token", token, 3500)
            return SangforToken(token)
        } finally {
            tokenLock.unlock()
        }
    }

    internal suspend fun getAdminToken(): SangforToken {
        authRedis.getValueByKey("sangfor_admin_token")?.let { return SangforToken(it) }
        tokenLock.lock()
        try {
            // 再次检查 Redis，避免其他协程已经写入
            authRedis.getValueByKey("sangfor_admin_token")?.let { return SangforToken(it) }

            val token = connect("admin", adminPassword)
            authRedis.setExpireKey("sangfor_admin_token", token, 3500)
            return SangforToken(token)
        } finally {
            tokenLock.unlock()
        }
    }

    private suspend fun getHostVmCount(hostId: String, tokenProvider: suspend () -> String): Int {
        val response = client.get("janus/20180725/servers") {
            configureHeader()
            addAuthorization(tokenProvider)
            parameter("page_num", "0")
            parameter("page_size", "1")
            parameter("az_id", hostId)
        }.body<String>()

        return jsonMapper.readTree(response)
            .get("data")
            .get("total_size")
            .intValue()
    }

    private suspend fun getHostResourceStatus(hostId: String, tokenProvider: suspend () -> String): SangforResources {
        val response = client.get("janus/20180725/azs/${hostId}") {
            configureHeader()
            addAuthorization(tokenProvider)
        }.body<String>()

        val data = jsonMapper.readTree(response)
            .get("data")

        val physicalResources = data.get("physical_resources")

        val cpu = physicalResources.first { it.get("name").textValue() == "cpu" }
        val memory = physicalResources.first { it.get("name").textValue() == "memory" }
        val storage = physicalResources.first { it.get("name").textValue() == "storage" }

        return SangforResources(
            totalCPUMhz = cpu.get("total").doubleValue(),
            usedCPUMhz = cpu.get("used").doubleValue(),

            totalMemoryMB = memory.get("total").doubleValue(),
            usedMemoryMB = memory.get("used").doubleValue(),

            totalStorageBytes = storage.get("total").doubleValue().toLong() * 1024 * 1024,
            usedStorageBytes = storage.get("used").doubleValue().toLong() * 1024 * 1024,
        )
    }

    override suspend fun getHosts(): Result<List<Host>> = coroutineScope {
        val token = getAdminToken().id
        val response = client.get("janus/20190725/azs") {
            addAuthorization(token)
            configureHeader()
        }.body<String>()

        val hostJsonArray = jsonMapper.readTree(response)["data"]["data"]

        val deferred = hostJsonArray.map { hostJson ->
            async {
                val hostId = hostJson["id"].textValue()
                val vmCount = getHostVmCount(hostId, suspend { getToken().id })
                val resource = getHostResourceStatus(hostId, suspend { getToken().id })
                Host(
                    // TODO: 暂未找到为深信服资源池获取管理IP的方法
                    ip                  = "sangfor-noip-for-host",
                    status              = hostJson["status"].textValue(),
                    totalMemMB          = resource.totalMemoryMB,
                    usedMemMB           = resource.usedMemoryMB,
                    totalCPUMhz         = resource.totalCPUMhz,
                    usedCPUMhz          = resource.usedCPUMhz,
                    totalStorageBytes   = resource.totalStorageBytes,
                    usedStorageBytes    = resource.usedStorageBytes,
                    count               = vmCount,
                )
            }
        }

        Result.success(deferred.awaitAll())
    }

    override suspend fun getAllVMs(): Result<List<VirtualMachine>> {
        createLock.withLock {
            val token = getToken().id
            val vmsRes = client.get("janus/20180725/servers") {
                configureHeader()
                addAuthorization(token)
            }.body<String>()

            val vmsJsonArray = jsonMapper.readTree(vmsRes)["data"]["data"]

            val list = vmsJsonArray.map{
                VirtualMachine().apply {
                uuid            = it["id"].textValue()
                platform        = "sangfor"
                name            = it["name"].textValue()
                host            = it["host_name"].textValue()
                memory          = it["memory_mb"].intValue()
                cpu             = it["cores"].intValue()
                osFullName      = it["os_name"].textValue()
                diskNum         = it["disks"].size()
                diskSize        = it["disks"].sumOf { disk -> disk["size_mb"].longValue() } * 1048576L
                powerState      = VirtualMachine.PowerState.from(if (it["power_state"].textValue() == "on") "poweredon" else "poweredoff")
                overallStatus   = VirtualMachine.OverallStatus.from("green")
                netInfos        = it["networks"].map { net ->
                    VirtualMachine.NetInfo(
                    macAddress = net["mac_address"].textValue(),
                    ipList = listOf(net["ip_address"].textValue())
                    )
                }
                applySangforExtraInfo(it["description"].textValue())
                }
            }
            return Result.success(list)
        }
    }

    override suspend fun getVM(uuid: String): Result<VirtualMachine> = runCatching {
        // NOTE: Sangfor API does not support querying VM by UUID.
        // This is a fallback implementation based on getAllVMs().

        this.getAllVMs()
            .getOrElse { emptyList() }
            .find { vm ->
                vm.uuid == uuid
            } ?: throw NotFoundException("virtualMachine(uuid=$uuid) not found")
    }

    override suspend fun getVMByName(name: String, applyId: String): Result<VirtualMachine> = runCatching {
        this.getAllVMs()
            .getOrElse { emptyList() }
            .find { vm ->
                vm.name == name && vm.applyId == applyId
            } ?: throw NotFoundException("virtualMachine($name) not found")
    }

    override suspend fun powerOnSync(uuid: String): Result<Unit> {
        powerOnAsync(uuid)
        return waitForDone(50000L, 500L) {
            val token = getToken().id
            val vmRes: String = client.get("janus/20180725/servers/$uuid") {
                configureHeader()
                addAuthorization(token)
            }.body()

            val vmJson = jsonMapper.readTree(vmRes)
            vmJson["data"]["status"].textValue() == "on"
        }
    }

    override suspend fun powerOnAsync(uuid: String) {
        val token = getAdminToken().id
        client.post("janus/20180725/servers/action") {
            configureHeader()
            addAuthorization(token)
            setBody("""
                {
                    "server_ids": ["$uuid"],
                    "server_action": {
                        "start_servers_action": ""
                    }
                }
            """.trimIndent())
        }.also { SangforHttpException.mustBeSuccess(it) }
    }

    override suspend fun powerOffSync(uuid: String): Result<Unit> {
        powerOffAsync(uuid)
        return waitForDone(50000L, 500L) {
            val token = getToken().id
            val vmRes: String = client.get("janus/20180725/servers/$uuid") {
                configureHeader()
                addAuthorization(token)
            }.body()

            val vmJson = jsonMapper.readTree(vmRes)
            vmJson["data"]["status"].textValue() == "off"
        }
    }

    override suspend fun powerOffAsync(uuid: String) {
        val token = getAdminToken().id
        client.post("janus/20180725/servers/action") {
            configureHeader()
            addAuthorization(token)
            setBody("""
                {
                    "server_ids": ["$uuid"],
                    "server_action": {
                        "stop_servers_action": ""
                    }
                }
            """.trimIndent())
        }.also { SangforHttpException.mustBeSuccess(it) }
    }

    // NOTE: Sangfor platform bug make editing impossible
    override suspend fun configVM(
        uuid: String,
        experimentId: Int?,
        adminId: String?,
        teacherId: String?,
        studentId: String?
    ): Result<VirtualMachine> {
        val vmJson = client.get("janus/20180725/servers/$uuid") {
            configureHeader()
            addAuthorization(suspend { getAdminToken().id })
        }.body<String>()

        val vmResponse = jsonMapper.readTree(vmJson)["data"]

        val legacyDescription: String? = vmResponse["description"]?.textValue()
        val infoArray = legacyDescription?.split(',')

        val owner = studentId?.takeIf { it != "default" }
                    ?: teacherId?.takeIf { it != "default" }
                    ?: "default"

        var eid = experimentId
        if (eid == null) {
            eid = infoArray
                ?.takeIf { it.size == 4 }
                ?.get(2)
                ?.toIntOrNull()
                ?: -1
        }

        val applyId = infoArray?.takeIf { it.size == 4 }
            ?.get(3)
            ?: ""

        val description = "$owner,false,$eid,$applyId"

        val task = client.put("janus/20180728/servers/$uuid") {
            configureHeader()
            addAuthorization(suspend { getAdminToken().id })
            setBody("""
                {
                    "description": "$description"
                }
            """.trimIndent())
        }.also { SangforHttpException.mustBeSuccess(it) }
            .bodyAsText()
            .let { jsonMapper.readTree(it) }
            .get("data")
            .get("task_id")
            .textValue()
            .let { SangforAsyncTask(taskId = it, extraData = Unit) }

        task.await(client, suspend { getToken().id })

        return getVM(uuid)
    }

    override suspend fun createVM(options: CreateVmOptions): Result<VirtualMachine> {
        val log = logger("sangfor-vm-create")()
        // the lock needs to surround all, else
        // system might create duplications of same vm
        if (!createLock.tryLock()) {
            return Result.failure(Exception("Another thread is already creating or is reading by another thread"))
        }
        // Send clone vm request.
        val virtualMachineUUID: String
        val asyncTask: SangforAsyncTask<String>

        try {
            val owner = if (options.extraInfo.teacherId != "default") options.extraInfo.teacherId
            else if (options.extraInfo.studentId != "default") options.extraInfo.studentId
            else "default"
            val description = "$owner,false,${options.extraInfo.experimentId},${options.extraInfo.applyId}"
            log.info("cloning template")
            asyncTask = clone(
                getToken().id,
                options.name,
                options.extraInfo.templateUuid,
                description
            )
            val tokenProvider = suspend { getToken().id }
            virtualMachineUUID = asyncTask.extraData
            asyncTask.await(client, tokenProvider)
            log.info("template cloned")
        } finally {
            // put unlock to 'finally' block so that when it fails
            // due to exception the lock will release
            createLock.unlock()
        }

        log.info("waiting for vm to exist and be configurable")
        // wait for vm to exist
        waitForDone(timeout = 60000L * 5, interval = 5000L) {
            isVmExistAndConfigurable(virtualMachineUUID)
        }
        ensurePoweredOff(virtualMachineUUID)
        log.info("vm ready, configuring vm")

        var taskIdNode: JsonNode? = null

        retry(5) {
            waitForDone(timeout = 60000L * 5, interval = 5000L) {
                taskIdNode = client.put("janus/20210725/servers/$virtualMachineUUID") {
                    configureHeader()
                    addAuthorization(suspend { getToken().id })
                    setBody("""
                        {
                            "memory_mb": ${options.memory},
                            "cores": ${options.cpu},
                            "disks": [{
                                "id": "ide0",
                                "type": "new_disk",
                                "preallocate": "off",
                                "size_mb": ${options.diskSize / 1048576L},
                                "is_old_disk": 1,
                                "storage_file": "3600d0231000859694803abfa3b686284:vm-disk-1.qcow2",
                                "use_virtio": 1,
                                "discard": 0
                            }],
                            "networks": [{
                                "connect": 1,
                                "vif_id": "net0",
                                "name": "物理出口1",
                                "device_id": "7008958d-4b99-4ff6-866e-f2d5873f1aa6",
                                "mac_address": "${randomMac()}",
                                "model": "virtio",
                                "port_id": "c89b4b0c-84c2-4e1b-87d0-cd3f3f0a7417"
                            }],
                            "advance_param": {
                                "balloon_memory": 0,
                                "mouse_type": "usb",
                                "use_vblk": 1,
                                "bios": {
                                    "bios_type": "SEABIOS",
                                    "boot_delay_seconds": 0
                                },
                                "real_use_vblk": 1,
                                "timing_sync_vm_clock_enable": 0,
                                "boot_order": "dc",
                                "cpu_hotplug": 1,
                                "use_uuid": 1,
                                "abnormal_recovery": 1,
                                "mem_hotplug": 1,
                                "boot_disk": "ide0",
                                "invtsc": 0,
                                "schedopt": 0,
                                "hugepage_memory": 0,
                                "cpu_exclusive": 0,
                                "sandbox": 0,
                                "cpu_type": "core2duo",
                                "graphic_type": "cirrus",
                                "onboot": 0,
                                "dir": "71dc87680938",
                                "numa": 1
                            }
                        }
                    """.trimIndent())
                }.bodyAsText()
                    .apply { log.info(this) }
                    .let { jsonMapper.readTree(it) }
                    .get("data")
                    .get("task_id")
                taskIdNode != null
            }.getOrThrow()
        }

        log.info("vm configured, waiting for task done")

        taskIdNode!!
            .textValue()
            .let { SangforAsyncTask(taskId = it, extraData = Unit) }
            .apply { await(client, suspend { getToken().id }) }

        log.info("vm configure success")

        if (options.powerOn) {
            powerOnAsync(virtualMachineUUID)
        }

        log.info("done")

        return getVM(virtualMachineUUID)
    }

    fun randomMac(): String {
        val mac = ByteArray(6)
        Random.nextBytes(mac)

        mac[0] = (mac[0].toInt() and 0b11111110).toByte()

        mac[0] = (mac[0].toInt() or 0b00000010).toByte()

        return mac.joinToString(":") {
            "%02x".format(it)
        }
    }

    private suspend fun isVmExistAndConfigurable(virtualMachineUUID: String): Boolean {
        val response = client.get("janus/20180725/servers/$virtualMachineUUID") {
            configureHeader()
            addAuthorization(suspend { getToken().id })
        }

        return if (!response.status.isSuccess()) {
            false
        } else {
            val json = response.body<String>()
            val jsonObject = jsonMapper.readTree(json)
            return jsonObject["status"].textValue() == "stopped"
        }
    }

    override suspend fun deleteVM(uuid: String): Result<Unit> {
        ensurePoweredOff(uuid)
        val response = client.delete("/janus/20180725/servers/$uuid") {
            configureHeader()
            addAuthorization(suspend { getToken().id })

            // soft delete
            setBody("""
                {"force":0}
            """.trimIndent())
        }

        if (!response.status.isSuccess()) {
            val exception = SangforHttpException(response.status, response.bodyAsText())
            return Result.failure(exception)
        }

        return Result.success(Unit)
    }

    override suspend fun convertVMToTemplate(uuid: String): Result<VirtualMachine> {
        ensurePoweredOff(uuid)

        val vmRes = client.get("janus/20180725/servers/$uuid") {
            addAuthorization(getToken().id)
            configureHeader()
        }.also { SangforHttpException.mustBeSuccess(it) }
            .bodyAsText()
            .let { jsonMapper.readTree(it) }

        val description = vmRes["data"]["description"].textValue()
        val infoArray = description.split(',')

        val newDescription =
            if (infoArray.size == 4) "${infoArray[0]},true,${infoArray[2]},${infoArray[3]}"
            else "default,true,-1,default"

        val task = client.put("janus/20180725/servers/$uuid") {
            addAuthorization(getToken().id)
            configureHeader()
            setBody("""
                {
                    "description": "$newDescription"
                }
            """.trimIndent())
        }.also { SangforHttpException.mustBeSuccess(it) }
            .bodyAsText()
            .let { jsonMapper.readTree(it) }
            .get("data")
            .get("task_id")
            .textValue()
            .let { SangforAsyncTask(taskId = it, extraData = Unit) }

        task.await(client, suspend { getToken().id })

        return getVM(uuid)
    }

    private suspend fun ensurePoweredOff(uuid: String) {
        val vm = getVM(uuid)
        vm.fold(onSuccess = {
            if (it.powerState == VirtualMachine.PowerState.PoweredOn)
            {
                powerOffSync(it.uuid)
            }
        }, onFailure = { throw SangforHttpException(HttpStatusCode.NotFound, "no machine with id $uuid found")})
    }

    suspend fun clone(tokenString: String,
                      name: String,
                      templateUuid: String,
                      description: String
    ): SangforAsyncTask<String> {
        val response = client.post("janus/20180725/servers/$templateUuid/clone") {
            configureHeader()
            addAuthorization(tokenString)
            setBody("""
                {
                    "name": "$name",
                    "power_on": 0,
                    "count": 1,
                    "description": "$description",
                    "storage_tag_id": "11111111-1111-1111-1111-111111111111",
                    "advance_param": {
                        "return_uuids": 1
                    },
                    "clone_type": "full"
                }
            """.trimIndent())
        }.also { SangforHttpException.mustBeSuccess(it) }

        val responseJson = jsonMapper.readTree(response.body<String>())
        return SangforAsyncTask(
            taskId = responseJson["data"]["task_id"].textValue(),
            extraData = responseJson["data"]["uuids"].get(0).textValue())
    }

    internal fun HttpRequestBuilder.configureHeader() {
        contentType(ContentType.Application.Json)
        header("Cookie", "aCMPAuthToken=$aCMPAuthToken")
    }

    internal fun HttpRequestBuilder.addAuthorization(token :String) {
        header("Authorization", "Token $token")
    }

    internal fun HttpRequestBuilder.addAuthorization(tokenProvider : suspend () -> String) {
        addAuthorization(runBlocking { tokenProvider() })
    }
}

data class SangforToken(val id: String)
data class SangforAsyncTask<TData>(val taskId: String, val extraData :TData) {
    suspend fun await(
        client: HttpClient,
        tokenProvider: suspend () -> String): JsonNode {

        val log = logger("sangfor-task")()

        val queryTask = suspend {
            val taskQueryResponse = client.get("janus/20180725/tasks/$taskId") {
                configureHeader()
                addAuthorization(tokenProvider)
            }.also { SangforHttpException.mustBeSuccess(it) }

            jsonMapper.readTree(taskQueryResponse.body<String>())["data"]
        }

        var taskQueryData: JsonNode = queryTask()
        log.info(taskQueryData.toPrettyString())
        when (taskQueryData["status"].textValue()) {
            "finish" -> return taskQueryData
            "failure" -> throw SangforAsyncTaskException(
                taskId,
                taskQueryData["description"].textValue()
            )
        }

        waitForDone(timeout = 60000L * 60, interval = 500L) {
            taskQueryData = queryTask()
            val status = taskQueryData["status"].textValue()
            log.info(taskQueryData.toPrettyString())
            if (status == "failure") {
                throw SangforAsyncTaskException(
                    taskId,
                    taskQueryData["description"].textValue()
                )
            }

            status == "finish"
        }.getOrThrow()

        return taskQueryData
    }
}

private data class SangforResources(
    val totalMemoryMB: Double,
    val usedMemoryMB: Double,
    val totalCPUMhz: Double,
    val usedCPUMhz: Double,
    val totalStorageBytes: Long,
    val usedStorageBytes: Long
)

internal object SangforRSA {
    private fun buildPublicKey(modulusHex: String): RSAPublicKey {
        val modulus = BigInteger(modulusHex, 16)   // OK：正数
        val exponent = BigInteger("10001", 16)     // 固定

        val spec = RSAPublicKeySpec(modulus, exponent)
        val factory = KeyFactory.getInstance("RSA")
        return factory.generatePublic(spec) as RSAPublicKey
    }


    private fun rsaEncryptToHex(
        plainText: String,
        publicKey: RSAPublicKey
    ): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            publicKey,
            SecureRandom()
        )

        val encrypted = cipher.doFinal(
            plainText.toByteArray(Charsets.UTF_8)
        )

        return encrypted.joinToString("") {
            "%02x".format(it)
        }
    }

    fun encrypt(text: String, publicKeyHex: String): String {
        val publicKey = buildPublicKey(publicKeyHex)
        val encrypted = rsaEncryptToHex(text, publicKey)

        return encrypted
    }
}
