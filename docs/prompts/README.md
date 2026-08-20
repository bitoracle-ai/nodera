# Prompts

The **only** place reusable prompts live. A prompt that exists in a tool's private configuration and
nowhere else is a prompt the next contributor cannot run and nobody can review.

These are plain Markdown, tool-neutral by construction: paste into any assistant, or point a native
adapter at the file. They are versioned, so a change to how reviews are conducted is a diff.

## Catalogue

| Prompt | Use |
|---|---|
| [`code-review.prompt.md`](code-review.prompt.md) | The canonical phase-4 independent review. Run in a **separate instance** from the one that wrote the code. |
| [`maintain-adapters.prompt.md`](maintain-adapters.prompt.md) | Catch semantic drift between layer-2 sources and their layer-1 distillates — the thing `lint_adapters.py` cannot see. |

## Adding one

It earns its place by being **run more than once by more than one person**. A one-off instruction
belongs in the ticket.
