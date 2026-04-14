package cn.edu.buaa.scs.model

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.int
import org.ktorm.schema.varchar

interface App : Entity<App>, IEntity {
    val id: Int
    var url: String
    var cloud: Boolean
    var title: String
    var colorClass: String
    var logoClass: String
    var logoText: String
    var displayPriority: Int
    companion object : Entity.Factory<App>()
}

open class Apps(alias: String?) : Table<App>("application", alias) {
    companion object : Apps(null)

    override fun aliased(alias: String) = Apps(alias)

    @SuppressWarnings("unused")
    val id = int("id").primaryKey().bindTo { it.id }

    @SuppressWarnings("unused")
    val url = varchar("url").bindTo { it.url }

    @SuppressWarnings("unused")
    val cloud = boolean("cloud").bindTo { it.cloud }

    @SuppressWarnings("unused")
    val title = varchar("title").bindTo { it.title }

    @SuppressWarnings("unused")
    val colorClass = varchar("color_class").bindTo { it.colorClass }

    @SuppressWarnings("unused")
    val logoClass = varchar("logo_class").bindTo { it.logoClass }

    @SuppressWarnings("unused")
    val logoText = varchar("logo_text").bindTo { it.logoText }

    @SuppressWarnings("unused")
    val displayPriority = int("display_priority").bindTo { it.displayPriority }
}

val Database.apps get() = this.sequenceOf(Apps)