package cn.edu.buaa.scs.controller.models

data class EditApplicationRequest (
    val url: String?            = null,
    val cloud: Boolean?         = null,
    val title: String?          = null,
    val colorClass: String?     = null,
    val logoClass: String?      = null,
    val logoText: String?       = null,
    val displayPriority: Int?   = null
);