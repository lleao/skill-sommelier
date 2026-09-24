# Skill Sommelier Maven Plugin

A package manager for AI coding-agent **skills**, for Java/Maven projects. It installs skills from Git
repositories or local folders into the directory your agent reads, and keeps them up to date.

| Agent          | `-Dtarget` | Skills are installed in     |
|----------------|------------|-----------------------------|
| OpenCode       | `opencode` | `.opencode/skills/<skill>/` |
| Claude Code    | `claude`   | `.claude/skills/<skill>/`   |
| GitHub Copilot | `copilot`  | `.github/skills/<skill>/`   |

A skill is a folder with a `SKILL.md` file: YAML front matter with `name` and `description`, followed by
Markdown instructions. It can also hold supporting files. A **source** is a Git repository or a directory
with a top-level `skills/` folder, one subfolder per skill:

```
skills/
├── pdf/SKILL.md
└── kick-off/
    ├── SKILL.md
    └── scripts/run.sh
```

## Requirements

- Maven 3.6.3+
- Java 17+
- `git` on the `PATH`, version 2.25 or later for sparse clones

## Setup

Add the plugin and your sources to `pom.xml`:

```xml
<plugin>
  <groupId>br.com.codelikeaboss</groupId>
  <artifactId>skill-sommelier-maven-plugin</artifactId>
  <version>1.0.0</version>
  <executions>
    <!-- optional: keep installed skills up to date on every build -->
    <execution>
      <goals><goal>sync</goal></goals>
    </execution>
  </executions>
  <configuration>
    <catalog>
      <sources>
        <source>
          <name>anthropics</name>
          <url>https://github.com/anthropics/skills</url>
          <ref>main</ref>                     <!-- optional: branch, tag or commit id -->
        </source>
        <source>
          <name>company</name>
          <url>https://github.com/acme/private-skills.git</url>
          <token>${env.GITHUB_TOKEN}</token>   <!-- optional, HTTPS only -->
        </source>
        <source>
            <name>addyosmani-agent-skills</name>
            <url>https://github.com/addyosmani/agent-skills</url>
        </source>
        <source>
            <name>ponytail</name>
            <url>https://github.com/DietrichGebert/ponytail</url>
        </source>        
        <source>
          <name>shared</name>
          <url>../shared-skills</url>          <!-- local folder, relative to this pom -->
        </source>
      </sources>
    </catalog>
    <!-- optional: skills this project must have, installed and removed by sync -->
    <skills>
      <skill>
        <name>pdf</name>
        <source>anthropics</source>
        <target>claude,copilot</target>
      </skill>
    </skills>
  </configuration>
</plugin>
```

Keep `<configuration>` at the plugin level, as above, not inside an `<execution>`: goals run from the command
line (`add`, `check`, …) only see plugin-level configuration.

To use the short `skill-sommelier:` prefix, add the group to `~/.m2/settings.xml`:

```xml
<pluginGroups>
  <pluginGroup>br.com.codelikeaboss</pluginGroup>
</pluginGroups>
```

## Usage

```bash
mvn skill-sommelier:list                                   # skills per source, with descriptions and install status
mvn skill-sommelier:add                                    # interactive: source → skills → agent
mvn skill-sommelier:add -Dsource=anthropics/pdf -Dtarget=claude
mvn skill-sommelier:add -Dsource=anthropics -Dskill=pdf    # same, explicit
mvn skill-sommelier:add -Dsource=https://github.com/acme/skills -Dref=v2 -Dskill=x   # pinned URL source
mvn skill-sommelier:sync                                   # update / restore installed skills now
mvn skill-sommelier:check                                  # fail if anything differs from the lock file (CI)
mvn skill-sommelier:update                                 # fetch every source, then sync
mvn skill-sommelier:remove -Dskill=pdf                     # uninstall (omit -Dskill to choose)
mvn skill-sommelier:clean                                  # free space in the clone cache
mvn skill-sommelier:help -Ddetail=true -Dgoal=add          # all parameters of a goal
```

When `add` runs without `-Dsource`/`-Dskill`, it opens menus:

- 10 items per page (`-Dskillsommelier.pageSize`)
- pick one or more: `1,3 5-7`
- `a` selects all
- `n` / `p` changes page
- `/text` filters
- `q` quits

In batch mode (`-B`, CI), missing parameters are an error instead.

`add` also works outside a Maven project. Use a URL or a path as the source:
`mvn br.com.codelikeaboss:skill-sommelier-maven-plugin:1.0.0:add -Dsource=https://github.com/anthropics/skills -Dskill=pdf`.

## Keeping skills up to date

`add` records each skill it installs in **`skill-sommelier.lock.json`**, at the project root. Commit this file.
It stores the source, the target agent, a content hash and, for Git sources, the ref and the commit the skill was
copied from. It never stores the token.

`sync` compares each recorded skill with its source:

