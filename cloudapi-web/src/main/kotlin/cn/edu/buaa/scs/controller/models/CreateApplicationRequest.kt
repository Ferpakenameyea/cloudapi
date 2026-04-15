package cn.edu.buaa.scs.controller.models

data class CreateApplicationRequest (
    val url: String,
    val cloud: Boolean = false,
    val title: String,
    val colorClass: String = "green",
    val logoClass: String = "",
    val logoText: String = "",
    val displayPriority: Int = 0
);