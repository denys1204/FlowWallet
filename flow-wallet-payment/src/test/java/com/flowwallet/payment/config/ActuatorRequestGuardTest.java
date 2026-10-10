package com.flowwallet.payment.config;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class ActuatorRequestGuardTest {
    private static final String ORIGIN = "https://evil.example";
    private static final String POD_HOST = "10.42.1.17:8082";
    private static final String LOOPBACK = "localhost:8082";
    private static final String QUERY = "reference=query-marker-7f3a";
    private static final String ACTION_PATH = "/actuator/payouts/reference-marker-9c1d/requeue";
    private static final String PAGE_DETAIL = "The actuator does not answer requests a web page sends.";
    private static final String HOST_DETAIL = "The payouts endpoint answers only a loopback host.";
    private static final String SHAPE_DETAIL = "The payouts endpoint takes no query string and no body.";
    private static final String PLAIN_DETAIL = "The path must be plain: no path parameters, escapes or dot segments.";
    private static final String OPERATOR = "X-FlowWallet-Operator";
    private static final String OPERATOR_DETAIL = "A payouts write needs the X-FlowWallet-Operator header.";
    private static final TypeReference<Map<String, Object>> PROBLEM = new TypeReference<>() {
    };

    private final WebEndpointProperties endpoints = new WebEndpointProperties();
    private final ActuatorRequestGuard guard = new ActuatorRequestGuard(endpoints);
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * What the filter did with one request: the chain it called or not, and what it wrote to the response.
     */
    private record Outcome(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
        boolean passed() {
            return chain.getRequest() != null;
        }

        String body() throws Exception {
            return response.getContentAsString();
        }
    }

    private static MockHttpServletRequest request(String method, String path, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Host", host);
        return request;
    }

    private Outcome run(MockHttpServletRequest request) throws Exception {
        return run(guard, request);
    }

    private static Outcome run(ActuatorRequestGuard filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(request, response, chain);
    }

    private void assertRefused(Outcome outcome, int status, String title, String detail) throws Exception {
        assertThat(outcome.passed()).as("the chain was called").isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(status);
        assertThat(outcome.response().getContentType()).startsWith("application/problem+json");
        Map<String, Object> problem = mapper.readValue(outcome.body(), PROBLEM);
        assertThat(problem).containsOnly(entry("title", title), entry("status", status), entry("detail", detail));
    }

    private static void assertPassed(Outcome outcome) throws Exception {
        assertThat(outcome.chain().getRequest()).isSameAs(outcome.request());
        assertThat(outcome.chain().getResponse()).isSameAs(outcome.response());
        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(outcome.body()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "/api/payments/webhooks/stripe",
            "/api/actuator/payouts",
            "/actuatorx/health",
            "/actuatorx/payouts/ref/requeue"
    })
    void aRequestOutsideTheActuatorPassesUntouchedWhateverItsHeaders(String path) throws Exception {
        // Guards the filter turning into a gate for the whole service: the webhook and the deposit calls are not the
        // actuator, and a prefix that stops at the word "actuator" would refuse "/actuatorx" as well.
        MockHttpServletRequest request = request("POST", path, "evil.example");
        request.addHeader("Origin", ORIGIN);
        request.addHeader("Sec-Fetch-Site", "cross-site");
        request.setQueryString(QUERY);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        assertPassed(run(request));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET, /actuator",
            "GET, /actuator/health",
            "POST, /actuator/outbox",
            "GET, /actuator/payoutsx",
            "GET, /actuator/payouts",
            "POST, /actuator/payouts/reference-marker-9c1d/requeue"
    })
    void anActuatorRequestWithAnOriginIsRefusedAsAForbiddenProblemAndNeverReachesTheChain(
            String method,
            String path
    ) throws Exception {
        // Guards a write a web page sends to localhost: a POST with no body and no custom header needs no preflight,
        // so the browser sends it, and only the Origin it always adds tells it from a call of the operator's curl.
        MockHttpServletRequest request = request(method, path, LOOPBACK);
        request.addHeader("Origin", ORIGIN);

        Outcome outcome = run(request);

        assertRefused(outcome, 403, "Forbidden", PAGE_DETAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://localhost:8082", "null"})
    void anyOriginIsRefusedEvenTheOneOfTheSameHostOrTheOpaqueOne(String origin) throws Exception {
        // Guards an allow-list of origins, which a page on another port of localhost, a sandboxed frame (it sends the
        // text null) or a rebound name would walk through: no page has a business at the actuator.
        MockHttpServletRequest request = request("POST", ACTION_PATH, LOOPBACK);
        request.addHeader("Origin", origin);

        assertRefused(run(request), 403, "Forbidden", PAGE_DETAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-origin", "cross-site", "same-site"})
    void aFetchSiteOtherThanNoneIsRefusedAsAForbiddenProblem(String fetchSite) throws Exception {
        // Guards a page whose request carries no Origin, such as a GET, reaching the actuator: the browser says where
        // the request came from in Sec-Fetch-Site, and only one the user typed or opened by hand says none.
        MockHttpServletRequest request = request("GET", "/actuator/payouts", LOOPBACK);
        request.addHeader("Sec-Fetch-Site", fetchSite);

        assertRefused(run(request), 403, "Forbidden", PAGE_DETAIL);
    }

    @Test
    void aFetchSiteOfNonePasses() throws Exception {
        // Guards the operator's own browser tab being locked out: a request typed into the address bar says none, and
        // is the one request of a browser the actuator should answer.
        MockHttpServletRequest request = request("GET", "/actuator/payouts", LOOPBACK);
        request.addHeader("Sec-Fetch-Site", "none");

        assertPassed(run(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator",
            "/actuator/health",
            "/actuator/health/readiness",
            "/actuator/info",
            "/actuator/metrics",
            "/actuator/outbox"
    })
    void aPlainRequestToTheActuatorPassesWhateverItsHost(String path) throws Exception {
        // Guards the kubelet's probes and a scraper, which reach the pod by its IP and send neither Origin nor
        // Sec-Fetch-Site: only the payouts endpoint answers a loopback host alone.
        MockHttpServletRequest request = request("GET", path, POD_HOST);
        request.addHeader("User-Agent", "kube-probe/1.33");

        assertPassed(run(request));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapesOfARequestToAnotherEndpoint")
    void aQueryStringOrABodyOutsideThePayoutsEndpointPasses(
            String path,
            Consumer<MockHttpServletRequest> shape
    ) throws Exception {
        // Guards the shape rule leaking to the whole actuator: a metric is read with a tag in the query string, and
        // the outbox's own write may carry a body, so only the payouts endpoint is cut down to a bare path.
        MockHttpServletRequest request = request("POST", path, POD_HOST);
        shape.accept(request);

        assertPassed(run(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost",
            "localhost:8082",
            "127.0.0.1",
            "127.0.0.1:8082",
            "[::1]",
            "[::1]:8082",
            "LOCALHOST"
    })
    void thePayoutsEndpointAnswersALoopbackHost(String host) throws Exception {
        // Guards the operator locked out of the endpoint on the way they reach it: the three loopback spellings, with
        // and without the port and in any case, are all the endpoint answers, and a curl of an action sends nothing
        // else but the operator header.
        MockHttpServletRequest request = request("POST", ACTION_PATH, host);
        request.addHeader(OPERATOR, "on-call");
        request.addHeader("User-Agent", "curl/8.7.1");
        request.addHeader("Accept", "*/*");

        assertPassed(run(request));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET, /actuator/payouts",
            "GET, /actuator/payouts/reference-marker-9c1d",
            "POST, /actuator/payouts/reference-marker-9c1d/reverse"
    })
    void everyPayoutsPathAnswersALoopbackHost(String method, String path) throws Exception {
        // Guards a guard that covers only the write: the list and the read of a payout show its Stripe ids, and carry
        // the same host rule.
        MockHttpServletRequest request = request(method, path, LOOPBACK);
        request.addHeader(OPERATOR, "on-call");

        assertPassed(run(request));
    }

    @Test
    void aWriteWithNoBodyOrWithAnEmptyOnePasses() throws Exception {
        // Guards a body rule that reads an empty POST as a body: an action is a POST with no data, which a client
        // sends with no Content-Length or with a zero one.
        MockHttpServletRequest none = request("POST", ACTION_PATH, LOOPBACK);
        none.addHeader(OPERATOR, "on-call");
        MockHttpServletRequest empty = request("POST", ACTION_PATH, LOOPBACK);
        empty.addHeader(OPERATOR, "on-call");
        empty.setContent(new byte[0]);

        assertPassed(run(none));
        assertPassed(run(empty));
    }

    @ParameterizedTest(name = "operator header \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void aPayoutsWriteWithoutTheOperatorHeaderIsRefused(String value) throws Exception {
        // Guards the one write a page can still send: an empty cross-origin form post from a browser that sends
        // neither Origin nor Sec-Fetch-Site. A page cannot add a custom header without a preflight, which the
        // actuator never answers, so only a terminal sends this one.
        MockHttpServletRequest request = request("POST", ACTION_PATH, LOOPBACK);
        if (value != null) {
            request.addHeader(OPERATOR, value);
        }

        assertRefused(run(request), 400, "Bad Request", OPERATOR_DETAIL);
    }

    @Test
    void anEmptyFormPostFromABrowserThatSendsNoOriginIsRefused() throws Exception {
        // Guards the legacy-browser seam end to end: a form post with no fields, addressed to localhost, with no
        // Origin and no Sec-Fetch-Site, passes every other rule.
        MockHttpServletRequest request = request("POST", ACTION_PATH, "localhost:8082");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(new byte[0]);

        assertRefused(run(request), 400, "Bad Request", OPERATOR_DETAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/payouts", "/actuator/payouts/reference-marker-9c1d"})
    void aPayoutsReadNeedsNoOperatorHeader(String path) throws Exception {
        // Guards a header rule that locks the operator out of the list, which they read before any write.
        assertPassed(run(request("GET", path, LOOPBACK)));
    }

    @Test
    void aWriteToAnotherActuatorEndpointNeedsNoOperatorHeader() throws Exception {
        // Guards the outbox requeue, a write of another endpoint, being refused for a header only payouts asks for.
        assertPassed(run(request("POST", "/actuator/outbox", POD_HOST)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10.0.0.5",
            "10.0.0.5:8082",
            "evil.example",
            "evil.example:8082",
            "localhost.evil.example",
            "127.0.0.1.evil.example",
            "0.0.0.0",
            "[::2]"
    })
    void thePayoutsEndpointRefusesAHostThatIsNotLoopback(String host) throws Exception {
        // Guards a rebound name reaching the endpoint through a client that sends neither Origin nor Sec-Fetch-Site:
        // such a request carries the attacker's name in Host, which the operator's own curl never does.
        Outcome outcome = run(request("POST", ACTION_PATH, host));

        assertRefused(outcome, 403, "Forbidden", HOST_DETAIL);
        assertThat(outcome.body()).doesNotContain(host);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET, /actuator/payouts",
            "GET, /actuator/payouts/reference-marker-9c1d",
            "POST, /actuator/payouts/reference-marker-9c1d/reverse"
    })
    void everyPayoutsPathRefusesAHostThatIsNotLoopback(String method, String path) throws Exception {
        // Guards a host rule on the write alone: a rebound page can read the list and a payout's Stripe ids as well.
        assertRefused(run(request(method, path, "evil.example")), 403, "Forbidden", HOST_DETAIL);
    }

    @ParameterizedTest(name = "{1} to {0}")
    @MethodSource("shapesOfAPayoutsRequest")
    void aQueryStringOrABodyToThePayoutsEndpointIsRefusedAsABadRequest(
            String path,
            Consumer<MockHttpServletRequest> shape
    ) throws Exception {
        // Guards the actuator merging a query parameter or a body field over the reference and the action in the
        // path: the operator would name one payout and move another, and the rule has to hold for every path of the
        // endpoint.
        MockHttpServletRequest request = request("POST", path, LOOPBACK);
        shape.accept(request);

        assertRefused(run(request), 400, "Bad Request", SHAPE_DETAIL);
    }

    @Test
    void aRefusalNeverQuotesTheOriginTheHostTheQueryStringOrThePath() throws Exception {
        // Guards a problem detail that echoes what the caller sent, which a page or a scanner would use to write into
        // the response (docs/adr/0026-problem-details-never-quote-rejected-input.md): every detail is fixed text.
        MockHttpServletRequest page = request("POST", ACTION_PATH, LOOPBACK);
        page.addHeader("Origin", ORIGIN);
        MockHttpServletRequest rebound = request("POST", ACTION_PATH, "evil.example");
        MockHttpServletRequest merged = request("POST", ACTION_PATH, LOOPBACK);
        merged.setQueryString(QUERY);

        String bodyOfPage = run(page).body();
        String bodyOfRebound = run(rebound).body();
        String bodyOfMerged = run(merged).body();

        assertThat(bodyOfPage).isNotEmpty().doesNotContain("evil.example", "reference-marker-9c1d");
        assertThat(bodyOfRebound).isNotEmpty().doesNotContain("evil.example", "reference-marker-9c1d");
        assertThat(bodyOfMerged).isNotEmpty().doesNotContain("query-marker-7f3a", "reference-marker-9c1d");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/payoutsx",
            "/actuator/payoutsx/reference-marker-9c1d/requeue",
            "/actuator/payouts-archive",
            "/actuator/payout",
            "/actuator/outbox/payouts"
    })
    void aPathThatOnlyStartsLikeThePayoutsEndpointIsNotTreatedAsIt(String path) throws Exception {
        // Guards a prefix test on the characters instead of the path segments: "/actuator/payoutsx" is another
        // endpoint, which gets the host-free rule of the rest of the actuator, and a plain request to it passes.
        MockHttpServletRequest request = request("POST", path, POD_HOST);
        request.setQueryString(QUERY);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        assertPassed(run(request));
    }

    @Test
    void aChangedBasePathMovesTheGuardWithIt() throws Exception {
        // Guards a guard bound to the word "actuator": management.endpoints.web.base-path moves the endpoints, and a
        // filter that kept watching the old path would leave the new one open to a page, and refuse the old one.
        endpoints.setBasePath("/manage");
        ActuatorRequestGuard moved = new ActuatorRequestGuard(endpoints);
        MockHttpServletRequest page = request("POST", "/manage/outbox", LOOPBACK);
        page.addHeader("Origin", ORIGIN);
        MockHttpServletRequest rebound = request("POST", "/manage/payouts/reference-marker-9c1d/requeue", POD_HOST);
        MockHttpServletRequest oldPath = request("POST", "/actuator/payouts/reference-marker-9c1d/requeue", POD_HOST);
        oldPath.addHeader("Origin", ORIGIN);

        assertRefused(run(moved, page), 403, "Forbidden", PAGE_DETAIL);
        assertRefused(run(moved, rebound), 403, "Forbidden", HOST_DETAIL);
        assertPassed(run(moved, oldPath));
    }

    @Test
    void theContextPathIsNotPartOfThePathTheGuardJudges() throws Exception {
        // Guards a guard that reads the raw request URI: under a context path the actuator sits at the path after it,
        // and a prefix test on the whole URI would let the endpoint through.
        MockHttpServletRequest rebound = request("POST", "/pay" + ACTION_PATH, POD_HOST);
        rebound.setContextPath("/pay");
        MockHttpServletRequest other = request("POST", "/pay/api/payments/webhooks/stripe", POD_HOST);
        other.setContextPath("/pay");
        other.addHeader("Origin", ORIGIN);

        assertRefused(run(rebound), 403, "Forbidden", HOST_DETAIL);
        assertPassed(run(other));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator;x=1/payouts/reference-marker-9c1d/requeue",
            "/%61ctuator/payouts/reference-marker-9c1d/requeue",
            "/actuator;x=1/health",
            "/actuator/payouts;x=1/reference-marker-9c1d/requeue",
            "/actuator/%70ayouts/reference-marker-9c1d/requeue",
            "//actuator/payouts/reference-marker-9c1d/requeue",
            "/actuator//payouts/reference-marker-9c1d/requeue",
            "/actuator/./payouts/reference-marker-9c1d/requeue",
            "/x/../actuator/payouts/reference-marker-9c1d/requeue",
            "/actuator\\payouts/reference-marker-9c1d/requeue",
            "/api/payments/webhooks/stripe;x=1",
            "/api/payments/webhooks/%73tripe"
    })
    void aPathWithAPathParameterAnEscapeOrADotSegmentIsRefusedOnEveryRoute(String path) throws Exception {
        // Guards the whole filter being walked around: Spring MVC matches a path after decoding it and dropping its
        // parameters, so a page's POST to "/actuator;x=1/..." or an escaped letter would reach the endpoint while the
        // guard read another path. Tomcat with a probe app showed such spellings answering 200.
        MockHttpServletRequest request = request("POST", path, "evil.example");
        request.addHeader("Origin", ORIGIN);

        assertRefused(run(request), 400, "Bad Request", PLAIN_DETAIL);
    }

    @ParameterizedTest
    @CsvSource({
            "/actuator/payouts/reference-marker-9c1d/requeue, true",
            "/api/payments/webhooks/stripe/connect, true",
            "/, true",
            "'', true",
            "/actuator;x=1/payouts, false",
            "/actuator/%70ayouts, false",
            "//actuator, false",
            "/actuator//payouts, false",
            "/actuator/./payouts, false",
            "/actuator/../actuator/payouts, false",
            "/actuator/.., false",
            "/actuator\\payouts, false",
            "/actuator/.well-known, true",
            "/actuator/payouts/a.b, true"
    })
    void aPathIsPlainOnlyWithoutParametersEscapesDoubledSlashesAndDotSegments(String path, boolean plain) {
        // Guards a spelling that Spring MVC reads as another path passing the check, and a dot inside a segment, which
        // reads the same either way, being refused.
        assertThat(ActuatorRequestGuard.plain(path)).isEqualTo(plain);
    }

    private static Stream<Arguments> shapesOfARequestToAnotherEndpoint() {
        return Stream.of(
                Arguments.of(
                        "/actuator/metrics/jvm.memory.used",
                        Named.<Consumer<MockHttpServletRequest>>of(
                                "a metric with a tag in the query string",
                                request -> request.setQueryString("tag=area:heap")
                        )
                ),
                Arguments.of(
                        "/actuator/outbox",
                        Named.<Consumer<MockHttpServletRequest>>of(
                                "the outbox with a body",
                                request -> request.setContent("{}".getBytes(StandardCharsets.UTF_8))
                        )
                ),
                Arguments.of(
                        "/actuator/outbox",
                        Named.<Consumer<MockHttpServletRequest>>of(
                                "the outbox with a chunked body",
                                request -> request.addHeader("Transfer-Encoding", "chunked")
                        )
                )
        );
    }

    private static Stream<Arguments> shapesOfAPayoutsRequest() {
        Named<Consumer<MockHttpServletRequest>> query = Named.of(
                "a query string",
                request -> request.setQueryString(QUERY)
        );
        Named<Consumer<MockHttpServletRequest>> emptyQuery = Named.of(
                "an empty query string",
                request -> request.setQueryString("")
        );
        Named<Consumer<MockHttpServletRequest>> body = Named.of(
                "a body",
                request -> request.setContent("{\"action\":\"reverse\"}".getBytes(StandardCharsets.UTF_8))
        );
        Named<Consumer<MockHttpServletRequest>> chunked = Named.of(
                "a chunked body",
                request -> request.addHeader("Transfer-Encoding", "chunked")
        );
        return Stream.of(ACTION_PATH, "/actuator/payouts/reference-marker-9c1d", "/actuator/payouts")
                .flatMap(path -> Stream.of(query, emptyQuery, body, chunked).map(shape -> Arguments.of(path, shape)));
    }
}
