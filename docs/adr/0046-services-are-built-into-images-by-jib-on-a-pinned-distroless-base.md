# 0046. Each service is built into an image by Jib on a digest-pinned distroless base, with no version of its own

- Status: Accepted, extends [0040](0040-services-are-released-on-their-own.md)
- Date: 2026-11-08

## Context

The DevOps lab deploys the three services as container images and promotes one image digest from environment to
environment instead of rebuilding it. Under [0040](0040-services-are-released-on-their-own.md) only the services are
deployed, and no image is tagged from `${project.version}`, because a service pom shows its last released version
between releases.

Four facts shape the decision:

- Jib builds an image from the Maven build, with no Dockerfile and no Docker daemon, and puts the dependencies, the
  snapshot dependencies, the resources and the classes in separate layers. A `jib:` goal named on the command line
  runs in every module of the reactor and skips only `pom` packaging.
- A digest is reproducible only if every layer is. Jib sets the image's creation time and its file times to the epoch,
  but the library jars in the snapshot layer keep their entry dates. Maven 3.10, the version `./mvnw` runs, dates every
  entry `1980-02-01` by default; Maven 3.9 dates them at build time, so each build gives a new digest.
- Each service binds `server.address` to `127.0.0.1` unless its `*_ADDRESS` variable says otherwise, and takes
  `X-User-Id` on trust. [0031](0031-callers-are-authenticated-in-front-of-the-gateway.md) leaves the wider address to
  the deployment, which also has to put an authentication layer in front of the gateway.
- The gateway's classpath carries Netty's native epoll library, `netty-transport-native-epoll`, for `linux-x86_64`
  only.

## Decision

- `jib-maven-plugin` is configured once, in the root `pom.xml`'s `pluginManagement`, and bound to no phase. The root
  sets `jib.skip` to `true`; the three services set it to `false` and name their image in `jib.to.image`:
  `flowwallet-payment`, `flowwallet-wallet` and `flowwallet-gateway`, after their release components. The libraries get
  no image of their own and reach the services' images as jars.
- The base is `gcr.io/distroless/java25-debian13:nonroot`, pinned by its index digest. It runs as the numeric user
  `65532` and has no shell and no package manager. The digest is refreshed by hand, and the `java25` in its name changes
  with `java.version`.
- Only `linux/amd64` is built, the platform the lab runs.
- The image is in OCI format and keeps Jib's defaults: the epoch as creation and file time, and an entrypoint of
  `java -cp @/app/jib-classpath-file` with the main class Jib infers.
- The root `pom.xml` sets `project.build.outputTimestamp` to `1980-02-01T00:00:00Z`, the value Maven 3.10 uses by
  default. An image is built with the full reactor or with `-pl <service> -am`.
- The image carries no version, label, port, JVM flag or bind address, and a local build tags it `latest`. The
  deployment sets the bind address and the heap size.

## Alternatives considered

- A Dockerfile per service: it needs a Docker daemon on the runner, the three files drift apart, and the layer split is
  written by hand.
- `spring-boot:build-image` with Cloud Native Buildpacks: it needs a daemon and a builder image, and builds a larger
  image more slowly.
- `eclipse-temurin:25-jre-noble`, Jib's own base for Java 25: it runs as root unless the pom sets a user, carries a
  shell and a package manager, and is about 104 MB compressed against about 73 MB for the distroless base.
- The base named by its tag alone: two builds of one commit would give different digests whenever the tag moves.
- Images for `linux/amd64` and `linux/arm64`: `jib:buildTar` refuses more than one platform and `jib:dockerBuild` builds
  only the daemon's, so nothing local could check the second. On arm64 the gateway would run Netty without epoll.
  A developer on an arm64 machine builds a local image with `-Djib.from.platforms=linux/arm64`.
- `0.0.0.0` as the image's bind address: `docker run -p` would then publish the unauthenticated API of every service on
  each interface of the host, and only the gateway warns about a wide address. A container that keeps the loopback
  address answers nothing on its published port, so a missing setting shows up at the first request or probe.
- JVM flags in the image: `jvmFlags` sit on the java command line, where no environment variable can override them, and
  defaults in `JDK_JAVA_OPTIONS` disappear without a trace when a deployment sets that variable for another reason. The
  heap size belongs next to the memory limit it depends on.
- The tag or labels in the pom: [0040](0040-services-are-released-on-their-own.md) rules out a tag from
  `${project.version}`, and labels given on the command line replace the pom's labels as a whole.
- Jib bound to the `package` phase: every build, the unit-test runs included, would pull the base image.

## Consequences

- `./mvnw clean package -DskipTests jib:buildTar` writes each service's image to `<service>/target/jib-image.tar` and
  its digest to `jib-image.digest` without a Docker daemon, and `jib:dockerBuild` loads the images into the local
  daemon. Two builds of one commit give the same digests; a build of one service without `-am` takes the libraries from
  `~/.m2` and gives another.
- Dependabot updates the plugin's version, which is a Maven property, but not the base digest. A base refresh changes
  only the root `pom.xml` and so releases no service on its own.
- A build that publishes an image names and labels it by overriding `jib.to.image` and `jib.container.labels` on the
  command line, one service per run, and reads its digest from `<service>/target/jib-image.digest`.
- A service in a container listens on loopback until the deployment sets its `*_ADDRESS` variable to `0.0.0.0`.
- The Java release is written in three places: `java.version` in the root `pom.xml`, `java-version` in `ci.yml` and the
  `java25` in the base image's name.
- Lombok, an optional compile dependency, sits on the image's classpath, because Jib copies the runtime classpath. It
  does nothing at run time.
- The build warns that the `${start-class}` main class in the Spring Boot parent's `maven-jar-plugin` configuration is
  not a valid class, then infers the main class itself.
