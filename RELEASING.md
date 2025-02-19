Releasing
========

 1. Update the version in `gradle.properties`.
 2. Update the `CHANGELOG.md` for the impending release.
 3. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
 4. `git tag -a X.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
 5. `git push && git push --tags`
 6. Publish to [Sonatype's staging repository](https://s01.oss.sonatype.org/#stagingRepositories) with `./gradlew publish`
 7. If the build contents look good Close and Release the staging build once the validation succeeds.
 6. Generate newly updated documentation with `./gradlew dokkaHtml`.
 7. Deploy new documentation to Github pages by pushing new contents of `/docs` to main.
