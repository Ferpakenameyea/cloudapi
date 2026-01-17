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
}