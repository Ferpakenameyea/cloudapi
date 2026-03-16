package cn.edu.buaa.scs.controller.models;

data class ForcedActivateUserRequest(
    val userId: String,
    val password: String
);