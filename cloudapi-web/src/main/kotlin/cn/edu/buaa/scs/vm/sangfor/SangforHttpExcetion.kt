package cn.edu.buaa.scs.vm.sangfor

import io.ktor.http.HttpStatusCode

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
}