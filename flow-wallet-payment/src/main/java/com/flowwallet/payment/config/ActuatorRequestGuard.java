package com.flowwallet.payment.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps the actuator, whose writes authenticate nobody, out of reach of a web page. A page open in the operator's
 * browser can send a POST with no body to {@code localhost} without a preflight, and through DNS rebinding read the
 * answers too, so binding to loopback alone does not stop it.
 * <ul>
 *     <li>A path with a path parameter, an escape, a doubled slash or a dot segment is refused on every route. Spring
 *     MVC matches a path after decoding it and dropping its parameters, so {@code /actuator;x=1/payouts} would reach
 *     the endpoint while a check of the path as sent saw another one; no route of Payment Service needs such a path,
 *     and on a plain one both read the same.</li>
 *     <li>A request a browser sends for a page, one with an {@code Origin} header or a {@code Sec-Fetch-Site} other
 *     than {@code none}, is refused on every actuator path; probes, scrapers and curl send neither.</li>
 *     <li>The {@code payouts} endpoint, whose writes credit wallets, also answers only a loopback {@code Host}, so
 *     a rebound name never reaches it, and refuses a query string or a body, which the actuator would merge over the
 *     reference and the action in its path. Its write needs the {@value #OPERATOR_HEADER} header: a page cannot set
 *     a custom header across origins without a preflight, which the actuator never answers, so even a browser that
 *     sends neither {@code Origin} nor {@code Sec-Fetch-Site} on a form post cannot send the write.</li>
 * </ul>
 * See docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
@Slf4j
@Component
public class ActuatorRequestGuard extends OncePerRequestFilter {
    static final String OPERATOR_HEADER = "X-FlowWallet-Operator";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final String basePath;

    public ActuatorRequestGuard(WebEndpointProperties endpoints) {
        this.basePath = endpoints.getBasePath();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String path = path(request);
        if (!plain(path)) {
            log.warn("Refused a request whose path is not plain");
            refuse(
                    response,
                    HttpStatus.BAD_REQUEST,
                    "The path must be plain: no path parameters, escapes or dot segments."
            );
            return;
        }
        if (!under(path, basePath)) {
            chain.doFilter(request, response);
            return;
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (request.getHeader("Origin") != null || (fetchSite != null && !fetchSite.equals("none"))) {
            log.warn("Refused an actuator request a web page sent to {}", path);
            refuse(response, HttpStatus.FORBIDDEN, "The actuator does not answer requests a web page sends.");
            return;
        }
        if (under(path, basePath + "/payouts")) {
            if (!LOOPBACK_HOSTS.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
                log.warn("Refused a payouts request to {} for a host other than loopback", path);
                refuse(response, HttpStatus.FORBIDDEN, "The payouts endpoint answers only a loopback host.");
                return;
            }
            if (request.getQueryString() != null
                    || request.getContentLengthLong() > 0
                    || request.getHeader("Transfer-Encoding") != null) {
                refuse(response, HttpStatus.BAD_REQUEST, "The payouts endpoint takes no query string and no body.");
                return;
            }
            String operator = request.getHeader(OPERATOR_HEADER);
            if (request.getMethod().equals("POST") && (operator == null || operator.isBlank())) {
                refuse(response, HttpStatus.BAD_REQUEST, "A payouts write needs the " + OPERATOR_HEADER + " header.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /**
     * Whether Spring MVC matches the path as sent: no path parameter, escape, backslash, doubled slash or dot segment.
     */
    static boolean plain(String path) {
        if (path.indexOf(';') >= 0 || path.indexOf('%') >= 0 || path.indexOf('\\') >= 0 || path.contains("//")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * A problem response in the platform's shape, with a fixed detail that quotes nothing the request sent.
     */
    private static void refuse(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                        + ",\"detail\":\"" + detail + "\"}"
        );
    }
}
