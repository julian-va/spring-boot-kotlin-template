---
name: security
description: Apply backend and frontend security practices covering OWASP Top 10, input validation, authn/authz, secrets management, secure headers, SCA/SAST/DAST scanning and a security PR checklist.
version: 0.2.1
tags: [security, backend, frontend, best-practices]
---

# SKILL: Security Best Practices

## Purpose

Security practices for humans and agents to validate, review and implement secure features consistently. The backend sections target **Kotlin 2.3 / Spring Boot 4 / Spring Security 7 / Java 25**; adapt the framework details when the project uses another stack, but keep the principles and the checklist.

## Scope

- Backend services (APIs, microservices, workers) built with Kotlin and Spring Boot 4.
- Frontend applications **only when the project ships a browser client**. For pure backend repositories, skip that section entirely.
- CI/CD pipelines, containers and deployment artifacts.

## Core principles

- **Defense in depth:** assume any single control can fail.
- **Least privilege:** minimal permissions by default, for users, services and tokens.
- **Fail securely:** default-deny, and never leak internals in errors.
- **Secure defaults:** the safe path must also be the easy path.
- **No security by obscurity:** hiding an endpoint is not access control.
- **Threat model** anything that touches auth, money, PII or the network boundary.

---

## Reasoning checklist (required before code)

Return this before implementing or changing security-relevant code, the same way `kotlin-backend` requires its own checklist.

1. Assets and data classification (public / internal / confidential / regulated).
2. Threats and abuse cases for this change (STRIDE or the OWASP category it maps to).
3. Trust boundaries crossed (client to API, service to DB, service to service).
4. Authentication and authorization decisions, including **who owns the resource**.
5. Inputs accepted, validation rules and size limits.
6. Secrets involved and where they come from.
7. Logging and audit impact: what is recorded and what must be redacted.
8. Residual risk and any accepted exception.

---

## Backend: Kotlin + Spring Boot 4 / Spring Security 7

### Filter chain

Use the lambda DSL; the `and()` chaining style is gone. Keep one explicit chain per security domain.

```kotlin
@Bean
fun api(http: HttpSecurity): SecurityFilterChain {
	http
		.authorizeHttpRequests { auth ->
			auth.requestMatchers("/actuator/health/**").permitAll()
			auth.anyRequest().authenticated()
		}
		.oauth2ResourceServer { oauth2 -> oauth2.jwt(Customizer.withDefaults()) }
		.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
		.csrf { it.disable() }
	return http.build()
}
```

- Deny by default: `anyRequest().authenticated()`, never `permitAll()` as the last rule.
- If the API is truly stateless, disable CSRF **and** make sure nothing uses cookies or sessions. Otherwise keep CSRF enabled.

### Password storage

- **Argon2id** is the current OWASP recommendation; `bcrypt` is acceptable, MD5/SHA-1/PBKDF2-with-low-iterations are not.
- Never hash passwords yourself, never log them, never return them in DTOs.

```kotlin
@Bean
fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
```

Requires BouncyCastle on the classpath.

### JWT / OAuth2 resource server

Spring validates `iss` and the time claims by default, but **does not validate `aud`**. A token issued for another service of the same issuer will be accepted as yours unless you add a validator.

```kotlin
@Bean
fun jwtDecoder(@Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") issuer: String): JwtDecoder {
	val decoder = JwtDecoders.fromIssuerLocation(issuer)
	decoder.setJwtValidator(
		DelegatingOAuth2TokenValidator(
			JwtValidators.createDefaultWithIssuer(issuer),
			JwtClaimValidator<List<String>>("aud") { audience -> audience?.contains("my-api") == true },
		),
	)
	return decoder
}
```

Also:

- Reject `alg: none` and never accept the algorithm declared by the token when you can pin it.
- Keep access tokens short-lived and validate `exp`, `nbf` and clock skew.
- Do not put PII or secrets in claims: they are only signed, not encrypted.
- Do not accept tokens in URLs, query strings or logs.

### Authorization

