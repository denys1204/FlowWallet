# 0047. Each service release publishes its image to ghcr.io once, from the tagged commit, with build provenance

- Status: Accepted, extends [0040](0040-services-are-released-on-their-own.md),
  [0042](0042-release-please-runs-as-a-github-app.md) and
  [0046](0046-services-are-built-into-images-by-jib-on-a-pinned-distroless-base.md)
- Date: 2026-11-08

## Context

The DevOps lab promotes one image digest per service from environment to environment, so it needs each released image
in a registry. [0046](0046-services-are-built-into-images-by-jib-on-a-pinned-distroless-base.md) builds the images and
pushes none, and [0040](0040-services-are-released-on-their-own.md) names a version only in the release tag,
`<component>/v<version>`.

Five facts shape the decision:

- release-please creates each release, and its tag, with the GitHub App's token
  ([0042](0042-release-please-runs-as-a-github-app.md)), so the release can start other workflows. The repository's
  events show a `release` event, action `published`, for every release so far, and no push event for any release tag.
- The `release tags` ruleset keeps a tag from being moved or deleted, but a tag on ghcr.io can be pushed again with
  other content.
- A Maven build runs the code of every plugin and dependency it resolves. A credential in the same job is in reach of
  that code.
- GitHub's build provenance attestations are free for a public repository and are checked with
  `gh attestation verify`. Storage records, which list an attested artifact in the repository, exist only for
  repositories an organization owns.
- The releases tagged before the image build existed, up to `payment/v1.0.4`, `wallet/v1.0.1` and `gateway/v1.0.0`,
  are on commits without this workflow, and only `payment/v1.0.4` has the Jib configuration.

## Decision

- `.github/workflows/publish-images.yml` runs on `release: published`, and on `workflow_dispatch` started on a tag
  (`gh workflow run publish-images.yml --ref <tag>`) for a run that can no longer be rerun. Each run publishes the one
  service its release names, as `ghcr.io/denys1204/flowwallet-<component>:<version>`, with the labels
  `org.opencontainers.image.version`, `org.opencontainers.image.revision` and `org.opencontainers.image.source`.
- The job `build` holds no credential that can write anywhere: a read-only `GITHUB_TOKEN`, no registry login, no
  OIDC token. It refuses a tag that is not `<payment|wallet|gateway>/vX.Y.Z`, a tagged commit that is not on `main`, and
  a version that `.release-please-manifest.json` at that commit does not record. It builds the image with
  `jib:buildTar` from Maven Central, with no Maven cache and no tests, and hands the tarball on as an artifact.
- The job `publish` runs no build code. It unpacks the tarball, an OCI layout, reads the digest from it and pushes the
  layout with `skopeo copy --preserve-digests`, which keeps its bytes and so its digest. A version is pushed once: when
  the tag already holds the same digest the push is skipped, and when it holds another digest the run fails. The job
  then attests the digest with `actions/attest`, pushing the attestation to the registry, and writes the pinned
  reference to the job summary and to the end of the release notes.
- No moving tag such as `latest` is pushed. Only `linux/amd64` is built, as in
  [0046](0046-services-are-built-into-images-by-jib-on-a-pinned-distroless-base.md).

## Alternatives considered

- `push` of the tag as the trigger: no such event was recorded for any release the App or `GITHUB_TOKEN` made, while
  `release: published` was.
- A job in `release-please.yml` on that action's outputs: the build would run in the workflow that holds the App's
  private key, or that workflow would need `packages` and `id-token` permissions, and republishing one service would
  mean rerunning release-please.
- `jib:build` in a single job: every Maven plugin would run next to the registry credential and, with the attestation
  in the same job, the OIDC request token.
- `workflow_dispatch` from `main` with a tag as input: the provenance would name `main`'s ref and commit as the source
  of an image built from another commit.
- Pushing the tag again on a rerun: the lab and the attestation pin a digest, but `:<version>` would stop naming one
  image.
- No provenance: the attestation costs one action and two permissions, and without it the lab has nothing for
  `gh attestation verify` to check.
- An SBOM and a cosign signature: nothing in the lab reads them yet, and the attestation already signs the digest
  through Sigstore.
- A Maven cache: a run on a tag restores caches that runs on `main` wrote.
- Images for the earlier releases: only `payment/v1.0.4` has the Jib configuration, and its commit holds no workflow.
- A `latest` tag: it moves, and the lab deploys digests.

## Consequences

- Each release gets one image, pushed once; a rerun that builds another digest fails instead of moving the tag. A
  rerun that builds the same digest finishes the attestation and the release notes of a run that stopped after the
  push, and adds a second attestation for that digest.
- Releases tagged before this workflow have no image. Neither do `payment/v1.0.5`, `wallet/v1.0.2` and
  `gateway/v1.0.1`, the first releases after it: their runs read the tarball with skopeo's `oci-archive` reader, which
  fails without root, and pushed nothing.
- A package that the workflow pushes first is linked to the repository and takes its public visibility, so anyone can
  pull the images without credentials.
- The release notes, which release-please writes, end in an `Image` section with the pinned reference.
- The Java release is also `java-version` in `publish-images.yml`.
- Each release run downloads the dependencies from Maven Central, and the job relies on the `skopeo` the runner image
  ships.
