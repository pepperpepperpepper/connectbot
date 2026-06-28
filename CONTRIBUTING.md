# Contributing

This repository is a **hard fork** of
[connectbot/connectbot](https://github.com/connectbot/connectbot), maintained
independently. We do **not** submit pull requests to the upstream project, and
changes here are not expected to flow back upstream.

Work happens directly in this repository.

## Workflow

* Create a topic branch from the branch you're basing your work on.
  * `git checkout -b my_fix main`
* Make commits of logical units with clear messages.
* Before pushing, make sure things build and pass:
  * `./gradlew assemble`
  * `./gradlew check test`
  * `./gradlew lint` — check that no new lint issues were introduced on your
    changed lines.
  * `git diff --check` — catch stray whitespace.
* Push your branch to `origin` and merge it here. There is no upstream pull
  request step.

## Notes

* This fork ships its own builds; see the project's release/publishing notes for
  the F-Droid and Play-style baseline details.
* The upstream project's contribution guidelines, issue templates, and pull
  request process do not apply to this fork.
