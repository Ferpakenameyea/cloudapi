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
import cn.edu.buaa.scs.utils.schedule.waitForDone
import cn.edu.buaa.scs.utils.setExpireKey
import cn.edu.buaa.scs.vm.CreateVmOptions
import cn.edu.buaa.scs.vm.IVMClient
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
import kotlinx.coroutines.sync.Mutex
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

object SangforClient : IVMClient {

    val username = application.getConfigString("vm.sangfor.username")
    val password = application.getConfigString("vm.sangfor.password")
    val adminPassword = application.getConfigString("vm.sangfor.adminPassword")
    val aCMPAuthToken = UUID.randomUUID().toString()

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
            contentType(ContentType.Application.Json)
            header("aCMPAuthToken", aCMPAuthToken)
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
            contentType(ContentType.Application.Json)
            header("Cookie", "aCMPAuthToken=$aCMPAuthToken")
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

    internal suspend fun getToken(): Token {
        authRedis.getValueByKey("sangfor_token")?.let { return Token(it) }
        tokenLock.lock()
        try {
            // 再次检查 Redis，避免其他协程已经写入
            authRedis.getValueByKey("sangfor_token")?.let { return Token(it) }

            val token = connect(username, password)
            authRedis.setExpireKey("sangfor_token", token, 3500)
            return Token(token)
        } finally {
            tokenLock.unlock()
        }
    }

    internal suspend fun getAdminToken(): Token {
        authRedis.getValueByKey("sangfor_admin_token")?.let { return Token(it) }
        tokenLock.lock()
        try {
            // 再次检查 Redis，避免其他协程已经写入
            authRedis.getValueByKey("sangfor_admin_token")?.let { return Token(it) }

            val token = connect("admin", adminPassword)
            authRedis.setExpireKey("sangfor_admin_token", token, 3500)
            return Token(token)
        } finally {
            tokenLock.unlock()
        }
    }

    private suspend fun getHostVmCount(hostId: String, token: String): Int {
        val response = client.get("janus/20180725/servers") {
            header("Authorization", "Token $token")
            header("Cookie", "aCMPAuthToken=$aCMPAuthToken")
            contentType(ContentType.Application.Json)
            parameter("page_num", "0")
            parameter("page_size", "1")
            parameter("host_id", hostId)
        }.body<String>()

        return jsonMapper.readTree(response)
            .get("data")
            .get("total_size")
            .intValue()
    }

    override suspend fun getHosts(): Result<List<Host>> = coroutineScope {
        val token = getAdminToken().id
        val response = client.get("janus/20180725/hosts") {
            header("Authorization", "Token $token")
            header("Cookie", "aCMPAuthToken=$aCMPAuthToken")
            contentType(ContentType.Application.Json)
        }.body<String>()

        val hostJsonArray = jsonMapper.readTree(response)["data"]["data"]

        val deferred = hostJsonArray.map { hostJson ->
            async {
                val hostId = hostJson["id"].textValue()
                val vmCount = getHostVmCount(hostId, token)
                Host(
                    ip           = hostJson["ip"].textValue(),
                    status       = hostJson["status"].textValue(),
                    totalMem     = hostJson["memory"]["total_mb"].doubleValue(),
                    usedMem      = hostJson["memory"]["used_mb"].doubleValue(),
                    totalCPU     = hostJson["cpu"]["total_mhz"].doubleValue(),
                    usedCPU      = hostJson["cpu"]["used_mhz"].doubleValue(),
                    totalStorage = hostJson["storage"]["total_mb"].doubleValue().toLong(),
                    usedStorage  = 0L,
                    count        = vmCount
                )
            }
        }

        Result.success(deferred.awaitAll())
    }

