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
            addAuthorization(token)
            configureHeader()
        }.body<String>()

        val hostJsonArray = jsonMapper.readTree(response)["data"]["data"]

        val deferred = hostJsonArray.map { hostJson ->
            async {
                val hostId = hostJson["id"].textValue()
                val vmCount = getHostVmCount(hostId, suspend { getToken().id })
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
                        macAddress = net["mac"].textValue(),
                        ipList = listOf(net["ip"].textValue())
                    )
                }
                applySangforExtraInfo(it["description"].textValue())
            }
        }

        return Result.success(list)
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
        val response = client.post("janus/20180725/servers/action") {
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
        }

        if (!response.status.isSuccess()) {
            throw SangforHttpExcetion(
                response.status,
                response.body<String>()
            )
        }
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
        val response = client.post("janus/20180725/servers/action") {
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
        }

        if (!response.status.isSuccess()) {
            throw SangforHttpExcetion(
                response.status,
                response.bodyAsText()
            )
        }
    }

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

        val response = client.put("janus/20180728/servers/$uuid") {
            configureHeader()
            addAuthorization(suspend { getAdminToken().id })
            setBody("""
                {
                    "description": "$description"
                }
            """.trimIndent())
        }

        if (!response.status.isSuccess()) {
            throw SangforHttpExcetion(
                response.status,
                response.body<String>()
            )
        }

        return getVM(uuid)
    }

    override suspend fun createVM(options: CreateVmOptions): Result<VirtualMachine> {
        createLock.lock()
        // Send clone vm request.

        val asyncTask: SangforAsyncTask<String>
        try {

            val owner = if (options.extraInfo.teacherId != "default") options.extraInfo.teacherId
            else if (options.extraInfo.studentId != "default") options.extraInfo.studentId
            else "default"
            val description = "$owner,false,${options.extraInfo.experimentId},${options.extraInfo.applyId}"
            asyncTask = clone(
                getToken().id,
                options.name,
                options.extraInfo.templateUuid,
                description
            )
        } finally {
            createLock.unlock()
        }

        val tokenProvider = suspend { getToken().id }
        val virtualMachineUUID = asyncTask.extraData

        asyncTask.await(client, tokenProvider)

        // wait for vm to exist
        waitForDone(timeout = 60000L * 5, interval = 5000L) {
            val response = client.get("janus/20180725/servers/$virtualMachineUUID") {
                configureHeader()
                addAuthorization(suspend { getToken().id })
            }

            response.status.isSuccess()
        }

        val configureResponse = client.put("janus/20180725/servers/$virtualMachineUUID") {
            configureHeader()
            addAuthorization(suspend { getToken().id })
            setBody("""
                {
                    "memory_mb": ${options.memory},
                    "cores": ${options.cpu},
                    "disks": [{
                        "id": "ide0",
                        "type": "new_disk",
                        "preallocate": "metadata",
                        "size_mb": ${options.diskSize / 1048576L},
                        "is_old_disk": 1,
                        "storage_file": "3600d0231000859694803abfa3b686284:vm-disk-1.qcow2"
                    }]
                }
            """.trimIndent())
        }

        if (!configureResponse.status.isSuccess()) {
            throw SangforHttpExcetion(
                configureResponse.status,
                configureResponse.body()
            )
        }

        if (options.powerOn) {
            powerOnAsync(virtualMachineUUID)
        }

        return getVM(virtualMachineUUID)
    }

    // TODO: still need testing
    override suspend fun deleteVM(uuid: String): Result<Unit> {
        val response = client.delete("/janus/20180725/servers/$uuid") {
            configureHeader()
            addAuthorization(suspend { getToken().id })

            // soft delete
            setBody("""
                {"force":0}
            """.trimIndent())
        }

        if (!response.status.isSuccess()) {
            val exception = SangforHttpExcetion(response.status, response.bodyAsText())
            return Result.failure(exception)
        }

        return Result.success(Unit)
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
                    }
                }
            """.trimIndent())
        }

        if (!response.status.isSuccess()) {
            throw SangforHttpExcetion(
                response.status,
                response.body()
            )
        }

        val responseJson = jsonMapper.readTree(response.body<String>())
        return SangforAsyncTask<String>(
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

    internal fun HttpRequestBuilder.addAuthorization(tokenProvider : () -> String) {
        addAuthorization(tokenProvider())
    }
}

data class SangforToken(val id: String)
data class SangforAsyncTask<TData>(val taskId: String, val extraData :TData) {
    suspend fun await(
        client: HttpClient,
        tokenProvider: suspend () -> String): JsonNode {

        val queryTask = suspend {
            val taskQueryResponse = client.get("janus/20180725/tasks/$taskId") {
                configureHeader()
                addAuthorization(tokenProvider)
            }

            if (!taskQueryResponse.status.isSuccess()) {
                throw SangforHttpExcetion(
                    taskQueryResponse.status,
                    taskQueryResponse.body()
                )
            }

            jsonMapper.readTree(taskQueryResponse.body<String>())["data"]
        }

        var taskQueryData: JsonNode = queryTask()
        if (taskQueryData["status"].textValue() == "finish") {
            return taskQueryData
        }

        waitForDone(timeout = 20000L, interval = 500L) {
            taskQueryData = queryTask()
            val status = taskQueryData["status"].textValue()
            status == "finish"
        }

        return taskQueryData
    }
}

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