| Situation                       | Result                                                |
|---------------------------------|-------------------------------------------------------|
| Source changed                  | new version copied, lock updated                      |
| Skill folder missing            | restored (use `remove` to uninstall)                  |
| Declared skill not installed    | installed                                             |
| Declaration removed             | uninstalled (kept, with a warning, if edited locally) |
| Skill edited locally            | kept, with a warning (`-Dskillsommelier.force=true` overwrites) |
| Source unreachable, skill gone  | warning                                               |

`sync` **never fails the build**. It also:

- works offline (`-o`) from the cache;
- during builds, contacts each source at most once every `skillsommelier.updateInterval` minutes (default 60), counting failed attempts;
- when invoked directly, always fetches;
- can be turned off with `-Dskillsommelier.sync.skip=true`.

### Pinning a version

By default a Git source follows its default branch, so `sync` brings new commits in. Add a `<ref>` to the catalog
source (branch, tag or full commit id) to stay on it: with a tag or a commit id, every machine installs exactly the
same content. Changing the `<ref>` and running `sync` moves the project to the new version. For a source outside the
catalog, use `add -Dref=…`; the ref is recorded in the lock file. Fetching a commit id needs a server that allows it
(GitHub, GitLab and Bitbucket do). Local folders are always used as they are.

### Declaring skills in the pom

Skills listed in `<skills>` are installed by `sync` without running `add`, like dependencies:

- `<name>`: the skill folder name in the source;
- `<source>`: catalog name, Git URL or local folder; optional when the catalog has a single source;
- `<target>`: one or more agents separated by commas (`opencode`, `claude`, `copilot`).

When a declaration is removed, `sync` uninstalls the skill; a skill edited locally is left on disk and dropped from
the lock file. Skills installed with `add` are never uninstalled this way. If an existing folder with the same name
differs from the source, it is not overwritten unless `-Dskillsommelier.force=true`. While a declaration is invalid
(unknown target, missing name), nothing is uninstalled.

### Checking in CI

`check` compares the project with the lock file, the declarations and the sources, changes nothing, and **fails
the build** when a skill:

- was edited locally;
- is missing or not recorded;
- is no longer declared;
- has a newer version upstream;
- or when its source is unreachable.

Run `sync` and commit the result to fix it. `-Dskillsommelier.check.upstream=false` skips the sources and only
checks the installed folders, without network access. To run it on every CI build, bind it to a phase:

```xml
<execution>
  <id>check-skills</id>
  <phase>verify</phase>
  <goals><goal>check</goal></goals>
</execution>
```

### Running when the project is opened

- **Eclipse / VS Code with the Red Hat Java extension (m2e):** once `sync` is bound as shown above, it runs when the project is imported or its configuration is updated.
- **VS Code, any Java extension:** add a task that runs when the folder opens. VS Code asks once to allow automatic tasks. Save it as `.vscode/tasks.json`:

  ```json
  {
    "version": "2.0.0",
    "tasks": [{
      "label": "Sync AI skills",
      "type": "shell",
      "command": "mvn -B -q br.com.codelikeaboss:skill-sommelier-maven-plugin:1.0.0:sync",
      "runOptions": { "runOn": "folderOpen" },
      "presentation": { "reveal": "silent" },
      "problemMatcher": []
    }]
  }
  ```
- **IntelliJ IDEA:** plugin goals do not run on import. The `validate` binding runs on every build.

## Cache and network

Remote sources are cloned into `~/.skill-sommelier/cache` (`-Dskillsommelier.cacheDir`).

- **Checkout size:** clones are shallow and sparse, so only `skills/` is checked out. Each ref of a source gets its own clone.
- **Updates:** fetch + hard reset, so upstream force-pushes are handled.
- **Concurrency:** access is locked, so an IDE sync and a terminal build can run at the same time.
- **Timeouts:** each git command has a timeout (`-Dskillsommelier.gitTimeout`, 300 s by default). Stalled HTTP transfers and unreachable SSH hosts are aborted early.
- **Cleanup:** `clean` removes clones not fetched in 30 days (`-Dskillsommelier.clean.olderThan`) and clones from older plugin versions. `-Dskillsommelier.clean.all=true` empties the cache.

## Security

- **Tokens** are passed to git through environment variables as an HTTP header. They never appear in the URL, the command line, the logs, `.git/config` or the lock file.
- **Symbolic links** inside a skill are not followed and not copied, so a source cannot make the plugin copy files from outside the repository into your project.
- **Skill names** are validated, so names such as `..` are rejected.

## Building

```bash
mvn verify        # unit tests + integration tests in src/it (maven-invoker-plugin)
mvn install       # install the plugin into ~/.m2
```

Releases are published to Maven Central and GitHub Releases by pushing a `vX.Y.Z` tag. See
[AGENTS.md](AGENTS.md#releasing).

## License

[Apache License 2.0](LICENSE)
