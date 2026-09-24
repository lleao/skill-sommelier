# AGENTS.md

Guidance for AI coding agents working on this repository. User-facing documentation is in
[README.md](README.md); keep both in sync when behaviour changes.

## Project purpose

**Skill Sommelier** is an open-source Maven plugin (Apache-2.0) that works as a package manager for AI-agent *skills*. A skill is a folder with a `SKILL.md` (YAML front matter `name` / `description` + Markdown), plus any supporting files. The plugin:

- lists the skills published by sources (Git repositories or local folders with a top-level `skills/` directory);
- installs them into the directory each agent reads: `.opencode/skills`, `.claude/skills` or `.github/skills`;
- records them in `skill-sommelier.lock.json`;
- keeps them up to date with the `sync` goal, on every build and when the IDE imports the project;
- installs the skills declared in the pom's `<skills>`, and verifies everything in CI with the `check` goal.

Coordinates: `br.com.codelikeaboss:skill-sommelier-maven-plugin`, goal prefix `skill-sommelier`, Java package `br.com.codelikeaboss.skillsommelier`.

## Tech stack

- Java 17 (`maven.compiler.release`). Maven 3.6.3+ (`<prerequisites>`). Built against Maven 3.9.9 APIs (`provided`).
- `maven-plugin-plugin` 3.15.1. It also generates the `help` goal from javadocs (`helpmojo`), so there is no hand-written help mojo.
- `jackson-databind` for the lock file.
- The system `git` binary, through `GitClient`.
- JUnit 5 for unit tests; `maven-invoker-plugin` for integration tests in `src/it`. Groovy is pinned to 4.0.28 there, because the bundled one cannot read JDK 25 class files.

## Repository layout

```
pom.xml  README.md  LICENSE  AGENTS.md  .github/workflows/{ci,release}.yml
src/main/java/br/com/codelikeaboss/skillsommelier/
├── model/
│   ├── SkillCatalog.java, SkillSource.java   # <catalog><sources><source> (name, url, token, ref)
│   ├── DeclaredSkill.java                    # <skills><skill> (name, source, target: comma-separated)
│   ├── AgentTarget.java                      # opencode | claude | copilot and their directories
│   └── InstalledSkill.java                   # lock file entry (name, target, source, url, hash, ref, commit, declared)
├── service/                                  # Maven-independent logic, unit-tested
│   ├── GitClient.java        # git with timeout, low-speed/SSH limits, token via env; sparse clone; fetch <ref>+reset
│   ├── SourceResolver.java   # catalog lookup, local vs cached clone, clean
│   ├── CachedClone.java      # one clone + its .fetched marker and .lock file (JVM + file lock)
│   ├── FetchPolicy.java      # strategy: whenMissing (resolve), always (update), throttled/offline (refresh)
│   ├── SkillInstaller.java   # list/describe/install/remove/hash skills; never follows symlinks
│   ├── FrontMatter.java      # SKILL.md front matter reader (description)
│   ├── FileTrees.java        # delete / copy regular files without following links
│   ├── Sha256.java           # hashing helpers
│   ├── LockFile.java         # skill-sommelier.lock.json
│   ├── SkillSync.java        # applies <skills>; compares lock hashes with sources; updates/restores; check (dry run)
│   └── Prompter.java         # paginated console menus
└── mojo/
    ├── AbstractSkillSommelierMojo.java   # shared params (catalog, skills, cacheDir, gitTimeout, pageSize), projectRoot(), fetchMaxAge(), sync runner
    ├── AddSkillMojo.java             # add
    ├── ListSkillMojo.java            # list
    ├── RemoveSkillMojo.java          # remove
    ├── SyncSkillMojo.java            # sync (default phase: validate)
    ├── CheckSkillMojo.java           # check (no default phase; fails on any difference)
    ├── UpdateSkillMojo.java          # update (fetch sources, then sync)
    └── CleanCacheMojo.java           # clean
src/main/resources/META-INF/m2e/lifecycle-mapping-metadata.xml   # m2e: run sync on import, ignore other goals
src/it/<scenario>/                    # invoker ITs: pom.xml (optional), invoker.properties, verify.groovy
src/test/java/...                     # unit tests
```

Ignore these paths. Never edit them or treat them as source:

- `target/`: build output.
- `.history/`: VS Code Local History.
- `.github/modernize/`: state of the Copilot modernization extension.

All three are git-ignored.