- Enforce authorization in the **use case / service layer**, not only in controllers. Controllers can be bypassed by another entry point.
- Check **ownership** (IDOR), not just roles: `resource.ownerId == currentUser.id`.
- Method security for cross-cutting rules:

```kotlin
@EnableMethodSecurity

@PreAuthorize("hasRole('ADMIN') or #userId == authentication.name")
```

- Never trust a client-provided `userId`, `tenantId`, `role` or price. Derive them from the authenticated principal.

### Actuator and API docs exposure

- Default exposure in Spring Boot is `health` only. Keep it that way unless there is a reason.
- Isolate management on another port or a separate chain, and protect it with a distinct role.
- In Boot 4, `EndpointRequest` moved to `org.springframework.boot.security.autoconfigure.actuate.web.servlet` (in Boot 3 it was `org.springframework.boot.actuate.autoconfigure.security.servlet`).

```kotlin
@Bean
@Order(1)
fun actuator(http: HttpSecurity): SecurityFilterChain {
	http
		.securityMatcher(EndpointRequest.toAnyEndpoint())
		.authorizeHttpRequests { it.anyRequest().hasRole("ACTUATOR") }
		.httpBasic(Customizer.withDefaults())
	return http.build()
}
```

- Do not publish actuator endpoints in the OpenAPI document (`springdoc.show-actuator` stays off). They are the operational plane, not the API contract.
- Disable springdoc in production (`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`) or protect it behind authentication.

### CORS and CSRF

- Configure CORS programmatically; never `allowedOrigins("*")` together with credentials.
- Prefer an allowlist of exact origins.

```kotlin
@Bean
fun corsConfigurationSource(): CorsConfigurationSource =
	UrlBasedCorsConfigurationSource().apply {
		registerCorsConfiguration(
			"/api/**",
			CorsConfiguration().apply {
				allowedOrigins = listOf("https://app.example.com")
				allowedMethods = listOf("GET", "POST", "PUT", "DELETE")
				allowedHeaders = listOf("Authorization", "Content-Type")
			},
		)
	}
```

- With cookies, keep CSRF enabled and use `SameSite=Lax` or `Strict`.

### Error handling

- Return RFC 9457 `ProblemDetail` without stack traces, SQL fragments or internal class names.
- Spring Boot defaults are already restrictive; keep them explicit:

```yaml
server:
  error:
    include-message: never
    include-stacktrace: never
```

- Map unexpected exceptions to a generic 500 and log the details only server-side.

### Kotlin-specific pitfalls

- **`data class` leaks secrets through `toString()`.** A `data class` generates `toString()` with every property, and that string ends up in logs and error reports. Do not put passwords, tokens or full PII in a data class, or override `toString()`.
- **Validation annotations need a use-site target.** Use `@field:NotBlank`, `@field:Email` and `@field:Size` on constructor properties. The compiler flag `-Xannotation-default-target=param-property` changes the default but does not guarantee the constraint hits the backing field.
- **Platform types hide nullability.** Java APIs return `T!`; assigning that to `String` compiles but can throw at runtime. Validate external values instead of trusting the platform type.
- **`lateinit` and `!!`** bypass null-safety checks. Neither is acceptable in security-relevant code paths.

### Virtual threads, coroutines and context propagation

This template enables virtual threads. `SecurityContextHolder` stores the context in a `ThreadLocal`, which works per request but is **not inherited** by work handed to another thread.

- `@Async`, `CompletableFuture`, `ExecutorService` or reactive bridges lose the authenticated principal unless you propagate it.
- With coroutines, use `ThreadContextElement` or Micrometer Context Propagation with `SecurityContextHolderThreadLocalAccessor`; `withContext(Dispatchers.IO)` alone does **not** carry the `SecurityContext`.
- Never copy a `SecurityContext` between requests or share it as a static.

---

## Input and data handling

- **Validate on the server** with Jakarta Bean Validation (`@Valid`, `@NotBlank`, `@Size`, `@Pattern`) at the boundary. Client-side validation is UX, not security.
- **DTOs at the boundary, never entities.** Prevents mass assignment (a client setting `role` or `id`).
- **SQL/JPQL injection:** use parameter binding. Derived Spring Data queries are safe; `@Query` with string concatenation is not, and `ORDER BY` cannot be parameterized — map it through an allowlist.
- **Deserialization:** do not enable polymorphic typing on untrusted input. In Boot 4 / Jackson 3 you can also cap the parser:

