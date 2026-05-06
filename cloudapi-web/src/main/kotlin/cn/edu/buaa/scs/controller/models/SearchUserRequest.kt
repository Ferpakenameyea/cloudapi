package cn.edu.buaa.scs.controller.models

enum class SearchUserType {
    ById,
    ByName
}

data class SearchUserRequest(
    val type: SearchUserType,
    val keyword: String
)