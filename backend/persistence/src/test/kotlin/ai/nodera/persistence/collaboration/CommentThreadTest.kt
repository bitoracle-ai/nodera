package ai.nodera.persistence.collaboration

import ai.nodera.application.collaboration.CommentResult
import ai.nodera.application.collaboration.CommentThreadResult
import ai.nodera.application.collaboration.ReviewRecordResult
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.collaboration.Comment
import ai.nodera.domain.collaboration.CommentContent
import ai.nodera.domain.collaboration.FindingDraft
import ai.nodera.domain.collaboration.ReviewVerdict
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.permission.ProjectRole
import ai.nodera.domain.ticket.FindingSeverity
import ai.nodera.domain.ticket.TicketId
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.audit.auditRows
import ai.nodera.persistence.audit.context
import ai.nodera.persistence.ticket.auditEntities
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** The two author kinds, driven through identical assertions — invariant CM2, and #1 underneath it. */
private class AuthorCase(
    val description: String,
    val kind: ActorKind,
    val actor: (CollaborationProject) -> UUID,
)

private val AUTHORS =
    listOf(
        AuthorCase("a human", ActorKind.HUMAN) { it.reporterId },
        AuthorCase("an agent", ActorKind.AGENT) { it.assigneeId },
    )

private fun Comment.visible(): CommentContent.Visible = content.shouldBeInstanceOf<CommentContent.Visible>()

