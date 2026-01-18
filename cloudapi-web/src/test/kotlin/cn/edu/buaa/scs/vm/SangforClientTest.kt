package cn.edu.buaa.scs.vm

import cn.edu.buaa.scs.testEnv
import cn.edu.buaa.scs.vm.sangfor.SangforClient
import io.ktor.server.testing.withApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * This test cannot run as batch test
 */
class SangforClientTest {
    @Test
    fun testLogin() {
        withApplication(testEnv) {
            runBlocking {
                val id = SangforClient.connect(
                    SangforClient.username,
                    SangforClient.password
                )

                assert(id.isNotEmpty())
            }
        }
    }

    @Test
    fun testGetToken() {
        withApplication(testEnv) {
            runBlocking {
                val token = SangforClient.getToken()
                assert(token.id.isNotEmpty())
            }
        }
    }

    @Test
    fun testGetAdminToken() {
        withApplication(testEnv) {
            runBlocking {
                val token = SangforClient.getAdminToken()
                assert(token.id.isNotEmpty())
            }
        }
    }

    @Test
    fun testGetHosts() {
        withApplication(testEnv) {
            runBlocking {
                SangforClient.getHosts()
            }
        }
    }

    @Test
    fun testGetVms() {
        withApplication(testEnv) {
            runBlocking {
                SangforClient.getAllVMs()
            }
        }
    }

    @Test
    fun testPowerOnVm() {
        withApplication(testEnv) {
            runBlocking {
                SangforClient.powerOnSync("20abbb5f-584b-4491-8abf-284d1968cba4")
            }
        }
    }

    @Test
    fun testPowerOffVm() {
        withApplication(testEnv) {
            runBlocking {
                SangforClient.powerOffSync("20abbb5f-584b-4491-8abf-284d1968cba4")
            }
        }
    }
}