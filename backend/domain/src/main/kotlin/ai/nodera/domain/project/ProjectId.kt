package ai.nodera.domain.project

import kotlin.uuid.Uuid

/**
 * The multi-project boundary, as a value.
 *
 * It always comes from the authenticated context, never from a request parameter, a header or a path
 * segment the client controls (invariant #5). Row-level security is the floor beneath that rule: a
 * forgotten `where project_id = …` returns zero rows rather than another team's backlog.
 */
@JvmInline
public value class ProjectId(
    public val value: Uuid,
)
