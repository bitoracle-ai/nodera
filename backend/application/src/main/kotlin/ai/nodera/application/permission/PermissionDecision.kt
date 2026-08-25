package ai.nodera.application.permission

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId

/**
 * The answer to one permission question, as a value rather than as a thrown exception.
 *
 * Errors are values in `:domain` and `:application`; adapters map them to an HTTP status or an MCP
 * error code. A throwing check would force every caller to reconstruct the taxonomy from exception
 * types, and the MCP surface needs the structured detail regardless.
 *
 * [Denied] names the missing capability on purpose. An agent that receives a bare `403` guesses; an
 * agent told which verb it lacks can ask for it.
 */
public sealed interface PermissionDecision {
    public data object Permitted : PermissionDecision

    public data class Denied(
        public val actorId: ActorId,
        public val projectId: ProjectId,
        public val capability: Capability,
    ) : PermissionDecision
}