> The project folder may still be named `skill‑forge` (the project's former name) with a **non-breaking hyphen (U+2011)**. Quote paths in shell commands.

## Build and test

```bash
mvn verify                 # 50+ unit tests + invoker ITs (need git on the PATH)
mvn install                # also installs into ~/.m2 (consumers resolve the -SNAPSHOT version from there)
mvn -Dinvoker.skip test    # unit tests only (fast)
mvn -Dinvoker.test=remove verify   # a single IT
```

CI (`.github/workflows/ci.yml`) runs `mvn verify` on Linux, macOS and Windows with JDK 17 and 21. Tests that need Unix commands or symlinks are `@DisabledOnOs(WINDOWS)`.

When scripting in zsh, write `${VAR}:goal`, not `$VAR:goal`. zsh reads `:a`, `:r`, `:l` and similar as modifiers and silently changes the command.

## Releasing

`main` stays on a `-SNAPSHOT` version. `.github/workflows/release.yml` runs when a tag `vX.Y.Z` is pushed. In each
job, `versions:set` sets the version to `X.Y.Z` in the runner only; nothing is committed back.

1. `build`: runs every test and builds the jar, sources jar and javadoc jar.
2. `central` (after `build`): asks the Central Portal API whether `X.Y.Z` is already published and, if not, runs
   `mvn -Prelease deploy`: signs with GPG and publishes through `central-publishing-maven-plugin` (`autoPublish`,
   waits until the release is published).
3. `github` (after `central`): creates the GitHub Release with generated notes and the jars from `build`, or
   re-uploads them if the release already exists.

Each job is idempotent, so after a failure **Re-run failed jobs** retries only the destination that failed. Builds
are reproducible (`project.build.outputTimestamp`), so the jars on GitHub and on Central are byte-identical.

Repository secrets: `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` (a Central Portal user token, not the login),
`GPG_PRIVATE_KEY` (ASCII-armoured secret key) and `GPG_PASSPHRASE`. The `br.com.codelikeaboss` namespace must be
verified in the Central Portal, and the public key must be on a keyserver such as `keys.openpgp.org`.

After a release, bump `<version>` in the pom to the next `-SNAPSHOT` and update the version shown in README.md.
Maven Central releases cannot be deleted or overwritten, so a failed release must use a new version number.

## How it works (key rules)

- **Project root (`projectRoot()`):** `${project.basedir}`. Without a pom (`add`, `remove`, `update`, `clean`, `help` have `requiresProject = false`), it falls back to the session's execution root directory. The lock file, the agent directories and **relative local sources** are resolved against it.
- **Sources (`SourceResolver`):** `-Dsource` is matched to a catalog `name`; otherwise it is used as a URL/path.
  - URLs with a scheme or `user@host:` are always remote. Relative paths are tried against the project, then against the working directory.
  - Remote sources are cloned into `<cacheDir>/<repo>-<sha256(url[#ref])[0..8]>` with `--depth 1 --filter=blob:none --sparse`, and `sparse-checkout set skills`. Each ref gets its own clone.
  - An optional `ref` (branch, tag or full commit id; validated by `GitClient.isValidRef`) pins a remote source: after cloning, and on every update, the plugin runs `fetch --depth 1 origin <ref>` + `reset --hard FETCH_HEAD`. Local directories ignore it. For lock entries, the catalog source's ref wins over the recorded one.
  - Two bookkeeping files sit next to each clone:
    - `<clone>.fetched`: its mtime is the last attempt and its content is `ok` or `failed: …`. It drives the back-off and `clean`.
    - `<clone>.lock`: all git work runs under a JVM `ReentrantLock` plus a `FileChannel` lock on this file.
  - `update()` does `fetch --depth 1 origin <ref|HEAD>` + `reset --hard FETCH_HEAD`, which survives force-pushes. The cache is read-only.
  - `refresh(maxAge, offline)`:
    - never touches the network in offline mode;
    - does not retry within `maxAge` after an attempt, including a failed one (it throws "retrying after the update interval");
    - with an old or missing attempt, it fetches.
- **Git (`GitClient`):**
  - hard timeout per command; output drained concurrently;
  - `GIT_TERMINAL_PROMPT=0`;
  - `GIT_HTTP_LOW_SPEED_LIMIT/TIME` and `GIT_SSH_COMMAND` with `ConnectTimeout`/`BatchMode`, unless the user already set them;
  - the token goes only through `GIT_CONFIG_*` as `http.extraHeader` (Basic `x-access-token:<token>`);
  - `--` precedes the URL in `clone`.
- **Install (`SkillInstaller`):**
  - the destination folder is deleted, then copied;
  - `.git` is skipped;
  - **symbolic links are never followed nor copied**; they are reported, and linked skill folders are not listed;
  - skill names must match `[A-Za-z0-9][A-Za-z0-9._-]*` without `..`.
  - `hash()` is SHA-256 over sorted relative paths and contents of regular files. CRLF is normalised to LF for text (no NUL byte) so hashes match across OSes.
- **Lock file:**
  - one entry per skill and target: name, target, source name, url, hash; for Git sources also `ref` (when pinned) and `commit` (HEAD of the clone the content was copied from); `declared: true` for entries that come from `<skills>`;
  - `sync` writes it only when its content changed (`saveIfChanged`);
  - local sources are recorded **relative to the project**;
  - sorted on save; unknown fields are ignored; tokens are never written.
- **`sync`** (`SkillSync`, run through `AbstractSkillSommelierMojo.syncInstalledSkills`):

  | Situation | Outcome |
  |---|---|
  | Hashes equal | `UP_TO_DATE` (ref/commit refreshed if the source's ref changed) |
  | Upstream changed | `UPDATED` |
  | Installed folder missing | `RESTORED` |
  | Declared skill not in the lock (folder missing, or identical to upstream) | `INSTALLED` |
  | `declared` entry no longer in `<skills>` | `UNINSTALLED` (`MODIFIED_LOCALLY` and folder kept if edited) |
  | Local edits | `MODIFIED_LOCALLY` (kept unless `force`) |
  | Skill gone from the source | `MISSING_UPSTREAM` |
  | Source unreachable | `SOURCE_UNAVAILABLE` |
  | Invalid entry | `INVALID_ENTRY` |

  Rules:
  - it never throws;
  - it is bound to `validate`;
  - it throttles with `skillsommelier.updateInterval` minutes;
  - when invoked directly (`default-cli`), `maxAge` is 0 (`fetchMaxAge`);
  - declarations are applied first; with any invalid declaration nothing is uninstalled; with `<skills>` not configured (null) nothing is uninstalled either; skills installed by `add` (no `declared` flag) are never uninstalled by it;
  - a declared skill whose folder exists but differs from upstream is not overwritten without `force`, and is not recorded.
- **`check`** (`SkillSync.check`): the same comparison as a dry run, never writes the project nor the lock file. Every outcome other than `UP_TO_DATE` fails the build; check-only outcomes are `OUTDATED`, `NOT_INSTALLED` and `UNDECLARED`. `skillsommelier.check.upstream=false` compares only installed folders with the lock (no network).
- **`update`:** fetches the chosen or all sources and fails on fetch errors, then syncs.
- **`remove`:** deletes folders and lock entries, so `sync` stops restoring them. It is interactive without `-Dskill`. It warns for declared skills, which `sync` installs again until their declaration is removed.
- **`clean`:** deletes clones without `.fetched` (older versions) or not fetched for `olderThan` days, or everything with `all`.
- **`add`:** asks interactively for whatever is missing: source, then skills (multi-select or all), then target. `-Dsource=<a>/<b>` is split only when `a` is a catalog name. `-Dref` pins a URL source (rejected for catalog sources, ignored for local ones). In batch mode, missing parameters fail.
- **Running on IDE open:**
  - m2e (Eclipse, Red Hat Java for VS Code) runs `sync` on import via the embedded metadata;
  - other VS Code setups need a `folderOpen` task (see README);
  - IntelliJ relies on the `validate` binding.
- **Errors:** user errors throw `MojoFailureException`. I/O and git errors throw `MojoExecutionException`. `sync` only warns.

## Conventions

- Keep mojos thin. Put logic in `service/` (no Maven types except `Log`), so it can be unit-tested with `@TempDir`.
- Every goal and user-facing parameter needs a javadoc: it becomes the generated `help` text. Mark internal parameters `readonly`.
- New goals: extend `AbstractSkillSommelierMojo`, and use `aggregator = true` unless the goal is meant to be bound to a phase. Add the goal to `META-INF/m2e/lifecycle-mapping-metadata.xml`: `execute` if it should run on import, otherwise `ignore`. Add a scenario under `src/it`.
- Goals with a default lifecycle phase (`sync`) must never fail the build. `check` has no default phase and is meant to fail.
- Never log or persist a token; pass it only through `GitClient`. Never follow symlinks from a source.
- Code, comments and messages in English. Use `java.nio.file`.
- Update README.md and this file when goals, parameters or behaviour change. Run `mvn verify` before calling a change done.