```yaml
spring:
  jackson:
    deserialization:
      fail-on-unknown-properties: true
    factory:
      constraints:
        read:
          max-nesting-depth: 50
          max-document-length: 2000000
          max-string-length: 200000
```

- **XXE:** disable external entities and DTDs on any XML parser.
- **SSRF:** every outbound URL built from user input needs an allowlist of hosts and schemes, plus blocking of link-local, loopback and metadata addresses. This applies to `RestClient`, `WebClient`, `HttpClient` and webhooks.
- **Path traversal:** resolve and `normalize()` the path, then verify it still starts with the intended base directory.
- **Uploads:** validate content type **and magic bytes**, cap size, store outside the web root, generate the filename, and scan untrusted files.
- **Request limits and timeouts:** cap body size and slow requests.

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 10MB

server:
  tomcat:
    max-http-form-post-size: 1MB
    connection-timeout: 5s
```

- **Rate limiting:** per principal and per IP, on login, token and expensive endpoints. Bucket4j or the gateway; Spring has no built-in limiter.

---

## Secrets

- Never in source, `application.yaml`, tests, container images or chat. Use environment variables or a secret manager (Vault, AWS/GCP secrets, Kubernetes Secrets with encryption at rest).
- Different secrets per environment; rotate on a schedule and revoke immediately on suspicion.
- **Scan for leaked secrets**, because "we are careful" is not a control:
  - pre-commit and CI scanning with `gitleaks` or `trufflehog`;
  - GitHub push protection and secret scanning enabled;
  - `gitleaks git .` on current versions (`gitleaks detect --source .` on older ones).
- A leaked secret in git history is compromised even after deletion: rotate it, then rewrite history if the repository is public.

---

## Transport and headers

- TLS 1.3 preferred; TLS 1.2 only with AEAD suites. Disable TLS 1.0/1.1 and weak ciphers at the terminator.
- HSTS in production, after you are sure HTTPS works everywhere.
- Spring Security adds `X-Content-Type-Options`, `X-Frame-Options`, `Cache-Control` and HSTS (on secure requests) by default. Do not disable them without a reason.
- No sensitive data in URLs: they land in proxies, access logs and browser history.

---

## Dependencies and supply chain (Gradle)

- **SCA** in CI: OWASP Dependency-Check plugin (`./gradlew dependencyCheckAnalyze`) or Dependabot/Renovate.
- **Pin what you resolve.** Gradle verification metadata catches a tampered artifact:

```bash
./gradlew --write-verification-metadata sha256 help
```

- Dependency locking for reproducible builds:

```kotlin
dependencyLocking { lockAllConfigurations() }
```

- Generate an SBOM (CycloneDX plugin, `./gradlew cyclonedxDirectBom`). Spring Boot 4 can also expose it at `/actuator/sbom`.
- Review transitive upgrades; a minor version bump is still a code change.

---

## Containers and runtime

- Run as non-root, read-only root filesystem where possible, drop Linux capabilities, set CPU/memory limits.
- No secrets baked into layers; they persist in image history.
- Minimal base image (distroless or slim) and a pinned digest.
- Scan images (Trivy) and sign them; verify signatures at deploy time.

---

## Logging and observability

- Log security events: failed logins, authorization denials, token validation failures, privilege changes.
- **Never** log passwords, tokens, session IDs, full PII or request bodies that may contain them. Redact centrally, not per call site.
- Remember that Kotlin `data class` `toString()` prints every field: a `data class` with a token in it will leak on any `log.info("$dto")`.
- Do not log the `Authorization` header, even at DEBUG.
- Keep audit logs append-only and access-controlled, with a defined retention period.

---

## Testing and validation

- Add negative tests: unauthenticated request returns 401, wrong role returns 403, foreign resource returns 403/404.
- `spring-boot-starter-security-test` (Boot 4 name; in Boot 3 it was `org.springframework.security:spring-security-test`).

```kotlin
@Test
fun `rejects anonymous requests`() {
	mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized())
}

