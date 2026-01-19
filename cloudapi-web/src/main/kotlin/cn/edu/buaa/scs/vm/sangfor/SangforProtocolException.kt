package cn.edu.buaa.scs.vm.sangfor

class SangforProtocolException(api: String, message: String)
    : Exception("api $api execution error, message: $message") {

}