package cn.edu.buaa.scs.vm.sangfor

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

class SangforHttpExcetion : Exception {
    val code: HttpStatusCode
    val httpMessage: String?

    constructor(code: HttpStatusCode, httpMessage: String?) {
        this.code = code
        this.httpMessage = httpMessage
    }

    fun what(): String{
        return "Sangfor http action returned with code $code, message: $httpMessage"
    }

    companion object {
        suspend fun mustBeSuccess(response: HttpResponse) {
            if (!response.status.isSuccess()) {
                throw SangforHttpExcetion(
                    response.status,
                    response.bodyAsText()
                )
            }
        }
    }
}