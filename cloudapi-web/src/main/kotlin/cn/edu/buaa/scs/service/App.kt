package cn.edu.buaa.scs.service

import cn.edu.buaa.scs.error.AuthorizationException
import cn.edu.buaa.scs.error.BadRequestException
import cn.edu.buaa.scs.error.NotFoundException
import cn.edu.buaa.scs.model.App
import cn.edu.buaa.scs.model.apps
import cn.edu.buaa.scs.storage.mysql
import cn.edu.buaa.scs.utils.user
import io.ktor.server.application.ApplicationCall
import org.ktorm.dsl.eq
import org.ktorm.entity.add
import org.ktorm.entity.filter
import org.ktorm.entity.first
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.ktorm.entity.sortedByDescending
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.ktorm.entity.update

val ApplicationCall.apps
    get() = AppService.getSvc(this) { AppService(this) }

class AppService(val call: ApplicationCall) : IService {
    companion object : IService.Caller<AppService>();

    fun newApplication(
        url: String,
        cloud: Boolean,
        title: String,
        colorClass: String,
        logoClass: String,
        logoText: String,
        displayPriority: Int
    ): App {
        if (!call.user().isAdmin()) {
            throw AuthorizationException("Only admins are allowed to post create new applications")
        }

        val application = App {
            this.url                = url
            this.cloud              = cloud
            this.title              = title
            this.colorClass         = colorClass
            this.logoClass          = logoClass
            this.logoText           = logoText
            this.displayPriority    = displayPriority
        }

        mysql.apps.add(application)
        return application
    }

    fun getAllApplications(): List<App> {
        val applications = mysql.apps
            .sortedByDescending { it.displayPriority }
            // have a limit here in case of extreme
            // conditions that some idiots inject too many
            // application instances.
            .take(1024)
            .toList()
        return applications
    }

    fun editApplication(
        id: Int,
        url: String?,
        cloud: Boolean?,
        title: String?,
        colorClass: String?,
        logoClass: String?,
        logoText: String?,
        displayPriority: Int?
    ): App {
        if (!call.user().isAdmin()) {
            throw AuthorizationException("Only admins are allowed to edit applications")
        }

        // ensure that the application exists
        val app = mysql.apps
            .filter { it.id eq id }
            .firstOrNull() ?: throw BadRequestException("Given app with id $id does not exist")

        // update the fields
        url?.apply              { app.url = this }
        cloud?.apply            { app.cloud = this }
        title?.apply            { app.title = this }
        colorClass?.apply       { app.colorClass = this }
        logoClass?.apply        { app.logoClass = this }
        logoText?.apply         { app.logoText = this }
        displayPriority?.apply  { app.displayPriority = this }

        // update the application
        mysql.apps.update(app)

        return app
    }

    fun getApplication(id: Int): App {
        val app = mysql.apps.filter { it.id eq id }
            .firstOrNull()
            ?: throw NotFoundException("Given app with id $id does not exist")

        return app
    }

    fun deleteApplication(id: Int) {
        if (!call.user().isAdmin()) {
            throw AuthorizationException("Only admins are allowed to delete applications")
        }

        val count = mysql.apps.removeIf { it.id eq id }
        if (count == 0) {
            throw NotFoundException("Given app with id $id does not exist")
        }
    }
}