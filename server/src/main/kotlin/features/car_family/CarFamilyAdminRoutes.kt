package com.revio.server.features.car_family

import com.revio.server.core.serialization.UUIDSerializer
import com.revio.server.core.util.toUuidOrNull
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.util.UUID

@Serializable
data class CreateCarFamilyRequest(val brand: String, val name: String)

@Serializable
data class AssignCarModelsRequest(
    val carModelIds: List<@Serializable(with = UUIDSerializer::class) UUID>,
)

@Serializable
data class CarFamilyAdminDTO(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val brand: String,
    val name: String,
)

private fun CarFamily.toAdminDTO() = CarFamilyAdminDTO(id = id, brand = brand, name = name)

/** Admin CRUD for car families, gated by the "admin" JWT realm (config/Security.kt). */
fun Route.carFamilyAdminRoutes() {
    val carFamilyService: ICarFamilyService by application.inject()

    authenticate("admin") {
        route("/admin/car-families") {
            get {
                call.respond(HttpStatusCode.OK, carFamilyService.listFamilies().map { it.toAdminDTO() })
            }

            post {
                val req = try {
                    call.receive<CreateCarFamilyRequest>()
                } catch (e: BadRequestException) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                }

                try {
                    val family = carFamilyService.createFamily(req.brand, req.name)
                    call.respond(HttpStatusCode.Created, family.toAdminDTO())
                } catch (e: CarFamilyAlreadyExistsException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Car family already exists")))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            // Preview of every car_models row currently linked to this family (plan §9-E1) — e.g.
            // the create-challenge wizard's "included models" list.
            get("/{id}/models") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                try {
                    call.respond(HttpStatusCode.OK, carFamilyService.getModelsForFamily(id))
                } catch (e: CarFamilyNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Car family not found"))
                }
            }

            // Assigns one or more existing car_models rows to this family. All-or-nothing (plan
            // §9-E4): if any requested id is missing, wrong-brand, or already claimed by a
            // different family, nothing is assigned — see CarFamilyService.assignModels.
            post("/{id}/models") {
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                val req = try {
                    call.receive<AssignCarModelsRequest>()
                } catch (e: BadRequestException) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                }

                try {
                    val models = carFamilyService.assignModels(id, req.carModelIds)
                    call.respond(HttpStatusCode.OK, models)
                } catch (e: CarFamilyNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Car family not found"))
                } catch (e: CarModelsNotFoundException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Car models not found")))
                } catch (e: CarModelBrandMismatchException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Car model brand mismatch")))
                } catch (e: CarModelAlreadyInOtherFamilyException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Car models already assigned elsewhere")))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }
        }

        route("/admin/car-models/unassigned") {
            get {
                val brand = call.request.queryParameters["brand"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing brand parameter"))

                call.respond(HttpStatusCode.OK, carFamilyService.getUnassignedModelsForBrand(brand))
            }
        }
    }
}
