package cn.edu.buaa.scs.route

import cn.edu.buaa.scs.controller.models.CreateApplicationRequest
import cn.edu.buaa.scs.controller.models.EditApplicationRequest
import cn.edu.buaa.scs.error.BadRequestException
import cn.edu.buaa.scs.model.App
import cn.edu.buaa.scs.service.apps
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.applicationRoute() {

    data class AppDto(
        val id: Int,
        val url: String,
        val cloud: Boolean,
        val title: String,
        val colorClass: String,
        val logoClass: String,
        val logoText: String,
        val displayPriority: Int
    );

    fun convertAppModel(app: App): AppDto = AppDto(
            id              = app.id,
            url             = app.url,
            cloud           = app.cloud,
            title           = app.title,
            colorClass      = app.colorClass,
            logoClass       = app.logoClass,
            logoText        = app.logoText,
            displayPriority = app.displayPriority
        )

    route("/application") {
        post {
            val request = call.receive<CreateApplicationRequest>()
            val appSql = call.apps.newApplication(
                    request.url,
                    request.cloud,
                    request.title,
                    request.colorClass,
                    request.logoClass,
                    request.logoText,
                    request.displayPriority
                )
            call.respond(convertAppModel(appSql))
        }

        route("/{id}") {
            fun ApplicationCall.getAppIdFromPath(): Int = this.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Given app id is not valid")

            patch {
                val id = call.getAppIdFromPath()
                val editRequest = call.receive<EditApplicationRequest>()

                val appSqlModel = call.apps.editApplication(id,
                    editRequest.url,
                    editRequest.cloud,
                    editRequest.title,
                    editRequest.colorClass,
                    editRequest.logoClass,
                    editRequest.logoText,
                    editRequest.displayPriority)

                call.respond(convertAppModel(appSqlModel))
            }

            get {
                val id = call.getAppIdFromPath()
                call.respond(convertAppModel(
                    call.apps.getApplication(id)
                ))
            }

            delete {
                val id = call.getAppIdFromPath()
                call.apps.deleteApplication(id)
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    get("/applications") {
        call.respond(call.apps.getAllApplications().map {
            convertAppModel(it)
        })
    }
}