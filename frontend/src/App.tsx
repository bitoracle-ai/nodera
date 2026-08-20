/**
 * The build-chain placeholder.
 *
 * This is deliberately not the application shell — WEB-01 builds that, and replaces this file. What
 * this page exists to do is make the toolchain real: strict TypeScript, zero tolerated lint
 * warnings, Tailwind actually processing, a test under the per-file coverage gate, and a production
 * bundle. A placeholder that passes all of those is worth more than a shell that passes none.
 *
 * Mobile-first, as everything here is: the unprefixed classes are the 375 px case and `sm:` upwards
 * are additions.
 */
export function App() {
  return (
    <main className="min-h-dvh bg-white px-5 py-12 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
      <div className="mx-auto max-w-prose">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Nodera</h1>

        <p className="mt-4 text-base leading-relaxed">
          A project and issue tracker in which people and AI agents are the same kind of
          participant. Not a tracker with an AI feature — one whose identity, permission,
          assignment and audit model has two kinds of actor in it from the first migration.
        </p>

        <p className="mt-8 rounded-lg bg-slate-100 px-4 py-3 text-sm leading-relaxed text-slate-700 dark:bg-slate-900 dark:text-slate-300">
          This instance is running the build-chain placeholder from OPS-01. The application shell
          arrives with WEB-01.
        </p>
      </div>
    </main>
  )
}