class CommentThreadTest :
    StringSpec({

        // AC1. Two authors, one code path, one set of assertions: the only thing the responses may
        // differ in is the kind the reader is told.
        AUTHORS.forEach { case ->
            "a comment by ${case.description} is stored, threaded and returned the same way" {
                val project = seedCollaborationProject()
                project.seedTicket()
                val author = case.actor(project)
                val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
                val ctx = context(author, UUID.randomUUID(), kind = case.kind)

                val root =
                    useCases.createComment
                        .create(ctx, project.id, project.key(1), body("first"))
                        .shouldBeInstanceOf<CommentResult.Written>()
                        .comment
                val reply =
                    useCases.createComment
                        .create(ctx, project.id, project.key(1), body("second"), inReplyTo = root.id)
                        .shouldBeInstanceOf<CommentResult.Written>()
                        .comment

                root.author.kind shouldBe case.kind
                root.author.handle.value shouldBe project.handleOf(author)
                root.visible().body.value shouldBe "first"
                reply.inReplyTo shouldBe root.id

                val thread =
                    useCases.listComments
                        .thread(ctx, project.id, project.key(1))
                        .shouldBeInstanceOf<CommentThreadResult.Thread>()
                        .comments

                thread.map { it.id } shouldContainExactly listOf(root.id, reply.id)
                thread.map { it.author.kind }.distinct() shouldContainExactly listOf(case.kind)
                commentRow(root.id.value.toJavaUuid()).shouldNotBeNull().author shouldBe author
                commentRow(reply.id.value.toJavaUuid()).shouldNotBeNull().inReplyTo shouldBe
                    root.id.value.toJavaUuid()
            }
        }

        // AC2, end to end: the payload is neutralised before it reaches the column, so every later
        // reader gets the safe form without re-deriving what safe means.
        "a script payload never reaches the row" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())

            val comment =
                useCases.createComment
                    .create(
                        context(project.reporterId, UUID.randomUUID()),
                        project.id,
                        project.key(1),
                        body("look: <script>alert(1)</script>\n```\n<b>in a fence</b>\n```"),
                    ).shouldBeInstanceOf<CommentResult.Written>()
                    .comment

            // Every angle bracket, in prose and in the fence alike — the sanitiser has no code
            // exemption, which is what makes this assertion a property rather than a sample.
            val stored = commentRow(comment.id.value.toJavaUuid()).shouldNotBeNull().body
            stored shouldContain "&lt;script>"
            stored shouldContain "&lt;b>in a fence"
            stored shouldNotContain "<"
        }

        "a reply to a comment on another ticket is refused as a value, not as an exception" {
            val project = seedCollaborationProject()
            project.seedTicket(number = 1)
            project.seedTicket(number = 2, assignee = null)
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val ctx = context(project.reporterId, UUID.randomUUID())

            val elsewhere =
                useCases.createComment
                    .create(ctx, project.id, project.key(2), body("on the other ticket"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment

            val refused = UUID.randomUUID()
            useCases.createComment.create(
                context(project.reporterId, refused),
                project.id,
                project.key(1),
                body("misplaced"),
                inReplyTo = elsewhere.id,
            ) shouldBe CommentResult.ReplyToAnotherTicket

            // V4 indexes the trail on (entity_type, entity_id). No comment was written, so the row
            // carries no id rather than the ticket's under entity_type 'comment'.
            SchemaFixture.asOwner { it.auditEntities(refused) } shouldContainExactly listOf(null)
        }

        // Deleting one's own needs the verb that wrote it; deleting somebody else's needs the
        // moderation verb, and a contributor holds only the first.
        "a contributor cannot delete another actor's comment" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val roles = project.maintainers() + (project.strangerId to ProjectRole.CONTRIBUTOR)
            val useCases = CollaborationUseCases(project.scope, project.id, roles)
            val author = context(project.reporterId, UUID.randomUUID())

            val comment =
                useCases.createComment
                    .create(author, project.id, project.key(1), body("mine"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment

            useCases.deleteComment.delete(
                context(project.strangerId, UUID.randomUUID()),
                project.id,
                comment.id,
            ) shouldBe CommentResult.Denied(Capability.COMMENT_MODERATE)
            commentRow(comment.id.value.toJavaUuid()).shouldNotBeNull().deletedAt shouldBe null

            useCases.deleteComment
                .delete(author, project.id, comment.id)
                .shouldBeInstanceOf<CommentResult.Written>()
            commentRow(comment.id.value.toJavaUuid()).shouldNotBeNull().deletedBy shouldBe project.reporterId
        }

        // Mentions: once per actor, and only for actors who are in the project. A handle inside a
        // code sample counts too — `:domain` no longer knows what code is, and the cost of that is
        // a notification rather than a tag.
        "mentions are extracted once per actor and only for members of the project" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val reviewer = project.handleOf(project.humanReviewerId)
            val stranger = project.handleOf(project.strangerId)

            val comment =
                useCases.createComment
                    .create(
                        context(project.reporterId, UUID.randomUUID()),
                        project.id,
                        project.key(1),
                        body("@$reviewer @$reviewer @$stranger `@$reviewer`"),
                    ).shouldBeInstanceOf<CommentResult.Written>()
                    .comment

            mentionedActors(comment.id.value.toJavaUuid()) shouldContainExactly listOf(project.humanReviewerId)
        }

        // `actor.handle` is citext: two spellings are two rows of the unnested array joining ONE
        // actor, and comment_mention is keyed (comment_id, actor_id). Without `distinct` in the
        // directory this comment fails on the primary key.
        "two spellings of one handle mention that actor once" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val handle = project.handleOf(project.humanReviewerId)

            val comment =
                useCases.createComment
                    .create(
                        context(project.reporterId, UUID.randomUUID()),
                        project.id,
                        project.key(1),
                        body("@$handle and @${handle.uppercase()}"),
                    ).shouldBeInstanceOf<CommentResult.Written>()
                    .comment

            mentionedActors(comment.id.value.toJavaUuid()) shouldContainExactly listOf(project.humanReviewerId)
        }

        "an edit stamps edited_at, keeps authorship, and is refused for anyone but the author" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val author = context(project.reporterId, UUID.randomUUID())

            val comment =
                useCases.createComment
                    .create(author, project.id, project.key(1), body("first draft"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment
            val id = comment.id.value.toJavaUuid()
            commentRow(id).shouldNotBeNull().editedAt shouldBe null

            useCases.editComment.edit(
                context(project.humanReviewerId, UUID.randomUUID()),
                project.id,
                comment.id,
                body("rewritten by someone else"),
            ) shouldBe CommentResult.NotAuthor
            commentRow(id).shouldNotBeNull().body shouldBe "first draft"

            val edited =
                useCases.editComment
                    .edit(author, project.id, comment.id, body("second draft"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment

            edited.visible().body.value shouldBe "second draft"
            edited.visible().editedAt shouldNotBe null
            edited.author.id shouldBe comment.author.id
            commentRow(id).shouldNotBeNull().author shouldBe project.reporterId
        }

        "a delete leaves a tombstone in place, and the second one does not rewrite the first" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val author = context(project.reporterId, UUID.randomUUID())

            val comment =
                useCases.createComment
                    .create(author, project.id, project.key(1), body("regrettable"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment
            val id = comment.id.value.toJavaUuid()

            useCases.deleteComment
                .delete(author, project.id, comment.id)
                .shouldBeInstanceOf<CommentResult.Written>()
                .comment
                .content
                .shouldBeInstanceOf<CommentContent.Tombstone>()

            val tombstone = commentRow(id).shouldNotBeNull()
            tombstone.body shouldBe ""
            tombstone.deletedBy shouldBe project.reporterId

            // A moderator arriving second is told the comment had already gone, and the trail keeps
            // naming the actor who actually removed it.
            useCases.deleteComment.delete(
                context(project.humanReviewerId, UUID.randomUUID()),
                project.id,
                comment.id,
            ) shouldBe CommentResult.AlreadyDeleted
            commentRow(id).shouldNotBeNull().deletedBy shouldBe project.reporterId

            useCases.editComment.edit(author, project.id, comment.id, body("undelete me")) shouldBe
                CommentResult.AlreadyDeleted
            commentRow(id).shouldNotBeNull().body shouldBe ""
        }

        // The tombstone keeps its position, which is the whole reason it is not a delete.
        "a deleted comment keeps its place in the thread" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val ctx = context(project.reporterId, UUID.randomUUID())

            val first = useCases.createComment.create(ctx, project.id, project.key(1), body("one"))
            useCases.createComment.create(ctx, project.id, project.key(1), body("two"))
            val deleted = first.shouldBeInstanceOf<CommentResult.Written>().comment
            useCases.deleteComment.delete(ctx, project.id, deleted.id)

            val thread =
                useCases.listComments
                    .thread(ctx, project.id, project.key(1))
                    .shouldBeInstanceOf<CommentThreadResult.Thread>()
                    .comments

            thread shouldHaveSize 2
            thread.first().id shouldBe deleted.id
            thread.first().content.shouldBeInstanceOf<CommentContent.Tombstone>()
        }

        // Two concurrent deletes: one tombstone, one AlreadyDeleted, one deleter on the row. Without
        // `and deleted_at is null` the second write lands and the trail names the wrong actor.
        "concurrent deletes leave exactly one deleter" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val comment =
                useCases.createComment
                    .create(context(project.reporterId, UUID.randomUUID()), project.id, project.key(1), body("go"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment

            val deleters = listOf(project.reporterId, project.humanReviewerId)
            val gate = CyclicBarrier(deleters.size)
            val outcomes =
                coroutineScope {
                    deleters
                        .map { actor ->
                            async(Dispatchers.IO) {
                                gate.await()
                                useCases.deleteComment.delete(
                                    context(actor, UUID.randomUUID()),
                                    project.id,
                                    comment.id,
                                )
                            }
                        }.awaitAll()
                }

            outcomes.filterIsInstance<CommentResult.Written>() shouldHaveSize 1
            outcomes.filterIsInstance<CommentResult.AlreadyDeleted>() shouldHaveSize 1
            commentRow(comment.id.value.toJavaUuid()).shouldNotBeNull().deletedBy shouldNotBe null
        }

        // Invariant #5, at the point where it is easiest to lose: the caller is scoped to both
        // projects and permitted in only one, so only the query's own project clause refuses.
        "a caller permitted in one project cannot edit or delete a comment in another" {
            val permitted = seedCollaborationProject()
            val other = seedCollaborationProject()
            permitted.seedTicket()
            other.seedTicket()
            val bothScopes = permitted.scope + other.scope

            val elsewhere =
                CollaborationUseCases(bothScopes, other.id, other.maintainers())
                    .createComment
                    .create(context(other.reporterId, UUID.randomUUID()), other.id, other.key(1), body("theirs"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment

            val caller = CollaborationUseCases(bothScopes, permitted.id, permitted.maintainers())
            val ctx = context(permitted.reporterId, UUID.randomUUID())

            caller.editComment.edit(ctx, permitted.id, elsewhere.id, body("reached across")) shouldBe
                CommentResult.NotFound
            caller.deleteComment.delete(ctx, permitted.id, elsewhere.id) shouldBe CommentResult.NotFound
            commentRow(elsewhere.id.value.toJavaUuid()).shouldNotBeNull().body shouldBe "theirs"
        }

        /*
         * Invariant #5 on the reads addressed by KEY rather than by id, which is where it is easiest
         * to lose: `ticket` is `unique (project_id, key)`, so two projects may both own `x-1`, and
         * without a project clause a caller scoped to both would be handed the other project's
         * thread, review record and findings under its own key. The two projects here share a prefix
         * deliberately — while every fixture derived its prefix from its own id, nothing could put
         * one key in two projects and this guard was deletable with the suite green.
         */
        "a caller scoped to two projects reads only the project it asked for" {
            val shared =
                "sh" +
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(8)
            val here = seedCollaborationProject(shared)
            val there = seedCollaborationProject(shared)
            here.seedTicket(number = 1)
            there.seedTicket(number = 1)
            there.seedTicket(number = 2, assignee = null)
            val bothScopes = here.scope + there.scope

            val mine = CollaborationUseCases(bothScopes, here.id, here.maintainers())
            val theirs = CollaborationUseCases(bothScopes, there.id, there.maintainers())
            mine.createComment.create(
                context(here.reporterId, UUID.randomUUID()),
                here.id,
                here.key(1),
                body("mine"),
            )
            theirs.createComment.create(
                context(there.reporterId, UUID.randomUUID()),
                there.id,
                there.key(1),
                body("theirs"),
            )
            theirs.submitReview.submit(
                context(there.humanReviewerId, UUID.randomUUID()),
                there.id,
                there.key(1),
                ReviewVerdict.CHANGES_REQUIRED,
                summary = "their round",
                findings = listOf(FindingDraft(FindingSeverity.BLOCKING, "their finding")),
            )
            mine.submitReview.submit(
                context(here.humanReviewerId, UUID.randomUUID()),
                here.id,
                here.key(1),
                ReviewVerdict.CHANGES_REQUIRED,
                summary = "my round",
                findings = listOf(FindingDraft(FindingSeverity.BLOCKING, "my finding")),
            )

            val ctx = context(here.reporterId, UUID.randomUUID())
            mine.listComments
                .thread(ctx, here.id, here.key(1))
                .shouldBeInstanceOf<CommentThreadResult.Thread>()
                .comments
                .map { it.visible().body.value } shouldContainExactly listOf("mine")

            val record =
                mine.listReviews
                    .rounds(ctx, here.id, here.key(1))
                    .shouldBeInstanceOf<ReviewRecordResult.Rounds>()
                    .reviews
            // The rounds themselves, not only their findings: the findings are grouped by review id,
            // so a leak in the *rounds* query would be invisible to an assertion about titles alone.
            // That is exactly what happened — this line is here because the first version passed with
            // the project clause deleted from the rounds query.
            record.map { it.summary } shouldContainExactly listOf("my round")
            record.flatMap { review -> review.findings.map { it.title } } shouldContainExactly listOf("my finding")

            // And a key that exists only in the other project is missing here, not empty here.
            mine.listComments.thread(ctx, here.id, here.key(2)) shouldBe CommentThreadResult.NotFound
            mine.listReviews.rounds(ctx, here.id, here.key(2)) shouldBe ReviewRecordResult.NotFound
        }

        // The distinction an empty list would erase.
        "a thread on a ticket the caller cannot see is not an empty thread" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val unscoped = CollaborationUseCases(emptyList(), project.id, project.maintainers())
            val scoped = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val ctx = context(project.reporterId, UUID.randomUUID())

            unscoped.listComments.thread(ctx, project.id, project.key(1)) shouldBe CommentThreadResult.NotFound
            scoped.listComments.thread(ctx, project.id, project.key(1)) shouldBe
                CommentThreadResult.Thread(emptyList())
        }

        "commenting on a ticket that does not exist is refused before anything is written" {
            val project = seedCollaborationProject()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val requestId = UUID.randomUUID()

            useCases.createComment.create(
                context(project.reporterId, requestId),
                project.id,
                project.key(9),
                body("into the void"),
            ) shouldBe CommentResult.NotFound

            auditRows(requestId, project.scope) shouldBe 1
        }

        // Invariant #3, through the harness rather than through an assertion: the commit itself
        // refuses a mutation that wrote any audit-event count but one.
        "each comment write records exactly one audit event" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val created = UUID.randomUUID()

            val comment =
                useCases.createComment
                    .create(context(project.reporterId, created), project.id, project.key(1), body("audited"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment
            outcomesOf(created) shouldContainExactly listOf("success")

            val edited = UUID.randomUUID()
            useCases.editComment.edit(context(project.reporterId, edited), project.id, comment.id, body("again"))
            outcomesOf(edited) shouldContainExactly listOf("success")

            val removed = UUID.randomUUID()
            useCases.deleteComment.delete(context(project.reporterId, removed), project.id, comment.id)
            outcomesOf(removed) shouldContainExactly listOf("success")
        }

        // A trail of successes cannot answer what an actor tried to do, so each denial is recorded
        // as one — and the outcome is asserted rather than the row count, which a success would also
        // satisfy.
        "a denied create, edit and delete each record one row with outcome denied, and mutate nothing" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val owner = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val comment =
                owner.createComment
                    .create(context(project.reporterId, UUID.randomUUID()), project.id, project.key(1), body("mine"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment

            val observers = project.maintainers() + (project.strangerId to ProjectRole.OBSERVER)
            val denied = CollaborationUseCases(project.scope, project.id, observers)

            val onCreate = UUID.randomUUID()
            denied.createComment.create(
                context(project.strangerId, onCreate),
                project.id,
                project.key(1),
                body("no verb"),
            ) shouldBe CommentResult.Denied(Capability.COMMENT_CREATE)
            outcomesOf(onCreate) shouldContainExactly listOf("denied")

            val onEdit = UUID.randomUUID()
            denied.editComment.edit(
                context(project.strangerId, onEdit),
                project.id,
                comment.id,
                body("not mine to edit"),
            ) shouldBe CommentResult.Denied(Capability.COMMENT_CREATE)
            outcomesOf(onEdit) shouldContainExactly listOf("denied")

            val onDelete = UUID.randomUUID()
            denied.deleteComment.delete(
                context(project.strangerId, onDelete),
                project.id,
                comment.id,
            ) shouldBe CommentResult.Denied(Capability.COMMENT_MODERATE)
            outcomesOf(onDelete) shouldContainExactly listOf("denied")

            val row = commentRow(comment.id.value.toJavaUuid()).shouldNotBeNull()
            row.body shouldBe "mine"
            row.deletedAt shouldBe null
        }

        // What an edit does not do, pinned so that changing it is a decision rather than a drift.
        // A mention row records that an actor was named on a comment; removing one would make the
        // trail say a naming that happened did not.
        "an edit leaves the mention rows of the text it replaced" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val author = context(project.reporterId, UUID.randomUUID())
            val first = project.handleOf(project.humanReviewerId)
            val second = project.handleOf(project.agentReviewerId)

            val comment =
                useCases.createComment
                    .create(author, project.id, project.key(1), body("@$first please look"))
                    .shouldBeInstanceOf<CommentResult.Written>()
                    .comment
            useCases.editComment.edit(author, project.id, comment.id, body("@$second please look"))

            mentionedActors(comment.id.value.toJavaUuid()) shouldContainExactly listOf(project.humanReviewerId)
        }

        // Where an unscoped caller is actually stopped, which is not where one would guess. The
        // ticket read returns nothing, so the use case reaches its refusal path — and the refusal's
        // own audit row carries a project_id the caller cannot see, which V4's audit policy rejects.
        // It never reaches `comment` at all, so this case does not prove `comment`'s policy; the one
        // below does.
        "commenting without a project context aborts on the trail, writing nothing" {
            val project = seedCollaborationProject()
            val ticketId = project.seedTicket()
            val unscoped = CollaborationUseCases(emptyList(), project.id, project.maintainers())

            val failure =
                shouldThrowAny {
                    unscoped.createComment.create(
                        context(project.reporterId, UUID.randomUUID()),
                        project.id,
                        project.key(1),
                        body("@${project.handleOf(project.humanReviewerId)} unscoped"),
                    )
                }

            failure.message.orEmpty() shouldContain "row-level security policy"
            failure.message.orEmpty() shouldContain "audit_event"
            SchemaFixture.asOwner { it.commentCount(ticketId) } shouldBe 0
        }

        // `comment` is `for all … using` with no separate `with check`, so one predicate governs the
        // read and the write: a row an unscoped caller cannot see is a row it cannot create. Driven
        // through the adapter directly, because the use case never gets this far.
        "the comment policy refuses an unscoped insert" {
            val project = seedCollaborationProject()
            val ticketId = project.seedTicket()
            val unscoped = CollaborationUseCases(emptyList(), project.id, project.maintainers())

            val failure =
                shouldThrowAny {
                    unscoped.unitOfWork.inTransaction {
                        JdbcCommentRepository().create(
                            TicketId(ticketId.toKotlinUuid()),
                            ActorId(project.reporterId.toKotlinUuid()),
                            body("straight at the table"),
                            null,
                        )
                    }
                }

            failure.message.orEmpty() shouldContain "row-level security policy"
            failure.message.orEmpty() shouldContain "comment"
            SchemaFixture.asOwner { it.commentCount(ticketId) } shouldBe 0
        }

        // The read use cases refuse too, and their refusal is the only thing they write. Without
        // this the capability each names and its `recordDenied` call are dead in the suite, so a
        // wrong verb or a dropped recorder would ship green.
        "a caller with no membership is refused the thread, and the refusal is recorded" {
            val project = seedCollaborationProject()
            project.seedTicket()
            // The stranger is left out of the directory entirely, so it holds nothing at all.
            val roles = project.maintainers() - project.strangerId
            val useCases = CollaborationUseCases(project.scope, project.id, roles)
            val denied = UUID.randomUUID()

            useCases.listComments.thread(
                context(project.strangerId, denied),
                project.id,
                project.key(1),
            ) shouldBe CommentThreadResult.Denied(Capability.COMMENT_READ)
            outcomesOf(denied) shouldContainExactly listOf("denied")
        }

        "the thread of a ticket with no comments is empty rather than missing" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())

            useCases.listComments
                .thread(context(project.reporterId, UUID.randomUUID()), project.id, project.key(1))
                .shouldBeInstanceOf<CommentThreadResult.Thread>()
                .comments
                .shouldBeEmpty()
        }
    })

private fun Connection.commentCount(ticketId: UUID): Long =
    prepareStatement("select count(*) from comment where ticket_id = ?").use { statement ->
        statement.setObject(1, ticketId)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }
