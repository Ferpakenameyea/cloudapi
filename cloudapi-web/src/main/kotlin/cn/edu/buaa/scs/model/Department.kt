package cn.edu.buaa.scs.model

import cn.edu.buaa.scs.error.BadRequestException
import cn.edu.buaa.scs.storage.mysql
import io.ktor.server.plugins.*
import kotlinx.coroutines.sync.Mutex
import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.dsl.notInList
import org.ktorm.entity.Entity
import org.ktorm.entity.filter
import org.ktorm.entity.find
import org.ktorm.entity.forEach
import org.ktorm.entity.sequenceOf
import org.ktorm.entity.toList
import org.ktorm.schema.Table
import org.ktorm.schema.varchar
import java.util.concurrent.locks.ReentrantReadWriteLock

interface Department : Entity<Department>, IEntity {
    companion object : Entity.Factory<Department>()

    var id: String
    var name: String

    override fun entityId(): IntOrString {
        return IntOrString(this.id)
    }
}

object Departments : Table<Department>("department") {
    val id = varchar("id").primaryKey().bindTo { it.id }
    val name = varchar("name").bindTo { it.name }
}

val Database.departments
    get() = this.sequenceOf(Departments)

val departmentRwLock = ReentrantReadWriteLock()
val departmentReadLock: ReentrantReadWriteLock.ReadLock = departmentRwLock.readLock()
val departmentWriteLock: ReentrantReadWriteLock.WriteLock = departmentRwLock.writeLock()

val departments: HashMap<String, Department> by lazy {
    val map = HashMap<String, Department>()
    val list = mysql.departments.toList()

    for (department in list) {
        map[department.id] = department
    }

    return@lazy map
}

fun Department.Companion.id(id: String): Department {
    departmentReadLock.lock()
    var department: Department?

    try {
        department = departments[id]
    } finally {
        departmentReadLock.unlock()
    }

    if (department != null) {
        return department
    }

    // fetch from database
    departmentWriteLock.lock()
    try {
        // double check
        department = departments[id]
        if (department != null) {
            return department
        }

        mysql.departments.filter { it.id.notInList(departments.keys) }
            .forEach { newDepartment -> departments[newDepartment.id] = newDepartment }

        department = departments[id]
        return department ?: throw BadRequestException("Department with id $id not found")
    } finally {
        departmentWriteLock.unlock()
    }
}

fun Department.Companion.id(id: Int): Department {
    return Department.id(id.toString())
}