    override suspend fun getAllVMs(): Result<List<VirtualMachine>> {
        val token = getToken().id
        val vmsRes = client.get("janus/20180725/servers") {
            header("Cookie", "aCMPAuthToken=$aCMPAuthToken")
            header("Authorization", "Token $token")
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
                        macAddress = net["mac"].textValue(),
                        ipList = listOf(net["ip"].textValue())
                    )
                }
                applySangforExtraInfo(it["description"].textValue())
            }
        }

        return Result.success(list)
    }

    override suspend fun getVM(uuid: String): Result<VirtualMachine> {
        val token = getToken().id
        val vmRes = client.get("janus/20180725/servers/$uuid") {
            header("Cookie", "aCMPAuthToken=${SangforClient.aCMPAuthToken}")
            header("Authorization", "Token $token")
        }.body<String>()

        val data = jsonMapper.readTree(vmRes)["data"]

        val vm = data.let { VirtualMachine().apply {
            this.uuid = uuid
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
                    macAddress = net["mac"].textValue(),
                    ipList = listOf(net["ip"].textValue())
                )
            }
            applySangforExtraInfo(it["description"].textValue())
        }}

        return Result.success(vm)
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
                contentType(ContentType.Application.Json)
                header("Authorization", "Token $token")
                header("Cookie", "aCMPAuthToken=$aCMPAuthToken")
            }.body()

            val vmJson = jsonMapper.readTree(vmRes)
            vmJson["data"]["status"].textValue() == "on"
        }
    }

    override suspend fun powerOnAsync(uuid: String) {
        val token = getAdminToken().id
        val response = client.post("janus/20180725/servers/action") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Token $token")
            header("Cookie", "aCMPAuthToken=$aCMPAuthToken")
            setBody("""
                {
                    "server_ids": ["$uuid"],
                    "server_action": {
                        "start_servers_action": ""
                    }
                }
            """.trimIndent())
        }

        if (!response.status.isSuccess()) {
            throw SangforHttpExcetion(
                response.status,
                response.bodyAsText()
            )
        }
    }

    override suspend fun powerOffSync(uuid: String): Result<Unit> {
        powerOffAsync(uuid)
        return waitForDone(50000L, 500L) {
            val token = getToken().id
            val vmRes: String = client.get("janus/20180725/servers/$uuid") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Token $token")
                header("Cookie", "aCMPAuthToken=$aCMPAuthToken")
            }.body()

            val vmJson = jsonMapper.readTree(vmRes)
            vmJson["data"]["status"].textValue() == "off"
        }
    }

    override suspend fun powerOffAsync(uuid: String) {
        val token = getAdminToken().id
        val response = client.post("janus/20180725/servers/action") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Token $token")
            header("Cookie", "aCMPAuthToken=$aCMPAuthToken")
            setBody("""
                {
                    "server_ids": ["$uuid"],
                    "server_action": {
                        "stop_servers_action": ""
                    }
                }
            """.trimIndent())
        }

        if (!response.status.isSuccess()) {
            throw SangforHttpExcetion(
                response.status,
                response.bodyAsText()
            )
        }
    }

    /*
    override suspend fun configVM(
        uuid: String,
        experimentId: Int?,
        adminId: String?,
        teacherId: String?,
        studentId: String?
    ): Result<VirtualMachine> {
        val token = getToken()
        val vmRes: String = client.get("admin/view/server-info?id=$uuid") {
            header("Cookie", "aCMPAuthToken=${token.id}")
        }.body()
        val vmJSON = jsonMapper.readTree(vmRes)["data"]
        val oldSetting = OldSetting(
            vmJSON["name"].toString().split('\"')[1],
            vmJSON["description"].toString().split('\"')[1],
            vmJSON["memory_mb"].intValue(),
            vmJSON["cores"].intValue(),
            vmJSON["data"]["disks"].map {
                it["size_mb"].longValue()
            }.reduce { s, s1 -> s + s1 },
            vmJSON["networks"][0]["mac"].toString().split('\"')[1].lowercase(),
            vmJSON["os_type"].toString().split('\"')[1],
        )
        var owner = "default"
        teacherId?.let {
            if (it != "default") owner = it
        }
        studentId?.let {
            if (it != "default") owner = it
        }
        val info = oldSetting.description.split(',')
        var eid = experimentId
        if (eid == null) {
            eid = if (info.size == 4) info[2].toInt() else -1
        }
        var applyId = ""
        if (info.size == 4) applyId = info[3]
        val description = "$owner,false,$eid,$applyId"
        initSettings(
            uuid,
            oldSetting.name,
            description,
            oldSetting.memory,
            oldSetting.cores,
            oldSetting.disk,
            oldSetting
        )
        return getVM(uuid)
    }
    */

    override suspend fun configVM(
        uuid: String,
        experimentId: Int?,
        adminId: String?,
        teacherId: String?,
        studentId: String?
    ): Result<VirtualMachine> {
        throw NotImplementedError("SangforClient.configVM is not implemented")
    }

    /*
    override suspend fun createVM(options: CreateVmOptions): Result<VirtualMachine> {
        createLock.lock()
        // Send clone vm request.

        val owner = if (options.extraInfo.teacherId != "default") options.extraInfo.teacherId
                    else if (options.extraInfo.studentId != "default") options.extraInfo.studentId
                    else "default"
        val description = "$owner,false,${options.extraInfo.experimentId},${options.extraInfo.applyId}"
        clone(
            options.name,
            options.extraInfo.templateUuid,
            description
        )
        // Wait the creation be done.
        var token: Token
        var uuid = ""
        waitForDone(20000L, 500L) {
            token = getToken()
            val vmsRes: String = client.get("openstack/compute/v2/servers/detail") {
                header("X-Auth-Token", token.id)
            }.body()
            val vms = jsonMapper.readTree(vmsRes)["servers"]
            for (vmJSON in vms) {
                if (vmJSON["OS-EXT-STS:task_state"].toString() == "\"creating\"") {
                    uuid = vmJSON["id"].toString().split('\"')[1]
                }
            }
            uuid != ""
        }
        waitForDone(300000L, 1000L) {
            token = getToken()
            val vmRes: String = client.get("openstack/compute/v2/servers/$uuid") {
                header("X-Auth-Token", token.id)
            }.body()
            jsonMapper.readTree(vmRes)["server"]["OS-EXT-STS:task_state"].toString() == "\"\""
        }
        createLock.unlock()
        // Initialize settings of the new virtual machine.
        token = getToken()
        val vmRes: String = client.get("admin/view/server-info?id=$uuid") {
            header("Cookie", "aCMPAuthToken=${token.id}")
        }.body()
        val vmJSON = jsonMapper.readTree(vmRes)["data"]
        println(vmJSON.toString())
        val oldSetting = OldSetting(
            vmJSON["name"].toString().split('\"')[1],
            vmJSON["description"].toString().split('\"')[1],
            vmJSON["memory_mb"].intValue(),
            vmJSON["cores"].intValue(),
            vmJSON["disks"].map {
                it["size_mb"].longValue()
            }.reduce { s, s1 -> s + s1 },
            vmJSON["networks"][0]["mac"].toString().split('\"')[1].lowercase(),
            vmJSON["os_type"].toString().split('\"')[1],
        )
        initSettings(
            uuid,
            oldSetting.name,
            oldSetting.description,
            options.memory,
            options.cpu,
            options.diskSize / 1048576L,
            oldSetting
        )
        if(options.powerOn) powerOnAsync(uuid)
        return getVM(uuid)
    }
    */

    override suspend fun createVM(options: CreateVmOptions): Result<VirtualMachine> {
        throw NotImplementedError("SangforClient.createVM is not implemented")
    }

    /*
    /* The virtual machine must be powered off. */
    override suspend fun deleteVM(uuid: String): Result<Unit> {
        /* 成功：204，失败：409 */
        val token = getToken()
        val resCode = client.delete("openstack/compute/v2/servers/$uuid") {
            contentType(ContentType.Application.Json)
            header("X-Auth-Token", token.id)
        }.status.value
        return Result.success(Unit)
    }
    */

    override suspend fun deleteVM(uuid: String): Result<Unit> {
        throw NotImplementedError("SangforClient.deleteVM is not implemented")
    }

    /*
    /* The virtual machine must be powered off. */
    override suspend fun convertVMToTemplate(uuid: String): Result<VirtualMachine> {
        val token = getToken()
        val vmRes: String = client.get("admin/view/server-info?id=$uuid") {
            header("Cookie", "aCMPAuthToken=${token.id}")
        }.body()
        val vmJSON = jsonMapper.readTree(vmRes)["data"]
        val oldSetting = OldSetting(
            vmJSON["name"].toString().split('\"')[1],
            vmJSON["description"].toString().split('\"')[1],
            vmJSON["memory_mb"].intValue(),
            vmJSON["cores"].intValue(),
            vmJSON["data"]["disks"].map {
                it["size_mb"].longValue()
            }.reduce { s, s1 -> s + s1 },
            vmJSON["networks"][0]["mac"].toString().split('\"')[1].lowercase(),
            vmJSON["os_type"].toString().split('\"')[1],
        )
        val info = vmJSON["description"].toString().split('\"')[1].split(',')
        var description = "default,true,-1,"
        if (info.size == 4) description = "${info[0]},true,${info[2]},${info[3]}"
        initSettings(
            uuid,
            oldSetting.name,
            description,
            oldSetting.memory,
            oldSetting.cores,
            oldSetting.disk,
            oldSetting
        )
        return getVM(uuid)
    }
    */

    override suspend fun convertVMToTemplate(uuid: String): Result<VirtualMachine> {
        throw NotImplementedError("SangforClient.convertVMToTemplate is not implemented")
    }

    /*
    suspend fun clone(name: String,
                      templateUuid: String,
                      description: String
    ) {
        val token = getToken()
        val resCode = client.post("admin/servers/$templateUuid/clone-servers") {
            contentType(ContentType.Application.Json)
            header("Cookie", "aCMPAuthToken=${token.id}")
            header("CSRFPreventionToken", token.ticket)
            header("sid", token.sid)
            setBody(
                """
                {
                    "batch_server_info": {
                        "name": "$name",
                        "description": "$description",
                        "location_type": "storage_tag",
                        "location": {
                            "storage_tag_id": "11111111-1111-1111-1111-111111111111"
                        },
                        "power_on": 0,
                        "hci_param": {},
                        "count": 1
                    }
                }
                """.trimIndent()
            )
        }.status.value
    }
    */

    suspend fun clone(name: String,
                      templateUuid: String,
                      description: String
    ) {
        throw NotImplementedError("SangforClient.clone is not implemented")
    }

    /*
    /* The virtual machine must be powered off. */
    suspend fun initSettings(uuid: String,
                               name: String,
                               description: String,
                               memory: Int,
                               cores: Int,
                               disk: Long,
                               oldSetting: OldSetting
    ) {
        val token = getToken()
        val resCode = client.put("admin/servers/$uuid") {
            contentType(ContentType.Application.Json)
            header("Cookie", "aCMPAuthToken=${token.id}")
            header("CSRFPreventionToken", token.ticket)
            header("sid", token.sid)
            setBody(
                """
                {
                    "server": {
                        "hci_param": {
                            "schedopt": 0,
                            "hugepage_memory": 0,
                            "use_vblk": 1,
                            "boot_order": "dc",
                            "cpu_hotplug": 0,
                            "balloon_memory": 0,
                            "use_uuid": 0,
                            "abnormal_recovery": 1,
                            "mem_hotplug": 0,
                            "cpu_type": "core2duo",
                            "real_use_vblk": 1,
                            "onboot": 0,
                            "dir": "71dc87680938",
                            "boot_disk": "ide0",
                            "regen_uuid": 0
                        },
                        "name": "$name",
                        "description": "$description",
                        "memory_mb": $memory,
                        "cores": $cores,
                        "sockets": 1,
                        "disks": [
                            {
                                "id": "ide0",
                                "type": "new_disk",
                                "preallocate": "metadata",
                                "size_mb": $disk,
                                "is_old_disk": 1,
                                "storage_file": "3600d0231000859694803abfa3b686284:vm-disk-1.qcow2"
                            }
                        ],
                        "networks": [
                            {
                                "network": "dvs66d81f0",
                                "name": "默认经典网络出口1-出口交换机",
                                "id": "net0",
                                "mac": "${oldSetting.mac}",
                                "connect": 1,
                                "model": "virtio",
                                "port": "12345678",
                                "host_tso": 0
                            }
                        ],
                        "usbs": [],
                        "os_type": "${oldSetting.osType}",
                        "compute_location": {
                            "id": "cluster",
                            "location": 0
                        },
                        "storage_location": "3600d0231000859694803abfa3b686284",
                        "cdroms": []
                    },
                    "old_server": {
                        "hci_param": {
                            "schedopt": 0,
                            "hugepage_memory": 0,
                            "use_vblk": 1,
                            "boot_order": "dc",
                            "cpu_hotplug": 0,
                            "balloon_memory": 0,
                            "use_uuid": 0,
                            "abnormal_recovery": 1,
                            "mem_hotplug": 0,
                            "cpu_type": "core2duo",
                            "real_use_vblk": 1,
                            "onboot": 0,
                            "dir": "71dc87680938",
                            "boot_disk": "ide0"
                        },
                        "name": "${oldSetting.name}",
                        "group_id": null,
                        "description": "${oldSetting.description}",
                        "memory_mb": ${oldSetting.memory},
                        "cores": ${oldSetting.cores},
                        "sockets": 1,
                        "disks": [
                            {
                                "preallocate": "metadata",
                                "storage_name": "iscsi",
                                "id": "ide0",
                                "size_mb": ${oldSetting.disk},
                                "use_virtio": 1,
                                "storage_file": "3600d0231000859694803abfa3b686284:vm-disk-1.qcow2",
                                "type": "new_disk",
                                "is_old_disk": 1
                            }
                        ],
                        "networks": [
                            {
                                "id": "net0",
                                "host_tso": 0,
                                "mac": "${oldSetting.mac}",
                                "model": "virtio",
                                "connect": 0
                            }
                        ],
                        "usbs": [],
                        "os_type": "${oldSetting.osType}",
                        "compute_location": {
                            "location": 0,
                            "policy_type": "",
                            "id": "cluster"
                        },
                        "storage_location": "3600d0231000859694803abfa3b686284",
                        "cdroms": []
                    }
                }
                """.trimIndent()
            )
        }.status.value
    }
    */

    suspend fun initSettings(uuid: String,
                               name: String,
                               description: String,
                               memory: Int,
                               cores: Int,
                               disk: Long,
                               oldSetting: OldSetting
    ) {
        throw NotImplementedError("SangforClient.initSettings is not implemented")
    }
}

data class Token(
    val id: String,
//    val ticket: String,
//    val sid: String
)

data class OldSetting(
    val name: String,
    val description: String,
    val memory: Int,
    val cores: Int,
    val disk: Long,
    val mac: String,
    val osType: String
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