@Test
@WithMockUser(roles = ["USER"])
fun `forbids cross-tenant access`() {
	mockMvc.perform(get("/api/orders/42")).andExpect(status().isForbidden())
}
```

- DAST in staging (OWASP ZAP baseline) and SAST in CI.
- Regression test every fixed vulnerability.

---

## Frontend (only if the project has a browser client)

- Prefer **HttpOnly, Secure, SameSite cookies** over tokens in `localStorage`; `localStorage` is readable by any XSS.
- For SPAs with a backend you control, consider the **BFF pattern** instead of holding tokens in the browser.
- Content Security Policy with nonces or hashes; avoid `unsafe-inline`.
- Escape untrusted content; never assign it to `innerHTML`.
- `SameSite` cookies or anti-CSRF tokens for state-changing requests.
- Restrict CORS to exact origins; no wildcard with credentials.
- SRI for third-party scripts served from CDNs.
- OAuth **PKCE** for public clients.
- Avoid sensitive data in the DOM, page titles or metadata.

---

## CI/CD and infrastructure

- Run SAST, SCA, secret scanning and tests on every PR.
- Protect pipelines: restrict who edits workflow files and secrets; require review for CI changes.
- Reproducible builds, signed artifacts, verified before deploy.
- Infrastructure as code with reviewable diffs and policy checks.

---

## Incident response

- Playbooks for leaked credentials, dependency compromise and data exposure.
- Rotate first, investigate second.
- Post-mortem without blame, with a concrete control added for each incident.

---

## Privacy

- Data minimization and purpose limitation.
- Anonymize or aggregate where possible; define retention and deletion.
- Follow GDPR/CCPA as applicable, including data subject requests.

---

## PR checklist (mapped to OWASP Top 10)

- [ ] **A01 Broken access control** — deny by default; ownership checked in the use case, not only roles in the controller
- [ ] **A02 Cryptographic failures** — TLS, Argon2id/bcrypt, no secrets in code or logs
- [ ] **A03 Injection** — server-side validation, parameter binding, output encoding, no polymorphic deserialization
- [ ] **A04 Insecure design** — threat model, size limits, rate limiting, timeouts
- [ ] **A05 Security misconfiguration** — actuator and OpenAPI exposure reviewed; no default credentials; errors do not leak
- [ ] **A06 Vulnerable components** — SCA results reviewed; verification metadata and SBOM updated
- [ ] **A07 Auth failures** — JWT audience/expiry validated; sessions and lockout defined
- [ ] **A08 Integrity failures** — dependency verification, signed images, no untrusted deserialization
- [ ] **A09 Logging failures** — security events logged, PII and tokens redacted; no data-class `toString` leaking secrets
- [ ] **A10 SSRF** — outbound URLs from user input use an allowlist
- [ ] Secret scanning clean (gitleaks/trufflehog in pre-commit and CI); no secrets in the diff or history
- [ ] Security tests added (401/403/IDOR regression)

---

## Tools and resources

- OWASP Top 10: https://owasp.org/www-project-top-ten/
- OWASP ASVS: https://owasp.org/www-project-application-security-verification-standard/
- OWASP Password Storage Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- OWASP ZAP: https://www.zaproxy.org/
- gitleaks: https://github.com/gitleaks/gitleaks
- Trivy: https://trivy.dev/
- OWASP Dependency-Check: https://owasp.org/www-project-dependency-check/
- CycloneDX Gradle plugin: https://github.com/CycloneDX/cyclonedx-gradle-plugin
- Dependabot / Renovate

---

## Change log

- 0.2.1: Kotlin adaptation (Kotlin samples, data class `toString` leak, coroutine context propagation)
- 0.2.0: Spring Boot 4 / Spring Security 7 specifics, secret scanning, API hardening, Gradle supply chain, security tests, checklist mapped to OWASP Top 10
- 0.1.0: Initial version
